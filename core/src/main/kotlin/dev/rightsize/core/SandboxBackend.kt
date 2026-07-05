package dev.rightsize.core

/** A tunnel/alias route: inside the consumer, `alias:guestPort` must reach `127.0.0.1:targetHostPort` on the host. */
data class NetworkLink(val alias: String, val guestPort: Int, val targetHostPort: Int)

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
    /** Creates (but does not start) a container from [spec]; [spec]'s ports are already chosen. */
    fun create(spec: ContainerSpec): SandboxHandle
    /** Starts a container previously returned by [create]. */
    fun start(handle: SandboxHandle)
    /** Stops a running container; safe to call on an already-stopped one. */
    fun stop(handle: SandboxHandle)
    /** Removes a stopped container's resources. */
    fun remove(handle: SandboxHandle)
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
