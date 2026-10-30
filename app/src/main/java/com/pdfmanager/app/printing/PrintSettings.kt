package com.pdfmanager.app.printing

/**
 * Print Properties স্ক্রিনের UI state model।
 *
 * এটি অ্যাপের নিজস্ব সেটিংস; Android-এর PrintAttributes-এর সব option-এর
 * সরাসরি সমতুল্য নেই। শুধু সমর্থিত মানগুলোই পরে print adapter/PrintAttributes-এ
 * প্রয়োগ করা হবে (Step 7)।
 */
data class PrintSettings(
    val pageRangeText: String = "",
    val copies: Int = 1,
    val paperSizeId: String = "ISO_A4",
    val orientation: OrientationChoice = OrientationChoice.AUTO,
    val colorMode: ColorChoice = ColorChoice.COLOR,
    val duplexMode: DuplexChoice = DuplexChoice.DEFAULT,
    val scaleMode: ScaleChoice = ScaleChoice.FIT,
    /** Print the last sheet first. Applied to the final PDF (after N-up/booklet), see PageOrder. */
    val reverseOrder: Boolean = false,
    /** For printers without two-sided printing: two guided passes (back sides, then front sides). */
    val manualDuplex: ManualDuplexChoice = ManualDuplexChoice.OFF,
    /** How the printed stack changes order between the two passes (printer dependent). */
    val stackStyle: StackStyle = StackStyle.REVERSES,
    /** Turn the pages of the 2nd pass upside down, if they come out rotated against the 1st pass. */
    val turnSecondPass: Boolean = false,
    val pagesPerSheet: Int = 1,
    val quality: QualityChoice = QualityChoice.DEFAULT,
    /** Step 9: how pages are arranged on each sheet. Needs a temporary PDF (see PrintPreprocessor). */
    val layout: LayoutChoice = LayoutChoice.NORMAL,
    /** Draw a thin border around every small page in N-up. */
    val layoutBorder: Boolean = true,
    /** Step 9: diagonal text printed on every page. Blank means no watermark. */
    val watermarkText: String = "",
    /** Step 9: print "n / total" at the bottom of every page. */
    val pageNumbers: Boolean = false
)

enum class OrientationChoice { AUTO, PORTRAIT, LANDSCAPE }
enum class ColorChoice { COLOR, GRAYSCALE, MONOCHROME }
enum class DuplexChoice { DEFAULT, ONE_SIDED, LONG_EDGE, SHORT_EDGE }
enum class ScaleChoice { FIT, FILL, ACTUAL_SIZE, SHRINK }
enum class QualityChoice { DRAFT, NORMAL, HIGH, DEFAULT }

enum class LayoutChoice { NORMAL, NUP, BOOKLET }

enum class ManualDuplexChoice { OFF, ON }

/**
 * What happens to the sheets between pass 1 and pass 2 when you pick them up and put them back:
 * - REVERSES: the stack comes out in reverse order (typical for printers that stack paper face
 *   down). Pass 1 prints the backs in reverse; pass 2 prints the fronts in normal order.
 * - SAME_ORDER: the stack keeps its order. Both passes print in normal order.
 */
enum class StackStyle { REVERSES, SAME_ORDER }

enum class DuplexPass { FIRST, SECOND }
