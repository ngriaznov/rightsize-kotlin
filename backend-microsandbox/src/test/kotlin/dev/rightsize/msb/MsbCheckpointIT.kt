package dev.rightsize.msb

import dev.rightsize.GenericContainer
import dev.rightsize.RunId
import dev.rightsize.core.Backends
import dev.rightsize.core.CacheDir
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration

/**
 * End-to-end checkpoint/restore against a real msb disk snapshot (see docs/checkpoints.md): boot
 * a sandbox, write a marker file into it, checkpoint — proving both that the SAME sandbox still
 * works afterward (the stop/snapshot/start cycle brings it back up, and `checkpoint()` re-runs
 * the wait strategy since `checkpointRestartsWorkload` is true) and that the reaper ledger is
 * untouched by the cycle (same sandbox name throughout, still owned by this run) — then restores
 * from the snapshot and proves the marker survived. Cleanup is guard-style (an outer `finally`),
 * so a mid-test assertion failure still removes both sandboxes and the snapshot.
 */
@Tag("sandbox-it")
class MsbCheckpointIT {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireMsb() {
            assumeTrue(MsbBackendProvider().isSupported())
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }

    private fun ledgerLines() =
        CacheDir.resolve().resolve("runs").resolve("${RunId.value}.sandboxes").let {
            if (Files.exists(it)) Files.readAllLines(it) else emptyList()
        }

    @Test fun `checkpoint then restore preserves filesystem state, and the stop-snapshot-start cycle never touches the ledger`() {
        val original = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c", "sleep 120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        original.start()

        var snapshotRef: String? = null
        try {
            val write = original.execInContainer("sh", "-c", // /srv, not /tmp: the microsandbox guest mounts /tmp as tmpfs, which a disk snapshot cannot capture.
            "echo checkpoint-marker > /srv/marker.txt && sync")
            assertEquals(0, write.exitCode, "writing the marker file failed: ${write.stderr}")

            val beforeLedger = ledgerLines()
            val cp = original.checkpoint()
            snapshotRef = cp.ref
            assertTrue(Regex("^rz-ckpt-[0-9a-f]{12}$").matches(cp.ref), "unexpected ref shape: '${cp.ref}'")
            assertEquals("microsandbox", cp.backend)
            assertEquals(beforeLedger, ledgerLines(),
                "the stop/snapshot/start cycle must not touch the reaper ledger — same sandbox, still this run's")

            // Proves the start-back-up + post-checkpoint wait re-run: the SAME container is
            // usable again, not left stopped or in a not-yet-ready state.
            val stillWorks = original.execInContainer("true")
            assertEquals(0, stillWorks.exitCode, "the original container must still work after checkpoint()")

            val restored = GenericContainer.fromCheckpoint(cp)
                .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
            restored.start()
            try {
                assertNotEquals(original.execInContainer("hostname").stdout, restored.execInContainer("hostname").stdout,
                    "the restored sandbox must get its own ordinary name, not reuse the original's")
                val read = restored.execInContainer("cat", "/srv/marker.txt")
                assertEquals(0, read.exitCode, "reading the marker file back failed: ${read.stderr}")
                assertTrue(read.stdout.contains("checkpoint-marker"),
                    "restored container is missing the checkpointed filesystem state: ${read.stdout}")
            } finally { restored.stop() }
        } finally {
            runCatching { original.stop() }
            // Snapshot artifacts are not auto-reaped (they're not containers) — see
            // docs/checkpoints.md's cleanup one-liner; this test cleans up its own after itself.
            snapshotRef?.let { ref -> runCatching { Backends.active().removeCheckpoint(ref) } }
        }
    }
}
