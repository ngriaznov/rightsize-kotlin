package dev.rightsize.msb

import dev.rightsize.GenericContainer
import dev.rightsize.RunId
import dev.rightsize.core.Backends
import dev.rightsize.core.CacheDir
import dev.rightsize.core.Checkpoint
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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

    /**
     * `exportTo`/`importFrom` round trip against a real `.tar.zst` `msb snapshot export`/`import`
     * cycle (see docs/checkpoints.md's "Moving checkpoints between machines" section): checkpoint
     * a nonce-named sandbox with a `/srv` marker, export it, remove the ORIGINAL checkpoint
     * (artifact and registry entry both — proving the archive alone, not the original artifact,
     * is what restores afterward), import it back, and confirm both that the imported ref is a
     * DIGEST different from the original name-derived ref (msb's own import contract: the
     * original snapshot name is never preserved) and that the checkpointed filesystem state
     * survived the round trip. Guard-style cleanup removes the archive file, the imported
     * digest's own snapshot artifact, and the registry entry, whether the test passes or fails
     * partway through.
     */
    @Test fun `exportTo then importFrom round-trips a named checkpoint through a real msb snapshot archive`(@TempDir tmp: Path) {
        val name = "ckpt-${RunId.value}-archive"
        val original = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c", "echo BOOT-MARKER; sleep 120")
            .waitingFor(Wait.forLogMessage(".*BOOT-MARKER.*"))
        original.start()

        var importedRef: String? = null
        try {
            val write = original.execInContainer("sh", "-c", // /srv, not /tmp: the microsandbox guest mounts /tmp as tmpfs, which a disk snapshot cannot capture.
                "echo checkpoint-marker > /srv/marker.txt && sync")
            assertEquals(0, write.exitCode, "writing the marker file failed: ${write.stderr}")

            val originalCp = original.checkpoint(name)
            original.stop()

            val archive = tmp.resolve("$name.tar")
            originalCp.exportTo(archive)
            assertTrue(Files.exists(archive), "exportTo must write the archive file")

            // Remove the ORIGINAL checkpoint entirely (artifact + registry entry) — proving the
            // archive, not the still-present original artifact, is what restores afterward.
            Checkpoint.remove(name)
            assertNull(Checkpoint.find(name), "the original checkpoint must be fully gone before import")

            val imported = Checkpoint.importFrom(archive)
            importedRef = imported.ref
            assertNotEquals(originalCp.ref, imported.ref,
                "msb's snapshot import never preserves the original name-derived ref — the effective ref must be a digest")
            assertEquals("microsandbox", imported.backend)

            val found = Checkpoint.find(name)
            assertNotNull(found, "a named archive's import must re-register the checkpoint under the same name")
            assertEquals(imported.ref, found!!.ref)

            val restored = GenericContainer.fromCheckpoint(imported)
                .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
            restored.start()
            try {
                val read = restored.execInContainer("cat", "/srv/marker.txt")
                assertEquals(0, read.exitCode, "reading the marker file back failed: ${read.stderr}")
                assertTrue(read.stdout.contains("checkpoint-marker"),
                    "restored container is missing the checkpointed filesystem state: ${read.stdout}")
            } finally { restored.stop() }
        } finally {
            runCatching { original.stop() }
            Checkpoint.remove(name)
            importedRef?.let { ref -> runCatching { Backends.active().removeCheckpoint(ref) } }
        }
    }
}
