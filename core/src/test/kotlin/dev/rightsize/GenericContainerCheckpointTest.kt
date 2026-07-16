package dev.rightsize

import dev.rightsize.core.BackendCapabilities
import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointBackendMismatchException
import dev.rightsize.core.CheckpointSpec
import dev.rightsize.core.CheckpointUnsupportedException
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.SandboxHandle
import dev.rightsize.core.checkpoint.CheckpointRegistry
import dev.rightsize.core.checkpoint.InvalidCheckpointNameException
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A wait strategy that is immediately ready — the fake backend runs nothing to connect to. */
private object CheckpointReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A wait strategy that counts every [waitUntilReady] call — proves `checkpoint()` does (or
 * does not) re-run it depending on `capabilities.checkpointRestartsWorkload`. */
private class CountingWait : WaitStrategy {
    var calls = 0
    override fun waitUntilReady(target: WaitTarget) { calls++ }
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A [FakeBackend] whose [capabilities].checkpoint (and, for the msb-shaped-ref/re-wait tests,
 * `checkpointRestartsWorkload`) are settable, and which records every [createCheckpoint] call
 * instead of throwing the interface's defensive default. Also records [removeCheckpoint] calls
 * (for the named-checkpoint replace tests below) and can be made to fail [createCheckpoint] via
 * [failCreateCheckpoint], for the "no registry entry on backend failure" test. */
private open class CheckpointFakeBackend(
    checkpointSupported: Boolean = true,
    checkpointRestartsWorkload: Boolean = false,
) : FakeBackend() {
    override val capabilities = BackendCapabilities(
        hardwareIsolated = false, checkpoint = checkpointSupported,
        checkpointRestartsWorkload = checkpointRestartsWorkload)
    val committed = mutableListOf<Pair<String, String>>()
    val removedRefs = mutableListOf<String>()
    var failCreateCheckpoint = false
    override fun createCheckpoint(handle: SandboxHandle, ref: String) {
        if (failCreateCheckpoint) error("simulated backend checkpoint failure")
        committed += handle.id to ref
    }
    override fun removeCheckpoint(ref: String) { removedRefs += ref }
}

class GenericContainerCheckpointTest {

    @Test fun `checkpoint on an unsupported backend throws before any backend call`() {
        val backend = CheckpointFakeBackend(checkpointSupported = false)
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start()
        try {
            val e = assertThrows(CheckpointUnsupportedException::class.java) { c.checkpoint() }
            assertTrue(backend.committed.isEmpty(), "no createCheckpoint call when the backend lacks checkpoint support")
            assertTrue(e.message!!.contains("fake"), "message should name the active backend: ${e.message}")
        } finally { c.stop() }
    }

    @Test fun `checkpoint validates the name before the capability gate - an invalid name on an unsupported backend still raises the name error`(
        @TempDir tmp: Path,
    ) {
        // Pins the doc comment's own claim (checkpoint()'s KDoc, "checked before any backend
        // call, before even the capability gate's own backend-name read"): a backend with
        // capabilities.checkpoint = false combined with an invalid name must surface
        // InvalidCheckpointNameException, not CheckpointUnsupportedException.
        val backend = CheckpointFakeBackend(checkpointSupported = false)
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            assertThrows(InvalidCheckpointNameException::class.java) { c.checkpoint("Bad_Name!") }
            assertTrue(backend.committed.isEmpty(), "no createCheckpoint call either way")
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

    @Test fun `checkpoint on a running container commits to a rightsize-checkpoint ref and carries the spec`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8080).withEnv("A", "1").withCommand("sleep", "120").withMemoryLimit(256)
        c.start()
        try {
            val cp = c.checkpoint()
            assertTrue(Regex("^rightsize/checkpoint:[0-9a-f]{12}$").matches(cp.ref),
                "ref must be 'rightsize/checkpoint:<12 hex>' for a non-msb backend, got '${cp.ref}'")
            assertEquals("fake", cp.backend)
            val (committedHandleId, committedRef) = backend.committed.single()
            assertEquals(cp.ref, committedRef)
            assertEquals(backend.created.single().name, committedHandleId)
            assertEquals(mapOf("A" to "1"), cp.spec.env)
            assertEquals(listOf("sleep", "120"), cp.spec.command)
            assertEquals(listOf(8080), cp.spec.exposedPorts)
            assertEquals(256L, cp.spec.memoryLimitMb)
        } finally { c.stop() }
    }

    @Test fun `checkpoint mints an msb-shaped snapshot ref when the active backend is named microsandbox`() {
        val backend = object : CheckpointFakeBackend() { override val name = "microsandbox" }
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start()
        try {
            val cp = c.checkpoint()
            assertTrue(Regex("^rz-ckpt-[0-9a-f]{12}$").matches(cp.ref),
                "ref must be 'rz-ckpt-<12 hex>' for the microsandbox backend, got '${cp.ref}'")
            assertEquals("microsandbox", cp.backend)
        } finally { c.stop() }
    }

    @Test fun `two checkpoints of the same container mint two different random refs`() {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
        c.start()
        try {
            val first = c.checkpoint()
            val second = c.checkpoint()
            assertNotEquals(first.ref, second.ref)
        } finally { c.stop() }
    }

    // --- checkpointRestartsWorkload gates the post-checkpoint wait re-run ---

    @Test fun `checkpoint re-runs the wait strategy when checkpointRestartsWorkload is true`() {
        val backend = CheckpointFakeBackend(checkpointRestartsWorkload = true)
        val wait = CountingWait()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(wait)
        c.start()
        try {
            assertEquals(1, wait.calls, "start() itself must have run the wait strategy once")
            c.checkpoint()
            assertEquals(2, wait.calls, "checkpoint() must re-run the wait strategy when checkpointRestartsWorkload is true")
        } finally { c.stop() }
    }

    @Test fun `checkpoint does not re-run the wait strategy when checkpointRestartsWorkload is false`() {
        val backend = CheckpointFakeBackend(checkpointRestartsWorkload = false)
        val wait = CountingWait()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(wait)
        c.start()
        try {
            assertEquals(1, wait.calls)
            c.checkpoint()
            assertEquals(1, wait.calls, "checkpoint() must not re-run the wait strategy when the container was left undisturbed")
        } finally { c.stop() }
    }

    // --- checkpointRestartsWorkload also re-installs emulated network links ---

    @Test fun `checkpoint re-installs network links when checkpointRestartsWorkload is true`() {
        val backend = CheckpointFakeBackend(checkpointRestartsWorkload = true)
        val net = Network.newNetwork()
        val stub = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8888).withNetwork(net).withNetworkAliases("configuration-stub")
        stub.start()
        val app = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8080).withNetwork(net)
        app.start()
        try {
            val linksAtStart = backend.installedLinks.single().second
            app.checkpoint()
            assertEquals(2, backend.installedLinks.size,
                "installNetworkLinks must be called again by checkpoint() when checkpointRestartsWorkload is true")
            val (_, linksAfterCheckpoint) = backend.installedLinks.last()
            assertEquals(linksAtStart, linksAfterCheckpoint, "checkpoint() must re-install the same links start() installed")
        } finally { app.stop(); stub.stop() }
    }

    @Test fun `checkpoint does not re-install network links when checkpointRestartsWorkload is false`() {
        val backend = CheckpointFakeBackend(checkpointRestartsWorkload = false)
        val net = Network.newNetwork()
        val stub = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8888).withNetwork(net).withNetworkAliases("configuration-stub")
        stub.start()
        val app = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withExposedPorts(8080).withNetwork(net)
        app.start()
        try {
            assertEquals(1, backend.installedLinks.size)
            app.checkpoint()
            assertEquals(1, backend.installedLinks.size,
                "installNetworkLinks must not be called again when checkpointRestartsWorkload is false")
        } finally { app.stop(); stub.stop() }
    }

    // --- fromCheckpoint ---

    @Test fun `fromCheckpoint builds a container from the checkpoint ref and spec defaults`() {
        val backend = CheckpointFakeBackend()
        val cp = Checkpoint(
            ref = "rightsize/checkpoint:0123456789ab", backend = "fake",
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
            assertEquals("rightsize/checkpoint:0123456789ab", spec.checkpointRef)
            assertEquals(mapOf("SEEDED" to "true"), spec.env)
            assertEquals(listOf("sh", "-c", "sleep 120"), spec.command)
            assertEquals(listOf(5432), spec.ports.map { it.guestPort })
            assertEquals(512L, spec.memoryLimitMb)
        } finally { restored.stop() }
    }

    @Test fun `fromCheckpoint allows the caller to override a spec default before start`() {
        val backend = CheckpointFakeBackend()
        val cp = Checkpoint(
            ref = "rightsize/checkpoint:abcdefabcdef", backend = "fake",
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
            ref = "rightsize/checkpoint:112233445566", backend = "docker",
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

    // --- Backend mismatch: restoring under a different active backend than the creator ---

    @Test fun `fromCheckpoint under a different active backend throws before any backend call`() {
        val backend = object : CheckpointFakeBackend() { override val name = "docker" }
        val cp = Checkpoint(
            ref = "rz-ckpt-0123456789ab", backend = "microsandbox",
            spec = CheckpointSpec(env = emptyMap(), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
        )
        val restored = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(CheckpointReady)
        val e = assertThrows(CheckpointBackendMismatchException::class.java) { restored.start() }
        assertTrue(backend.created.isEmpty(), "no create call when the checkpoint's creator backend doesn't match")
        assertTrue(e.message!!.contains("microsandbox") && e.message!!.contains("docker"),
            "message should name both backends: ${e.message}")
    }

    @Test fun `fromCheckpoint under the same active backend starts normally`() {
        val backend = object : CheckpointFakeBackend() { override val name = "docker" }
        val cp = Checkpoint(
            ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            spec = CheckpointSpec(env = emptyMap(), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
        )
        val restored = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(CheckpointReady)
        restored.start()
        try { assertTrue(restored.isRunning) } finally { restored.stop() }
    }

    // --- Named checkpoints (see docs/checkpoints.md's "Reusing checkpoints across runs" section) ---

    @Test fun `checkpoint with an invalid name throws before any backend call`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            val e = assertThrows(InvalidCheckpointNameException::class.java) { c.checkpoint("Bad_Name!") }
            assertTrue(backend.committed.isEmpty(), "no createCheckpoint call for an invalid name")
            assertTrue(e.message!!.contains("Bad_Name!"), "message should name the offending value: ${e.message}")
        } finally { c.stop() }
    }

    @Test fun `checkpoint name validation rejects every invalid shape before any backend call`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            listOf("-leading-hyphen", "Uppercase", "has_underscore", "", "a".repeat(42), "has space").forEach { bad ->
                assertThrows(InvalidCheckpointNameException::class.java) { c.checkpoint(bad) }
            }
            assertTrue(backend.committed.isEmpty(), "no createCheckpoint call for any invalid name")
        } finally { c.stop() }
    }

    @Test fun `checkpoint name validation accepts the boundary-valid shapes`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            assertDoesNotThrow { c.checkpoint("a") }             // single char, minimal valid name
            assertDoesNotThrow { c.checkpoint("a".repeat(41)) }   // 41 chars, the maximum length
        } finally { c.stop() }
    }

    @Test fun `a named checkpoint writes the pinned registry JSON only after the backend succeeds`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
            .withExposedPorts(8080).withEnv("A", "1").withCommand("sleep", "120").withMemoryLimit(256)
        c.start()
        try {
            val cp = c.checkpoint("seeded-db")
            val file = tmp.resolve("checkpoints").resolve("seeded-db.json")
            assertTrue(Files.exists(file), "registry file must exist after a successful named checkpoint")
            val json = Files.readString(file)
            // Field names (including the nested spec object's own fields) are pinned identically
            // across the three rightsize libraries — asserted literally, not just via round-trip.
            for (field in listOf(
                "\"name\"", "\"ref\"", "\"backend\"", "\"createdIso\"", "\"spec\"",
                "\"env\"", "\"command\"", "\"exposedPorts\"", "\"memoryLimitMb\"",
            )) {
                assertTrue(field in json, "registry JSON missing pinned field $field: $json")
            }
            assertTrue("\"name\":\"seeded-db\"" in json, "json: $json")
            assertTrue("\"ref\":\"${cp.ref}\"" in json, "json: $json")
            assertTrue("\"backend\":\"fake\"" in json, "json: $json")

            val record = CheckpointRegistry(tmp).read("seeded-db")
            assertEquals(cp.ref, record?.ref)
            assertEquals(cp.backend, record?.backend)
            assertEquals(cp.spec, record?.spec)
        } finally { c.stop() }
    }

    @Test fun `a failed backend checkpoint leaves no registry entry`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend().apply { failCreateCheckpoint = true }
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            assertThrows(IllegalStateException::class.java) { c.checkpoint("seeded-db") }
            assertFalse(Files.exists(tmp.resolve("checkpoints").resolve("seeded-db.json")),
                "a failed backend checkpoint must never leave a registry entry")
        } finally { c.stop() }
    }

    @Test fun `an unnamed checkpoint never touches the registry`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            c.checkpoint()
            assertFalse(Files.isDirectory(tmp.resolve("checkpoints")),
                "an unnamed checkpoint must never create the registry directory")
        } finally { c.stop() }
    }

    @Test fun `re-checkpointing an existing name replaces it - old ref removed first, then re-captured`(@TempDir tmp: Path) {
        val backend = CheckpointFakeBackend()
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            val first = c.checkpoint("seeded-db")
            assertTrue(backend.removedRefs.isEmpty(), "the first checkpoint() for a name has nothing to replace")

            val second = c.checkpoint("seeded-db")
            // A named checkpoint's ref is derived from the name, not random — so "replace" means
            // the SAME ref is best-effort removed and then re-captured, not swapped for a new one.
            assertEquals(first.ref, second.ref, "a named checkpoint's ref must stay derived from the name")
            assertEquals(listOf(first.ref), backend.removedRefs,
                "the OLD ref must be best-effort removed before the new checkpoint is captured")
            assertEquals(2, backend.committed.size, "createCheckpoint must run again to re-capture the replacement")

            val record = CheckpointRegistry(tmp).read("seeded-db")
            assertEquals(second.ref, record?.ref, "the registry must hold the current ref after replace")
        } finally { c.stop() }
    }

    @Test fun `re-checkpointing a name recorded under a different backend skips the removal call but still captures and rewrites the entry`(
        @TempDir tmp: Path,
    ) {
        val backend = object : CheckpointFakeBackend() { override val name = "docker" }
        val c = GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CheckpointReady)
            .withCheckpointCacheDir(tmp)
        c.start()
        try {
            // Seed a registry entry as if a PRIOR process had written it under microsandbox —
            // the active backend here (docker) never created "rz-ckpt-seeded-db".
            CheckpointRegistry(tmp).write(
                "seeded-db",
                dev.rightsize.core.checkpoint.CheckpointRecord(
                    name = "seeded-db", ref = "rz-ckpt-seeded-db", backend = "microsandbox",
                    createdIso = "2026-07-11T12:00:00Z",
                    spec = CheckpointSpec(env = emptyMap(), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
                ),
            )

            val cp = c.checkpoint("seeded-db")

            assertTrue(backend.removedRefs.isEmpty(),
                "a different-backend entry's ref must never reach removeCheckpoint on the active backend")
            assertEquals(1, backend.committed.size, "the new checkpoint must still be captured")
            assertEquals("rightsize/checkpoint:seeded-db", cp.ref)
            assertEquals("docker", cp.backend)

            val record = CheckpointRegistry(tmp).read("seeded-db")
            assertEquals("docker", record?.backend, "the entry must be rewritten to the new (active) backend")
            assertEquals(cp.ref, record?.ref)
        } finally { c.stop() }
    }
}
