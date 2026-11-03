package com.pdfmanager.app.data

/** Rules of the Ask AI chat that need no Android classes. */
object AiChat {

    /**
     * Gemini keeps nothing between requests, so a follow up that does not carry the PDF can only be answered from the
     * earlier chat text and cannot look at the page or a figure again. With this on, every question carries the PDF
     * of its own scope. That is cheap: a cut page is cached, a big file is a saved link, and a small file is a local copy.
     * Set it to false to attach only when [AiScope.needsAttach] says the scope changed.
     */
    const val ALWAYS_ATTACH = true

    /** One finished question and its answer. */
    class Pair(val prompt: String, val answer: String)

    /** The earlier turns as text, user then model, in order. A question that got no answer is not part of it. */
    fun history(done: List<Pair>): List<GeminiRequest.Turn> = done.flatMap {
        listOf(
            GeminiRequest.Turn(GeminiRequest.USER, it.prompt),
            GeminiRequest.Turn(GeminiRequest.MODEL, it.answer),
        )
    }

    /** The scope to pass as "attached last time". Null makes [AiScope.needsAttach] true, so the PDF is attached again. */
    fun attachedFor(last: AiScope?): AiScope? = if (ALWAYS_ATTACH) null else last
}
