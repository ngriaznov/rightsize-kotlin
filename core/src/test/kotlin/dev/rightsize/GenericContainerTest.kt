package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** A wait strategy that is immediately ready — the fake backend runs nothing to connect to. */
private object ReadyImmediately : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A wait strategy that always fails — used to force the start()-catch(stop) cleanup path. */
private object NeverReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget): Unit = throw IllegalStateException("never ready")
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

open class FakeBackend : SandboxBackend {
    val created = mutableListOf<ContainerSpec>()
    val started = mutableListOf<String>(); val stopped = mutableListOf<String>()
    val installedLinks = mutableListOf<Pair<String, List<NetworkLink>>>()
    var logsText = ""
    override val name = "fake"
    override val supportsNativeNetworks = false
    override fun create(spec: ContainerSpec) = object : SandboxHandle {
        override val id = spec.name; override val spec = spec
    }.also { created += spec }
    override fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>) {
        if (links.isNotEmpty()) installedLinks += handle.id to links
    }
    override fun start(handle: SandboxHandle) { started += handle.id }
    override fun stop(handle: SandboxHandle) { stopped += handle.id }
    override fun remove(handle: SandboxHandle) {}
    override fun exec(handle: SandboxHandle, cmd: List<String>) = ExecResult(0, cmd.joinToString(" "), "")
    override fun logs(handle: SandboxHandle) = logsText
    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit) = AutoCloseable {}
    override fun ensureNetwork(networkId: String) {}
    override fun removeNetwork(networkId: String) {}
}

class GenericContainerTest {
    private fun container(backend: FakeBackend) =
        GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately) // internal test seam

    @Test fun `start allocates host ports, creates spec, waits, and maps ports`() {
        val backend = FakeBackend()
        val c = container(backend).withExposedPorts(6379).withEnv("A", "1")
        c.start()
        val spec = backend.created.single()
        assertEquals("redis:8.6-alpine", spec.image)
        assertEquals(mapOf("A" to "1"), spec.env)
        assertEquals(6379, spec.ports.single().guestPort)
        assertTrue(spec.ports.single().hostPort > 0)
        assertEquals(spec.ports.single().hostPort, c.getMappedPort(6379))
        assertTrue(c.isRunning)
        c.stop(); assertFalse(c.isRunning)
    }

    // getMappedPort used to say "not exposed" even when the real cause was that the container
    // had simply stopped — misleading, since "not exposed" suggests a missing withExposedPorts()
    // call. It now distinguishes the two cases explicitly.
    @Test fun `getMappedPort reports not-running (not not-exposed) after stop clears the mappings`() {
        val backend = FakeBackend()
        val c = container(backend).withExposedPorts(6379)
        c.start()
        assertTrue(c.getMappedPort(6379) > 0)
        c.stop()
        val e = assertThrows(IllegalStateException::class.java) { c.getMappedPort(6379) }
        assertTrue(e.message!!.contains("not running"), "should report the not-running error: ${e.message}")
        assertFalse(e.message!!.contains("not exposed"), "must not blame a missing withExposedPorts call: ${e.message}")
    }

    @Test fun `getMappedPort reports not-exposed for a port never declared via withExposedPorts`() {
        val backend = FakeBackend()
        val c = container(backend).withExposedPorts(6379)
        c.start()
        val e = assertThrows(IllegalStateException::class.java) { c.getMappedPort(9999) }
        assertTrue(e.message!!.contains("not exposed"), "should report the not-exposed error: ${e.message}")
        c.stop()
    }

    @Test fun `starting on a network installs links to running siblings`() {
        val backend = FakeBackend()
        val net = Network.newNetwork()
        val stub = container(backend).withExposedPorts(8888).withNetwork(net).withNetworkAliases("configuration-stub")
        stub.start()
        val app = container(backend).withExposedPorts(8080).withNetwork(net)
        app.start()
        val (consumerId, links) = backend.installedLinks.single()
        assertEquals(backend.created.last().name, consumerId)   // links installed on the app container
        assertEquals(listOf(NetworkLink("configuration-stub", 8888, stub.getMappedPort(8888))), links)
        assertEquals("configuration-stub:8888", net.resolve("configuration-stub", 8888))
        assertThrows(IllegalStateException::class.java) { net.resolve("nope", 1) }
    }

    @Test fun `single container on a network installs no links but is still registered as a member`() {
        val backend = FakeBackend()
        val net = Network.newNetwork()
        val solo = container(backend).withExposedPorts(9999).withNetwork(net).withNetworkAliases("solo")
        solo.start()
        assertTrue(backend.installedLinks.isEmpty(), "a lone container must not link to itself")
        // Proof that `register` still ran despite no links being installed: a second container
        // joining afterward gets a link back to the first (register happened after link install).
        val joiner = container(backend).withExposedPorts(8080).withNetwork(net)
        joiner.start()
        val (_, links) = backend.installedLinks.single()
        assertEquals(listOf(NetworkLink("solo", 9999, solo.getMappedPort(9999))), links)
    }

    @Test fun `start records the network in the reaper ledger before asking the backend to create it`() {
        // Named "docker" (not "fake") so the real `Reaper` singleton's ledger actually
        // participates for this one test — see core/build.gradle.kts, which points
        // RIGHTSIZE_CACHE_DIR at a build-local dir and RIGHTSIZE_REAPER at "sweep" (ledger
        // writes without the watchdog's detached-process spawn) for exactly this purpose.
        val backend = object : FakeBackend() {
            override val name = "docker"
            var networkWasLedgeredBeforeEnsureNetwork = false
            override fun ensureNetwork(networkId: String) {
                val networksFile = dev.rightsize.core.CacheDir.resolve().resolve("runs").resolve("${RunId.value}.networks")
                networkWasLedgeredBeforeEnsureNetwork = Files.exists(networksFile) &&
                    Files.readAllLines(networksFile).contains(networkId)
                super.ensureNetwork(networkId)
            }
        }
        val net = Network.newNetwork()
        val c = container(backend).withExposedPorts(8080).withNetwork(net)
        c.start()
        assertTrue(backend.networkWasLedgeredBeforeEnsureNetwork,
            "the network must be appended to the reaper's ledger before the backend is asked to " +
                "create it — otherwise a crash in between leaks the network with no ledger entry " +
                "to ever find it")
        c.stop()
    }

    @Test fun `wait-strategy failure stops the container and releases its host ports`() {
        val backend = FakeBackend()
        // Capture the port from inside the wait strategy itself — before the failing start()
        // clears the mapping — so the assertion below is tied to the exact port this container
        // was allocated, not just "some" port.
        val precapturedPort = AtomicInteger(-1)
        val probe = object : WaitStrategy by NeverReady {
            override fun waitUntilReady(target: WaitTarget) {
                precapturedPort.set(target.mappedPort(6379))
                NeverReady.waitUntilReady(target)
            }
        }
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(probe).withExposedPorts(6379)
        assertThrows(IllegalStateException::class.java) { c.start() }
        assertFalse(c.isRunning)
        val name = backend.created.single().name
        assertTrue(backend.stopped.contains(name), "started container must be stopped when the wait strategy fails")
        // Discriminating proof: the exact port allocated for this container must no longer be
        // held in FreePorts' issued set — i.e. the wait-strategy-failure cleanup path released it
        // specifically. (Binding a ServerSocket to the port would pass even if release() were
        // never called, since FakeBackend never binds OS ports — this asserts FreePorts' own
        // bookkeeping instead.)
        val port = precapturedPort.get()
        assertTrue(port > 0, "wait strategy must have observed a real mapped port")
        assertFalse(port in FreePorts.issuedView(),
            "port $port must be released by the wait-strategy-failure cleanup path")
    }

    @Test fun `stop is a no-op before start and idempotent when called twice`() {
        val backend = FakeBackend()
        val c = container(backend).withExposedPorts(6379)
        c.stop()   // never started: must not throw, must stay not-running
        assertFalse(c.isRunning)
        assertTrue(backend.stopped.isEmpty())
        c.start()
        val name = backend.created.single().name
        val mappedPort = c.getMappedPort(6379)
        c.stop()
        assertEquals(1, backend.stopped.count { it == name }, "backend.stop must be called exactly once")
        c.stop()   // second stop: no double-release, no second backend call
        assertEquals(1, backend.stopped.count { it == name }, "a second stop() must not re-call backend.stop")
        assertFalse(c.isRunning)
        // Discriminating proof: the port must actually be gone from FreePorts' issued set — not
        // just "still bindable" (FakeBackend never binds OS ports, so a ServerSocket-bind
        // assertion here would pass even if stop() never called FreePorts.release at all).
        assertFalse(mappedPort in FreePorts.issuedView(),
            "port $mappedPort must be released by stop()")
    }

    /**
     * Fails `start` with a host-port bind conflict the first [failFirst] times, then succeeds.
     * [conflictException] lets the retry-loop tests drive different message phrasings (and
     * non-conflict exceptions) without duplicating the whole fake.
     */
    private class PortConflictBackend(
        private val failFirst: Int,
        private val conflictException: (Int) -> Exception = { port ->
            RuntimeException("driver failed programming external connectivity: " +
                "failed to bind host port 127.0.0.1:$port/tcp: address already in use")
        },
    ) : SandboxBackend {
        val created = mutableListOf<ContainerSpec>()
        val startedPorts = mutableListOf<List<Int>>()
        private var startAttempts = 0
        val createCount get() = created.size
        override val name = "port-conflict"
        override val supportsNativeNetworks = false
        override fun create(spec: ContainerSpec) = object : SandboxHandle {
            override val id = spec.name; override val spec = spec
        }.also { created += spec }
        override fun start(handle: SandboxHandle) {
            startAttempts++
            startedPorts += handle.spec.ports.map { it.hostPort }
            if (startAttempts <= failFirst) throw conflictException(handle.spec.ports.first().hostPort)
        }
        override fun stop(handle: SandboxHandle) {}
        override fun remove(handle: SandboxHandle) {}
        override fun exec(handle: SandboxHandle, cmd: List<String>) = ExecResult(0, "", "")
        override fun logs(handle: SandboxHandle) = ""
        override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit) = AutoCloseable {}
        override fun ensureNetwork(networkId: String) {}
        override fun removeNetwork(networkId: String) {}
    }

    @Test fun `start retries with fresh host ports on a bind conflict`() {
        val backend = PortConflictBackend(failFirst = 2)
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        c.start()
        assertTrue(c.isRunning)
        assertEquals(3, backend.createCount, "each attempt recreates the container")
        assertEquals(3, backend.startedPorts.size, "start attempted three times")
        // Ports are reallocated per attempt, not reused after a conflict.
        assertEquals(backend.startedPorts.size, backend.startedPorts.map { it.single() }.distinct().size)
    }

    // The typed PortBindConflictException is the primary signal a backend can throw — this must
    // retry exactly like the string-matched phrasings above, including when a backend wraps it
    // under another exception.
    @Test fun `start retries on the typed PortBindConflictException, nested or bare`() {
        val bare = PortConflictBackend(failFirst = 1) { port ->
            PortBindConflictException("could not bind host port $port")
        }
        val bc = GenericContainer("redis:8.6-alpine").withBackend(bare).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        bc.start()
        assertTrue(bc.isRunning)
        assertEquals(2, bare.createCount, "must retry exactly once for the typed exception")
        bc.stop()

        val nested = PortConflictBackend(failFirst = 1) { port ->
            RuntimeException("start failed", PortBindConflictException("could not bind host port $port"))
        }
        val nc = GenericContainer("redis:8.6-alpine").withBackend(nested).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        nc.start()
        assertTrue(nc.isRunning)
        assertEquals(2, nested.createCount, "must unwrap to find a nested typed exception")
        nc.stop()
    }

    // PortBindConflictException is the primary signal (see the test above); this pins the
    // string-matching fallback classifier, which is retained unchanged for backends that don't
    // throw the typed exception yet — same phrasings, same non-conflict fail-fast.
    @Test fun `isPortBindConflict classifies known phrasings as retryable, everything else fails fast`() {
        val conflictPhrasings = listOf(
            "address already in use",
            "port is already allocated",
            "bind: address already in use",
            "Bind for 0.0.0.0:32770 failed: PORT IS ALREADY ALLOCATED",   // case-insensitive
        )
        conflictPhrasings.forEach { phrasing ->
            val backend = PortConflictBackend(failFirst = 1) { RuntimeException(phrasing) }
            val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately)
                .withExposedPorts(6379)
            c.start()
            assertTrue(c.isRunning, "must retry and succeed for phrasing: $phrasing")
            assertEquals(2, backend.createCount, "must retry exactly once for phrasing: $phrasing")
            c.stop()
        }
        // Wrapped two cause-levels deep: the classifier must walk the cause chain.
        val wrapped = PortConflictBackend(failFirst = 1) { port ->
            RuntimeException("start failed", RuntimeException("io error",
                RuntimeException("failed to bind host port 127.0.0.1:$port/tcp: address already in use")))
        }
        val wc = GenericContainer("redis:8.6-alpine").withBackend(wrapped).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        wc.start()
        assertTrue(wc.isRunning, "must unwrap nested causes to find the conflict phrasing")
        assertEquals(2, wrapped.createCount)
        wc.stop()
        // Negative: an unrelated failure must NOT be treated as a conflict — fails immediately,
        // no retry, and the original exception propagates unwrapped.
        val boom = PortConflictBackend(failFirst = 99) { RuntimeException("boom") }
        val bc = GenericContainer("redis:8.6-alpine").withBackend(boom).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        val e = assertThrows(RuntimeException::class.java) { bc.start() }
        assertEquals("boom", e.message)
        assertEquals(1, boom.createCount, "a non-conflict exception must fail fast, no retry")
        assertFalse(bc.isRunning)
    }

    @Test fun `start gives up after the retry budget is exhausted`() {
        val backend = PortConflictBackend(failFirst = 99)
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately)
            .withExposedPorts(6379)
        val e = assertThrows(IllegalStateException::class.java) { c.start() }
        assertTrue(e.message!!.contains("free host ports"))
        assertFalse(c.isRunning)
        assertEquals(5, backend.startedPorts.size, "all 5 attempts must have been tried")
        // Discriminating proof: no leak means every attempt's port is gone from FreePorts' issued
        // set. (A ServerSocket-bind check would pass even with zero release() calls, since
        // PortConflictBackend never binds OS ports either.)
        val attemptedPorts = backend.startedPorts.map { it.single() }
        assertEquals(5, attemptedPorts.distinct().size, "each retry must allocate a fresh port")
        val issued = FreePorts.issuedView()
        attemptedPorts.forEach { p -> assertFalse(p in issued, "port $p from a discarded attempt must be released") }
    }

    @Test fun `start stops the container when network-link installation fails`() {
        val backend = object : FakeBackend() {
            override fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>) {
                throw UnsupportedByBackendException("network links (no nc in image; try docker)", name)
            }
        }
        val net = Network.newNetwork()
        val c = container(backend).withExposedPorts(8080).withNetwork(net)
        val e = assertThrows(UnsupportedByBackendException::class.java) { c.start() }
        assertTrue(e.message!!.contains("nc"))
        val name = backend.created.single().name
        assertTrue(backend.stopped.contains(name), "started container must be stopped on link-install failure")
        assertFalse(c.isRunning)
    }

    // Memory-limit knob: withMemoryLimit must reach the ContainerSpec a backend receives, and
    // stay null (runtime default) when never called.
    @Test fun `withMemoryLimit carries through to the ContainerSpec, null when unset`() {
        val backend = FakeBackend()
        val limited = container(backend).withExposedPorts(6379).withMemoryLimit(1024)
        limited.start()
        assertEquals(1024L, backend.created.single().memoryLimitMb)
        limited.stop()

        val unset = container(backend).withExposedPorts(6379)
        unset.start()
        assertNull(backend.created.last().memoryLimitMb)
        unset.stop()
    }

    @Test fun `execInContainer requires running container`() {
        val backend = FakeBackend()
        val c = container(backend)
        assertThrows(IllegalStateException::class.java) { c.execInContainer("ls") }
        c.start()
        assertEquals("ls -la", c.execInContainer("ls", "-la").stdout)
    }
}
