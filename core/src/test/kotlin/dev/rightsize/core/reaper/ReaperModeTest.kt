package dev.rightsize.core.reaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReaperModeTest {
    @Test fun `default and unset value is ON`() {
        assertEquals(ReaperMode.ON, ReaperMode.from(null))
        assertEquals(ReaperMode.ON, ReaperMode.from(""))
        assertEquals(ReaperMode.ON, ReaperMode.from("on"))
    }

    @Test fun `sweep and off are recognized, case-insensitively, trimmed`() {
        assertEquals(ReaperMode.SWEEP, ReaperMode.from("sweep"))
        assertEquals(ReaperMode.SWEEP, ReaperMode.from(" SWEEP "))
        assertEquals(ReaperMode.OFF, ReaperMode.from("off"))
        assertEquals(ReaperMode.OFF, ReaperMode.from("OFF"))
    }

    @Test fun `unknown values fall back to ON`() {
        assertEquals(ReaperMode.ON, ReaperMode.from("bogus"))
        assertEquals(ReaperMode.ON, ReaperMode.from("true"))
    }
}
