package com.pdfmanager.app.printing

/**
 * The one "Sides" choice shown in the print settings. It replaces the old three rows
 * ("Your printer type", "Auto sides", "Manual sides") and maps onto the same two stored settings,
 * [PrintSettings.duplexMode] and [PrintSettings.manualDuplex], so printing itself is unchanged.
 */
enum class SidesChoice {
    /** Let the printer decide. */
    PRINTER_DEFAULT,
    ONE_SIDE,
    /** The printer prints both sides by itself. */
    BOTH_AUTO,
    /** Guided two passes: the person turns the paper over between them. */
    BOTH_MANUAL;

    companion object {
        fun of(s: PrintSettings): SidesChoice = when {
            s.manualDuplex == ManualDuplexChoice.ON -> BOTH_MANUAL
            s.duplexMode == DuplexChoice.ONE_SIDED -> ONE_SIDE
            s.duplexMode == DuplexChoice.LONG_EDGE || s.duplexMode == DuplexChoice.SHORT_EDGE -> BOTH_AUTO
            else -> PRINTER_DEFAULT
        }
    }

    fun applyTo(s: PrintSettings): PrintSettings = when (this) {
        PRINTER_DEFAULT -> s.copy(manualDuplex = ManualDuplexChoice.OFF, duplexMode = DuplexChoice.DEFAULT)
        ONE_SIDE -> s.copy(manualDuplex = ManualDuplexChoice.OFF, duplexMode = DuplexChoice.ONE_SIDED)
        BOTH_AUTO -> s.copy(manualDuplex = ManualDuplexChoice.OFF, duplexMode = DuplexChoice.LONG_EDGE)
        BOTH_MANUAL -> s.copy(manualDuplex = ManualDuplexChoice.ON, duplexMode = DuplexChoice.ONE_SIDED)
    }
}
