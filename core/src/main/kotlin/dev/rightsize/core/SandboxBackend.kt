package dev.rightsize.core

/** A tunnel/alias route: inside the consumer, `alias:guestPort` must reach `127.0.0.1:targetHostPort` on the host. */
data class NetworkLink(val alias: String, val guestPort: Int, val targetHostPort: Int)

/**
 * Argv command prefixes the reaper's watchdog script needs to remove sandboxes/networks by
 * name from a plain OS process — spawned before the first sandbox create, so it must be able
 * to act after this JVM has died and its [SandboxBackend] instance no longer exists. Each
 * list, with the resource's name appended as the final argument, is a directly-runnable
 * command (e.g. `docker rm -f <name>`); an empty list means "no such step for this backend"
 * (the watchdog skips it). [sandboxStop] is separate from [sandboxRemove] because msb needs
 * both (`msb stop` then `msb rm`), while a single force-remove already covers it for docker.
 */
data class WatchdogCommands(
    val sandboxStop: List<String> = emptyList(),
    val sandboxRemove: List<String> = emptyList(),
    val networkRemove: List<String> = emptyList(),
)

/**
 * Static properties of a backend's execution model — deliberately small, designed to grow
 * further flags without another SPI-wide change. Both flags are backend-wide constants, not
 * per-container: a backend either always runs each sandbox in its own microVM or it never does,
 * and either always supports checkpoint or never does.
 */
data class BackendCapabilities(
    /** True when each sandbox gets its own kernel (a microVM) rather than sharing the host's —
     * microsandbox: true, Docker: false (containers share the host kernel). See
     * [dev.rightsize.core.IsolationRequiredException]/`GenericContainer.withRequireIsolation`. */
    val hardwareIsolated: Boolean,
    /**
     * True when the backend can checkpoint/restore a running sandbox — Docker: `true`, via
     * image commit; microsandbox: `false`, no upstream snapshot primitive yet. See
     * [dev.rightsize.core.CheckpointUnsupportedException]/`GenericContainer.checkpoint` and
     * docs/checkpoints.md.
     */
    val checkpoint: Boolean,
)

/**
 * The entire contract a runtime must satisfy to back rightsize containers — deliberately tiny,
 * because two runtimes as different as Docker and microVM-over-CLI both live behind it. Docker
 * maps most calls straight to the daemon API; the microsandbox backend drives the `msb` CLI and
 * *emulates* the parts a microVM lacks — most notably networking, which it fakes with per-link
 * exec-stream tunnels (see [installNetworkLinks], a no-op here for backends with real networks).
 *
 * Host ports arrive already chosen in `ContainerSpec.ports`: a backend binds them, it never
 * allocates. That one rule is what lets a module advertise its own mapped port at boot.
 */
interface SandboxBackend : AutoCloseable {
    /** Human backend id, e.g. "docker" / "microsandbox"; shown in errors and `RIGHTSIZE_BACKEND`. */
    val name: String
    /** True when the runtime has real container networks; false means links are emulated. */
    val supportsNativeNetworks: Boolean
    /**
     * See [BackendCapabilities]. Defaults to all-`false` for backends that don't declare it
     * (e.g. test doubles) — the safe default for [hardwareIsolated] in particular, since an
     * unknown backend must not be assumed to provide hardware isolation.
     */
    val capabilities: BackendCapabilities get() = BackendCapabilities(hardwareIsolated = false, checkpoint = false)
    /** Creates (but does not start) a container from [spec]; [spec]'s ports are already chosen. */
    fun create(spec: ContainerSpec): SandboxHandle
    /** Starts a container previously returned by [create]. */
    fun start(handle: SandboxHandle)
    /** Stops a running container; safe to call on an already-stopped one. */
    fun stop(handle: SandboxHandle)
    /** Removes a stopped container's resources. */
    fun remove(handle: SandboxHandle)
    /**
     * Best-effort stop+remove of the sandbox named [name] — unlike [remove], this needs no
     * live [SandboxHandle]: it's how the reaper's init-time sweep reaps a *different*, dead
     * process's leftovers, given only the name recorded in that process's ledger. "Not
     * found" is treated as success — sweeps are idempotent and may race another process's own
     * sweep of the same dead run. Defaults to a no-op for backends that don't participate in
     * reaping (e.g. test doubles).
     */
    fun removeByName(name: String) {}
    /**
     * Looks up an already-running sandbox named [name] without creating one — the reuse
     * feature's adopt path (see docs/reuse.md) uses this to check whether a name recorded in
     * the reuse registry is still backed by a live sandbox before trusting its recorded ports.
     * Returns `null` for "no such sandbox" and "found but not running" alike — the caller
     * treats both identically (fall back to a fresh create), so this never needs to distinguish
     * them. The returned handle's `spec` is a minimal reconstruction (just enough to satisfy the
     * [SandboxHandle] contract — name, image if cheaply available, `keepAlive = true`), not the
     * original creating spec, which no backend can recover from a bare name. Defaults to `null`
     * for backends that don't participate in reuse (e.g. test doubles).
     */
    fun findRunning(name: String): SandboxHandle? = null
    /** See [WatchdogCommands]. Defaults to all-empty (no watchdog action) for backends that
     * don't participate in reaping. */
    val watchdogCommands: WatchdogCommands get() = WatchdogCommands()
    /**
     * Commits [handle]'s current filesystem to a new image tagged [imageRef] — the backend
     * primitive behind `GenericContainer.checkpoint()`. Only ever called when
     * [capabilities]`.checkpoint` is true: the generic layer gates on that flag BEFORE reaching
     * this method, so a backend that doesn't support it (microsandbox today — no upstream
     * image-commit or snapshot primitive) never needs a real implementation. Defaults to
     * throwing [UnsupportedByBackendException] for exactly that reason — a defensive backstop,
     * since the capability gate above should make this unreachable in practice.
     */
    fun commitToImage(handle: SandboxHandle, imageRef: String): Unit =
        throw UnsupportedByBackendException("checkpoint", name,
            "use the docker backend, which implements checkpoint via image commit")
    /** Runs [cmd] inside the running container and returns its exit code plus captured output. */
    fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult
    /** The container's full captured logs so far. */
    fun logs(handle: SandboxHandle): String
    /** Streams log lines to [consumer] as they arrive; closing the returned handle halts delivery. */
    fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit): AutoCloseable
    /** Creates the named network if the runtime needs an explicit create step; no-op otherwise. */
    fun ensureNetwork(networkId: String)
    /** Removes the named network; no-op for runtimes with nothing to remove. */
    fun removeNetwork(networkId: String)
    /**
     * Called after [start], before the wait strategy runs, to make [links] reachable from
     * [handle] by alias. Docker: no-op (native networks already resolve aliases). A backend that
     * emulates networking (e.g. microsandbox) should make each [NetworkLink.alias]:
     * [NetworkLink.guestPort] resolve to `127.0.0.1:`[NetworkLink.targetHostPort] from inside the
     * container, and fail fast with an actionable [UnsupportedByBackendException] for anything it
     * cannot support (e.g. a consumer image missing a required tool) rather than silently no-op.
     */
    fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>) {}
    /** Releases any resources the backend itself owns (e.g. its runtime client). */
    override fun close() {}
}

/**
 * A `ServiceLoader`-discoverable factory for a [SandboxBackend]. Each backend module ships one
 * (e.g. `DockerBackendProvider`, `MsbBackendProvider`); `Backends.resolve` picks among the
 * providers on the classpath by [priority], honoring `RIGHTSIZE_BACKEND` when set.
 */
interface BackendProvider {
    /** Human backend id, e.g. "docker" / "microsandbox"; matched case-insensitively against `RIGHTSIZE_BACKEND`. */
    val name: String
    /** Higher wins when auto-selecting; msb=20 outranks docker=10 so a microVM host prefers the microVM. */
    val priority: Int
    /** True if this backend's runtime preconditions are met on the current host (e.g. KVM, a Docker daemon). */
    fun isSupported(): Boolean
    /** A human-readable reason [isSupported] is false, for the error when this backend was explicitly requested. */
    fun unsupportedReason(): String
    /** Instantiates the backend. Only called after [isSupported] is confirmed true. */
    fun create(): SandboxBackend
}
