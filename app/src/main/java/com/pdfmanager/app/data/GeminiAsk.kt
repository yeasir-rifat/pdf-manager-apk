package com.pdfmanager.app.data

import android.content.Context
import android.net.Uri

/** What one question to Gemini came to: an answer with text, or one short error. */
sealed interface GeminiAskResult {
    class Answer(val answer: GeminiAnswer) : GeminiAskResult
    /** [retryAfterSec] is above 0 only for a limit error where Google said how long to wait. */
    class Failed(val error: GeminiError, val retryAfterSec: Int = 0) : GeminiAskResult
}

/**
 * One question about a whole PDF, no UI. The Ask AI panel (Step 7) calls this.
 * Nothing here logs, and no error text ever holds the key, the URL or a file name.
 */
object GeminiAsk {

    /**
     * Opens a private copy of the PDF at [uri], asks [question], and always deletes the copy afterwards.
     * [uri] is the viewer's openUri, so a password PDF sends its unlocked copy.
     * Throws only CancellationException.
     */
    suspend fun askWholePdf(
        context: Context,
        uri: Uri,
        question: String,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        endpoints: GeminiEndpoints = GeminiEndpoints(),
    ): GeminiAskResult = when (val opened = AiPdfOpen.open(context, uri)) {
        is AiPdfOpen.Result.Failed -> GeminiAskResult.Failed(opened.error)
        is AiPdfOpen.Result.Ready -> opened.source.use {
            askPdf(it, question, apiKey, modelId, language, length, endpoints)
        }
    }

    /**
     * One question about [scope] of the PDF at [uri]. [totalPages] and [readerPage] come from the viewer, and the
     * viewer reads [readerPage] when Send is tapped, so scrolling later never changes what the question was about.
     *
     * [history] holds the earlier turns as text. [attachedScope] is the scope of the PDF attached last time, or null.
     * The PDF is attached again only when [AiScope.needsAttach] says so. Otherwise nothing is opened or cut.
     * Page numbers in the answer are mapped back to the original PDF.
     * With [onText] the answer is streamed: [onText] gets all the text so far (from a background thread) each time it grows.
     * When the stream breaks or ends without an answer the result is Failed, and what [onText] already got stays valid.
     * Throws only CancellationException.
     */
    suspend fun ask(
        context: Context,
        uri: Uri,
        scope: AiScope,
        readerPage: Int,
        totalPages: Int,
        question: String,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        history: List<GeminiRequest.Turn> = emptyList(),
        attachedScope: AiScope? = null,
        onProgress: (Int) -> Unit = {},
        endpoints: GeminiEndpoints = GeminiEndpoints(),
        onText: ((String) -> Unit)? = null,
    ): GeminiAskResult {
        if (apiKey.isBlank()) return GeminiAskResult.Failed(GeminiError.Key)
        if (!GeminiClient.isValidModelId(modelId)) return GeminiAskResult.Failed(GeminiError.Model)
        if (!scope.isValidFor(totalPages)) return GeminiAskResult.Failed(GeminiError.File)
        if (totalPages > AiPdfRules.MAX_PAGES && scope == AiScope.Whole) return GeminiAskResult.Failed(GeminiError.Pages)
        val contextLine = AiScope.contextLine(scope, totalPages, readerPage)
        if (!AiScope.needsAttach(attachedScope, scope)) {
            val turns = history + GeminiRequest.Turn(GeminiRequest.USER, question)
            return send(turns, scope, contextLine, apiKey, modelId, language, length, endpoints, onText)
        }
        if (scope == AiScope.Whole) {
            // A PDF over the inline limit goes through the Files API, and a link from earlier is reused
            val id = AiPdfOpen.identify(context, uri)
            if (id != null && id.sizeBytes > AiPdfRules.INLINE_MAX_BYTES) {
                if (id.sizeBytes > AiPdfRules.FILES_MAX_BYTES) return GeminiAskResult.Failed(GeminiError.File)
                return askLargePdf(
                    id.fileKey, AiFileCache.of(context), { AiPdfOpen.openForUpload(context, uri) },
                    question, apiKey, modelId, language, length, history, contextLine, onProgress, endpoints,
                    onText = onText,
                )
            }
        }
        return when (val opened = AiPdfOpen.forScope(context, uri, scope)) {
            is AiPdfOpen.Result.Failed -> GeminiAskResult.Failed(opened.error)
            is AiPdfOpen.Result.Ready -> opened.source.use {
                // A cut page or range over the inline limit goes through the Files API, like a big whole PDF
                val part = if (it.sizeBytes > AiPdfRules.INLINE_MAX_BYTES) {
                    val up = try {
                        GeminiClient(apiKey, endpoints).uploadPdf(it.file, onProgress)
                    } catch (e: GeminiNetworkException) {
                        return@use GeminiAskResult.Failed(GeminiErrors.fromNetwork(e))
                    }
                    when (up) {
                        is GeminiUpload.Failed -> return@use GeminiAskResult.Failed(up.error)
                        is GeminiUpload.Done -> GeminiRequest.PdfPart.remote(up.fileUri)
                    }
                } else it.part()
                val turns = history + GeminiRequest.Turn(GeminiRequest.USER, question, part)
                send(turns, scope, contextLine, apiKey, modelId, language, length, endpoints, onText)
            }
        }
    }

    /** Errors that can mean a saved file link is no longer good: a refused or unknown file shows up as these. */
    private fun maybeStaleLink(e: GeminiError) = e == GeminiError.Key || e == GeminiError.Denied || e == GeminiError.Model

    /**
     * One question about the whole PDF through the Files API, for a PDF over the inline limit. No Android classes.
     *
     * A link saved in [cache] for [fileKey] is tried first, so the second question starts without any upload.
     * When Google refuses it (the file was deleted or expired) the link is dropped and the PDF is uploaded fresh, once.
     * [openSource] makes the private copy that is uploaded, and the copy is deleted afterwards. [onProgress] gets 0 to 100.
     * The upload and the question are two separate requests, and nothing is retried in a loop.
     */
    suspend fun askLargePdf(
        fileKey: String,
        cache: AiFileCache,
        openSource: suspend () -> AiPdfOpen.Result,
        question: String,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        history: List<GeminiRequest.Turn> = emptyList(),
        contextLine: String? = null,
        onProgress: (Int) -> Unit = {},
        endpoints: GeminiEndpoints = GeminiEndpoints(),
        poll: GeminiClient.Poll = GeminiClient.Poll(),
        onText: ((String) -> Unit)? = null,
    ): GeminiAskResult {
        if (apiKey.isBlank()) return GeminiAskResult.Failed(GeminiError.Key)
        if (!GeminiClient.isValidModelId(modelId)) return GeminiAskResult.Failed(GeminiError.Model)
        val cacheKey = AiFileCache.key(fileKey, apiKey)
        fun turns(uri: String) = history + GeminiRequest.Turn(GeminiRequest.USER, question, GeminiRequest.PdfPart.remote(uri))

        cache.get(cacheKey)?.let { saved ->
            val r = send(turns(saved), AiScope.Whole, contextLine, apiKey, modelId, language, length, endpoints, onText)
            if (r !is GeminiAskResult.Failed || !maybeStaleLink(r.error)) return r
            cache.remove(cacheKey)
        }

        return when (val opened = openSource()) {
            is AiPdfOpen.Result.Failed -> GeminiAskResult.Failed(opened.error)
            is AiPdfOpen.Result.Ready -> opened.source.use { src ->
                val up = try {
                    GeminiClient(apiKey, endpoints).uploadPdf(src.file, onProgress, poll)
                } catch (e: GeminiNetworkException) {
                    return@use GeminiAskResult.Failed(GeminiErrors.fromNetwork(e))
                }
                when (up) {
                    is GeminiUpload.Failed -> GeminiAskResult.Failed(up.error)
                    is GeminiUpload.Done -> {
                        cache.put(cacheKey, up.fileUri)
                        send(turns(up.fileUri), AiScope.Whole, contextLine, apiKey, modelId, language, length, endpoints, onText)
                    }
                }
            }
        }
    }

    /**
     * Sends [source] inline, in front of [question]. Nothing is sent when the key or model is not usable,
     * or when the PDF is over the page or size limit. Does not close [source].
     */
    suspend fun askPdf(
        source: AiPdfSource,
        question: String,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        endpoints: GeminiEndpoints = GeminiEndpoints(),
    ): GeminiAskResult {
        if (apiKey.isBlank()) return GeminiAskResult.Failed(GeminiError.Key)
        if (!GeminiClient.isValidModelId(modelId)) return GeminiAskResult.Failed(GeminiError.Model)
        AiPdfRules.refusal(source.sizeBytes, source.pages)?.let { return GeminiAskResult.Failed(it) }
        val turns = listOf(GeminiRequest.Turn(GeminiRequest.USER, question, source.part()))
        return send(turns, AiScope.Whole, null, apiKey, modelId, language, length, endpoints)
    }

    /** A failed result. A limit error carries the wait Google asked for, so the panel can pause Send. */
    private fun limited(e: GeminiError, r: GeminiHttpResult) =
        GeminiAskResult.Failed(e, if (e == GeminiError.Limit) GeminiRetry.seconds(r) else 0)

    private fun systemContext(scope: AiScope, contextLine: String?): String? =
        // Answers never carry page references, so every scoped question tells the model not to write them
        listOfNotNull(contextLine, AiScope.NO_PAGE_REF_RULE.takeIf { scope != AiScope.Whole || contextLine != null })
            .joinToString(" ").ifBlank { null }

    private suspend fun send(
        turns: List<GeminiRequest.Turn>,
        scope: AiScope,
        contextLine: String?,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        endpoints: GeminiEndpoints,
        onText: ((String) -> Unit)? = null,
    ): GeminiAskResult {
        val context = systemContext(scope, contextLine)
        if (onText != null) return sendStreaming(turns, scope, context, apiKey, modelId, language, length, endpoints, onText)
        val result = try {
            GeminiClient(apiKey, endpoints).generateStreaming(modelId) { out ->
                GeminiRequest.write(out, turns, language, length, context)
            }
        } catch (e: GeminiNetworkException) {
            return GeminiAskResult.Failed(GeminiErrors.fromNetwork(e))
        } catch (e: GeminiFileException) {
            return GeminiAskResult.Failed(GeminiError.File)
        }
        GeminiErrors.fromResponse(result)?.let { return limited(it, result) }
        val answer = GeminiResponse.parse(result.body) ?: return GeminiAskResult.Failed(GeminiError.Empty)
        val mapped = GeminiAnswer(AiScope.remapPageRefs(answer.text, scope), answer.finishReason, answer.blockReason, answer.usage)
        return GeminiAskResult.Answer(mapped)
    }

    /**
     * The same question as a stream. [onText] gets the whole text so far (page references already mapped back)
     * each time it grows. A stream that breaks returns Failed, and the text that came stays with the caller.
     */
    private suspend fun sendStreaming(
        turns: List<GeminiRequest.Turn>,
        scope: AiScope,
        context: String?,
        apiKey: String,
        modelId: String,
        language: String,
        length: String,
        endpoints: GeminiEndpoints,
        onText: (String) -> Unit,
    ): GeminiAskResult {
        val acc = GeminiStreamAccumulator()
        val result = try {
            GeminiClient(apiKey, endpoints).generateSse(
                modelId,
                { out -> GeminiRequest.write(out, turns, language, length, context) },
            ) { json -> if (acc.add(json)) onText(AiScope.remapPageRefs(acc.text, scope)) }
        } catch (e: GeminiNetworkException) {
            return GeminiAskResult.Failed(GeminiErrors.fromNetwork(e))
        } catch (e: GeminiFileException) {
            return GeminiAskResult.Failed(GeminiError.File)
        }
        GeminiErrors.fromResult(result)?.let { return limited(it, result) }
        acc.streamError?.let { return GeminiAskResult.Failed(it) }
        val answer = acc.answer()
        GeminiErrors.fromAnswer(answer)?.let { return GeminiAskResult.Failed(it) }
        val mapped = GeminiAnswer(AiScope.remapPageRefs(answer.text, scope), answer.finishReason, answer.blockReason, answer.usage)
        return GeminiAskResult.Answer(mapped)
    }
}
