package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.diagnostics.LiveContainers
import dev.rightsize.core.reuse.ReuseIdentity
import dev.rightsize.core.reuse.ReuseIdentitySpec
import dev.rightsize.core.reuse.ReuseFromCheckpointConflictException
import dev.rightsize.core.reuse.ReuseNetworkConflictException
import dev.rightsize.core.reuse.ReuseRecord
import dev.rightsize.core.reuse.ReuseRegistry
import dev.rightsize.core.reuse.SandboxNameCollisionException
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/** A wait strategy that is immediately ready. */
private object ReuseReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A wait strategy that always fails — forces the adopt/create failure paths. */
private object ReuseNeverReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget): Unit = throw IllegalStateException("never ready")
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** Derives an env var from its mapped port in [customizeSpec], the way `KafkaContainer` does. */
private class AdvertisingContainer(image: String) : GenericContainer<AdvertisingContainer>(image) {
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec =
        spec.copy(env = spec.env + ("ADVERTISED_LISTENERS" to "PLAINTEXT://127.0.0.1:${mapped(9092)}"))
}

/**
 * [FakeBackend] plus the two behaviors reuse needs from a real backend: [findRunning] backed by
 * a settable "currently running" name set (populated automatically by [start], cleared by
 * [stop]), and a one-shot simulated name collision on [create] for the collision-retry test.
 */
private open class ReuseFakeBackend : FakeBackend() {
    val runningNames = mutableSetOf<String>()
    var collideOnCreate: String? = null
    // Tracks every removeByName call, for tests proving the orphan-cleanup call does (or does
    // not) happen — distinct from `stopped`/`created`, which cover the handle-based lifecycle.
    val removedByName = mutableListOf<String>()
    // Tracks every findRunning call, for tests proving it's consulted (or skipped) at the right
    // point in the fresh-create path.
    val findRunningCalls = mutableListOf<String>()
    // Ordered log of "create:<name>" / "findRunning:<name>" / "removeByName:<name>" entries —
    // lets a test prove *ordering* (removed BEFORE create), not just that each call happened.
    val callOrder = mutableListOf<String>()
    // Simulates docker's real behavior: `create` 409s when a sandbox is already running under
    // the requested name (docker at least 409s on the name — see the crash-mid-boot orphan
    // scenario this backend exists to reproduce). A test proving "removed before create" is
    // meaningless without this: it's what makes the ordering load-bearing rather than cosmetic.
    var rejectCreateWhenNameRunning = false

    // Simulates a host-port bind conflict the first [failStartsOnPortBind] calls to start(),
    // same shape as GenericContainerTest's PortConflictBackend — for the reuse-fresh
    // port-bind-retry test below.
    var failStartsOnPortBind = 0
    val startAttemptedPorts = mutableListOf<List<Int>>()

    override fun create(spec: ContainerSpec): SandboxHandle {
        callOrder += "create:${spec.name}"
        if (spec.name == collideOnCreate) {
            collideOnCreate = null
            throw SandboxNameCollisionException("sandbox named '${spec.name}' already exists")
        }
        if (rejectCreateWhenNameRunning && spec.name in runningNames) {
            throw SandboxNameCollisionException("sandbox named '${spec.name}' already exists")
        }
        return super.create(spec)
    }

    override fun start(handle: SandboxHandle) {
        startAttemptedPorts += handle.spec.ports.map { it.hostPort }
        if (failStartsOnPortBind > 0) {
            failStartsOnPortBind--
            throw PortBindConflictException(
                "could not bind host port ${handle.spec.ports.first().hostPort}")
        }
        super.start(handle); runningNames += handle.id
    }
    override fun stop(handle: SandboxHandle) { super.stop(handle); runningNames -= handle.id }

    override fun removeByName(name: String) {
        callOrder += "removeByName:$name"
        removedByName += name
        runningNames -= name
    }

    // The specs the adopt path handed to findRunning(spec), one entry per adopt attempt. A found
    // sandbox's handle carries that spec, the way the real backends' handles do.
    val adoptSpecs = mutableListOf<ContainerSpec>()
    override fun findRunning(spec: ContainerSpec): SandboxHandle? {
        adoptSpecs += spec
        val found = super.findRunning(spec) ?: return null
        return object : SandboxHandle {
            override val id = found.id
            override val spec = spec
        }
    }

    override fun findRunning(name: String): SandboxHandle? {
        callOrder += "findRunning:$name"
        findRunningCalls += name
        if (name !in runningNames) return null
        return object : SandboxHandle {
            override val id = name
            override val spec = ContainerSpec(name = name, image = "irrelevant", runId = "run", keepAlive = true)
        }
    }
}

class GenericContainerReuseTest {
    private fun reuseContainer(
        backend: ReuseFakeBackend, cacheDir: Path, envEnabled: Boolean = true,
    ): GenericContainer<*> {
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReuseReady)
            .withExposedPorts(6379).withReuse().withReuseCacheDir(cacheDir)
        return if (envEnabled) c.withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true"))
        else c.withReuseEnvOverride(emptyMap())
    }

    // --- Double opt-in: all four combinations ---

    @Test fun `withReuse plus RIGHTSIZE_REUSE enabled actually reuses`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp, envEnabled = true)
        c.start()
        val spec = backend.created.single()
        assertTrue(spec.keepAlive, "reuse-active create must set keepAlive")
        assertTrue(spec.name.startsWith("rz-reuse-"), "reuse-active create must use the reuse name shape")
    }

    @Test fun `withReuse without RIGHTSIZE_REUSE behaves as an ordinary ephemeral container`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp, envEnabled = false)
        c.start()
        val spec = backend.created.single()
        assertFalse(spec.keepAlive, "API-marked-but-env-disabled must not set keepAlive")
        assertFalse(spec.name.startsWith("rz-reuse-"), "must use the ordinary run-id name shape")
        c.stop()
        assertTrue(backend.stopped.contains(spec.name), "an ordinary container must actually be stopped")
    }

    @Test fun `RIGHTSIZE_REUSE enabled without withReuse behaves as an ordinary ephemeral container`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReuseReady)
            .withExposedPorts(6379).withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true"))
            .withReuseCacheDir(tmp)
        c.start()
        val spec = backend.created.single()
        assertFalse(spec.keepAlive)
        c.stop()
    }

    @Test fun `neither withReuse nor RIGHTSIZE_REUSE is an ordinary ephemeral container`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReuseReady)
            .withExposedPorts(6379)
        c.start()
        assertFalse(backend.created.single().keepAlive)
        c.stop()
    }

    // --- Adopt path ---

    @Test fun `a second equivalent instance adopts the first - no second create call, same port`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val first = reuseContainer(backend, tmp).withEnv("SEED", "1")
        first.start()
        val name = backend.created.single().name
        val port = first.getMappedPort(6379)
        first.stop()   // leaves it running per reuse semantics

        val second = reuseContainer(backend, tmp).withEnv("SEED", "1")
        second.start()
        assertEquals(1, backend.created.size, "adoption must not call backend.create again")
        assertEquals(name, backend.created.single().name)
        assertEquals(port, second.getMappedPort(6379))
        assertTrue(second.isRunning)
        second.stop()
    }

    // A copied file is part of reuse identity (guest path + content hash), so the adopter's own
    // mounts are the files the running sandbox was started with. The adopt path hands them to the
    // backend, which a checkpoint() reboot of an adopted container needs to mount them again.
    @Test fun `adopting passes this container's copied files to the backend`(@TempDir tmp: Path) {
        val file = Files.writeString(tmp.resolve("app.conf"), "same content")
        val backend = ReuseFakeBackend()
        fun container() = reuseContainer(backend, tmp)
            .withCopyFileToContainer(MountableFile.forHostPath(file.toString()), "/etc/app.conf")
        val first = container()
        first.start()
        first.stop()   // leaves it running per reuse semantics

        val second = container()
        second.start()

        assertEquals(1, backend.created.size, "the second instance must adopt, not create")
        assertEquals(listOf(FileMount(file, "/etc/app.conf")), backend.adoptSpecs.single().mounts)
        second.stop()
    }

    // The adopting process never saw the creating spec, but reuse identity says the running sandbox
    // was started from an equivalent one, so the adopt path rebuilds it from this container's own
    // builder, with the registry's host ports. A checkpoint() that reboots the sandbox reads it back.
    @Test fun `adopting hands the backend the spec this container would have created the sandbox with`(@TempDir tmp: Path) {
        val file = Files.writeString(tmp.resolve("app.conf"), "same content")
        val backend = ReuseFakeBackend()
        fun container() = reuseContainer(backend, tmp)
            .withEnv("SEED", "1").withCommand("redis-server", "--appendonly", "yes")
            .withMemoryLimit(256).withDiskLimit(1024).withNetworkDisabled()
            .withCopyFileToContainer(MountableFile.forHostPath(file.toString()), "/etc/app.conf")
        val first = container()
        first.start()
        first.stop()   // leaves it running per reuse semantics

        val second = container()
        second.start()

        assertEquals(1, backend.created.size, "the second instance must adopt, not create")
        assertEquals(backend.created.single(), backend.adoptSpecs.single(),
            "the adopted spec must be the spec the sandbox was created with")
        second.stop()
    }

    // customizeSpec runs on the adopted spec too, against the registry's host ports, so a module
    // that derives env or a command from its mapped port (Kafka's advertised listener) gets the
    // same value the creating process put on its spec.
    @Test fun `the adopted spec goes through customizeSpec with the registry's host ports`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("apache/kafka:3.9.0", emptyMap(), emptyList(), listOf(9092), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "apache/kafka:3.9.0", mapOf(9092 to 45999),
            java.time.Instant.now().toString(), "fake"))

        val c = AdvertisingContainer("apache/kafka:3.9.0").withBackend(backend).waitingFor(ReuseReady)
            .withExposedPorts(9092).withReuse().withReuseCacheDir(tmp)
            .withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true"))
        c.start()

        assertTrue(backend.created.isEmpty(), "must adopt, not create a fresh sandbox")
        val spec = backend.adoptSpecs.single()
        assertEquals(name, spec.name)
        assertEquals(listOf(PortBinding(hostPort = 45999, guestPort = 9092)), spec.ports)
        assertEquals("PLAINTEXT://127.0.0.1:45999", spec.env["ADVERTISED_LISTENERS"])
        c.stop()
    }

    // GenericContainer.checkpoint() has no reuse guard: it hands the backend the adopted handle, and
    // the msb backend reboots the sandbox from that handle's spec. Its published ports and memory
    // limit must be there, or the rebooted sandbox loses them.
    @Test fun `checkpoint of an adopted container hands the backend the adopted ports and memory limit`(@TempDir tmp: Path) {
        val backend = object : ReuseFakeBackend() {
            override val capabilities = BackendCapabilities(hardwareIsolated = false, checkpoint = true)
            var checkpointed: ContainerSpec? = null
            override fun createCheckpoint(handle: SandboxHandle, ref: String): String {
                checkpointed = handle.spec
                return ref
            }
        }
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), 256, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 45124),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp).withMemoryLimit(256)
        c.start()
        try {
            assertTrue(backend.created.isEmpty(), "must adopt, not create a fresh sandbox")
            c.checkpoint()
            val spec = backend.checkpointed!!
            assertEquals(listOf(PortBinding(hostPort = 45124, guestPort = 6379)), spec.ports)
            assertEquals(256L, spec.memoryLimitMb)
        } finally { c.stop() }
    }

    // --- UDP ports (withExposedUdpPorts) ---

    @Test fun `a fresh reuse container records its UDP host ports in the registry`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        c.start()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null,
            emptyList(), exposedUdpPorts = listOf(5353))
        val record = ReuseRegistry(tmp).read(ReuseIdentity.hash(identity))
        assertNotNull(record, "the fresh create must write a registry entry")
        assertEquals(mapOf(5353 to c.getMappedUdpPort(5353)), record!!.udpPorts)
        c.stop()
    }

    @Test fun `an adopted container maps, reserves and hands the backend the registry's UDP host ports`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null,
            emptyList(), exposedUdpPorts = listOf(5353))
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 45202),
            java.time.Instant.now().toString(), "fake", udpPorts = mapOf(5353 to 45203)))

        val c = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        c.start()
        try {
            assertTrue(backend.created.isEmpty(), "must adopt, not create a fresh sandbox")
            assertEquals(45203, c.getMappedUdpPort(5353))
            assertTrue(45203 in FreePorts.issuedView(),
                "an adopted sandbox's UDP host port must be reserved so this process never re-issues it")
            assertTrue(PortBinding(hostPort = 45203, guestPort = 5353, protocol = PortProtocol.UDP) in
                backend.adoptSpecs.single().ports, "the adopted spec must publish the UDP port too")
        } finally { c.stop() }
    }

    @Test fun `a second equivalent UDP-exposing instance adopts with the spec the first created`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val first = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        first.start()
        val udpPort = first.getMappedUdpPort(5353)
        first.stop()   // leaves it running per reuse semantics

        val second = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        second.start()

        assertEquals(1, backend.created.size, "the second instance must adopt, not create")
        assertEquals(udpPort, second.getMappedUdpPort(5353))
        assertEquals(backend.created.single(), backend.adoptSpecs.single())
        second.stop()
    }

    // No wait strategy looks at UDP ports, so a record that doesn't say where an exposed UDP port is
    // published (one written by an earlier release, or by hand) would adopt a sandbox this container
    // can't reach over UDP. It is treated like any other unusable record: replaced by a fresh sandbox.
    @Test fun `a record missing an exposed UDP port is not adopted - the sandbox is replaced`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null,
            emptyList(), exposedUdpPorts = listOf(5353))
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 45204),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        c.start()
        try {
            assertEquals(listOf(name), backend.removedByName, "the unusable sandbox must be removed first")
            assertEquals(name, backend.created.single().name, "a fresh sandbox must be created under the same name")
            assertEquals(mapOf(5353 to c.getMappedUdpPort(5353)), ReuseRegistry(tmp).read(hash)!!.udpPorts)
        } finally { c.stop() }
    }

    @Test fun `stop on a reuse container clears its UDP mapping too`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp).withExposedUdpPorts(5353)
        c.start()
        c.stop()
        val e = assertThrows(IllegalStateException::class.java) { c.getMappedUdpPort(5353) }
        assertTrue(e.message!!.contains("not running"), "should report the not-running error: ${e.message}")
    }

    // A backend written against 0.7.15 overrides findRunning(name, mounts), not findRunning(spec).
    // The spec overload's default still reaches it, with the adopter's mounts.
    @Test fun `a backend that only overrides the deprecated findRunning(name, mounts) still adopts with the mounts`(@TempDir tmp: Path) {
        val file = Files.writeString(tmp.resolve("app.conf"), "same content")
        val backend = object : FakeBackend() {
            val running = mutableSetOf<String>()
            val seenMounts = mutableListOf<List<FileMount>>()
            override fun start(handle: SandboxHandle) { super.start(handle); running += handle.id }
            @Suppress("OVERRIDE_DEPRECATION")
            override fun findRunning(name: String, mounts: List<FileMount>): SandboxHandle? {
                seenMounts += mounts
                if (name !in running) return null
                return object : SandboxHandle {
                    override val id = name
                    override val spec = ContainerSpec(name = name, image = "", runId = "run", mounts = mounts, keepAlive = true)
                }
            }
        }
        fun container() = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReuseReady)
            .withExposedPorts(6379).withReuse().withReuseCacheDir(tmp)
            .withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true"))
            .withCopyFileToContainer(MountableFile.forHostPath(file.toString()), "/etc/app.conf")
        val first = container()
        first.start()
        first.stop()

        val second = container()
        second.start()

        assertEquals(1, backend.created.size, "the second instance must adopt, not create")
        assertEquals(listOf(FileMount(file, "/etc/app.conf")), backend.seenMounts.last())
        second.stop()
    }

    // The adopted spec is built from this container's builder, so every builder setting it copies
    // has to be part of the reuse identity: a container that differs in one of them must look up a
    // different sandbox name, never adopt (and hand the backend a spec for) one started without it.
    @Test fun `a container whose identity differs in a setting the adopted spec carries never adopts`(@TempDir tmp: Path) {
        val variations: List<Pair<String, (GenericContainer<*>) -> GenericContainer<*>>> = listOf(
            "env" to { c -> c.withEnv("SEED", "2") },
            "command" to { c -> c.withCommand("redis-server", "--port", "6380") },
            "exposed port" to { c -> c.withExposedPorts(6380) },
            "exposed UDP port" to { c -> c.withExposedUdpPorts(5353) },
            "memory limit" to { c -> c.withMemoryLimit(512) },
            "disk limit" to { c -> c.withDiskLimit(2048) },
            "tmpfs root" to { c -> c.withTmpfsRoot(64) },
            "network disabled" to { c -> c.withNetworkDisabled() },
        )
        val backend = ReuseFakeBackend()
        val base = reuseContainer(backend, tmp).withEnv("SEED", "1")
        base.start()
        val baseName = backend.created.single().name
        base.stop()   // leaves it running per reuse semantics

        for ((setting, vary) in variations) {
            backend.adoptSpecs.clear()
            val createdBefore = backend.created.size
            val c = vary(reuseContainer(backend, tmp).withEnv("SEED", "1"))
            c.start()
            assertEquals(createdBefore + 1, backend.created.size, "a different $setting must create, not adopt")
            assertNotEquals(baseName, backend.created.last().name, "a different $setting must use a different reuse name")
            assertTrue(backend.adoptSpecs.none { it.name == baseName },
                "a different $setting must never look up the base container's sandbox")
            c.stop()
        }
    }

    // Discriminating proof for the port-collision gap: an adopted sandbox's host port was bound
    // by whoever started it (possibly a different process entirely), never by this process's own
    // FreePorts.allocate() — so without an explicit reservation, this process's own allocator has
    // no idea the port is taken and could hand it to an unrelated sibling container.
    @Test fun `adopting an existing sandbox reserves its ports in this process's own allocator`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        val adoptedPort = 45123
        // Simulates a sandbox some other process already started and left running per reuse
        // semantics: it is live (in runningNames) and registered, but this instance never called
        // FreePorts.allocate() for its port.
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to adoptedPort),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp)
        c.start()
        assertTrue(c.isRunning)
        assertEquals(adoptedPort, c.getMappedPort(6379))
        assertTrue(backend.created.isEmpty(), "must adopt, not create a fresh sandbox")
        assertTrue(adoptedPort in FreePorts.issuedView(),
            "an adopted sandbox's host port must be reserved so this process's own allocator never re-issues it")
        c.stop()
    }

    @Test fun `stale registry - backend says not running - is removed and a fresh sandbox is created`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val staleName = ReuseIdentity.name(hash)
        val registry = ReuseRegistry(tmp)
        registry.write(hash, ReuseRecord(staleName, "redis:8.6-alpine", mapOf(6379 to 40000),
            "2020-01-01T00:00:00Z", "fake"))
        // staleName is NOT in backend.runningNames — findRunning must report it gone.

        val c = reuseContainer(backend, tmp)
        c.start()
        assertEquals(1, backend.created.size, "must have created exactly one fresh sandbox")
        val fresh = registry.read(hash)!!
        assertNotEquals("2020-01-01T00:00:00Z", fresh.createdIso, "the registry entry must be rewritten")
        c.stop()
    }

    @Test fun `corrupted registry JSON falls back to a fresh create`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val registry = ReuseRegistry(tmp)
        Files.createDirectories(tmp.resolve("reuse"))
        Files.writeString(registry.file(hash), "{ this is not valid json")

        val c = reuseContainer(backend, tmp)
        c.start()
        assertEquals(1, backend.created.size)
        assertNotNull(registry.read(hash), "a valid registry entry must exist after the fresh create")
        c.stop()
    }

    // --- Crash-mid-boot orphan: a running-but-unregistered sandbox before fresh create ---
    //
    // Reproduces the observed live defect (see docs/reuse.md): a prior process crashed (or
    // failed its own wait) strictly AFTER `create` succeeded but BEFORE the registry entry was
    // written. That sandbox is still running (keepAlive makes it invisible to reaping) but has
    // no registry entry at all — `registry.read(hash)` returns null exactly as it would for a
    // sandbox that was never created. Without checking `findRunning` first, the next fresh
    // create either 409s (docker) or happily starts a second workload against the same name
    // (msb) — a permanent wedge.

    @Test fun `a running-but-unregistered sandbox is removed before the fresh create`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        backend.rejectCreateWhenNameRunning = true
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        // The orphan: running under the reuse name, but NO registry entry was ever written —
        // simulates a crash between `create` succeeding and the registry write.
        backend.runningNames += name

        val c = reuseContainer(backend, tmp)
        c.start()

        assertTrue(name in backend.removedByName, "the orphaned running sandbox must be removed before create")
        assertTrue(c.isRunning, "the fresh create must succeed once the orphan is out of the way")
        assertEquals(1, backend.created.size, "exactly one fresh sandbox must be created")
        val removeIndex = backend.callOrder.indexOf("removeByName:$name")
        val createIndex = backend.callOrder.indexOf("create:$name")
        assertTrue(removeIndex in 0 until createIndex,
            "removeByName must happen strictly before create: ${backend.callOrder}")
        assertNotNull(ReuseRegistry(tmp).read(hash), "the fresh create must write a new registry entry")
        c.stop()
    }

    @Test fun `registry present and verified adopts without ever calling removeByName`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 45700),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp)
        c.start()

        assertTrue(c.isRunning)
        assertTrue(backend.created.isEmpty(), "a verified registry hit must adopt, not create")
        assertTrue(backend.removedByName.isEmpty(), "adopting a live, registered sandbox must never call removeByName")
        c.stop()
    }

    @Test fun `findRunning returning null before a fresh create never calls removeByName`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        // No registry entry, and no sandbox running under the reuse name either — the ordinary,
        // non-orphan "first ever start" case.
        val c = reuseContainer(backend, tmp)
        c.start()

        assertTrue(c.isRunning)
        assertEquals(1, backend.created.size)
        assertTrue(backend.findRunningCalls.isNotEmpty(), "the fresh-create path must still consult findRunning")
        assertTrue(backend.removedByName.isEmpty(), "findRunning reporting nothing running must never call removeByName")
        c.stop()
    }

    // --- Stop semantics ---

    @Test fun `stop on a reuse-active container leaves the sandbox running - no backend stop or remove call`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp)
        c.start()
        val name = backend.created.single().name
        c.stop()
        assertFalse(backend.stopped.contains(name), "reuse stop() must never call backend.stop")
        assertFalse(c.isRunning, "the instance itself must report not-running after its own stop()")
        assertTrue(name in backend.runningNames, "the sandbox itself must still be running")
    }

    // --- Live-container registry (diagnostics) on the reuse paths ---

    @Test fun `a freshly created reuse container is registered in LiveContainers, its own stop deregisters it`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val c = reuseContainer(backend, tmp)
        c.start()
        val name = backend.created.single().name
        assertTrue(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "createReuseFresh must register the container in LiveContainers, same as an ordinary start")
        c.stop()
        assertFalse(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "stop() on a reuse-active container must deregister from LiveContainers even though the " +
                "sandbox itself is left running")
    }

    @Test fun `an adopted reuse container is registered in LiveContainers, its own stop deregisters it`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 45600),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp)
        c.start()
        assertTrue(backend.created.isEmpty(), "must adopt, not create a fresh sandbox")
        assertTrue(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "tryAdopt must register the adopted sandbox in LiveContainers, same as a fresh create")
        c.stop()
        assertFalse(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "stop() on an adopted reuse container must deregister from LiveContainers even though the " +
                "sandbox itself is left running")
    }

    // Named "docker" (not "fake") so the real `Reaper` singleton's ledger actually participates
    // — see core/build.gradle.kts (RIGHTSIZE_CACHE_DIR/RIGHTSIZE_REAPER pinned for the `test`
    // task) and the identical precedent in GenericContainerTest's network-ledger-ordering test.
    @Test fun `a reuse container never appears in the run's sandboxes ledger, start to stop`(@TempDir tmp: Path) {
        val backend = object : ReuseFakeBackend() { override val name = "docker" }
        val c = reuseContainer(backend, tmp)
        val sandboxesFile = dev.rightsize.core.CacheDir.resolve().resolve("runs").resolve("${RunId.value}.sandboxes")
        val before = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()
        c.start()
        val after = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()
        assertEquals(before, after, "starting a reuse container must never append a ledger line")
        c.stop()
        val afterStop = if (Files.exists(sandboxesFile)) Files.readAllLines(sandboxesFile) else emptyList()
        assertEquals(before, afterStop)
    }

    // --- Unsupported combinations ---

    @Test fun `reuse plus a custom network is a typed error`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val net = Network.newNetwork()
        val c = reuseContainer(backend, tmp).withNetwork(net)
        assertThrows(ReuseNetworkConflictException::class.java) { c.start() }
    }

    // checkpointRef is deliberately excluded from reuse identity (see ReuseIdentitySpec's doc) —
    // the two features are not a supported combination, same shape as the network conflict above.
    @Test fun `reuse plus a restored checkpoint container is a typed error`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val cp = Checkpoint(
            ref = "rightsize/checkpoint:0123456789ab", backend = "fake",
            spec = CheckpointSpec(env = emptyMap(), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
        )
        val c = GenericContainer.fromCheckpoint(cp).withBackend(backend).waitingFor(ReuseReady)
            .withReuse().withReuseCacheDir(tmp).withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true"))
        assertThrows(ReuseFromCheckpointConflictException::class.java) { c.start() }
    }

    // --- Port-bind conflict on the fresh-create path ---

    // Reuse fresh-create shares the ordinary path's PORT_BIND_ATTEMPTS budget (5): a host-port
    // bind conflict on start() must retry with freshly allocated ports rather than failing after
    // a single attempt.
    @Test fun `createReuseFresh retries with fresh host ports on a bind conflict, same as an ordinary create`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        backend.failStartsOnPortBind = 2
        val c = reuseContainer(backend, tmp)
        c.start()
        assertTrue(c.isRunning)
        assertEquals(3, backend.startAttemptedPorts.size, "start attempted three times before succeeding")
        // Ports are reallocated per attempt and never repeat: conflicted ports stay quarantined
        // in FreePorts until the retry loop exits, so this distinctness is guaranteed, not an
        // accident of the OS's ephemeral-port choice.
        assertEquals(backend.startAttemptedPorts.size,
            backend.startAttemptedPorts.map { it.single() }.distinct().size)
        assertEquals(3, backend.created.size, "each attempt recreates the container, same as the ordinary path")
        assertTrue(backend.created.all { it.keepAlive }, "every attempt's spec must still be reuse-marked")
        c.stop()
    }

    // Exhausting the retry budget on the fresh-create path must fail the same way the ordinary
    // path does, not hang or silently give up early.
    @Test fun `createReuseFresh exhausting the port-bind retry budget fails with the port-bind error`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        backend.failStartsOnPortBind = 99
        val c = reuseContainer(backend, tmp)
        assertThrows(IllegalStateException::class.java) { c.start() }
        assertFalse(c.isRunning)
    }

    // --- Name collision ---

    @Test fun `name collision on create re-enters the adopt path once`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        // Simulate a concurrent winner: the sandbox is already running and registered, but our
        // own create() call for the same name still races into a collision.
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 41000),
            java.time.Instant.now().toString(), "fake"))
        backend.collideOnCreate = name

        val c = reuseContainer(backend, tmp)
        c.start()
        assertTrue(c.isRunning)
        assertEquals(41000, c.getMappedPort(6379), "must adopt the winner's recorded port, not create its own")
        assertTrue(backend.created.isEmpty(), "the colliding create() attempt must not count as a successful create")
        c.stop()
    }

    @Test fun `name collision with no winner to adopt propagates the original failure`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.collideOnCreate = name   // no registry entry to adopt from

        val c = reuseContainer(backend, tmp)
        val e = assertThrows(SandboxNameCollisionException::class.java) { c.start() }
        assertTrue(e.message!!.contains(name))
        assertFalse(c.isRunning)
    }

    // A registry entry exists (another process seemed to win), but its sandbox is actually gone
    // by the time we look — the collision retry must not swallow the original failure just
    // because *some* registry entry was there to try.
    @Test fun `name collision with a registry entry whose sandbox is gone still propagates the original failure`(@TempDir tmp: Path) {
        val backend = ReuseFakeBackend()
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 41000),
            java.time.Instant.now().toString(), "fake"))
        // Deliberately NOT added to backend.runningNames: findRunning must report it gone.
        backend.collideOnCreate = name

        val c = reuseContainer(backend, tmp)
        assertThrows(SandboxNameCollisionException::class.java) { c.start() }
        assertFalse(c.isRunning)
    }

    // The typed SandboxNameCollisionException is the primary signal; a plain exception with a
    // recognizable phrasing must be classified identically (fallback string classifier).
    @Test fun `a plain exception with an already-exists phrasing is also treated as a name collision`(@TempDir tmp: Path) {
        val backend = object : ReuseFakeBackend() {
            override fun create(spec: ContainerSpec): SandboxHandle {
                if (spec.name == collideOnCreate) {
                    collideOnCreate = null
                    throw RuntimeException("sandbox '${spec.name}' already exists")
                }
                return super.create(spec)
            }
        }
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        backend.runningNames += name
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 41500),
            java.time.Instant.now().toString(), "fake"))
        backend.collideOnCreate = name

        val c = reuseContainer(backend, tmp)
        c.start()
        assertEquals(41500, c.getMappedPort(6379))
        c.stop()
    }

    // tryAdopt must swallow a findRunning that throws (a backend query failure), not propagate
    // it — the caller falls back to a fresh create exactly as it would for a plain "not found".
    @Test fun `adopt swallows an exception from findRunning and falls back to a fresh create`(@TempDir tmp: Path) {
        val backend = object : ReuseFakeBackend() {
            override fun findRunning(name: String): SandboxHandle? = error("backend query failed")
        }
        val identity = ReuseIdentitySpec("redis:8.6-alpine", emptyMap(), emptyList(), listOf(6379), null, emptyList())
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)
        ReuseRegistry(tmp).write(hash, ReuseRecord(name, "redis:8.6-alpine", mapOf(6379 to 42000),
            java.time.Instant.now().toString(), "fake"))

        val c = reuseContainer(backend, tmp)
        c.start()
        assertTrue(c.isRunning)
        assertEquals(1, backend.created.size, "findRunning failing must fall back to a fresh create")
        c.stop()
    }
}
