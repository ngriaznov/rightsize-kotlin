package dev.rightsize.msb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MsbInstallLockTest {
    @Test fun `matches the captured refusal verbatim`() {
        // Captured from a windows-2025 hosted runner: `msb run` refused mid-suite while
        // msb's internal install lock was held, with ordinary boots succeeding on both
        // sides of the failure.
        assertTrue(isMsbInstallLockActive(
            "error: runtime error: microsandbox install operation in progress until " +
                "2026-07-31 20:55:04.779845600; retry after it completes"))
    }

    @Test fun `matches regardless of the deadline timestamp`() {
        // The timestamp varies per occurrence; the classifier must key on the stable
        // phrase only.
        assertTrue(isMsbInstallLockActive(
            "error: runtime error: microsandbox install operation in progress until " +
                "2027-01-01 00:00:00.000000000; retry after it completes"))
    }

    @Test fun `ignores other msb runtime errors and a guest command's own failure`() {
        assertFalse(isMsbInstallLockActive("error: runtime error: something else entirely"))
        assertFalse(isMsbInstallLockActive("error: failed to start \"rz-abc-1\""))
        assertFalse(isMsbInstallLockActive(""))
    }
}
