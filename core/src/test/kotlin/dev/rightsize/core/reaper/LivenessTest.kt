package dev.rightsize.core.reaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant

class LivenessTest {
    /** A PID that reliably no longer exists: spawn a trivial child and wait for it to exit.
     * PID reuse within one test process's lifetime is astronomically unlikely on every OS
     * rightsize supports. Portable (no `/bin/true` assumption): re-launches this same JVM. */
    private fun deadPid(): Long {
        val javaBin = Path(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()
        val proc = ProcessBuilder(javaBin, "-version").redirectErrorStream(true).start()
        proc.waitFor()
        return proc.pid()
    }

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
    private fun Path(vararg parts: String) = java.nio.file.Path.of(parts.first(), *parts.drop(1).toTypedArray())

    @Test fun `own pid with its real start time is alive`() {
        val self = ProcessHandle.current()
        val startedIso = self.info().startInstant().orElseThrow().toString()
        assertTrue(Liveness.isAlive(self.pid(), startedIso))
    }

    @Test fun `own pid with a wrong start time counts as dead (defeats pid reuse)`() {
        val self = ProcessHandle.current()
        val wrongStart = Instant.parse("1999-01-01T00:00:00Z").toString()
        assertFalse(Liveness.isAlive(self.pid(), wrongStart))
    }

    @Test fun `a pid that no longer exists is dead`() {
        val pid = deadPid()
        assertFalse(Liveness.isAlive(pid, Instant.now().toString()))
    }

    @Test fun `unparseable startedIso is treated as dead`() {
        val self = ProcessHandle.current()
        assertFalse(Liveness.isAlive(self.pid(), "not-an-instant"))
    }

    @Test fun `a start time within the 2s tolerance still counts as alive`() {
        val self = ProcessHandle.current()
        val real = self.info().startInstant().orElseThrow()
        assertTrue(Liveness.isAlive(self.pid(), real.plusMillis(1500).toString()))
        assertTrue(Liveness.isAlive(self.pid(), real.minusMillis(1500).toString()))
    }

    @Test fun `currentProcessStartedIso parses back to a real instant`() {
        val iso = Liveness.currentProcessStartedIso()
        assertDoesNotThrow { Instant.parse(iso) }
    }
}
