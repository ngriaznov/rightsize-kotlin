package dev.rightsize.docker

import dev.rightsize.GenericContainer
import dev.rightsize.RunId
import dev.rightsize.core.Checkpoint
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * End-to-end checkpoint/restore against a real daemon (see docs/checkpoints.md): boot a
 * container, write a marker file into it, checkpoint, stop the original, restore from the
 * checkpoint image, and prove the marker survived — a filesystem capture, not a process one
 * (the restored container is a fresh `sh -c "sleep 120"`, not a resumed one).
 */
@Tag("sandbox-it")
class DockerCheckpointIT {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireDocker() {
            assumeTrue(DockerBackendProvider().isSupported(), "docker socket not reachable")
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("microsandbox", true) != true)
        }
    }

    @Test fun `checkpoint then restore preserves filesystem state written after boot`() {
        val original = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c", "sleep 120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        original.start()

        var checkpointImageRef: String? = null
        try {
            val write = original.execInContainer("sh", "-c", "echo checkpoint-marker > /tmp/marker.txt")
            assertEquals(0, write.exitCode, "writing the marker file failed: ${write.stderr}")

            val cp = original.checkpoint()
            assertEquals("docker", cp.backend)
            checkpointImageRef = cp.ref
            original.stop()

            val restored = GenericContainer.fromCheckpoint(cp)
                .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
            restored.start()
            try {
                val read = restored.execInContainer("cat", "/tmp/marker.txt")
                assertEquals(0, read.exitCode, "reading the marker file back failed: ${read.stderr}")
                assertTrue(read.stdout.contains("checkpoint-marker"),
                    "restored container is missing the checkpointed filesystem state: ${read.stdout}")
            } finally { restored.stop() }
        } finally {
            runCatching { original.stop() }
            // Checkpoint images are not auto-reaped (they're images, not containers) — see
            // docs/checkpoints.md's cleanup one-liner; this test cleans up its own after itself.
            checkpointImageRef?.let { ref ->
                runCatching { ProcessBuilder("docker", "rmi", "-f", ref).inheritIO().start().waitFor() }
            }
        }
    }

    /**
     * `exportTo`/`importFrom` round trip against a real `docker save`/`docker load` cycle (see
     * docs/checkpoints.md's "Moving checkpoints between machines" section): checkpoint a
     * nonce-named container with a marker file, export it, remove the ORIGINAL checkpoint image
     * (proving the archive alone, not the still-present original image, is what restores
     * afterward), import it back, and confirm both that docker's own `load` round-trips the
     * effective ref UNCHANGED (unlike microsandbox, whose import always mints a fresh digest) and
     * that the checkpointed filesystem state survived the round trip. Guard-style cleanup removes
     * the archive file and the loaded image, whether the test passes or fails partway through.
     */
    @Test fun `exportTo then importFrom round-trips a named checkpoint through a real docker save-load archive`(@TempDir tmp: Path) {
        val name = "ckpt-${RunId.value}-archive"
        val original = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c", "sleep 120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        original.start()

        var importedRef: String? = null
        try {
            val write = original.execInContainer("sh", "-c", "echo checkpoint-marker > /tmp/marker.txt")
            assertEquals(0, write.exitCode, "writing the marker file failed: ${write.stderr}")

            val originalCp = original.checkpoint(name)
            original.stop()

            val archive = tmp.resolve("$name.tar")
            originalCp.exportTo(archive)
            assertTrue(Files.exists(archive), "exportTo must write the archive file")

            // Remove the ORIGINAL checkpoint entirely (image + registry entry) — proving the
            // archive, not the still-present original image, is what restores afterward.
            Checkpoint.remove(name)
            assertNull(Checkpoint.find(name), "the original checkpoint must be fully gone before import")

            val imported = Checkpoint.importFrom(archive)
            importedRef = imported.ref
            assertEquals(originalCp.ref, imported.ref, "docker load preserves the original tag — the effective ref must round-trip unchanged")
            assertEquals("docker", imported.backend)

            val found = Checkpoint.find(name)
            assertNotNull(found, "a named archive's import must re-register the checkpoint under the same name")
            assertEquals(imported.ref, found!!.ref)

            val restored = GenericContainer.fromCheckpoint(imported)
                .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
            restored.start()
            try {
                val read = restored.execInContainer("cat", "/tmp/marker.txt")
                assertEquals(0, read.exitCode, "reading the marker file back failed: ${read.stderr}")
                assertTrue(read.stdout.contains("checkpoint-marker"),
                    "restored container is missing the checkpointed filesystem state: ${read.stdout}")
            } finally { restored.stop() }
        } finally {
            runCatching { original.stop() }
            Checkpoint.remove(name)
            importedRef?.let { ref ->
                runCatching { ProcessBuilder("docker", "rmi", "-f", ref).inheritIO().start().waitFor() }
            }
        }
    }
}
