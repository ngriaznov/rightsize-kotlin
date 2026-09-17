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
        // `--root-disk` covers both a size cap and a tmpfs root (GenericContainer.start()'s
        // validation rejects both being set at once, so at most one of these fires — checked both
        // against the builder-set fields before customizeSpec runs and again against the spec
        // customizeSpec actually produces, so a subclass hook can't reach this emission with an
        // unvalidated, conflicting spec either).
        spec.diskLimitMb?.let { add("--root-disk"); add("${it}M") }
        spec.tmpfsRootMb?.let { add("--root-disk"); add("tmpfs:${it}M") }
        // `--net private` keeps published ports and private-range network links working while
        // blocking public-internet egress — `--net none` was tried and breaks port forwarding.
        if (spec.networkDisabled) { add("--net"); add("private") }
        spec.ports.forEach { add("-p"); add("${it.hostPort}:${it.guestPort}") }
        spec.env.forEach { (k, v) -> add("-e"); add("$k=$v") }
        // The option block is always spelled out, never left to msb's defaults, for two
        // reasons on top of each other. The access token (`ro`/`rw`) carries
        // FileMount.readOnly, which msb enforces as a genuine guest-side write block — and it
        // keeps OUR spec parseable on Windows: msb stages each mount into a temp directory and
        // canonicalizes it, which there yields the extended-length `\\?\C:\...` form, and its
        // splitter skips a drive prefix only for a bare drive letter, so a spec with no option
        // block splits at the drive's colon and rejects the path tail as options. `nodev`
        // exists because msb then rebuilds an INTERNAL `tag:staged_path[:opts]` spec for the
        // same mount, carrying over only NON-DEFAULT option tokens — `rw` is its default and
        // is dropped, which on Windows strips the internal spec's option block and re-creates
        // the same misparse one layer down (captured: `--mount "fm_…:\\?\C:\…": expected flag
        // or key=value option`). `nodev` always survives the carry-over, and for a single-file
        // mount it is meaningless (no device nodes to block): verified against a real msb
        // 0.6.8 — `rw,nodev` mounts `rw,nodev` and accepts an in-guest write, `ro,nodev`
        // rejects one with `Read-only file system`.
        spec.mounts.forEach {
            add("--mount-file")
            add("${it.hostPath}:${it.guestPath}:${if (it.readOnly) "ro" else "rw"},nodev")
        }
        add(spec.image)
        spec.command?.let { add("--"); addAll(it) }   // null => image default ENTRYPOINT/CMD runs
    }

    /**
     * `msb restore <ref> --name <name> [-m <mem>M] [-p host:guest]...` — msb 0.7's dedicated
     * restore command, the sole survivor of what `run --from-snapshot` used to do. msb 0.7
     * removed `--from-snapshot` from `run` outright: clap now rejects it as an unrecognized
     * flag, and `msb run` itself bails "snapshot sources require `msb restore SNAPSHOT --name
     * NAME`" for a snapshot-shaped source (confirmed against msb's own source at tag v0.7.1 —
     * crates/cli/lib/commands/{run,restore}.rs). [MsbCliBackend] routes here instead of [run]
     * whenever [spec]'s `checkpointRef` is set — both [MsbCliBackend.createCheckpoint]'s own
     * re-boot and a `GenericContainer.fromCheckpoint(cp).start()` call reach this the same way.
     *
     * NEVER `--disk-only`, as of msb 0.7.1: every snapshot this backend ever creates or restores
     * is DISK-scope (`msb snapshot create --from-sandbox`, no memory capture), and a disk-scope
     * snapshot now REJECTS `--disk-only` outright — `invalid config: disk_only requires a full
     * snapshot with checkpoint state` (verified empirically against the real 0.7.1 binary).
     * Restoring a disk-scope snapshot is inherently a cold boot of the captured disk alone, with
     * no resumed RAM/processes and no flag needed to ask for that — matching what this backend's
     * checkpoint has always meant (a filesystem snapshot, not a memory snapshot — see
     * docs/checkpoints.md). A prior pin of this library against msb 0.7.0 emitted `--disk-only`
     * here; that flag must stay omitted against 0.7.1 and later.
     *
     * `restore` has no `-e`/`--env` flag AND no trailing command at all — unlike `RunArgs`,
     * `RestoreArgs` (crates/cli/lib/commands/restore.rs) declares neither. A restore
     * replays the sandbox's captured configuration (its own baked env and boot command) exactly
     * as the snapshot recorded it, with no CLI override for either — so [spec.env]/[spec.command]
     * are never emitted here. For [MsbCliBackend.createCheckpoint]'s own re-boot that
     * configuration IS [spec]'s own (the container's unchanged env/command, captured into the
     * snapshot moments earlier by the very same [spec]), so not re-passing it changes nothing
     * observable. A caller reaching this with a GENUINELY different env/command (via
     * `GenericContainer.fromCheckpoint(cp).withEnv(...)`/`withCommand(...)`) never gets here at
     * all — that's rejected earlier, before any backend call, by
     * [dev.rightsize.core.CheckpointRestoreOverrideUnsupportedException].
     *
     * [spec.mounts] and [spec.diskLimitMb]/[spec.tmpfsRootMb]/[spec.networkDisabled] are likewise
     * never emitted: `restore`'s own resource/control flags (`--volume`, network-policy flags)
     * don't line up with `run`'s (`--mount-file`, `--root-disk`, `--net private`), and neither
     * this backend's tests nor `docs/checkpoints.md` promise either survives a restore today — a
     * `tmpfsRootMb` container is refused before capture even happens (see
     * [MsbCliBackend.createCheckpoint]'s `TmpfsRootCheckpointException` guard), and mounts are
     * never part of `CheckpointSpec` in the first place (see its own doc comment: the checkpoint's
     * filesystem already carries whatever a mount would have copied in). For
     * [MsbCliBackend.createCheckpoint]'s own re-boot, whatever `diskLimitMb`/`networkDisabled` the
     * live sandbox already had simply rides along unemitted, same as env/command — the
     * restore boots the exact disk state that geometry was already baked into, so nothing
     * observable changes. A caller reaching this via `GenericContainer.fromCheckpoint(cp)
     * .withDiskLimit(...)`/`.withTmpfsRoot(...)`/`.withNetworkDisabled()` — asking for GENUINELY
     * different disk/network geometry than the snapshot captured — never gets here at all: that's
     * rejected earlier, before any backend call, by the same
     * [dev.rightsize.core.CheckpointRestoreOverrideUnsupportedException] guard that covers
     * env/command (see `GenericContainer.start`'s override check). [spec.ports] and
     * [spec.memoryLimitMb] DO carry over — `RestoreResourceArgs`/`RestoreControlArgs` both take
     * `-p`/`-m` for exactly this, sizing the fresh destination sandbox rather than describing
     * what was captured.
     */
    fun restore(spec: ContainerSpec): List<String> = buildList {
        val ref = requireNotNull(spec.checkpointRef) { "MsbCommands.restore requires spec.checkpointRef to be set" }
        add("restore"); add(ref)
        add("--name"); add(spec.name)
        spec.memoryLimitMb?.let { add("-m"); add("${it}M") }
        spec.ports.forEach { add("-p"); add("${it.hostPort}:${it.guestPort}") }
    }

    fun exec(name: String, cmd: List<String>) = listOf("exec", name, "--") + cmd
    fun execStream(name: String, cmd: List<String>) = listOf("exec", "--stream", name, "--") + cmd
    fun logs(name: String) = listOf("logs", name, "--tail", "1000")
    /** `msb logs <name> --source system --tail 1000` — the system log, distinct from the
     * workload's own output [logs] reads. [MsbCliBackend]'s fast-exit post-mortem
     * classification reads this for the boot-completion marker line the guest agent writes
     * only once it has actually come up (see [MsbCliBackend.isCleanFastExit]).
     */
    fun logsSystem(name: String) = listOf("logs", name, "--source", "system", "--tail", "1000")
    fun followLogs(name: String) = listOf("logs", name, "-f")
    fun stop(name: String) = listOf("stop", name)
    fun rm(name: String) = listOf("rm", name)
    fun ls() = listOf("ls", "--format", "json")
    /**
     * `msb snapshot create --from-sandbox <sandbox> <name>` requires [sandbox] STOPPED and
     * writes a DISK-scope snapshot artifact — under [destDir] when given (a path-ref checkpoint
     * — see [MsbCliBackend.createCheckpoint]), or msb's own default `~/.microsandbox/snapshots/`
     * store otherwise. [name] is NOT the artifact's on-disk location as of msb 0.7.1: it only
     * appears in msb's index (as `<sandbox>:<name>` in `snapshot list`) and in `snapshot
     * inspect` output — the real artifact path is `<dest-dir-or-default>/<sandbox>/snap_<32-hex
     * digest>`, msb's own choice, which `snapshot create` prints as the LAST line of its stdout
     * on success (see [MsbCliBackend.createCheckpoint]'s stdout-parsing). [JvmOverloads] keeps
     * the original 2-arg `(sandbox, name)` JVM descriptor alongside the 3-arg one — [destDir]
     * getting a default value would otherwise drop that descriptor from a published artifact and
     * break any compiled-against-the-old-jar caller.
     */
    @JvmOverloads
    fun snapshotCreate(sandbox: String, name: String, destDir: Path? = null) =
        listOf("snapshot", "create", "--from-sandbox", sandbox, name) +
            (destDir?.let { listOf("--dest-dir", it.toString()) } ?: emptyList())
    /** `msb snapshot rm <ref> -f` — msb 0.7.1 resolves `snapshot rm` reliably only against the
     * snapshot's own artifact path (a bare name or a `group:member` spec does not resolve —
     * verified empirically against the real binary), so [ref] is passed through verbatim, never
     * reduced to a basename; `-f` skips msb's own removal confirmation. Removing the NEWEST
     * (head) snapshot of a source sandbox while an OLDER sibling still exists is refused by msb
     * itself (`invalid config: cannot remove current head ...; first select another snapshot
     * with 'msb snapshot head src:<snapshot>'`) — [MsbCliBackend.removeCheckpoint] propagates
     * that refusal as-is rather than attempting any automatic head rotation; see
     * docs/checkpoints.md's Cleanup section. */
    fun snapshotRemove(ref: String) = listOf("snapshot", "rm", ref, "-f")
    /** `msb snapshot inspect <ref>` — its exit code alone is [MsbCliBackend.hasCheckpoint]'s
     * signal (0 = exists, non-zero = doesn't); see docs/checkpoints.md. As of msb 0.7.1 this
     * resolves reliably only against the snapshot's own artifact path for a path-shaped [ref]
     * (name-based resolution does not — verified empirically against the real binary), which is
     * exactly what every ref [MsbCliBackend.createCheckpoint] mints resolves to; a bare [ref]
     * (e.g. the digest-dir name [MsbCliBackend.importCheckpoint] returns) is passed through
     * unchanged, as it always has been. */
    fun snapshotInspect(ref: String) = listOf("snapshot", "inspect", ref)

    /**
     * `msb snapshot save <ref> <dest>` — writes a `.tar.zst` artifact archive for [ref] to
     * [dest]; the artifact half of a portable checkpoint archive (see
     * [MsbCliBackend.exportCheckpoint], docs/checkpoints.md's "Moving checkpoints between
     * machines" section). Deliberately never `--with-image`: its import fails an integrity check
     * ("raw manifest digest mismatch") on msb 0.6.6, so the destination machine pulls the OCI
     * image on the restored container's first boot instead.
     */
    fun snapshotExport(ref: String, dest: Path) = listOf("snapshot", "save", ref, dest.toString())

    /** `msb snapshot load <archive>` — unpacks [archive] into a digest-derived directory under
     * `~/.microsandbox/snapshots/`, discarding the original snapshot name entirely (see
     * [MsbCliBackend.importCheckpoint]). */
    fun snapshotImport(archive: Path) = listOf("snapshot", "load", archive.toString())

    /** `msb snapshot list --format json` — the only way to confirm the digest-dir basename
     * `snapshot load` itself prints is genuinely registered (see
     * [MsbCliBackend.importCheckpoint] and [MsbSnapshotListJson]). */
    fun snapshotList() = listOf("snapshot", "list", "--format", "json")

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

    /** The `status` field of the entry named [name], or `null` if no entry has that name
     * (including when `json` isn't the documented array shape at all). Unlike [runningNames],
     * which only ever answers "is this name currently Running", this reports the status
     * verbatim — [MsbCliBackend]'s fast-exit post-mortem classification needs to tell `Stopped`
     * apart from every other status a completed sandbox could be left in.
     */
    fun statusOf(json: String, name: String): String? = runCatching {
        this.json.decodeFromString<List<LsEntry>>(json)
    }.getOrDefault(emptyList()).firstOrNull { it.name == name }?.status
}

/** One entry of `msb snapshot list --format json`'s output — only the fields
 * [MsbSnapshotListJson.contains] reads; `created_at`/`digest` (and anything a future msb version
 * adds) are ignored by the `ignoreUnknownKeys` [Json] instance. `artifact_path` names the on-disk
 * snapshot directory (whose basename is the digest-dir name `msb snapshot load` itself
 * prints); `name` was confirmed to carry that same digest-dir value against msb 0.6.8 for an
 * imported snapshot — matching on both `name` and `artifact_path`'s basename covers whichever
 * one a future msb version favors.
 */
@Serializable
private data class SnapshotEntry(
    val name: String? = null,
    val artifact_path: String? = null,
)

/**
 * Parses `msb snapshot list --format json`, used to confirm the digest-dir basename `msb
 * snapshot import`'s own output names is genuinely a registered snapshot (see
 * [MsbCliBackend.importCheckpoint]). The FULL `sha256:<64hex>` `digest` field is deliberately
 * never surfaced here: msb does not resolve it as a snapshot ref (`msb snapshot inspect
 * sha256:<full>` fails "snapshot not found", treating it as a literal path) — only the
 * digest-dir name (`sha256-<16hex>`) does, for `inspect`, `rm`, and `restore` alike.
 *
 * PIN: keep this in sync with `msb snapshot list --format json`'s actual shape if msb changes it
 * — the msb `sandbox-it` lane is the only guard on that short of a live-CLI shape drift, same as
 * [MsbLsJson]'s own pin.
 */
internal object MsbSnapshotListJson {
    private val json = Json { ignoreUnknownKeys = true }

    /** True if an entry's `name` equals [digestDir], or its `artifact_path`'s basename does —
     * `false` if no entry matches or [json] isn't the documented array shape. */
    fun contains(json: String, digestDir: String): Boolean = runCatching {
        this.json.decodeFromString<List<SnapshotEntry>>(json)
    }.getOrDefault(emptyList()).any { entry ->
        entry.name == digestDir || entry.artifact_path?.let { Path.of(it).fileName?.toString() } == digestDir
    }
}
