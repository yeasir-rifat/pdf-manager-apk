package com.pdfmanager.app.printing

import org.junit.Assert.assertEquals
import org.junit.Test

class SidesChoiceTest {
    @Test fun every_choice_reads_back_as_itself() {
        SidesChoice.entries.forEach { c ->
            assertEquals(c, SidesChoice.of(c.applyTo(PrintSettings())))
        }
    }

    @Test fun manual_both_sides_matches_the_old_non_duplex_setup() {
        val s = SidesChoice.BOTH_MANUAL.applyTo(PrintSettings())
        assertEquals(ManualDuplexChoice.ON, s.manualDuplex)
        assertEquals(DuplexChoice.ONE_SIDED, s.duplexMode)
    }

    @Test fun auto_both_sides_never_leaves_manual_on() {
        val s = SidesChoice.BOTH_AUTO.applyTo(PrintSettings(manualDuplex = ManualDuplexChoice.ON))
        assertEquals(ManualDuplexChoice.OFF, s.manualDuplex)
        assertEquals(DuplexChoice.LONG_EDGE, s.duplexMode)
    }

    @Test fun saved_settings_map_to_the_matching_choice() {
        assertEquals(SidesChoice.PRINTER_DEFAULT, SidesChoice.of(PrintSettings()))
        assertEquals(SidesChoice.ONE_SIDE, SidesChoice.of(PrintSettings(duplexMode = DuplexChoice.ONE_SIDED)))
        assertEquals(SidesChoice.BOTH_AUTO, SidesChoice.of(PrintSettings(duplexMode = DuplexChoice.SHORT_EDGE)))
        assertEquals(SidesChoice.BOTH_MANUAL, SidesChoice.of(PrintSettings(manualDuplex = ManualDuplexChoice.ON)))
    }

    @Test fun other_settings_are_untouched() {
        val before = PrintSettings(copies = 3, pageRangeText = "1-2", colorMode = ColorChoice.GRAYSCALE)
        val after = SidesChoice.BOTH_AUTO.applyTo(before)
        assertEquals(before.copy(duplexMode = DuplexChoice.LONG_EDGE), after)
    }
}
