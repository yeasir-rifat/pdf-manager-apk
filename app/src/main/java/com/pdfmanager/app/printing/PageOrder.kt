package com.pdfmanager.app.printing

/**
 * Decides in which order the pages of the final print PDF are written, for "Reverse" and
 * guided manual two-sided printing. Pure logic, so it can be unit tested without a device.
 *
 * Pages are numbered 0 until count. [BLANK] stands for an empty page that must be added.
 *
 * Manual two-sided printing of N pages uses ceil(N / 2) sheets. Sheet k has page 2k-1 on the
 * front and page 2k on the back:
 * - Pass 1 prints the backs (pages 2, 4, 6 ...). With an odd page count the last sheet has an
 *   empty back, so one blank page stands in for it. Style REVERSES prints them last sheet first.
 * - Pass 2 prints the fronts (pages 1, 3, 5 ...) in normal order.
 */
object PageOrder {

    const val BLANK = -1

    /** Plain order: normal, or the last page first. */
    fun sequence(count: Int, reverse: Boolean): List<Int> {
        if (count <= 0) return emptyList()
        val all = (0 until count).toList()
        return if (reverse) all.reversed() else all
    }

    /** The whole sequence repeated [copies] times, so copies come out collated (1,2,3, 1,2,3). */
    fun repeated(sequence: List<Int>, copies: Int): List<Int> =
        if (copies <= 1) sequence else List(copies) { sequence }.flatten()

    /** Pages of one pass, in print order. */
    fun duplexPass(count: Int, pass: DuplexPass, style: StackStyle): List<Int> {
        if (count <= 0) return emptyList()
        return when (pass) {
            DuplexPass.FIRST -> {
                val backs = (1 until count step 2).toMutableList()
                if (count % 2 == 1) backs.add(BLANK)
                if (style == StackStyle.REVERSES) backs.reversed() else backs
            }
            DuplexPass.SECOND -> (0 until count step 2).toList()
        }
    }

    /** Number of sheets in a two-sided job of [count] pages. */
    fun sheets(count: Int): Int = (count + 1) / 2

    /** True when the final PDF has to be rewritten to follow a new order. */
    fun needsReorder(reverse: Boolean, manual: ManualDuplexChoice): Boolean =
        reverse || manual != ManualDuplexChoice.OFF

    /**
     * Resuming after a stop: [done] sheets of this pass already came out, so skip them.
     * Returns null when [done] is not between 0 and the number of sheets - 1 of the pass.
     */
    fun resume(sequence: List<Int>, done: Int): List<Int>? =
        if (done in 0 until sequence.size) sequence.drop(done) else null
}
