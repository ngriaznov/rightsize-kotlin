package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class MsbPortBindConflictTest {

    @Test fun `matches the long-standing phrasings`() {
        assertTrue(isPortBindConflict("Error: bind: address already in use"))
        assertTrue(isPortBindConflict("Bind for 0.0.0.0:5432 failed: port is already allocated"))
        assertTrue(isPortBindConflict("ADDRESS ALREADY IN USE"))
        assertTrue(isPortBindConflict("host port 5432 already in use by another process"))
    }

    @Test fun `matches msb 0_7_14's pre-boot port claim failure on Linux, macOS and Windows`() {
        assertTrue(isPortBindConflict(
            "failed to publish tcp port 127.0.0.1:49213: Address already in use (os error 98)"))
        assertTrue(isPortBindConflict(
            "failed to publish udp port 127.0.0.1:49214: Address already in use (os error 48)"))
        assertTrue(isPortBindConflict(
            "failed to publish tcp port 127.0.0.1:49213: Only one usage of each socket address " +
                "(protocol/network address/port) is normally permitted. (os error 10048)"))
    }

    @Test fun `negative cases do not match`() {
        assertFalse(isPortBindConflict(""))
        assertFalse(isPortBindConflict("Error: no such image: redis:doesnotexist"))
        assertFalse(isPortBindConflict("permission denied"))
        // Another socket error from the same pre-boot claim is not a conflict a new port fixes.
        assertFalse(isPortBindConflict(
            "failed to publish tcp port 127.0.0.1:80: Permission denied (os error 13)"))
    }
}
