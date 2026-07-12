package dev.rightsize.docker

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
            checkpointImageRef = cp.imageRef
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
}
