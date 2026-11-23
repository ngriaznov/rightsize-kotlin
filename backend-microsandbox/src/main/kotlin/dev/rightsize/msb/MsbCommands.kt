package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.FileMount
import dev.rightsize.core.PortProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Pure msb CLI argv construction. Flag spellings verified empirically against the real
 * `msb` binary. rightsize always builds ATTACHED-mode argv (no `-d`) — see
 * [MsbCliBackend]'s own doc for why it stays attached.
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
        // One rule per UDP network link this sandbox needs to reach — a bare --net-rule (no
        // --net flag) PREPENDS to msb's default policy rather than replacing it, so public
        // internet/DNS/published-port ingress keep working alongside it.
        spec.hostUdpEgressPorts.forEach { add("--net-rule"); add("allow@host:udp:$it") }
        spec.ports.forEach { add("-p"); add(portArg(it)) }
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
        // mount it is meaningless (no device nodes to block): this parity gap closed as of msb
        // 0.6.8, confirmed against a real binary — `rw,nodev` mounts `rw,nodev` and accepts an
        // in-guest write, `ro,nodev` rejects one with `Read-only file system`.
        // The string itself comes from [mountSpec], shared with [restore]'s `--volume`.
        spec.mounts.forEach { add("--mount-file"); add(mountSpec(it)) }
        add(spec.image)
        spec.command?.let { add("--"); addAll(it) }   // null => image default ENTRYPOINT/CMD runs
    }

    /**
     * `msb restore <ref> --name <name> [-m <mem>M] [-p host:guest]... [--volume host:guest:opts]...`
     * — msb 0.7's dedicated restore command, the sole survivor of what `run --from-snapshot` used
     * to do. msb 0.7 removed `--from-snapshot` from `run`; msb 0.8.1 accepts it there again, but
     * this backend keeps using `restore` — the command that takes the per-restore flags below
     * (msb's own source, crates/cli/lib/commands/{run,restore}.rs). [MsbCliBackend] routes here instead of [run]
     * whenever [spec]'s `checkpointRef` is set — both [MsbCliBackend.createCheckpoint]'s own
     * re-boot and a `GenericContainer.fromCheckpoint(cp).start()` call reach this the same way.
     *
     * `restore.rs`'s own doc calls this "Restore a snapshot into a new **detached** sandbox" —
     * confirmed empirically against the real 0.7.1 binary: the `restore` process activates the
     * sandbox and EXITS (typically within seconds, with little or no stdout) once activation
     * succeeds, while the sandbox keeps booting toward Running in the background; a nonzero exit
     * means the restore itself failed, the reason on stderr/stdout. This is NOT the attached-child
     * supervision `msb run` gives [MsbCliBackend] (see [MsbCliBackend.awaitRunning]'s doc) — a
     * restore boot is supervised by [MsbCliBackend.awaitRestoreRunning] instead: wait for this
     * process to exit, then poll `msb ls` for Running separately. See docs/checkpoints.md.
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
     * [spec.diskLimitMb]/[spec.tmpfsRootMb]/[spec.networkDisabled] are likewise never emitted:
     * `restore`'s own control flags (network-policy flags) don't line up with `run`'s
     * (`--root-disk`, `--net private`), and neither this backend's tests nor
     * `docs/checkpoints.md` promise either survives a restore today — a `tmpfsRootMb` container
     * is refused before capture even happens (see [MsbCliBackend.createCheckpoint]'s
     * `TmpfsRootCheckpointException` guard). For [MsbCliBackend.createCheckpoint]'s own re-boot,
     * whatever `diskLimitMb`/`networkDisabled` the live sandbox already had simply rides along
     * unemitted, same as env/command — the restore boots the exact disk state that geometry was
     * already baked into, so nothing observable changes. A caller reaching this via
     * `GenericContainer.fromCheckpoint(cp).withDiskLimit(...)`/`.withTmpfsRoot(...)`/
     * `.withNetworkDisabled()` — asking for GENUINELY different disk/network geometry than the
     * snapshot captured — never gets here at all: that's rejected earlier, before any backend
     * call, by the same [dev.rightsize.core.CheckpointRestoreOverrideUnsupportedException] guard
     * that covers env/command (see `GenericContainer.start`'s override check). [spec.ports] and
     * [spec.memoryLimitMb] DO carry over — `RestoreResourceArgs`/`RestoreControlArgs` both take
     * `-p`/`-m` for exactly this, sizing the fresh destination sandbox rather than describing
     * what was captured.
     *
     * [spec.mounts] carry over too, as one `--volume HOST:GUEST:ro|rw,nodev` per mount in spec
     * order, spelled by the same [mountSpec] [run] uses for `--mount-file` (verified against the
     * real binary on msb 0.7.6 and 0.7.8). A file added with `withCopyFileToContainer` is a
     * start-time bind mount of a host file, and a snapshot does NOT capture its content: restored
     * without a `--volume` for it, 0.7.6 hands back the guest path EMPTY (0 bytes) and 0.7.8
     * refuses with `restore requires destination bindings for: mount <guest path>` (see
     * [isRestoreMissingBindings]). So [MsbCliBackend.createCheckpoint]'s own re-boot passes the
     * container's mounts here to keep the files, and a `fromCheckpoint(cp)` container passes
     * whatever it was given with `withCopyFileToContainer` — those are not recorded in the
     * checkpoint, so the caller re-adds them. `--allow-missing-resources` is never emitted: a
     * missing file must stop the restore, not come back empty. Only a snapshot taken by msb
     * 0.7.8 or later records its mounts, so only those are refused; an older snapshot restores
     * with the file empty whatever this emits.
     *
     * [spec.hostUdpEgressPorts] DOES carry over too, as the `--net-default`/
     * `--net-rule` policy below — unlike env/command/disk/network-disabled, it was never
     * part of what the snapshot captured in the first place: it is per-run wiring the core
     * recomputes from this restore's own [dev.rightsize.core.NetworkLink]s before every start
     * (see `GenericContainer.start`'s own doc), so re-deriving it fresh for THIS restore is
     * exactly right, not a captured value being replayed.
     */
    fun restore(spec: ContainerSpec): List<String> = buildList {
        val ref = requireNotNull(spec.checkpointRef) { "MsbCommands.restore requires spec.checkpointRef to be set" }
        add("restore"); add(ref)
        add("--name"); add(spec.name)
        spec.memoryLimitMb?.let { add("-m"); add("${it}M") }
        spec.ports.forEach { add("-p"); add(portArg(it)) }
        // Same option block and rationale as run()'s --mount-file (see the comment there); restore
        // takes the file back through --volume instead. After -p, before the network policy.
        spec.mounts.forEach { add("--volume"); add(mountSpec(it)) }
        // Three reasons this is a full replacement policy, not a single added rule like run()'s:
        // restore never carries a CLI-given policy into the restored sandbox at all (msb falls
        // back to its own default); restore's --net-rule only takes effect together with
        // --net-default (there is no --net profile flag on restore); and --net-default replaces
        // BOTH directions at once, so the published-port ingress a bare policy gets for free on
        // `run` has to be re-stated here explicitly (allow:ingress@any) or restored ports go dark.
        if (spec.hostUdpEgressPorts.isNotEmpty()) {
            add("--net-default"); add("deny")
            add("--net-rule")
            add((listOf("allow@public", "allow@dns") +
                spec.hostUdpEgressPorts.map { "allow@host:udp:$it" } +
                listOf("allow:ingress@any")).joinToString(","))
        }
    }

    /** `<host>:<guest>`, with a trailing `/udp` for a UDP [dev.rightsize.core.PortBinding] — the
     * one place both [run] and [restore] decide a `-p` argument's shape, so a checkpoint reboot
     * or an ordinary `fromCheckpoint(...).start()` restore re-publishes a UDP mapping exactly like
     * the original `run` did, never silently dropping back to TCP. */
    private fun portArg(binding: dev.rightsize.core.PortBinding): String =
        "${binding.hostPort}:${binding.guestPort}" + if (binding.protocol == PortProtocol.UDP) "/udp" else ""

    /** `<host>:<guest>:ro|rw,nodev` — the one place both [run] (`--mount-file`) and [restore]
     * (`--volume`) decide a file mount's spec string, so the two cannot drift apart. The always-
     * present option block is explained at [run]'s `--mount-file` emission. */
    private fun mountSpec(mount: FileMount): String =
        "${mount.hostPath}:${mount.guestPath}:${if (mount.readOnly) "ro" else "rw"},nodev"

    fun exec(name: String, cmd: List<String>) = listOf("exec", name, "--") + cmd
    fun execStream(name: String, cmd: List<String>) = listOf("exec", "--stream", name, "--") + cmd

    /**
     * `msb exec [-e KEY=VALUE]... <name> -- <cmd...>` — the long-lived workload-revival exec
     * session [MsbCliBackend] spawns once a restore reaches Running (see
     * [MsbCliBackend.spawnWorkloadExecChild]'s doc and docs/checkpoints.md): restoring boots the
     * sandbox idle (only `agentd` inside — confirmed empirically against the real 0.7.1 binary),
     * so this is what actually re-runs the checkpointed workload, and it becomes this boot's new
     * supervising attached child (the slot [MsbCliBackend.Handle.attached] left `null` for a plain
     * restore before this existed). Unlike [exec]'s short one-shot probes, THIS session's own
     * stdout/stderr are what msb's own log capture records — `msb exec` is exactly what lands in
     * `exec.log`, served back by `msb logs`/`-f` (confirmed empirically against the real 0.7.1
     * binary) — so, unlike every other invocation this backend spawns, this process's output is
     * never drained for THIS backend's own diagnostics beyond a short early-exit tail; `logs()`
     * keeps sourcing fresh from `msb logs` as it always has.
     *
     * [env] pairs are emitted as repeated `-e KEY=VALUE` flags, BEFORE the sandbox name/`--` —
     * `msb exec --help` documents `-e, --env <ENV>` as repeatable (verified empirically against
     * the real 0.7.1 binary), the same flag spelling and options-before-positional convention
     * [run] already uses for the ordinary (non-restore) boot path. `restore` itself has no
     * `-e`/env flag at all (see [restore]'s own doc) — this is what lets the revived workload see
     * the checkpoint's own env after all, despite the restore step that brought the sandbox up
     * dropping it.
     */
    fun execWorkload(name: String, env: Map<String, String>, cmd: List<String>): List<String> = buildList {
        add("exec")
        env.forEach { (k, v) -> add("-e"); add("$k=$v") }
        add(name); add("--"); addAll(cmd)
    }
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
    /** `msb rm -f <name>` — removes the sandbox record and directory whatever state the row is
     * in, including one msb left stuck in `Starting` after a failed boot, which plain [rm]
     * refuses as "still running". Used between the two attempts of [MsbCliBackend]'s
     * agent-relay retry; every other caller keeps plain [rm]. */
    fun rmForce(name: String) = listOf("rm", "-f", name)
    fun ls() = listOf("ls", "--format", "json")
    /**
     * `msb snapshot create --from-sandbox <sandbox> <group>` requires [sandbox] STOPPED and
     * writes a DISK-scope snapshot into a NEW snapshot group called [group] — under [destDir]
     * when given (a path-ref checkpoint — see [MsbCliBackend.createCheckpoint]), or msb's own
     * default `~/.microsandbox/snapshots/` store otherwise. msb names the member itself
     * (`snap_<32-hex digest>`), so the real artifact path is
     * `<dest-dir-or-default>/<group>/snap_<id>`, which `snapshot create` prints as the LAST line
     * of its stdout on success (see [MsbCliBackend.createCheckpoint]'s stdout-parsing). msb 0.8
     * refuses a [group] whose directory already exists (`snapshot already exists: <group>`),
     * including one left empty by `snapshot rm --checkpoint` — see [snapshotRemoveGroup].
     * [JvmOverloads] keeps the original 2-arg `(sandbox, group)` JVM descriptor alongside the
     * 3-arg one — [destDir] getting a default value would otherwise drop that descriptor from a
     * published artifact and break any compiled-against-the-old-jar caller.
     */
    @JvmOverloads
    fun snapshotCreate(sandbox: String, group: String, destDir: Path? = null) =
        listOf("snapshot", "create", "--from-sandbox", sandbox, group) +
            (destDir?.let { listOf("--dest-dir", it.toString()) } ?: emptyList())
    /** `msb snapshot rm <groupDir> --all` — removes a whole snapshot group directory (its
     * `group.json` included), so the group name can be used again. msb 0.8 only addresses a
     * group (a member path such as `<group>/snap_<id>` is refused), which is why
     * [MsbCliBackend.removeCheckpoint] derives [groupDir] as the parent of the stored ref. `-f`
     * conflicts with `--all` and is never sent. */
    fun snapshotRemoveGroup(groupDir: String) = listOf("snapshot", "rm", groupDir, "--all")
    /** `msb snapshot rm <groupDir> --checkpoint <member> -f` — removes one member from a group
     * that holds other members too; `-f` skips msb's own removal confirmation. Used by
     * [MsbCliBackend.removeCheckpoint] only when the group has another member, since removing
     * the last member this way would leave the empty group directory behind. */
    fun snapshotRemoveMember(groupDir: String, member: String) =
        listOf("snapshot", "rm", groupDir, "--checkpoint", member, "-f")
    /** `msb snapshot inspect <ref>` — its exit code alone is [MsbCliBackend.hasCheckpoint]'s
     * signal for a bare-name ref (0 = exists, non-zero = doesn't); see docs/checkpoints.md. msb
     * 0.8 takes a GROUP here, so an absolute member path is refused — [MsbCliBackend.hasCheckpoint]
     * answers every absolute ref (the only shape this library mints) with a filesystem check
     * instead. [ref] is passed through unchanged. */
    fun snapshotInspect(ref: String) = listOf("snapshot", "inspect", ref)

    /**
     * `msb snapshot save <groupDir> --checkpoint <member> <dest>` — writes a `.tar.zst` artifact
     * archive of one group member to [dest]; the artifact half of a portable checkpoint archive
     * (see [MsbCliBackend.exportCheckpoint], docs/checkpoints.md's "Moving checkpoints between
     * machines" section). [groupDir] and [member] are the parent and basename of the stored ref.
     * Deliberately never `--with-image`: this library doesn't bundle the OCI image into the
     * archive, so the destination machine pulls it on the restored container's first boot
     * instead. `--with-image` itself works as of msb 0.7.1; bundling the image is a roadmap
     * item, not something this library does yet — see docs/roadmap.md. `--compatibility` is left
     * at msb's default (`current`).
     */
    fun snapshotExport(groupDir: String, member: String, dest: Path) =
        listOf("snapshot", "save", groupDir, "--checkpoint", member, dest.toString())

    /** `msb snapshot load <group> --file <archive> --dest <destDir>` — unpacks [archive] into a
     * member of the new group [group] under [destDir], this library's own checkpoints directory
     * (`CacheDir.resolve()/checkpoints` — the same directory [snapshotCreate]'s own `--dest-dir`
     * writes under; see [MsbCliBackend.importCheckpoint]'s doc), rather than msb's own default
     * `~/.microsandbox/snapshots/` store [destDir]'s absence would target — [destDir] is
     * therefore ALWAYS passed, never omitted. msb 0.8 keeps the member's own `snap_<id>` name and
     * prints `Added: <id>` or `Reused: <id>` on stdout, no longer a path (see
     * [parseSnapshotLoadMemberId]). */
    fun snapshotImport(archive: Path, group: String, destDir: Path) =
        listOf("snapshot", "load", group, "--file", archive.toString(), "--dest", destDir.toString())

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

    /** Whether [json] lists an entry named [name] at all, regardless of its status — or `null`
     * when [json] doesn't parse as the documented array shape, i.e. the listing itself is
     * inconclusive rather than a confirmed absence.
     *
     * [statusOf]/[runningNames] deliberately fold a parse failure into their own "no such
     * entry"/"not Running" default, which is safe for their callers (a malformed or empty `msb
     * ls` result should read as "nothing confirmed yet", not crash a readiness poll). This
     * function exists because [MsbCliBackend.awaitNameReleased] is NOT one of those callers: it
     * treats "the name is gone from `msb ls`" as a green light to reboot into a fresh restore,
     * so a listing it can't confirm the presence OR absence of must never be read as "released"
     * — unlike [statusOf]'s `null`, which conflates "not listed" with "couldn't tell", this keeps
     * those two outcomes distinct so the caller can require the former specifically.
     */
    fun isListed(json: String, name: String): Boolean? = runCatching {
        this.json.decodeFromString<List<LsEntry>>(json)
    }.map { entries -> entries.any { it.name == name } }.getOrNull()
}

// `MsbSnapshotListJson` (parsing `msb snapshot list --format json` to confirm a digest-dir
// basename was genuinely registered) and the `snapshot list` command builder it backed were
// removed: `snapshot load` names the member it wrote on stdout (`Added:`/`Reused:`), which
// [MsbCliBackend.importCheckpoint] turns into the ref directly (see [parseSnapshotLoadMemberId]).
