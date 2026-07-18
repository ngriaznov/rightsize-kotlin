package dev.rightsize.core

import java.nio.file.Path

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
     * True when the backend can checkpoint/restore a running sandbox — both real backends today:
     * Docker via image commit, microsandbox via disk snapshot. See
     * [dev.rightsize.core.CheckpointUnsupportedException]/`GenericContainer.checkpoint` and
     * docs/checkpoints.md.
     */
    val checkpoint: Boolean,
    /**
     * True when a [checkpoint] cycle restarts the sandbox's workload — Docker: `false`, the
     * container is committed and left running undisturbed; microsandbox: `true`, its disk
     * snapshot needs the sandbox stopped, so the workload command re-runs from scratch once it
     * resumes. `GenericContainer.checkpoint` re-applies the container's own wait strategy before
     * returning when this is true, so a caller never gets back a false-ready container. Defaults
     * to `false`, the safer assumption for a backend that hasn't declared it.
     */
    val checkpointRestartsWorkload: Boolean = false,
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
     * Captures [handle]'s current filesystem as a checkpoint identified by [ref] — the backend
     * primitive behind `GenericContainer.checkpoint()`. Only ever called when
     * [capabilities]`.checkpoint` is true: the generic layer gates on that flag BEFORE reaching
     * this method, so a backend that doesn't support it never needs a real implementation.
     * [ref]'s shape is backend-specific and already minted by the generic layer before this
     * call — a docker image tag (`rightsize/checkpoint:<12-hex>`) or an msb snapshot name
     * (`rz-ckpt-<12-hex>`); this method does only the capture. Docker commits the running
     * container to an image, leaving it undisturbed ([capabilities]`.checkpointRestartsWorkload`
     * = `false`); microsandbox stops the sandbox, snapshots its disk, then resumes it
     * (`checkpointRestartsWorkload = true`, since the resumed workload restarts from scratch —
     * see docs/checkpoints.md). Defaults to throwing [UnsupportedByBackendException] — a
     * defensive backstop, since the capability gate above should make this unreachable in
     * practice.
     */
    fun createCheckpoint(handle: SandboxHandle, ref: String): Unit =
        throw UnsupportedByBackendException("checkpoint", name,
            "the active backend must advertise capabilities.checkpoint to support this")
    /**
     * Best-effort removal of a checkpoint identified by [ref] — docker removes the tagged image
     * (`docker rmi`), msb removes the snapshot (`msb snapshot rm`). "Not found" is treated as
     * success, the same contract as [removeByName]. SPI-only: no public `GenericContainer`
     * method calls this — checkpoint images/snapshots are not auto-pruned (see
     * docs/checkpoints.md's manual cleanup one-liners for users); this exists as a
     * backend-internal affordance so tests can keep shared CI state clean. Defaults to a no-op
     * for backends that don't participate in checkpointing (e.g. test doubles).
     */
    fun removeCheckpoint(ref: String) {}
    /**
     * Probes whether a checkpoint identified by [ref] still exists — docker via image inspect,
     * msb via `msb snapshot inspect`'s exit code, both through the same invocation plumbing every
     * other SPI method already uses. Backs `Checkpoint.find`'s staleness check (see
     * docs/checkpoints.md's "Reusing checkpoints across runs" section): unlike
     * [removeByName]/[removeCheckpoint], a probe FAILURE must never be swallowed into `false` —
     * only a definite "no such checkpoint" resolves to `false`; any other failure (the backend
     * itself unreachable, a malformed [ref], etc.) must propagate, so `Checkpoint.find` never
     * silently mistakes a broken host for a missing checkpoint. Defaults to throwing
     * [UnsupportedByBackendException] for backends that don't implement it (e.g. test doubles) —
     * the same defensive-backstop posture [createCheckpoint]'s default has.
     */
    fun hasCheckpoint(ref: String): Boolean =
        throw UnsupportedByBackendException("checkpoint probe", name,
            "the active backend must implement hasCheckpoint to support named-checkpoint rediscovery")
    /**
     * Writes checkpoint [ref]'s backend payload to [dest] — the artifact half of a portable
     * checkpoint archive (see docs/checkpoints.md's "Moving checkpoints between machines"
     * section) behind `Checkpoint.exportTo`: docker `docker save -o <dest> <ref>`, microsandbox
     * `msb snapshot export <ref> <dest>` (never `--with-image`; its import fails an integrity
     * check on msb 0.6.6, so archives never bundle the OCI image — the destination machine pulls
     * it on the restored container's first boot instead). Only ever called after the generic
     * layer's own backend-match and [hasCheckpoint] checks have both passed. Defaults to
     * throwing [UnsupportedByBackendException] for backends that don't implement it.
     */
    fun exportCheckpoint(ref: String, dest: Path): Unit =
        throw UnsupportedByBackendException("checkpoint export", name,
            "the active backend must implement exportCheckpoint to support Checkpoint.exportTo")
    /**
     * Materializes an archive's extracted artifact payload at [src] as a checkpoint reachable by
     * some ref, and returns that EFFECTIVE ref — behind `Checkpoint.importFrom`. This is not
     * always [ref] itself: `docker load` preserves the original tag, so docker returns [ref]
     * unchanged, but microsandbox's `msb snapshot import` mints a content-addressed digest-shaped
     * ref of its own, discarding the original snapshot name entirely — treating an
     * already-exists failure as success (the artifact is already present) either way. Only ever
     * called after the generic layer's own archive validation (format version, name, backend
     * match) has passed. Defaults to throwing [UnsupportedByBackendException] for backends that
     * don't implement it.
     */
    fun importCheckpoint(src: Path, ref: String): String =
        throw UnsupportedByBackendException("checkpoint import", name,
            "the active backend must implement importCheckpoint to support Checkpoint.importFrom")
    /**
     * Copies [hostPath] (a file or directory) into the running container at [containerPath] —
     * the backend primitive behind `GenericContainer.copyFileToContainer`/
     * `copyContentToContainer`. By the time this is called, the generic layer has already
     * verified the container is running, that [containerPath] is absolute, and created its
     * parent directory in the guest (via the existing [exec] SPI) — this method does only the
     * transfer. Directory-vs-file source and "copy into a nonexistent destination" naming follow
     * the same `cp -r`-style semantics `docker cp`/`msb copy` already have (see docs/copy.md).
     * Defaults to throwing [UnsupportedByBackendException] for backends that don't implement it
     * (e.g. test doubles).
     */
    fun copyToContainer(handle: SandboxHandle, hostPath: Path, containerPath: String): Unit =
        throw UnsupportedByBackendException("runtime copy", name)
    /**
     * Copies [containerPath] (a file or directory) out of the running container to [hostPath] —
     * the backend primitive behind `GenericContainer.copyFileFromContainer`. By the time this is
     * called, the generic layer has already verified the container is running, that
     * [containerPath] is absolute, and created [hostPath]'s parent directory on the host — this
     * method does only the transfer. Defaults to throwing [UnsupportedByBackendException] for
     * backends that don't implement it (e.g. test doubles).
     */
    fun copyFromContainer(handle: SandboxHandle, containerPath: String, hostPath: Path): Unit =
        throw UnsupportedByBackendException("runtime copy", name)
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
