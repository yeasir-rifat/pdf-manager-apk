package com.pdfmanager.app.data

/** Token counts from usageMetadata. A missing number is 0. */
class GeminiUsage(val promptTokens: Int, val answerTokens: Int, val totalTokens: Int)

/** How a parsed answer ended. [GeminiErrors] turns Blocked and Empty into short messages. */
enum class GeminiOutcome {
    /** Finished normally with text. */
    Ok,

    /** Has text, but Google stopped early (for example the length limit). Show the text. */
    Partial,

    /** The prompt or the answer was stopped by a safety rule and there is no text. */
    Blocked,

    /** No text and no block reason. */
    Empty,
}

/** What a generateContent answer holds. [text] is empty when there is none. */
class GeminiAnswer(
    val text: String,
    val finishReason: String?,
    val blockReason: String?,
    val usage: GeminiUsage?,
) {
    val outcome: GeminiOutcome
        get() = when {
            text.isNotBlank() -> if (finishReason == null || finishReason == GeminiResponse.FINISH_STOP) GeminiOutcome.Ok else GeminiOutcome.Partial
            blockReason != null || finishReason in GeminiResponse.BLOCKING_FINISH -> GeminiOutcome.Blocked
            else -> GeminiOutcome.Empty
        }
}

/**
 * Reads the JSON of a generateContent answer. Plain Kotlin on [DriveJson], so it runs in unit tests.
 * Reads candidates[0].content.parts[*].text (parts marked as thoughts are skipped), finishReason,
 * promptFeedback.blockReason and usageMetadata.
 */
object GeminiResponse {
    const val FINISH_STOP = "STOP"

    /** Finish reasons that mean a safety or policy rule stopped the answer. */
    val BLOCKING_FINISH = setOf(
        "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "RECITATION",
    )

    /** The answer in [body], or null when [body] is not a JSON object. */
    fun parse(body: String): GeminiAnswer? = DriveJson.parseObject(body)?.let { fromRoot(it) }

    /** The answer in an already parsed JSON object. A streamed event has the same shape, with a piece of the text. */
    fun fromRoot(root: Map<*, *>): GeminiAnswer {
        val candidate = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val parts = (candidate?.get("content") as? Map<*, *>)?.get("parts") as? List<*>
        val text = StringBuilder()
        parts?.forEach { p ->
            val part = p as? Map<*, *> ?: return@forEach
            if (part["thought"] == true) return@forEach
            (part["text"] as? String)?.let { text.append(it) }
        }
        val finish = (candidate?.get("finishReason") as? String)?.takeIf { it.isNotBlank() }
        val block = ((root["promptFeedback"] as? Map<*, *>)?.get("blockReason") as? String)?.takeIf { it.isNotBlank() }
        return GeminiAnswer(text.toString(), finish, block, usage(root["usageMetadata"] as? Map<*, *>))
    }

    private fun usage(m: Map<*, *>?): GeminiUsage? {
        if (m == null) return null
        fun n(key: String) = (m[key] as? Double)?.toInt() ?: 0
        return GeminiUsage(n("promptTokenCount"), n("candidatesTokenCount"), n("totalTokenCount"))
    }
}
