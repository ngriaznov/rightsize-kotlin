package dev.rightsize

import dev.rightsize.core.BackendCapabilities
import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointSpec
import dev.rightsize.core.CheckpointUnsupportedException
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.SandboxHandle
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** A wait strategy that is immediately ready — the fake backend runs nothing to connect to. */
private object CheckpointReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A [FakeBackend] whose [capabilities].checkpoint is settable, and which records every
 * [commitToImage] call instead of throwing the interface's defensive default. */
private open class CheckpointFakeBackend(checkpointSupported: Boolean = true) : FakeBackend() {
    override val capabilities = BackendCapabilities(hardwareIsolated = false, checkpoint = checkpointSupported)
    val committed = mutableListOf<Pair<String, String>>()
    override fun commitToImage(handle: SandboxHandle, imageRef: String) {
        committed += handle.id to imageRef
    }
}

class GenericContainerCheckpointTest {

    @Test fun `checkpoint on an unsupported backend throws before any backend call`() {
        val backend = CheckpointFakeBackend(checkpointSupported = false)
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start()
        try {
            val e = assertThrows(CheckpointUnsupportedException::class.java) { c.checkpoint() }
            assertTrue(backend.committed.isEmpty(), "no commitToImage call when the backend lacks checkpoint support")
            assertTrue(e.message!!.contains("fake"), "message should name the active backend: ${e.message}")
        } finally { c.stop() }
    }

    @Test fun `checkpoint on a never-started container is a state error`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        val e = assertThrows(IllegalStateException::class.java) { c.checkpoint() }
        assertTrue(backend.committed.isEmpty())
        assertTrue(e.message!!.contains("not running"), "message: ${e.message}")
    }

    @Test fun `checkpoint on a stopped container is a state error`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start(); c.stop()
        assertThrows(IllegalStateException::class.java) { c.checkpoint() }
    }

    @Test fun `checkpoint on a running container commits to a rightsize-checkpoint image and carries the spec`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8080).withEnv("A", "1").withCommand("sleep", "120").withMemoryLimit(256)
        c.start()
        try {
            val cp = c.checkpoint()
            assertTrue(Regex("^rightsize/checkpoint:[0-9a-f]{12}$").matches(cp.imageRef),
                "imageRef must be 'rightsize/checkpoint:<12 hex>', got '${cp.imageRef}'")
            val (committedHandleId, committedImageRef) = backend.committed.single()
            assertEquals(cp.imageRef, committedImageRef)
            assertEquals(backend.created.single().name, committedHandleId)
            assertEquals(mapOf("A" to "1"), cp.spec.env)
            assertEquals(listOf("sleep", "120"), cp.spec.command)
            assertEquals(listOf(8080), cp.spec.exposedPorts)
            assertEquals(256L, cp.spec.memoryLimitMb)
        } finally { c.stop() }
    }

    @Test fun `two checkpoints of the same container mint two different random image refs`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start()
        try {
            val first = c.checkpoint()
            val second = c.checkpoint()
            assertNotEquals(first.imageRef, second.imageRef)
        } finally { c.stop() }
    }

    // --- fromCheckpoint ---

    @Test fun `fromCheckpoint builds a container from the checkpoint image and spec defaults`() {
        val backend = CheckpointFakeBackend()
        val cp = Checkpoint(
            imageRef = "rightsize/checkpoint:0123456789ab",
            spec = CheckpointSpec(
                env = mapOf("SEEDED" to "true"), command = listOf("sh", "-c", "sleep 120"),
                exposedPorts = listOf(5432), memoryLimitMb = 512,
            ),
        )
        val restored = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(CheckpointReady)
        restored.start()
        try {
            val spec = backend.created.single()
            assertEquals("rightsize/checkpoint:0123456789ab", spec.image)
            assertEquals(mapOf("SEEDED" to "true"), spec.env)
            assertEquals(listOf("sh", "-c", "sleep 120"), spec.command)
            assertEquals(listOf(5432), spec.ports.map { it.guestPort })
            assertEquals(512L, spec.memoryLimitMb)
        } finally { restored.stop() }
    }

    @Test fun `fromCheckpoint allows the caller to override a spec default before start`() {
        val backend = CheckpointFakeBackend()
        val cp = Checkpoint(
            imageRef = "rightsize/checkpoint:abcdefabcdef",
            spec = CheckpointSpec(env = mapOf("A" to "1"), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
        )
        val restored = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(CheckpointReady)
            .withEnv("B", "2").withMemoryLimit(1024)
        restored.start()
        try {
            val spec = backend.created.single()
            assertEquals(mapOf("A" to "1", "B" to "2"), spec.env)
            assertEquals(1024L, spec.memoryLimitMb)
        } finally { restored.stop() }
    }

    @Test fun `a restored container is ordinary - fresh name, normal reaping ledger entry, normal stop`() {
        // Named "docker" (not "fake") so the real Reaper singleton's ledger actually participates
        // — same precedent as GenericContainerTest's network-ledger-ordering test (see
        // core/build.gradle.kts for the RIGHTSIZE_CACHE_DIR/RIGHTSIZE_REAPER pin on the test task).
        val backend = object : CheckpointFakeBackend() { override val name = "docker" }
        val cp = Checkpoint(
            imageRef = "rightsize/checkpoint:112233445566",
            spec = CheckpointSpec(env = emptyMap(), command = listOf("sleep", "120"), exposedPorts = emptyList(), memoryLimitMb = null),
        )
        val sandboxesFile = dev.rightsize.core.CacheDir.resolve().resolve("runs").resolve("${RunId.value}.sandboxes")
        val before = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()

        val restored = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(CheckpointReady)
        restored.start()
        val name = backend.created.single().name
        assertTrue(name.startsWith("rz-${RunId.value}-"), "a restored container gets an ordinary rz-<runid>-<n> name, not a special one")
        val after = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()
        assertEquals(before.size + 1, after.size, "starting a restored container must append one ledger line, same as any other container")

        restored.stop()
        val afterStop = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()
        assertEquals(before, afterStop, "a normal stop on a restored container must remove its ledger line")
        assertFalse(restored.isRunning)
    }
}
