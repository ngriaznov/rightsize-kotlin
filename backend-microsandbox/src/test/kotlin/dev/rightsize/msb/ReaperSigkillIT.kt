package dev.rightsize.msb

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * SIGKILL end-to-end: a helper child JVM (see [ReaperSigkillHelperMain]) starts a real msb
 * sandbox, signals readiness via a marker file, and is SIGKILLed. The watchdog it spawned
 * before its first sandbox create must reap that sandbox — and delete its run-record files —
 * within the spec's 30s ceiling, without any cooperation from the (now-dead) helper process.
 *
 * Generous, poll-based budget (60s ceiling, not a fixed sleep) for slow msb-linux/msb-windows
 * CI runners, per the reaping spec's integration-test guidance.
 */
@Tag("sandbox-it")
class ReaperSigkillIT {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            assumeTrue(MsbBackendProvider().isSupported())
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }

    @Test fun `SIGKILLing the owning process leaves the watchdog to reap its sandbox`() {
        val javaBin = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
        val marker = Files.createTempFile("rz-sigkill-marker-", ".txt")
        Files.deleteIfExists(marker)   // helper creates it; absence is the "not ready yet" signal

        val pb = ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
            "dev.rightsize.msb.ReaperSigkillHelperMainKt", marker.toString())
        pb.environment()["RIGHTSIZE_BACKEND"] = "microsandbox"
        pb.redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val helper = pb.start()

        try {
            val readyDeadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < readyDeadline && !Files.exists(marker)) Thread.sleep(200)
            assertTrue(Files.exists(marker), "helper process never signaled readiness within 60s")

            val lines = Files.readAllLines(marker)
            val runId = lines[0]
            val cacheDir = Path.of(lines[1])
            val sandboxName = "rz-$runId-1"

            helper.destroyForcibly()   // SIGKILL on POSIX; TerminateProcess on Windows — no shutdown hook runs
            assertTrue(helper.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "helper process did not die")

            val msb = MsbCliBackend(MsbProvisioner.ensureInstalled())
            val recordFile = cacheDir.resolve("runs").resolve("$runId.json")
            val sandboxesFile = cacheDir.resolve("runs").resolve("$runId.sandboxes")

            val reapDeadline = System.currentTimeMillis() + 30_000
            var reaped = false
            while (System.currentTimeMillis() < reapDeadline) {
                if (sandboxName !in msb.runningSandboxNames() && !Files.exists(recordFile)) { reaped = true; break }
                Thread.sleep(500)
            }
            assertTrue(reaped, "watchdog did not reap sandbox $sandboxName / delete $recordFile within 30s")
            assertFalse(Files.exists(sandboxesFile), "sandboxes ledger file must be deleted too")
        } finally {
            helper.destroyForcibly()
            Files.deleteIfExists(marker)
        }
    }
}
