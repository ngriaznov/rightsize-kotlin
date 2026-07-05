package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.wait.*
import java.lang.Long.toHexString
import java.util.concurrent.atomic.AtomicInteger

/**
 * Identifies every container started by this JVM process, so a backend can find and sweep its
 * own leftovers (e.g. after a crashed run) without touching containers from a concurrent run.
 * Stays at the root package and public (not `internal`) — `DockerBackend`/`MsbCliBackend` read
 * it from separate Gradle modules, where Kotlin `internal` in `core` would not be visible.
 */
object RunId { val value: String = toHexString(System.nanoTime()).takeLast(8) }
private val counter = AtomicInteger()

/**
 * A single sandboxed container, built with a Testcontainers-shaped fluent API and run by
 * whichever [dev.rightsize.core.SandboxBackend] is active (Docker or microsandbox). Configure it
 * with the `withX` builders, call [start] to boot it, and use [getMappedPort]/[host]/[logs]/
 * [execInContainer]/[followOutput] to interact with the running container. [stop] tears it down;
 * both are idempotent and safe to call in either order relative to failures.
 *
 * Module containers (e.g. `RedisContainer`) subclass this directly, supplying an [image] and
 * calling the builders from their `init` block; most tests use module containers or this class
 * directly rather than subclassing it themselves.
 */
@Suppress("UNCHECKED_CAST")
open class GenericContainer<SELF : GenericContainer<SELF>>(private val image: String) {
    private val env = linkedMapOf<String, String>()
    private val exposedPorts = mutableListOf<Int>()
    private var command: List<String>? = null
    private var network: Network? = null
    private val aliases = mutableListOf<String>()
    private val mounts = mutableListOf<FileMount>()
    private var waitStrategy: WaitStrategy = Wait.forListeningPort()
    private var backendOverride: SandboxBackend? = null
    private var memoryLimitMb: Long? = null

    private var handle: SandboxHandle? = null
    private var mappedPorts: Map<Int, Int> = emptyMap()

    protected val backend: SandboxBackend get() = backendOverride ?: Backends.active()

    /** Sets a single environment variable for the container process. Call again to add more. */
    fun withEnv(key: String, value: String): SELF { env[key] = value; return this as SELF }
    protected fun removeEnv(key: String): SELF { env.remove(key); return this as SELF }
    /** Declares guest ports to publish; each gets a host port assigned before boot (see [getMappedPort]). */
    fun withExposedPorts(vararg ports: Int): SELF { exposedPorts += ports.toList(); return this as SELF }
    /** Overrides the image's default entrypoint/command. */
    fun withCommand(vararg cmd: String): SELF { command = cmd.toList(); return this as SELF }
    /** Joins a [Network], making this container's exposed ports reachable at its [withNetworkAliases]. */
    fun withNetwork(net: Network): SELF { network = net; return this as SELF }
    /** Names this container is reachable as on its [Network] (see [Network.resolve]). */
    fun withNetworkAliases(vararg names: String): SELF { aliases += names; return this as SELF }
    /** Mounts [file] read-only into the guest at [guestPath]; takes effect at the next [start]. */
    fun withCopyFileToContainer(file: MountableFile, guestPath: String): SELF {
        mounts += FileMount(file.path, guestPath); return this as SELF
    }
    /** Overrides the readiness check run after boot; defaults to [dev.rightsize.core.wait.Wait.forListeningPort]. */
    fun waitingFor(strategy: WaitStrategy): SELF { waitStrategy = strategy; return this as SELF }
    /**
     * Caps the container's guest memory at [megabytes], for images whose default footprint
     * doesn't fit a backend's default VM/container sizing — e.g. a Paketo-buildpack JVM image
     * whose computed heap+metaspace regions exceed microsandbox's default microVM RAM. Maps to
     * msb's `-m ${megabytes}M` and Docker's `HostConfig.memory` (bytes); leaving this unset (the
     * default) lets each runtime apply its own default instead of a fixed value here.
     */
    fun withMemoryLimit(megabytes: Long): SELF { memoryLimitMb = megabytes; return this as SELF }
    internal fun withBackend(b: SandboxBackend): SELF { backendOverride = b; return this as SELF }

    /** True from a successful [start] until [stop]; false before the first [start] and after. */
    open val isRunning: Boolean get() = handle != null
    /** The host address published ports are reachable on — always loopback. */
    val host: String get() = "127.0.0.1"
    /** The full logs captured so far. Requires the container to be running; see [followOutput] to stream. */
    val logs: String get() = backend.logs(requireHandle())

    /** The host port [guestPort] is published on; only valid once [start] has succeeded. */
    fun getMappedPort(guestPort: Int): Int = mappedPorts[guestPort] ?: error(
        if (!isRunning) "Cannot get mapped port $guestPort on ${describe()}: the container is not running " +
            "— call start() first, or check that it did not stop/fail after start()"
        else "Port $guestPort is not exposed on ${describe()} — call withExposedPorts($guestPort) " +
            "before start(), or check exposedPorts for the port you actually declared"
    )

    /** Runs [cmd] inside the running container and returns its exit code and captured output. */
    fun execInContainer(vararg cmd: String): ExecResult = backend.exec(requireHandle(), cmd.toList())
    /**
     * Streams log lines to [consumer] as they're produced, starting from the current position.
     * Returns an [AutoCloseable]; closing it stops delivery (no further lines reach [consumer]
     * afterward, even if the container keeps running).
     */
    fun followOutput(consumer: (String) -> Unit): AutoCloseable = backend.followLogs(requireHandle(), consumer)

    protected open fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec = spec
    protected open fun containerIsStarted() {}

    internal fun mappedPortsView(): Map<Int, Int> = mappedPorts

    /**
     * Boots the container: allocates host ports, creates and starts it on the active backend
     * (retrying with fresh ports on a host-port bind race), links it to already-running network
     * siblings, then blocks until the configured [waitingFor] strategy reports ready. A no-op if
     * already running. On any failure partway through, [stop] runs before the exception
     * propagates, so a half-started container never leaks.
     */
    @Synchronized open fun start() {
        if (isRunning) return
        val net = network?.also { backend.ensureNetwork(it.id) }

        val h = createStartedContainer(net)   // allocate → create → start (with port-retry)
        handle = h

        try {
            linkToRunningSiblings(h, net)      // dial-able before our own boot begins
            net?.register(this, aliases, backend)   // AFTER linking, so we never link to ourselves
            waitStrategy.waitUntilReady(waitTarget())
        } catch (e: Exception) {
            runCatching { stop() }             // a half-started container never leaks
            throw e
        }
        containerIsStarted()                    // subclass hook: replica-set init, etc.
    }

    /** Links to siblings already running when we start; a failure here still tears us down. */
    private fun linkToRunningSiblings(handle: SandboxHandle, net: Network?) {
        net?.let { backend.installNetworkLinks(handle, it.linksForNewMember()) }
    }

    /** Allocates one free host port per exposed guest port, replacing any previous mapping. */
    private fun allocatePorts() { mappedPorts = exposedPorts.associateWith { FreePorts.allocate() } }

    /** Returns every currently mapped host port to the allocator and clears the mapping. */
    private fun releasePorts() {
        mappedPorts.values.forEach { FreePorts.release(it) }
        mappedPorts = emptyMap()
    }

    /**
     * Host ports are picked from the ephemeral range, but there is an unavoidable window between
     * allocation and the backend binding them; a sibling container in the same run can grab the
     * same port meanwhile. Retries the whole create+start with freshly allocated ports when the
     * backend reports a host-port bind conflict — the retry is this method's own concern, not
     * something a caller should have to orchestrate.
     */
    private fun createStartedContainer(net: Network?): SandboxHandle {
        var lastConflict: Exception? = null
        repeat(PORT_BIND_ATTEMPTS) {
            allocatePorts()
            var spec = ContainerSpec(
                name = "rz-${RunId.value}-${counter.incrementAndGet()}",
                image = image,
                env = env.toMap(),
                command = command,
                ports = mappedPorts.map { (g, h) -> PortBinding(hostPort = h, guestPort = g) },
                mounts = mounts.toList(),
                networkId = net?.id,
                aliases = aliases.toList(),
                runId = RunId.value,
                memoryLimitMb = memoryLimitMb,
            )
            spec = customizeSpec(spec) { guest -> mappedPorts.getValue(guest) }
            val h = backend.create(spec)
            try {
                backend.start(h)
                return h
            } catch (e: Exception) {
                // The container was created with fixed host ports; discard it before retrying and
                // return this attempt's ports to the allocator (a retry allocates fresh ones).
                runCatching { backend.stop(h) }
                runCatching { backend.remove(h) }
                releasePorts()
                if (isPortBindConflict(e)) { lastConflict = e; return@repeat }
                throw e
            }
        }
        throw IllegalStateException(
            "Could not bind free host ports for ${describe()} after $PORT_BIND_ATTEMPTS attempts " +
                "— another process kept grabbing the allocated ports first; if this persists, check " +
                "for a port scanner/leaked process racing the allocator on this host", lastConflict)
    }

    /**
     * True if [e] represents a host-port bind conflict that is worth retrying with fresh ports.
     * Prefers the typed [PortBindConflictException] a backend can throw when it positively
     * identifies the condition; falls back to matching known message phrasings for backends that
     * don't yet map it. Walks the cause chain so a typed exception (or a matching phrasing)
     * nested under a wrapping exception is still recognized.
     */
    private fun isPortBindConflict(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { t ->
            t is PortBindConflictException ||
                t.message?.lowercase()?.let { "address already in use" in it || "already allocated" in it } == true
        }

    /**
     * Stops and removes the container on the active backend and releases its mapped host ports.
     * Idempotent: a no-op if not running (never throws, safe to call unconditionally, e.g. in a
     * `finally` block), and calling it twice does not double-release ports or double-call the
     * backend.
     */
    @Synchronized open fun stop() {
        val h = handle ?: return
        handle = null
        runCatching { backend.stop(h) }
        runCatching { backend.remove(h) }
        // Return the host ports to the allocator and drop the mappings, so getMappedPort on a
        // stopped container fails with the not-exposed error instead of handing back a dead port.
        releasePorts()
    }

    private fun requireHandle(): SandboxHandle =
        handle ?: throw IllegalStateException("${describe()} is not running — call start() first")

    private fun describe() = "container(image=$image, id=${handle?.id ?: "unstarted"})"

    private fun waitTarget() = object : WaitTarget {
        override val host = this@GenericContainer.host
        override fun mappedPort(guestPort: Int) = getMappedPort(guestPort)
        override val exposedGuestPorts = exposedPorts.toList()
        override fun currentLogs() = runCatching { logs }.getOrDefault("")
        override fun describe() = this@GenericContainer.describe()
    }

    companion object {
        private const val PORT_BIND_ATTEMPTS = 5
        private class Anonymous(image: String) : GenericContainer<Anonymous>(image)
        operator fun invoke(image: String): GenericContainer<*> = Anonymous(image)
    }
}
