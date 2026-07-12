package dev.rightsize.core.reuse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReuseModeTest {
    @Test fun `exact string true or 1 enables, everything else does not`() {
        assertTrue(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "true")))
        assertTrue(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "1")))
        assertFalse(ReuseMode.enabled(emptyMap()))
        assertFalse(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "")))
        assertFalse(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "TRUE")))
        assertFalse(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "yes")))
        assertFalse(ReuseMode.enabled(mapOf("RIGHTSIZE_REUSE" to "0")))
    }
}
