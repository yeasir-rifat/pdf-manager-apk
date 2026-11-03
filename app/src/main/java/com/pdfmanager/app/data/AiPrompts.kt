package com.pdfmanager.app.data

/**
 * The quick chips of the Ask AI panel. The chip shows a short label, and the longer prompt behind it is only sent.
 * Plain Kotlin, so the chips for each scope are unit tested. The answer language comes from the system instruction.
 */
enum class AiChip(val prompt: String) {
    Explain("Explain this in simple words, step by step."),
    Summarize("Summarize the attached content."),
    Detail("Explain the attached content in detail, with every step and the reason for it."),
    Points("List the key points as short bullet points."),
    Outline("Give a structured outline of the attached content."),
    Quiz("Write 5 quiz questions about the attached content, then give the answers at the end.");

    companion object {
        private val forPage = listOf(Explain, Summarize, Detail, Points)
        private val forMany = listOf(Summarize, Points, Outline, Quiz)

        /** This page has Explain, Summarize, In detail, Key points. Whole PDF and Range have Summarize, Key points, Outline, Quiz me. */
        fun forScope(scope: AiScope): List<AiChip> = if (scope is AiScope.Page) forPage else forMany
    }
}
