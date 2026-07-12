package dev.rightsize.core

import java.nio.file.Path

/** A host↔guest port map entry: the runtime binds [hostPort] on loopback, forwards to [guestPort]. */
data class PortBinding(val hostPort: Int, val guestPort: Int)

/** A host file exposed read-only (by default) inside the guest at [guestPath]. */
data class FileMount(val hostPath: Path, val guestPath: String, val readOnly: Boolean = true)

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** The everything-a-backend-needs-to-create-one-container record. Host ports are already chosen. */
data class ContainerSpec(
    val name: String,
    val image: String,
    val env: Map<String, String> = emptyMap(),
    val command: List<String>? = null,
    val ports: List<PortBinding> = emptyList(),
    val mounts: List<FileMount> = emptyList(),
    val networkId: String? = null,
    val aliases: List<String> = emptyList(),
    val runId: String,
    val memoryLimitMb: Long? = null,
    /**
     * Marks this container as a reuse sandbox (unused before reuse itself ships — always
     * `false` today). A `true` spec must stay out of every own-run cleanup path a backend
     * runs today, and the reaper's ledger never lists a `keepAlive` sandbox in a
     * `.sandboxes` file — a reuse sandbox must be structurally immune to any sweep.
     */
    val keepAlive: Boolean = false,
)

/** Opaque per-backend container reference; [id] is backend-native, [spec] is what created it. */
interface SandboxHandle { val id: String; val spec: ContainerSpec }

/**
 * [remedy], when given, is appended as " — $remedy" after the base sentence — e.g. a hint to
 * retry with a different backend. Keep [feature] a short noun phrase (what's missing/unsupported)
 * and put any actionable advice in [remedy] rather than embedding it in [feature]; mixing the two
 * into one clause renders as a run-on ("...backend the image 'X' — do Y' is not supported...").
 */
class UnsupportedByBackendException(feature: String, backend: String, remedy: String? = null) :
    RuntimeException(
        "Feature '$feature' is not supported by the '$backend' backend" + (remedy?.let { " — $it" } ?: ""))

/**
 * Thrown by `GenericContainer.start()` when `withRequireIsolation()` was called but the active
 * backend's `capabilities.hardwareIsolated` is false — e.g. Docker, whose containers share the
 * host kernel rather than each getting its own microVM. Raised before any create/network work,
 * so no sandbox is ever created for a rejected start.
 */
class IsolationRequiredException(backend: String) : RuntimeException(
    "withRequireIsolation() requires a hardware-isolated backend, but the active backend is " +
        "'$backend', which is not — set RIGHTSIZE_BACKEND=microsandbox to use the microsandbox backend, " +
        "which runs each sandbox in its own microVM")

/**
 * Signals that a backend's `start()` failed because a host port it tried to bind was already in
 * use by something else. Backend authors should throw this directly when they can positively
 * identify the condition (e.g. from a structured error the runtime returns), so
 * [dev.rightsize.GenericContainer]'s port-retry loop no longer has to grep exception messages.
 *
 * Backends that cannot yet detect the condition this precisely are not required to throw it —
 * `GenericContainer` retains a string-matching fallback classifier for those, so no existing
 * backend's retry behavior regresses by not adopting this type immediately.
 */
class PortBindConflictException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The reuse-relevant subset of [ContainerSpec] carried by a [Checkpoint] — deliberately
 * narrower than the full spec (no name, no host ports, no `runId`, no `networkId`/`aliases`/
 * `mounts`): those describe how a *specific* container instance happened to run, not what a
 * restored container needs to boot an equivalent one from the checkpoint's image. Mounts are
 * intentionally excluded — the checkpoint's filesystem already carries whatever a mount would
 * have copied in.
 */
data class CheckpointSpec(
    val env: Map<String, String> = emptyMap(),
    val command: List<String>? = null,
    val exposedPorts: List<Int> = emptyList(),
    val memoryLimitMb: Long? = null,
)

/**
 * The result of `GenericContainer.checkpoint()`: a filesystem snapshot of a running container,
 * captured as a committed image ([imageRef], `rightsize/checkpoint:<12-hex>`, random per call)
 * plus enough of the source container's configuration ([spec]) to boot an equivalent one via
 * `GenericContainer.fromCheckpoint`. This is a filesystem capture, not a memory snapshot — a
 * restored container's processes restart from scratch. See docs/checkpoints.md.
 */
data class Checkpoint(val imageRef: String, val spec: CheckpointSpec)

/**
 * Thrown by `GenericContainer.checkpoint()` when the active backend's `capabilities.checkpoint`
 * is false — e.g. microsandbox, which has no upstream image-commit or snapshot primitive yet.
 * Raised before any backend call, same as [IsolationRequiredException]. Points at the docker
 * backend and the roadmap (native microVM memory snapshots, which need upstream microsandbox
 * support) as the remedy.
 */
class CheckpointUnsupportedException(backend: String) : RuntimeException(
    "checkpoint() requires a backend with checkpoint support, but the active backend is " +
        "'$backend', which does not support it — set RIGHTSIZE_BACKEND=docker to use the docker " +
        "backend, which implements checkpoint via image commit (native microVM memory snapshots for " +
        "microsandbox are on the roadmap)")
