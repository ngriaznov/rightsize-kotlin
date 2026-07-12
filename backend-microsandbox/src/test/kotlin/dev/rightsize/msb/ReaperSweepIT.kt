package dev.rightsize.msb

import dev.rightsize.core.CacheDir
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.reaper.RunRecord
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Sweep end-to-end: a real sandbox is started, then hand-registered under a fabricated dead
 * run record (a PID that doesn't exist). A genuinely fresh library init — a new JVM process,
 * per the reaping spec's "new process or internal reset hook" guidance — must reap it via the
 * real `Backends.active()` -> init-time-sweep path, not just the `Sweeper` function directly
 * (see the record-file-lifecycle/sweep contract-suite entries in `BackendContractTest` for
 * that narrower, in-process check).
 */
@Tag("sandbox-it")
class ReaperSweepIT {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            assumeTrue(MsbBackendProvider().isSupported())
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }

    @Test fun `a fresh process's init-time sweep reaps a fabricated dead run`() {
        val msb = MsbCliBackend(MsbProvisioner.ensureInstalled())
        val name = "rz-sweepe2e-${System.nanoTime().toString().takeLast(6)}-1"
        val spec = ContainerSpec(name = name, image = "alpine:3.19", command = listOf("sleep", "120"), runId = "sweepe2e")
        val handle = msb.create(spec)
        msb.start(handle)
        try {
            val cacheDir = CacheDir.resolve()
            val runsDir = Files.createDirectories(cacheDir.resolve("runs"))
            val deadRunId = "rz-sweepe2e-dead-${System.nanoTime()}"
            Files.writeString(runsDir.resolve("$deadRunId.json"),
                RunRecord(pid = 999_999_999, startedIso = Instant.now().toString(), backend = "msb").toJson())
            Files.writeString(runsDir.resolve("$deadRunId.sandboxes"), "$name\n")

            val javaBin = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
            val pb = ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                "dev.rightsize.msb.ReaperSweepHelperMainKt")
            pb.environment()["RIGHTSIZE_BACKEND"] = "microsandbox"
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            val helper = pb.start()
            assertTrue(helper.waitFor(60, TimeUnit.SECONDS), "sweep helper process did not exit in time")

            assertFalse(Files.exists(runsDir.resolve("$deadRunId.json")), "the dead run's record must be deleted")
            assertFalse(Files.exists(runsDir.resolve("$deadRunId.sandboxes")))
            assertFalse(name in msb.runningSandboxNames(), "sandbox $name must have been reaped by the fresh process's sweep")
        } finally {
            runCatching { msb.stop(handle) }
            runCatching { msb.remove(handle) }
        }
    }
}
