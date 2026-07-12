package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.diagnostics.LiveContainers
import dev.rightsize.core.reuse.ReuseIdentity
import dev.rightsize.core.reuse.ReuseIdentitySpec
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
        // Ports are reallocated per attempt, not reused after a conflict.
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
