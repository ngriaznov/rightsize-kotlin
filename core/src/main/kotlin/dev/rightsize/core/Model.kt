package dev.rightsize.core

import dev.rightsize.core.checkpoint.CheckpointArchiver
import dev.rightsize.core.checkpoint.CheckpointRegistry
import dev.rightsize.core.checkpoint.validateCheckpointName
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
    /**
     * Set by `GenericContainer.fromCheckpoint` to the source [Checkpoint.ref] — docker ignores
     * it (the ref already IS `image`, so the normal create path just works); msb boots via
     * `msb run --snapshot <checkpointRef>` instead of its normal image boot when this is set,
     * keeping every other flag (name, ports, env, memory) identical. Never part of reuse
     * identity (see `dev.rightsize.core.reuse.ReuseFromCheckpointConflictException`) — reuse and
     * `fromCheckpoint` are not a supported combination.
     */
    val checkpointRef: String? = null,
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
 * captured via whichever backend was active ([backend], `"docker"`/`"microsandbox"` — matching
 * `RIGHTSIZE_BACKEND`) plus enough of the source container's configuration ([spec]) to boot an
 * equivalent one via `GenericContainer.fromCheckpoint`. [ref]'s shape is backend-specific: a
 * docker image tag (`rightsize/checkpoint:<12-hex>`) or an msb snapshot name
 * (`rz-ckpt-<12-hex>`) for an unnamed checkpoint (random per call), or the same shapes with a
 * caller-chosen name in place of the hex (`rightsize/checkpoint:<name>` / `rz-ckpt-<name>`) for
 * `GenericContainer.checkpoint(name)`. This is a filesystem capture, not a memory snapshot — a
 * restored container's processes restart from scratch. Restoring under a different active
 * backend than the one that created it throws [CheckpointBackendMismatchException] before any
 * backend call. See docs/checkpoints.md.
 *
 * [spec] is a [CheckpointSpec], never the full original [ContainerSpec] — this holds whether the
 * [Checkpoint] came straight back from `GenericContainer.checkpoint()`/`checkpoint(name)` or was
 * rediscovered later via `Checkpoint.find`/`Checkpoint.list`: only the restore-relevant fields
 * `fromCheckpoint` actually reads (`env`, `command`, `exposedPorts`, `memoryLimitMb`) are ever
 * carried, not the source container's name, host ports, mounts, network, or run id.
 */
data class Checkpoint(val ref: String, val backend: String, val spec: CheckpointSpec) {
    /**
     * Writes this checkpoint out as a portable archive at [path] — a plain tar containing
     * `checkpoint.json` (this checkpoint's metadata, plus a format version) and `artifact` (the
     * backend's own payload: a docker `save` tar or an msb `snapshot export` `.tar.zst`) — see
     * docs/checkpoints.md's "Moving checkpoints between machines" section. Requires the ACTIVE
     * backend (`Backends.active()`) to equal [backend], or this throws
     * [CheckpointBackendMismatchException] before any backend or filesystem work; requires the
     * artifact to still exist (`SandboxBackend.hasCheckpoint`), or this throws
     * [dev.rightsize.core.checkpoint.StaleCheckpointException] rather than writing a broken
     * archive. [path]'s parent directories are created if missing; an existing file there is
     * overwritten. See [dev.rightsize.core.checkpoint.CheckpointArchiver.exportArchive] for the
     * actual logic, unit-tested there directly against a fake backend and a temp directory.
     */
    fun exportTo(path: Path) {
        CheckpointArchiver(CheckpointRegistry(CacheDir.resolve())).exportArchive(this, Backends.active(), path)
    }

    companion object {
        /**
         * Rediscovers the checkpoint created by `GenericContainer.checkpoint(name)` in ANY
         * process — against the active backend (`Backends.active()`) and the real rightsize
         * cache dir (`CacheDir.resolve()`) — see docs/checkpoints.md's "Reusing checkpoints
         * across runs" section. `null` when no registry entry exists for [name], the entry is
         * corrupt (best-effort cleaned up), or the entry's backend matches the active one but its
         * artifact is gone (also cleaned up as stale). An entry recorded under a DIFFERENT
         * backend than the active one is returned WITHOUT probing — restoring it still goes
         * through `GenericContainer.fromCheckpoint`'s own [CheckpointBackendMismatchException]
         * gate. See [CheckpointRegistry.find] for the actual logic, unit-tested there directly
         * against fakes and a temp directory.
         *
         * [name] is validated (throwing [dev.rightsize.core.checkpoint.InvalidCheckpointNameException]
         * on a non-matching shape) before either `Backends.active()` or `CacheDir.resolve()` runs
         * — the same before-any-real-work placement `GenericContainer.checkpoint(name)` uses on
         * the write path, not merely relying on [CheckpointRegistry.file]'s own boundary check
         * (which still validates again regardless, in depth).
         */
        fun find(name: String): Checkpoint? {
            validateCheckpointName(name)
            return CheckpointRegistry(CacheDir.resolve()).find(Backends.active(), name)
        }

        /** Every named checkpoint currently in the registry — no artifact probing (unlike
         * [find]), so a stale entry is still listed here; only [find]/[remove] resolve
         * staleness. See [CheckpointRegistry.list]. */
        fun list(): List<Checkpoint> = CheckpointRegistry(CacheDir.resolve()).list()

        /** Best-effort removes [name]'s backend artifact (via the active backend's
         * `removeCheckpoint`) and its registry entry. `true` only if a registry entry actually
         * existed; idempotent either way — calling it again on the same [name] is always safe.
         * [name] is validated the same way, at the same point, as [find]. See [CheckpointRegistry.remove]. */
        fun remove(name: String): Boolean {
            validateCheckpointName(name)
            return CheckpointRegistry(CacheDir.resolve()).remove(Backends.active(), name)
        }

        /**
         * Materializes the archive at [path] (written by [exportTo], on this machine or another
         * one running the same backend) as a restorable [Checkpoint] — against the active
         * backend (`Backends.active()`) and the real rightsize cache dir (`CacheDir.resolve()`).
         * `checkpoint.json` is validated (format version, its `name` against the checkpoint-name
         * grammar when non-null, and its recorded `backend` against the active one) entirely
         * before any backend call — a missing file, a malformed archive, or a wrong-backend one
         * throws a typed error ([dev.rightsize.core.checkpoint.MalformedCheckpointArchiveException]
         * / [CheckpointBackendMismatchException]) and never reaches `SandboxBackend.importCheckpoint`.
         * The returned [Checkpoint] carries the backend's own EFFECTIVE ref (not necessarily the
         * archived one — see `SandboxBackend.importCheckpoint`'s doc), and for a NAMED archive
         * that's also what the registry entry is rewritten to point at — replace semantics
         * matching `GenericContainer.checkpoint(name)`'s own (the old same-backend artifact is
         * best-effort removed first). A nameless archive returns an ephemeral [Checkpoint] with
         * no registry write. Named `importFrom`, not `import` (a reserved word in several of the
         * sibling rightsize libraries) — the pinned name across all three. See
         * [dev.rightsize.core.checkpoint.CheckpointArchiver.importArchive] for the actual logic,
         * unit-tested there directly against a fake backend and a temp directory.
         */
        fun importFrom(path: Path): Checkpoint =
            CheckpointArchiver(CheckpointRegistry(CacheDir.resolve())).importArchive(Backends.active(), path)
    }
}

/**
 * Thrown by `GenericContainer.checkpoint()` when the active backend's `capabilities.checkpoint`
 * is false. Raised before any backend call, same as [IsolationRequiredException]. Both real
 * backends (docker, microsandbox) support checkpoint today — this only fires for a backend
 * that doesn't declare the capability (e.g. a test double).
 */
class CheckpointUnsupportedException(backend: String) : RuntimeException(
    "checkpoint() requires a backend with checkpoint support (capabilities.checkpoint), but the " +
        "active backend is '$backend', which does not support it")

/**
 * Thrown by `GenericContainer.fromCheckpoint(cp).start()` when the active backend differs from
 * the one that created [Checkpoint.ref] — an msb snapshot name is meaningless to docker and a
 * docker image tag is meaningless to msb. Raised before any backend call, same as
 * [IsolationRequiredException].
 */
class CheckpointBackendMismatchException(creatorBackend: String, activeBackend: String) : RuntimeException(
    "This checkpoint was created by the '$creatorBackend' backend, but the active backend is " +
        "'$activeBackend' — set RIGHTSIZE_BACKEND=$creatorBackend to restore it, or call " +
        "checkpoint() again under '$activeBackend' to create one it can restore")

/**
 * Thrown by `GenericContainer.copyFileToContainer`/`copyContentToContainer`/
 * `copyFileFromContainer` when the given container path is not absolute — both `msb copy` and
 * `docker cp` require a `NAME:/abs/path` shape, so a relative path is rejected before any
 * backend call. See docs/copy.md.
 */
class NonAbsoluteContainerPathException(path: String) : RuntimeException(
    "Container path '$path' must be absolute (start with '/') — both backends require an " +
        "absolute guest path for a runtime copy")

/**
 * Thrown when a backend's runtime copy ([dev.rightsize.core.SandboxBackend.copyToContainer]/
 * [dev.rightsize.core.SandboxBackend.copyFromContainer]) fails — carries the underlying tool's
 * stderr, so a failed copy (missing source in the guest, permission denied, etc.) is never a
 * silent success. See docs/copy.md.
 */
class ContainerCopyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
