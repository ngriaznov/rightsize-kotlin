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
