package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** Unit coverage for the boot-completion marker classifier that backs
 * [MsbCliBackend.isCleanFastExit]'s fast-exit post-mortem check — see the dedicated
 * [MsbFastExitClassificationTest] for the end-to-end behavior through a fake msb binary. */
class SandboxStartedMarkerTest {

    @Test fun `matches a system log carrying the marker line`() {
        assertTrue(hasSandboxStartedMarker("--- sandbox started ---"))
    }

    @Test fun `matches when the marker is one line among others`() {
        assertTrue(hasSandboxStartedMarker(
            "guest kernel booting\n--- sandbox started ---\nagentd: listening\n"))
    }

    @Test fun `does not match an empty or unrelated system log`() {
        assertFalse(hasSandboxStartedMarker(""))
        assertFalse(hasSandboxStartedMarker("guest kernel booting\nagentd: listening\n"))
        // Close but not the exact marker text — never fuzzy-match this signal.
        assertFalse(hasSandboxStartedMarker("sandbox started"))
    }
}
