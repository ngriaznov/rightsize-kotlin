package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.diagnostics.LiveContainers
import dev.rightsize.core.reaper.Reaper
import dev.rightsize.core.reaper.canonicalBackendId
import dev.rightsize.core.reuse.*
import dev.rightsize.core.wait.*
import java.lang.Long.toHexString
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
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
    private var reuseRequested = false
    private var reuseEnvOverride: Map<String, String>? = null
    private var reuseCacheDirOverride: Path? = null
    private var requireIsolationRequested = false

    private var handle: SandboxHandle? = null
    private var mappedPorts: Map<Int, Int> = emptyMap()
    // True only while `handle` refers to a reuse-active sandbox (adopted or freshly created via
    // the reuse path) — `stop()` branches on this to leave the sandbox running instead of
    // stopping/removing it. Never true for an ordinary container.
    private var reuseActive = false

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
    /**
     * Marks this container for reuse: survive `stop()`/process exit and be adopted — not
     * re-created — by the next equivalent container, in this process or a later one. Only takes
     * effect when `RIGHTSIZE_REUSE` is also enabled (double opt-in; see docs/reuse.md) — marked
     * but not enabled behaves exactly like an ordinary container, with a one-line stderr note.
     * Incompatible with [withNetwork]: reuse identity covers a container's own configuration,
     * never cross-container network topology.
     */
    fun withReuse(): SELF { reuseRequested = true; return this as SELF }
    /**
     * Requires the active backend to provide hardware isolation (`capabilities.hardwareIsolated`)
     * — for tests that run untrusted code and must not silently fall back to a shared-kernel
     * backend. Checked at the very top of [start], before any create/network work: a backend
     * that doesn't qualify throws [IsolationRequiredException] and no sandbox is created. See
     * docs/isolation.md.
     */
    fun withRequireIsolation(): SELF { requireIsolationRequested = true; return this as SELF }
    internal fun withBackend(b: SandboxBackend): SELF { backendOverride = b; return this as SELF }
    /** Internal test seam: injects the environment [withReuse]'s env half is read from, instead
     * of the real `System.getenv()`. */
    internal fun withReuseEnvOverride(env: Map<String, String>): SELF { reuseEnvOverride = env; return this as SELF }
    /** Internal test seam: injects the reuse registry's root directory instead of the real
     * rightsize cache dir, so reuse tests never touch a developer's `~/.cache/rightsize`. */
    internal fun withReuseCacheDir(dir: Path): SELF { reuseCacheDirOverride = dir; return this as SELF }

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

    /**
     * Captures this RUNNING container's current filesystem as a new image
     * (`rightsize/checkpoint:<12-hex>`, random per call) via the active backend's
     * `commitToImage` — a filesystem snapshot, not a memory snapshot: [fromCheckpoint] boots a
     * fresh container whose filesystem starts where this one left off, but its processes
     * restart from scratch (see docs/checkpoints.md). Gated on `capabilities.checkpoint` BEFORE
     * any backend call — an unsupported backend (microsandbox today) throws
     * [CheckpointUnsupportedException] and the backend is never reached. Requires this
     * container to be running; a never-started or already-stopped one throws
     * [IllegalStateException].
     */
    fun checkpoint(): Checkpoint {
        if (!backend.capabilities.checkpoint) throw CheckpointUnsupportedException(backend.name)
        val h = requireHandle()
        val imageRef = "rightsize/checkpoint:${randomImageTag()}"
        backend.commitToImage(h, imageRef)
        return Checkpoint(
            imageRef = imageRef,
            spec = CheckpointSpec(
                env = env.toMap(),
                command = command,
                exposedPorts = exposedPorts.toList(),
                memoryLimitMb = memoryLimitMb,
            ),
        )
    }

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
        // Checked before any network/port/create work, ahead of the reuse dispatch too — an
        // isolation requirement must reject a non-isolated backend whether this start would
        // have created fresh or adopted a running reuse sandbox.
        if (requireIsolationRequested && !backend.capabilities.hardwareIsolated) {
            throw IsolationRequiredException(backend.name)
        }
        if (reuseRequested) {
            if (reuseEnvEnabled()) { startReuse(); return }
            System.err.println(
                "rightsize: withReuse() was called on ${describe()} but RIGHTSIZE_REUSE is not " +
                    "enabled — starting as an ordinary (non-reused) container. Set RIGHTSIZE_REUSE=true " +
                    "(or \"1\") to opt in.")
        }
        // Ledger append BEFORE the backend actually creates the network — mirrors the sandbox
        // path below (Reaper.beforeCreate precedes backend.create) so a crash in the narrow
        // window between the two never leaves a created-but-unlisted network for the sweep or
        // watchdog to miss (see RunLedger.beforeNetworkCreate's doc comment).
        val net = network?.also { Reaper.beforeNetworkCreate(backend, it.id); backend.ensureNetwork(it.id) }

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
        LiveContainers.register(h, backend, host)
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
            Reaper.beforeCreate(backend, spec)
            val h = backend.create(spec)
            try {
                backend.start(h)
                return h
            } catch (e: Exception) {
                // The container was created with fixed host ports; discard it before retrying and
                // return this attempt's ports to the allocator (a retry allocates fresh ones).
                runCatching { backend.stop(h) }
                val removed = runCatching { backend.remove(h) }.isSuccess
                if (removed) Reaper.afterRemove(h.spec)
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
        LiveContainers.deregister(h.spec.name)
        if (reuseActive) {
            // The whole point of reuse: the sandbox is LEFT RUNNING. Only this instance's own
            // in-process bookkeeping is cleared — no backend.stop/remove call, no port release
            // (the ports are still bound by the still-running sandbox), no reaper ledger entry
            // (there never was one; keepAlive specs are never appended in the first place).
            reuseActive = false
            mappedPorts = emptyMap()
            return
        }
        runCatching { backend.stop(h) }
        // Only tell the reaper's ledger the sandbox is gone if `remove` actually succeeded —
        // the ledger's superset invariant (never lists fewer names than are really live)
        // depends on this: a failed remove leaves the sandbox for the ledger to still cover.
        val removed = runCatching { backend.remove(h) }.isSuccess
        if (removed) Reaper.afterRemove(h.spec)
        // Return the host ports to the allocator and drop the mappings, so getMappedPort on a
        // stopped container fails with the not-exposed error instead of handing back a dead port.
        releasePorts()
    }

    private fun reuseEnvEnabled(): Boolean = ReuseMode.enabled(reuseEnvOverride ?: System.getenv())
    private fun reuseCacheDir(): Path = reuseCacheDirOverride ?: CacheDir.resolve()

    /**
     * Reuse's entry point from [start] once double opt-in is confirmed (see docs/reuse.md):
     * compute this container's identity hash, look up its registry entry, and either adopt an
     * already-running sandbox or create a fresh one. `withNetwork` is rejected here (not
     * earlier) so an ordinary, non-reuse-active container can still freely combine
     * `withReuse()` with `withNetwork()` when the env half of the opt-in is off.
     */
    private fun startReuse() {
        if (network != null) throw ReuseNetworkConflictException(
            "Reuse (withReuse()) cannot be combined with a custom network (withNetwork()) on " +
                "${describe()} — reuse identity covers only this container's own configuration, never " +
                "cross-container network topology. Drop withNetwork() or withReuse().")
        val hash = ReuseIdentity.hash(buildIdentitySpec())
        val name = ReuseIdentity.name(hash)
        val registry = ReuseRegistry(reuseCacheDir())
        val record = registry.read(hash)
        if (record != null) {
            if (tryAdopt(record)) return
            // Stale or corrupt: the registry entry doesn't describe a sandbox we can trust;
            // clear it so a later run doesn't hit the same dead entry. Whether the backend still
            // has something running under this name is handled uniformly below, the same as the
            // no-registry-entry-at-all case.
            registry.delete(hash)
        }
        removeOrphanedRunningSandbox(name)
        createReuseFresh(name, hash, registry)
    }

    /**
     * Guards the crash-mid-boot orphan (see docs/reuse.md): a fresh-create sandbox that finished
     * `create`+`start`+wait on some prior process but crashed (or was killed) strictly BEFORE
     * that process wrote the registry entry is left RUNNING — `keepAlive` makes it invisible to
     * reaping, by design — with no registry entry pointing at it. [startReuse] has just
     * concluded there is no usable registry entry for [name] (missing, corrupt, or failed
     * verification); before creating again under the same name, ask the backend whether it
     * already has a running sandbox by that name and best-effort remove it first. A `null` from
     * [SandboxBackend.findRunning] (the ordinary "nothing to clean up" case) never calls
     * [SandboxBackend.removeByName] — this is the narrow orphan-recovery path, not a general
     * "always clear the name" step. The concurrent-creator race (another live process wins the
     * name between here and [createReuseFresh]'s own `create` call) is unaffected: that's still
     * handled by the name-collision-retry-into-adopt behavior there, since a registry entry
     * appearing at that point means another live process won and should be adopted, not removed.
     */
    private fun removeOrphanedRunningSandbox(name: String) {
        runCatching { backend.findRunning(name) }.getOrNull() ?: return
        runCatching { backend.removeByName(name) }
    }

    /** The reuse-relevant subset of this container's current configuration — see
     * [ReuseIdentitySpec]'s doc for why this is narrower than [ContainerSpec]. */
    private fun buildIdentitySpec(): ReuseIdentitySpec = ReuseIdentitySpec(
        image = image,
        env = env.toMap(),
        command = command ?: emptyList(),
        exposedPorts = exposedPorts.toList(),
        memoryLimitMb = memoryLimitMb,
        copies = mounts.map { ReuseIdentitySpec.CopyEntry(it.guestPath, ReuseIdentity.sha256OfFile(it.hostPath)) },
    )

    /**
     * Verifies [record]'s sandbox is actually running, then re-runs this container's own wait
     * strategy against its recorded ports (bounded by the normal startup budget) before trusting
     * it as this instance's running container. Never throws: any disqualifier (not running, wait
     * failure) leaves this instance exactly as it was before the call and returns `false`, so the
     * caller can fall back to a fresh create.
     */
    private fun tryAdopt(record: ReuseRecord): Boolean {
        val h = runCatching { backend.findRunning(record.name) }.getOrNull() ?: return false
        handle = h
        mappedPorts = record.ports
        // The adopted sandbox's host ports are already bound by that (possibly foreign) process —
        // register them with this process's own allocator so it never hands the same host port to
        // an unrelated sibling container started later in this run. Never released: the ports stay
        // genuinely occupied by the still-running sandbox for as long as it lives.
        record.ports.values.forEach(FreePorts::reserve)
        reuseActive = true
        return try {
            waitStrategy.waitUntilReady(waitTarget())
            containerIsStarted()
            LiveContainers.register(h, backend, host)
            true
        } catch (e: Exception) {
            handle = null; mappedPorts = emptyMap(); reuseActive = false
            false
        }
    }

    /**
     * No registry hit (or a stale one already cleared): allocate host ports, create+start with
     * `keepAlive = true` under the reuse [name], wait, then persist the registry entry. Shares
     * [createStartedContainer]'s [PORT_BIND_ATTEMPTS] retry budget on a host-port bind conflict —
     * the TOCTOU window between [FreePorts] allocation and the backend actually binding the port
     * is exactly as real here as on the ordinary path, so it gets the same retry-with-fresh-ports
     * treatment. A name collision on `create` (another process's create won the race) re-enters
     * the adopt path exactly once; any other start failure tears the half-created sandbox down
     * like an ordinary container.
     */
    private fun createReuseFresh(name: String, hash: String, registry: ReuseRegistry) {
        var lastConflict: Exception? = null
        repeat(PORT_BIND_ATTEMPTS) {
            allocatePorts()
            var spec = ContainerSpec(
                name = name,
                image = image,
                env = env.toMap(),
                command = command,
                ports = mappedPorts.map { (g, h) -> PortBinding(hostPort = h, guestPort = g) },
                mounts = mounts.toList(),
                runId = RunId.value,
                memoryLimitMb = memoryLimitMb,
                keepAlive = true,
            )
            spec = customizeSpec(spec) { guest -> mappedPorts.getValue(guest) }
            val h = try {
                backend.create(spec)
            } catch (e: Exception) {
                releasePorts()
                val record = if (isNameCollision(e)) registry.read(hash) else null
                if (record != null && tryAdopt(record)) return
                throw e
            }
            try {
                backend.start(h)
            } catch (e: Exception) {
                runCatching { backend.stop(h) }
                runCatching { backend.remove(h) }
                releasePorts()
                if (isPortBindConflict(e)) { lastConflict = e; return@repeat }
                throw e
            }
            handle = h
            reuseActive = true
            try {
                waitStrategy.waitUntilReady(waitTarget())
            } catch (e: Exception) {
                handle = null; reuseActive = false
                runCatching { backend.stop(h) }
                runCatching { backend.remove(h) }
                releasePorts()
                throw e
            }
            containerIsStarted()
            LiveContainers.register(h, backend, host)
            registry.write(hash, ReuseRecord(
                name = name, image = image, ports = mappedPorts,
                createdIso = Instant.now().toString(), backend = canonicalBackendId(backend.name)))
            return
        }
        throw IllegalStateException(
            "Could not bind free host ports for ${describe()} after $PORT_BIND_ATTEMPTS attempts " +
                "— another process kept grabbing the allocated ports first; if this persists, check " +
                "for a port scanner/leaked process racing the allocator on this host", lastConflict)
    }

    /**
     * True if [e] signals that the reuse sandbox name was already taken by a concurrent
     * process's create — the one case reuse's fresh-create path retries (via the adopt path)
     * rather than failing outright. Same primary-typed/fallback-string pattern as
     * [isPortBindConflict]: prefers [SandboxNameCollisionException], falls back to known message
     * phrasings for backends that don't throw it yet, and walks the cause chain either way.
     */
    private fun isNameCollision(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { t ->
            t is SandboxNameCollisionException ||
                t.message?.lowercase()?.let { "already exists" in it || ("already in use" in it && "name" in it) } == true
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
        private val checkpointRandom = SecureRandom()
        private class Anonymous(image: String) : GenericContainer<Anonymous>(image)
        operator fun invoke(image: String): GenericContainer<*> = Anonymous(image)

        /** 12 lowercase hex chars from 6 cryptographically random bytes — the random suffix of
         * a `rightsize/checkpoint:<12-hex>` image tag (see [checkpoint]). */
        private fun randomImageTag(): String {
            val bytes = ByteArray(6)
            checkpointRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Builds a normal container from [checkpoint]'s image and the source container's spec
         * defaults (env, command, exposed ports, memory limit) — the usual `withX` builders
         * still apply afterward, so a caller can override any of these (e.g. a different wait
         * strategy) before calling `start()`. The result is an ordinary container in every
         * respect: fresh host ports, normal reaping ledger entry, normal stop. Nothing about it
         * is "special" once started. See docs/checkpoints.md.
         */
        fun fromCheckpoint(checkpoint: Checkpoint): GenericContainer<*> {
            val c = invoke(checkpoint.imageRef)
            checkpoint.spec.env.forEach { (k, v) -> c.withEnv(k, v) }
            checkpoint.spec.command?.let { c.withCommand(*it.toTypedArray()) }
            if (checkpoint.spec.exposedPorts.isNotEmpty()) {
                c.withExposedPorts(*checkpoint.spec.exposedPorts.toIntArray())
            }
            checkpoint.spec.memoryLimitMb?.let { c.withMemoryLimit(it) }
            return c
        }
    }
}
