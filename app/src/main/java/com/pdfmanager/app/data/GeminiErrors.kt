package com.pdfmanager.app.data

import com.pdfmanager.app.R

/** The short reasons the Ask AI UI can show. One value per `ai_err_*` string. */
enum class GeminiError {
    Key, Denied, Model, Limit, Busy, Offline, Timeout, Empty, Blocked, Pages, File
}

/**
 * Maps every failure to one [GeminiError]. The server's own message text is never read or shown,
 * so a key or a file name can never leak into the UI through an error.
 */
object GeminiErrors {

    /**
     * The error for an HTTP [code], or null for a success (2xx).
     * 400 and 401 mean the key is not valid, 403 access denied, 404 model not found, 429 limit reached,
     * 408 and 504 timed out, and every other failure (500, 502, 503 and anything unexpected) is Google being busy.
     */
    fun fromHttp(code: Int): GeminiError? = when {
        code in 200..299 -> null
        code == 400 || code == 401 -> GeminiError.Key
        code == 403 -> GeminiError.Denied
        code == 404 -> GeminiError.Model
        code == 429 -> GeminiError.Limit
        code == 413 -> GeminiError.File
        code == 408 || code == 504 -> GeminiError.Timeout
        else -> GeminiError.Busy
    }

    fun fromResult(result: GeminiHttpResult): GeminiError? {
        // A 400 that talks about size, pages or the document is a problem with the request, not with the key.
        // The text is only matched here, never shown.
        if (result.code == 400) {
            val b = result.body.lowercase()
            val aboutKey = b.contains("api key") || b.contains("api_key")
            val aboutRequest = listOf("exceed", "too large", "page", "document", "pdf", "size").any { b.contains(it) }
            if (!aboutKey && aboutRequest) return GeminiError.File
        }
        return fromHttp(result.code)
    }

    /** No internet or a timeout. */
    fun fromNetwork(e: GeminiNetworkException): GeminiError =
        if (e.timeout) GeminiError.Timeout else GeminiError.Offline

    /** An answer that came back with HTTP 200 but holds nothing to show: Blocked or Empty. Null when there is text. */
    fun fromAnswer(answer: GeminiAnswer?): GeminiError? = when {
        answer == null -> GeminiError.Empty
        answer.outcome == GeminiOutcome.Blocked -> GeminiError.Blocked
        answer.outcome == GeminiOutcome.Empty -> GeminiError.Empty
        else -> null
    }

    /**
     * Everything in one step: the HTTP result first, then the answer inside it.
     * Returns null when there is an answer with text (Ok or Partial). Read it with [GeminiResponse.parse].
     */
    fun fromResponse(result: GeminiHttpResult): GeminiError? {
        fromResult(result)?.let { return it }
        return fromAnswer(GeminiResponse.parse(result.body))
    }

    /** The `ai_err_*` string for [error]. */
    fun messageRes(error: GeminiError): Int = when (error) {
        GeminiError.Key -> R.string.ai_err_key
        GeminiError.Denied -> R.string.ai_err_denied
        GeminiError.Model -> R.string.ai_err_model
        GeminiError.Limit -> R.string.ai_err_limit
        GeminiError.Busy -> R.string.ai_err_busy
        GeminiError.Offline -> R.string.ai_err_offline
        GeminiError.Timeout -> R.string.ai_err_timeout
        GeminiError.Empty -> R.string.ai_err_empty
        GeminiError.Blocked -> R.string.ai_err_blocked
        GeminiError.Pages -> R.string.ai_err_pages
        GeminiError.File -> R.string.ai_err_file
    }
}
