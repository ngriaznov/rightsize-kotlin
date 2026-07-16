package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Pure msb CLI argv construction. Flag spellings verified empirically against the real
 * `msb` binary. ATTACHED mode (no -d): `msb run -d` (detached) never starts the image's
 * own ENTRYPOINT/CMD, only attached mode does.
 */
object MsbCommands {
    fun run(spec: ContainerSpec): List<String> = buildList {
        add("run"); add("--name"); add(spec.name)
        spec.memoryLimitMb?.let { add("-m"); add("${it}M") }   // `msb run --help`: -m/--memory <MEMORY>, e.g. 512M/1G
        spec.ports.forEach { add("-p"); add("${it.hostPort}:${it.guestPort}") }
        spec.env.forEach { (k, v) -> add("-e"); add("$k=$v") }
        spec.mounts.forEach { add("--mount-file"); add("${it.hostPath}:${it.guestPath}") }
        // --snapshot is mutually exclusive with the image arg (msb run --help): a checkpointRef
        // boots from a disk snapshot instead of the ordinary image (see docs/checkpoints.md).
        // Still no -d, same as every other boot this backend does — detached mode never starts
        // the image's own ENTRYPOINT/CMD (see this file's header comment), and that's just as
        // true of a snapshot-booted sandbox as an ordinary one.
        val checkpointRef = spec.checkpointRef
        if (checkpointRef != null) { add("--snapshot"); add(checkpointRef) } else add(spec.image)
        spec.command?.let { add("--"); addAll(it) }   // null => image default ENTRYPOINT/CMD runs
    }

    fun exec(name: String, cmd: List<String>) = listOf("exec", name, "--") + cmd
    fun execStream(name: String, cmd: List<String>) = listOf("exec", "--stream", name, "--") + cmd
    fun logs(name: String) = listOf("logs", name, "--tail", "1000")
    fun followLogs(name: String) = listOf("logs", name, "-f")
    fun stop(name: String) = listOf("stop", name)
    fun rm(name: String) = listOf("rm", name)
    fun ls() = listOf("ls", "--format", "json")
    /** `msb snapshot create --from <sandbox> <name>` requires [sandbox] STOPPED; writes a sparse
     * disk snapshot artifact under `~/.microsandbox/snapshots/<name>`. */
    fun snapshotCreate(sandbox: String, name: String) = listOf("snapshot", "create", "--from", sandbox, name)
    fun snapshotRemove(name: String) = listOf("snapshot", "rm", name)
    /** `msb snapshot inspect <name>` — its exit code alone is [MsbCliBackend.hasCheckpoint]'s
     * signal (0 = exists, non-zero = doesn't); see docs/checkpoints.md. */
    fun snapshotInspect(name: String) = listOf("snapshot", "inspect", name)

    /**
     * `msb copy -q <src> <name>:<dst>` — copies a host file or directory into the running
     * sandbox at [containerPath]; `-q` suppresses msb's own progress output (this backend never
     * reads copy's stdout, only its exit code and stderr on failure). See docs/copy.md.
     */
    fun copyTo(name: String, hostPath: Path, containerPath: String) =
        listOf("copy", "-q", hostPath.toString(), "$name:$containerPath")

    /** `msb copy -q <name>:<src> <dst>` — the reverse of [copyTo]. */
    fun copyFrom(name: String, containerPath: String, hostPath: Path) =
        listOf("copy", "-q", "$name:$containerPath", hostPath.toString())

    /**
     * `msb image remove <reference>` deletes one cached image's entry (manifest + layer
     * bookkeeping) so the next run/pull re-fetches it from scratch. Scoped to the single
     * image reference; never touches sandbox state or any other cached image, including
     * ones sharing layers with it (confirmed empirically: removing one image and
     * re-pulling it left a sibling's already-materialized shared base layer untouched
     * and bootable).
     */
    fun imageRemove(reference: String) = listOf("image", "remove", reference)
}

/** One entry of `msb ls --format json`'s output — only the two fields this backend reads.
 * `created_at`/`image` (and anything a future msb version adds) are ignored by the
 * [MsbLsJson.json] instance's `ignoreUnknownKeys`. Both fields are nullable rather than
 * defaulted to an empty string: an object missing one must be excluded outright, not
 * treated as an entry named `""`.
 */
@Serializable
private data class LsEntry(val name: String? = null, val status: String? = null)

/**
 * Parses `msb ls --format json`, the msb backend's only way to learn which sandboxes are
 * currently `Running`. msb 0.6.2's shape is a flat JSON array of objects with keys
 * `created_at, image, name, status` (status capitalized, e.g. "Running") — confirmed
 * empirically against the real binary.
 *
 * PIN: keep this in sync with `msb ls --format json`'s actual shape if msb changes it; the IT
 * (`MsbRunningSandboxNamesIT`) is the only guard on that short of a live-CLI shape drift.
 */
internal object MsbLsJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** Names of objects whose `status` field equals `Running`. An object missing `name` or
     * `status` is skipped, not counted; a `json` that isn't the documented array shape at all
     * yields an empty set rather than throwing, the same best-effort posture the hand-rolled
     * parser this replaced had.
     */
    fun runningNames(json: String): Set<String> = runCatching {
        this.json.decodeFromString<List<LsEntry>>(json)
    }.getOrDefault(emptyList()).mapNotNull { entry ->
        entry.name?.takeIf { entry.status == "Running" }
    }.toSet()
}
