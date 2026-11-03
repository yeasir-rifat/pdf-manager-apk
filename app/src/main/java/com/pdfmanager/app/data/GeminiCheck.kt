package com.pdfmanager.app.data

/**
 * The Test connection request in Settings: one word question, no PDF. Returns null when Gemini answered with
 * text (the row shows OK), otherwise the short [GeminiError]. Never throws, except for a cancelled coroutine.
 */
object GeminiCheck {
    const val PROMPT = "Reply with one word: OK"

    suspend fun run(
        apiKey: String,
        modelId: String,
        endpoints: GeminiEndpoints = GeminiEndpoints(),
    ): GeminiError? {
        if (apiKey.isBlank()) return GeminiError.Key
        if (!GeminiClient.isValidModelId(modelId)) return GeminiError.Model
        val body = GeminiRequest.body(PROMPT, AiPrefs.LANG_EN, AiPrefs.LEN_SHORT)
        return try {
            GeminiErrors.fromResponse(GeminiClient(apiKey, endpoints).generate(modelId, body))
        } catch (e: GeminiNetworkException) {
            GeminiErrors.fromNetwork(e)
        }
    }
}
