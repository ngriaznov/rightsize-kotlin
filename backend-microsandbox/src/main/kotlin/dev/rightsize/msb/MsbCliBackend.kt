package dev.rightsize.msb

import dev.rightsize.RunId
import dev.rightsize.core.*
import dev.rightsize.core.reuse.SandboxNameCollisionException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MsbCliBackend(internal val msb: Path) : SandboxBackend {
    override val name = "microsandbox"
    override val supportsNativeNetworks = false   // networks emulated via exec-tunnels
    // Each sandbox is its own microVM (hardware-isolated); checkpoint is backed by msb's disk
    // snapshot primitives (stop -> snapshot create -> rm -> run --snapshot), which restarts the
    // workload, hence checkpointRestartsWorkload = true (see docs/checkpoints.md and
    // BackendCapabilities' doc).
    override val capabilities = BackendCapabilities(
        hardwareIsolated = true, checkpoint = true, checkpointRestartsWorkload = true)

    internal class Handle(override val spec: ContainerSpec) : SandboxHandle {
        override val id = spec.name
        @Volatile var attached: Process? = null
        val resources = CopyOnWriteArrayList<AutoCloseable>()  // tunnels etc.
    }

    private companion object {
        // Permissive DNS-label charset: aliases are interpolated into a `sh -c` /etc/hosts
        // echo, so this exists to reject shell-breaking characters, not to enforce a strict
        // hostname grammar.
        val ALIAS_CHARSET = Regex("[A-Za-z0-9._-]+")
        const val FIRST_RUN_PULL_TIMEOUT_MS = 600_000L   // first run may pull the image
        const val READINESS_POLL_MS = 300L
        const val STOP_TIMEOUT_SEC = 60L
        // Before retrying a boot that hit msb's state-database error — enough for a winning
        // concurrent invocation's migration transaction to commit; the retry's own `msb run`
        // startup dwarfs this either way.
        const val STATE_DB_RETRY_DELAY_MS = 500L
        const val EXEC_TIMEOUT_SEC = 120L
        const val LOGS_TIMEOUT_SEC = 30L
        const val COPY_TIMEOUT_SEC = 120L
        // `msb snapshot create` writes a full disk image (sparse — a tiny alpine snapshot was
        // observed at 3.9 MB on disk despite a "4 GiB" nominal size) — generous but bounded.
        const val SNAPSHOT_TIMEOUT_SEC = 180L
        // `msb snapshot export`/`import` (see exportCheckpoint/importCheckpoint) — same order of
        // magnitude as SNAPSHOT_TIMEOUT_SEC, the artifact they move is the same payload.
        const val SNAPSHOT_EXPORT_TIMEOUT_SEC = 300L
        const val SNAPSHOT_IMPORT_TIMEOUT_SEC = 300L
        const val ATTACHED_PROC_STOP_TIMEOUT_SEC = 10L
        const val READER_JOIN_TIMEOUT_MS = 2000L
        const val TAIL_LINES = 50
    }

    private val startedNames = ConcurrentHashMap.newKeySet<String>()
    private val windowsHost = Platform.current()?.isWindows == true
    init { Runtime.getRuntime().addShutdownHook(Thread { startedNames.forEach { silently(it) } }) }

    /** Test-only view of the own-run-cleanup tracking set — asserts that [start] does (or, for
     * `keepAlive`, does not) register a sandbox without exposing [startedNames] itself. */
    internal fun trackedNames(): Set<String> = startedNames.toSet()

    override fun create(spec: ContainerSpec): SandboxHandle = Handle(spec)

    /**
     * ATTACHED-mode supervision (detached mode never starts the image ENTRYPOINT — confirmed
     * empirically against the real binary). The `msb run` child lives as long as the sandbox;
     * readiness = name Running in `msb ls --format json`. Workload logs come from `msb logs`,
     * not this process's stdout.
     *
     * A first boot attempt that exits before Running with msb's image-cache-corruption
     * signature (see [isImageCacheCorruption]) is healed by removing the affected image's
     * cache entry and retried exactly once — see [spawnAndAwaitRunning]. The failed attempt
     * never reached Running, so [Handle.attached] and [startedNames] (both populated only
     * after [spawnAndAwaitRunning] returns) carry no state from it to double-register, and
     * its child has already been reaped by [bootOnce].
     *
     * A `keepAlive` sandbox (container reuse, see docs/reuse.md) is never added to
     * [startedNames]: that set drives both the constructor shutdown hook and [close]'s
     * own-run sweep, and a reuse sandbox must stay out of every own-run cleanup path this
     * backend runs, not just the reaper's ledger.
     */
    override fun start(handle: SandboxHandle) {
        handle as Handle
        handle.attached = spawnAndAwaitRunning(handle, handle.spec)
        if (!handle.spec.keepAlive) startedNames += handle.id
    }

    /**
     * Boots via [bootOnce], retrying two classified transient failures once each. [spec] is
     * ordinarily [Handle.spec] itself (from [start]), but [createCheckpoint]'s re-boot passes a
     * copy with `checkpointRef` set instead — [handle] still supplies the identity (`id`) both
     * callers boot under. A boot that hit msb's state-database error — usually the
     * startup-migration race (see [isMsbStateDbError]) — is retried after a short delay with no
     * heal step; the race is transient by construction. On a first failure carrying msb's
     * image-cache-corruption signature, heals by removing just the affected image's cache entry
     * (`msb image remove <image>`, result ignored — including "image not found", since the
     * real signal is whether the retried boot succeeds, not whether removal reported
     * success) and retries the boot exactly once. A second identical failure surfaces an
     * error naming the image and the attempted heal instead of retrying further. The heal
     * is scoped to the one image reference — never the whole cache directory, and never any
     * sandbox state (`image remove` touches only the image cache's manifest and layer
     * bookkeeping).
     *
     * Two corruption shapes were found empirically and the same one command heals both:
     * the failing image's manifest was never committed to msb's cache database (a
     * concurrent pull lost the race for a shared base layer before its own manifest write
     * landed) — `image remove` reports "image not found" and the retry succeeds anyway,
     * because by then the concurrent winner has finished materializing the shared layer —
     * or the manifest IS committed but the cache file backing one of its layers is gone,
     * where `image remove` clears the stale entry and the retry re-pulls from scratch.
     */
    private fun spawnAndAwaitRunning(handle: Handle, spec: ContainerSpec): Process {
        val firstOutput = try {
            return bootOnce(handle, spec)
        } catch (race: MsbStateDbException) {
            // Usually the startup-migration race, transient by construction (see
            // [isMsbStateDbError]): the winning msb invocation's migration commits and a
            // retried boot finds the schema in place. No heal step, one retry, second
            // failure propagates — the same one-shot policy as the image-cache heal below.
            Thread.sleep(STATE_DB_RETRY_DELAY_MS)
            try {
                return bootOnce(handle, spec)
            } catch (second: MsbStateDbException) {
                error("msb run for sandbox ${handle.id} hit msb's state-database error twice in a " +
                    "row — the usual cause (concurrent msb invocations racing startup migrations) " +
                    "is transient and one retry covers it, so this looks like real state-database " +
                    "trouble on this host.\n" +
                    "first attempt:\n${race.output}\nafter retry:\n${second.output}")
            }
        } catch (first: ImageCacheCorruptionException) {
            first.output
        }
        val heal = runCatching { invoke(MsbCommands.imageRemove(spec.image), STOP_TIMEOUT_SEC) }
        try {
            return bootOnce(handle, spec)
        } catch (second: ImageCacheCorruptionException) {
            error("msb run for sandbox ${handle.id} hit its image cache error twice in a row for image " +
                "'${spec.image}', even after removing that image's cache entry (${describeHeal(heal)}) " +
                "and retrying — this is likely a deeper cache corruption than this backend's one-shot heal " +
                "covers; try clearing the msb image cache by hand (`msb image prune` or removing the cache " +
                "directory under MSB_HOME).\nfirst attempt:\n$firstOutput\nafter heal + retry:\n${second.output}")
        }
    }

    /**
     * One boot attempt: spawns the attached `msb run` child for [spec] and waits for [handle] to
     * reach Running. On any failure the child is reaped here (for the classified early-exit
     * failures it has already exited; a readiness timeout leaves it alive and it is
     * force-killed), so a failed attempt leaves no live process behind — the caller owns retry
     * policy, never cleanup.
     */
    private fun bootOnce(handle: Handle, spec: ContainerSpec): Process {
        val (proc, tail, drainer) = spawnAttachedRun(spec)   // ATTACHED mode: -d never runs the ENTRYPOINT
        try {
            awaitRunning(handle, proc, tail, drainer)          // readiness = name Running in `msb ls`
        } catch (t: Throwable) {
            if (proc.isAlive) proc.destroyForcibly()
            throw t
        }
        return proc
    }

    /** Renders a heal attempt's outcome for the second-failure message — the heal's own
     * failure (e.g. "image not found") is itself informative to whoever reads the error. */
    private fun describeHeal(result: Result<ExecResult>): String = result.fold(
        onSuccess = { r ->
            if (r.exitCode == 0) "removed"
            else "`msb image remove` exited ${r.exitCode}: ${r.stderr.trim()}"
        },
        onFailure = { e -> "`msb image remove` itself failed to run: ${e.message}" },
    )

    /**
     * Spawns `msb run` for [spec], draining its combined output to a tail kept for diagnostics
     * only — msb's own boot output (registry/pull errors, a crash before the sandbox exists),
     * never the workload's. [logs] never reads from this tail; it always shells out to `msb logs`.
     */
    private fun spawnAttachedRun(spec: ContainerSpec): Triple<Process, ConcurrentLinkedDeque<String>, Thread> {
        val proc = ProcessBuilder(listOf(msb.toString()) + MsbCommands.run(spec))
            .redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }   // no host stdin to forward; avoid EOF-wait stalls
        val tail = ConcurrentLinkedDeque<String>()
        val drainer = drain(proc.inputStream) { tail.addLast(it); if (tail.size > TAIL_LINES) tail.removeFirst() }
        return Triple(proc, tail, drainer)
    }

    /** Polls until [handle] reaches Running, or fails fast if the `msb run` child exits first.
     * An early exit is classified from the child's combined output: the image-cache-corruption
     * signature throws [ImageCacheCorruptionException] (the one failure [spawnAndAwaitRunning]
     * heals and retries), a host-port bind conflict throws [PortBindConflictException], and
     * anything else surfaces the raw output. */
    private fun awaitRunning(handle: Handle, proc: Process, tail: ConcurrentLinkedDeque<String>, drainer: Thread) {
        val deadline = System.currentTimeMillis() + FIRST_RUN_PULL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                // The child's death precedes the drainer thread consuming its final buffered
                // lines — join it (bounded) or the diagnostics below can miss the very stderr
                // that explains the exit.
                runCatching { drainer.join(5_000) }
                val output = tail.joinToString("\n")
                if (isImageCacheCorruption(output)) throw ImageCacheCorruptionException(output)
                if (isMsbStateDbError(output)) throw MsbStateDbException(output)
                if (isPortBindConflict(output)) {
                    throw PortBindConflictException(
                        "msb run for sandbox ${handle.id} could not bind a host port: $output")
                }
                if (isNameCollision(output)) {
                    throw SandboxNameCollisionException(
                        "sandbox named ${handle.id} already exists: $output")
                }
                error("msb run for sandbox ${handle.id} exited (code ${proc.exitValue()}) before reaching " +
                    "Running — check the image entrypoint and `msb run` output below:\n$output")
            }
            if (handle.id in runningSandboxNames()) return
            Thread.sleep(READINESS_POLL_MS)
        }
        error("Sandbox ${handle.id} did not reach Running within ${FIRST_RUN_PULL_TIMEOUT_MS / 1000}s — this can " +
            "mean a slow image pull, a crash-looping entrypoint, or msb itself being unresponsive; last output:\n" +
            tail.joinToString("\n"))
    }

    override fun stop(handle: SandboxHandle) {
        handle as Handle
        handle.resources.forEach { runCatching { it.close() } }; handle.resources.clear()
        invoke(MsbCommands.stop(handle.id), STOP_TIMEOUT_SEC)
        handle.attached?.let { p ->
            if (!p.waitFor(ATTACHED_PROC_STOP_TIMEOUT_SEC, TimeUnit.SECONDS)) p.destroyForcibly()
            handle.attached = null
        }
    }

    override fun remove(handle: SandboxHandle) {
        invoke(MsbCommands.rm(handle.id), STOP_TIMEOUT_SEC)
        startedNames -= handle.id
    }

    /** Reuses [silently] — the same best-effort stop-then-rm this backend has always run for
     * its own leftovers (constructor shutdown hook, [close]), now also driving the reaper's
     * init-time sweep for a *different*, dead process's ledger entries. */
    override fun removeByName(name: String) = silently(name)

    /**
     * Reuse's adopt path (see docs/reuse.md): [name] is Running iff it's in [runningSandboxNames]
     * — the same primitive [awaitRunning] itself polls. The returned handle's `spec` is a
     * minimal reconstruction: an adopting process never knew the original creating spec, and
     * nothing downstream of adopt (exec/logs/stop, which key off `Handle.id == spec.name`
     * here) needs more than the name.
     */
    override fun findRunning(name: String): SandboxHandle? {
        if (name !in runningSandboxNames()) return null
        return Handle(ContainerSpec(name = name, image = "", runId = RunId.value, keepAlive = true))
    }

    /** `msb stop`/`msb rm` by full binary path — the same subcommands [stop]/[remove] invoke,
     * argv-shaped for the reaper watchdog script (a plain OS process spawned before this JVM's
     * first sandbox, with no [MsbCliBackend] instance of its own to call). */
    override val watchdogCommands = WatchdogCommands(
        sandboxStop = listOf(msb.toString(), "stop"),
        sandboxRemove = listOf(msb.toString(), "rm"),
    )

    /**
     * Backs `GenericContainer.checkpoint()` via msb's disk-snapshot primitives (see
     * docs/checkpoints.md): `msb snapshot create` requires the sandbox STOPPED, so this reuses
     * [stop] itself (the same backend-internal stop this SPI method's own caller would otherwise
     * reach — it closes exec tunnels and reaps the supervising attached process, and touches
     * neither `startedNames` nor the reaper ledger, both of which only [remove] and
     * `GenericContainer`'s own bookkeeping touch) rather than duplicating its cleanup.
     *
     * Restoring is NOT `msb start`. Upstream, `msb start` is `Sandbox::start_detached`, and on
     * Windows the detached spawn passes `CREATE_BREAKAWAY_FROM_JOB` — a flag that fails with
     * `ERROR_ACCESS_DENIED` whenever msb runs inside a Windows job object that doesn't grant
     * breakaway rights, which is exactly the case when this JVM is itself a child of a Gradle
     * test process or a CI runner's job. That denial is deterministic, not transient, so no
     * retry ever clears it. Attached `msb run` — this backend's ordinary boot, no creation
     * flags — works everywhere, including a `--snapshot` boot, so the resume step here is
     * instead: remove the stopped sandbox (its disk state now lives entirely in the snapshot)
     * and boot a fresh attached sandbox from that snapshot under the SAME name/ports/env/memory
     * limit, via [spawnAndAwaitRunning] — the exact boot path [start] itself uses, just fed
     * [handle]'s own spec with `checkpointRef` set to the new ref (`MsbCommands.run` then emits
     * `--snapshot <ref>` in place of the image arg). [Handle.attached] is swapped to the new
     * child; `id`/`spec` — the ledger-relevant identity — are untouched.
     *
     * `capabilities.checkpointRestartsWorkload = true` is exactly why: the rebooted workload
     * starts from scratch, so `GenericContainer.checkpoint()` re-applies the container's own
     * wait strategy before returning.
     *
     * Failure handling has two distinct shapes:
     * - `snapshot create` fails: the sandbox is left STOPPED, never removed, and this throws
     *   naming the failed step and the by-hand remedy (`msb start <name>` — safe here, since a
     *   human running it from an interactive shell isn't inside the restrictive job object that
     *   makes it fail in CI). No "restart it for the caller" best-effort — that restart was
     *   exactly the broken call this method no longer makes.
     * - the snapshot succeeds but the re-boot from it fails, after the stopped sandbox has
     *   already been removed: this throws naming the ref and that its state is still recoverable
     *   via `GenericContainer.fromCheckpoint`, since the original sandbox is gone but the
     *   snapshot survives it.
     */
    override fun createCheckpoint(handle: SandboxHandle, ref: String) {
        handle as Handle
        stop(handle)
        val snap = invoke(MsbCommands.snapshotCreate(handle.id, ref), SNAPSHOT_TIMEOUT_SEC)
        if (snap.exitCode != 0) {
            error("msb snapshot create --from ${handle.id} $ref failed (exit ${snap.exitCode}): " +
                "${snap.stderr.trim().ifEmpty { snap.stdout.trim() }} — sandbox ${handle.id} is left " +
                "stopped; resume it by hand with `msb start ${handle.id}`.")
        }
        invoke(MsbCommands.rm(handle.id), STOP_TIMEOUT_SEC)
        try {
            handle.attached = spawnAndAwaitRunning(handle, handle.spec.copy(checkpointRef = ref))
        } catch (e: Exception) {
            error("re-booting sandbox ${handle.id} from checkpoint $ref failed: ${e.message} — the " +
                "sandbox was removed but its state is preserved in checkpoint $ref, restorable via " +
                "GenericContainer.fromCheckpoint.")
        }
    }

    /** Best-effort `msb snapshot rm` — "not found" is success, same contract as [removeByName].
     * Snapshot artifacts are never auto-pruned (see docs/checkpoints.md); this exists so tests
     * can keep shared CI state clean. */
    override fun removeCheckpoint(ref: String) {
        runCatching { invoke(MsbCommands.snapshotRemove(ref), STOP_TIMEOUT_SEC) }
    }

    /**
     * `msb snapshot inspect <ref>` exits 0 when the snapshot exists. A non-zero exit is only
     * "genuinely gone" — and thus only resolves to `false` — when stderr carries msb's own
     * miss framing (see [isCheckpointMiss]); msb has no separate structured error to
     * distinguish that from any other inspect failure (a corrupted state database, a
     * permission failure, a transient hiccup), so per the SPI contract those must never fold
     * into `false` — they throw instead, carrying stderr (falling back to stdout when empty),
     * the same substring-classifier discipline [isImageCacheCorruption]/[isMsbStateDbError]
     * already use. Unlike [removeCheckpoint]'s best-effort `runCatching`, a probe failure here
     * is never swallowed: [Checkpoint.find]'s stale-entry cleanup calls this to decide whether
     * to delete a registry entry, and folding a probe failure into `false` would let it
     * permanently orphan a live checkpoint.
     */
    override fun hasCheckpoint(ref: String): Boolean {
        val r = invoke(MsbCommands.snapshotInspect(ref), STOP_TIMEOUT_SEC)
        if (r.exitCode == 0) return true
        if (isCheckpointMiss(r.stderr)) return false
        error("msb snapshot inspect $ref failed (exit ${r.exitCode}): " +
            "${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
    }

    /**
     * Backs `Checkpoint.exportTo` (see docs/checkpoints.md's "Moving checkpoints between
     * machines" section): `msb snapshot export <ref> <dest>` writes [ref]'s `.tar.zst` artifact
     * to [dest], byte-for-byte what [importCheckpoint]'s `snapshot import` reads back.
     */
    override fun exportCheckpoint(ref: String, dest: Path) {
        val r = invoke(MsbCommands.snapshotExport(ref, dest), SNAPSHOT_EXPORT_TIMEOUT_SEC)
        if (r.exitCode != 0) {
            error("msb snapshot export $ref $dest failed (exit ${r.exitCode}): " +
                "${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
        }
    }

    /**
     * Backs `Checkpoint.importFrom`: `msb snapshot import <src>` unpacks [src] into a
     * DIGEST-derived directory under `~/.microsandbox/snapshots/` — the original snapshot name
     * ([ref]) is NOT preserved, so [ref] itself is unused beyond being part of this method's
     * signature (the SPI contract every backend shares; docker's own [ref] IS its effective ref,
     * unlike this one). Importing an archive whose digest already exists fails with msb's own
     * `error: snapshot already exists: <path>` — for a content-addressed archive that IS success
     * (the artifact is already present), so [isSnapshotAlreadyExists] treats it as one; any OTHER
     * failure surfaces with stderr. Either way, the printed line ends with the artifact path
     * whose basename is the digest-dir name ([parseImportedDigestDir]); [confirmDigestDirPresent]
     * then cross-references `msb snapshot list --format json` to make sure that basename is
     * genuinely registered. That digest-dir name (`sha256-<16hex>`) — NOT the full
     * `sha256:<64hex>` digest — is the EFFECTIVE ref this returns: verified empirically that msb
     * does not resolve the full digest as a snapshot ref at all (`msb snapshot inspect
     * sha256:<full>` fails "snapshot not found", treating it as a literal path), while the
     * digest-dir name resolves for `run --snapshot`, `snapshot rm`, and `snapshot inspect` alike.
     */
    override fun importCheckpoint(src: Path, ref: String): String {
        val r = invoke(MsbCommands.snapshotImport(src), SNAPSHOT_IMPORT_TIMEOUT_SEC)
        val alreadyExists = r.exitCode != 0 && isSnapshotAlreadyExists(r.stderr)
        if (r.exitCode != 0 && !alreadyExists) {
            error("msb snapshot import $src failed (exit ${r.exitCode}): " +
                "${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
        }
        val output = if (alreadyExists) r.stderr else r.stdout
        val digestDir = parseImportedDigestDir(output)
            ?: error("could not parse the imported snapshot's path from msb snapshot import output: ${output.trim()}")
        if (!confirmDigestDirPresent(digestDir)) {
            error("msb snapshot import reported digest-dir '$digestDir', but msb snapshot list --format json " +
                "has no matching entry")
        }
        return digestDir
    }

    /** `msb snapshot list --format json` -> [MsbSnapshotListJson.contains], confirming
     * [digestDir] (the basename [importCheckpoint] parsed from `snapshot import`'s own output) is
     * a genuinely registered snapshot before it's handed back as the effective ref. */
    private fun confirmDigestDirPresent(digestDir: String): Boolean =
        MsbSnapshotListJson.contains(invoke(MsbCommands.snapshotList(), LOGS_TIMEOUT_SEC).stdout, digestDir)

    /**
     * Runtime copy (see docs/copy.md), both directions: `msb copy -q <src> <name>:<dst>` /
     * `msb copy -q <name>:<src> <dst>`, through the same [invoke] plumbing every other one-shot
     * msb command in this backend uses. Unlike [exec]/[logs], a copy failure must never look
     * like a silent success, so the exit code is checked explicitly here and raised as
     * [ContainerCopyException] carrying stderr.
     */
    override fun copyToContainer(handle: SandboxHandle, hostPath: Path, containerPath: String) {
        val r = invoke(MsbCommands.copyTo(handle.id, hostPath, containerPath), COPY_TIMEOUT_SEC)
        if (r.exitCode != 0) throw ContainerCopyException(
            "msb copy into sandbox ${handle.id} failed (exit ${r.exitCode}): ${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
    }

    override fun copyFromContainer(handle: SandboxHandle, containerPath: String, hostPath: Path) {
        val r = invoke(MsbCommands.copyFrom(handle.id, containerPath, hostPath), COPY_TIMEOUT_SEC)
        if (r.exitCode != 0) throw ContainerCopyException(
            "msb copy from sandbox ${handle.id} failed (exit ${r.exitCode}): ${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
    }

    /**
     * True if msb's own diagnostic output for an early-exited `msb run` names a host-port bind
     * conflict. msb has no structured error for this — only the process's combined stdout/stderr
     * text — so this is a best-effort message match, same idea as `GenericContainer`'s fallback
     * classifier, kept local to this backend since the wording is msb-specific.
     */
    private fun isPortBindConflict(output: String): Boolean {
        val m = output.lowercase()
        return "address already in use" in m || "port is already allocated" in m ||
            "bind: address already in use" in m || ("already in use" in m && "port" in m)
    }

    /**
     * True if [output] names a sandbox-name collision — reuse's fresh-create path retries this
     * as an adopt (see [SandboxNameCollisionException]'s doc) instead of an ordinary failure.
     * Unlike [isPortBindConflict]/[isImageCacheCorruption]/[isMsbStateDbError], this phrasing has
     * not been observed against a real `msb run` collision (reuse names are minted from a content
     * hash, so two processes racing the exact same name is a rare, not-yet-reproduced case) —
     * kept deliberately conservative (only msb's own stable "already" vocabulary, matched the
     * same way the other classifiers here are) so it never misclassifies an unrelated failure as
     * a collision. Revisit against real `msb` output if/when this is exercised in practice.
     */
    private fun isNameCollision(output: String): Boolean {
        val m = output.lowercase()
        return "already exists" in m || ("already in use" in m && "name" in m)
    }

    /** Names with status Running from `msb ls --format json`; see [MsbLsJson] for the tolerant parse. */
    internal fun runningSandboxNames(): Set<String> =
        MsbLsJson.runningNames(invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC).stdout)

    override fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult =
        invoke(MsbCommands.exec(handle.id, cmd), timeoutSec = EXEC_TIMEOUT_SEC)

    /**
     * A fresh `msb logs <name> --tail 1000` invocation, same on every platform. This is the
     * workload's own output, as distinct from the attached `msb run` child's pipe (drained in
     * [spawnAttachedRun] into a tail kept only for pre-Running crash diagnostics): on Windows the
     * attached process does not relay guest stdout at all, while `msb logs` does everywhere, so
     * this is the only channel this method can source from. Never throws on a missing/removed
     * sandbox — [invoke] only enforces the timeout, not the exit code, so a failing `msb logs`
     * call yields whatever (possibly empty) stdout it produced rather than an exception.
     */
    override fun logs(handle: SandboxHandle): String =
        invoke(MsbCommands.logs(handle.id), LOGS_TIMEOUT_SEC).stdout

    /**
     * `msb logs -f` is documented to "exit cleanly when the sandbox stops", but on msb 0.6.2 it
     * blocks on read forever instead — so a workload's final unterminated line (no trailing '\n')
     * would otherwise never reach `consumer`. A watchdog thread works around it. Guarantees:
     *
     * 1. Once the sandbox leaves Running (per `runningSandboxNames()`), the watchdog quiesces the
     *    stuck follow process (`destroy()` + join the reader thread) and replays only the lines
     *    the live stream hadn't already delivered, via one final non-follow `msb logs` fetch.
     * 2. That replay fires at most once (`flushed`, guarded by `synchronized(this)`), so a
     *    complete line is never delivered twice — once live, once replayed.
     * 3. An explicit [AutoCloseable.close] never triggers the replay: closing means the caller
     *    asked delivery to stop, so nothing the live stream hadn't already produced is delivered
     *    retroactively (the "no delivery after close" contract).
     *
     * On Windows hosts this routes to [followLogsByPolling] instead: `msb logs -f` there keeps
     * running but never relays new log lines to its stdout pipe while the sandbox is Running
     * (confirmed empirically against the real binary — the same lines are retrievable through
     * non-follow `msb logs` the whole time), so a pipe-reading follow child can never deliver a
     * live line on Windows.
     */
    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit): AutoCloseable {
        if (windowsHost) return followLogsByPolling(handle, consumer)
        val proc = ProcessBuilder(listOf(msb.toString()) + MsbCommands.followLogs(handle.id))
            .redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }
        var delivered = 0
        val t = drain(proc.inputStream) { line -> synchronized(this) { delivered++ }; consumer(line) }

        var flushed = false
        fun flushTailOnce() {
            // Quiesce the reader FIRST: destroy the stuck `msb logs -f` process and wait for its
            // pump thread to finish delivering whatever it already had buffered, so the replay
            // below starts from a `delivered` count that reflects everything the live stream will
            // ever deliver. Doing this before touching `delivered`/`flushed` is what prevents the
            // reader and the replay from both delivering the same trailing complete line.
            proc.destroy()
            t.join(READER_JOIN_TIMEOUT_MS)
            synchronized(this) {
                if (flushed) return
                flushed = true
                val full = invoke(MsbCommands.logs(handle.id), LOGS_TIMEOUT_SEC).stdout
                val fullLines = full.lines().let { if (full.endsWith("\n")) it.dropLast(1) else it }
                fullLines.drop(delivered).forEach(consumer)
            }
        }
        val closeRequested = AtomicBoolean(false)
        val watchdog = Thread {
            while (proc.isAlive && !closeRequested.get()) {
                if (handle.id !in runningSandboxNames()) { flushTailOnce(); break }
                Thread.sleep(READINESS_POLL_MS)
            }
        }.apply { isDaemon = true; start() }
        return AutoCloseable {
            closeRequested.set(true)
            proc.destroy()
            t.join(READER_JOIN_TIMEOUT_MS)
            watchdog.join(READER_JOIN_TIMEOUT_MS)
            // No flushTailOnce() here: close() means the caller asked delivery to stop, so an
            // explicit close must not retroactively deliver anything the live stream hadn't
            // already produced — matching the "close halts delivery" contract. If the
            // sandbox had already stopped before close() was called, the watchdog's own flush
            // already ran (destroying `proc` and joining `t` itself) and flushTailOnce() is a
            // no-op here.
        }
    }

    /**
     * Windows follow-logs path: no follow process at all. A single worker thread polls the
     * non-follow `msb logs` fetch and delivers each fetch's not-yet-delivered lines, tracked by
     * a monotonic per-worker `delivered` index — the same index-based diffing the POSIX
     * watchdog's one-shot replay uses, made continuous. Delivery contract is identical to the
     * POSIX path: in order, each line at most once, nothing after [AutoCloseable.close].
     *
     * The last line of a fetch is held back while the sandbox is Running: the log may have been
     * read mid-write, and delivering a partial line would split one workload line into two
     * deliveries (the next fetch's index-based diff would then skip its complete form). Once the
     * sandbox leaves Running, a final fetch delivers everything outstanding — including a
     * trailing unterminated line, exactly as the POSIX watchdog's replay does.
     *
     * A failed msb invocation is no-signal — never content, never "stopped". msb's
     * per-invocation SQLite migration races concurrent msb processes on Windows, so a transient
     * `msb ls`/`msb logs` failure is expected here; reading it as an empty log would misplace
     * the `delivered` index, and reading it as a gone sandbox would end delivery with lines
     * still undelivered. Both invocations of an iteration must succeed before either is acted
     * on, which also makes the terminal flush a trusted fetch: the not-running branch only ever
     * runs against a successful `msb logs` snapshot.
     */
    private fun followLogsByPolling(handle: SandboxHandle, consumer: (String) -> Unit): AutoCloseable {
        val closeRequested = AtomicBoolean(false)
        val worker = Thread {
            var delivered = 0
            while (!closeRequested.get()) {
                val ls = invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC)
                val fetch = invoke(MsbCommands.logs(handle.id), LOGS_TIMEOUT_SEC)
                if (ls.exitCode != 0 || fetch.exitCode != 0) {
                    Thread.sleep(READINESS_POLL_MS)
                    continue
                }
                val running = handle.id in MsbLsJson.runningNames(ls.stdout)
                val full = fetch.stdout
                val lines = full.lines().let { if (full.endsWith("\n")) it.dropLast(1) else it }
                val deliverable = if (running) maxOf(delivered, lines.size - 1) else lines.size
                for (i in delivered until deliverable) {
                    if (closeRequested.get()) return@Thread
                    consumer(lines[i])
                }
                delivered = maxOf(delivered, deliverable)
                if (!running) break
                Thread.sleep(READINESS_POLL_MS)
            }
        }.apply { isDaemon = true; start() }
        return AutoCloseable {
            closeRequested.set(true)
            worker.join(READER_JOIN_TIMEOUT_MS)
        }
    }

    override fun ensureNetwork(networkId: String) {} // emulated via host gateway; nothing to create
    override fun removeNetwork(networkId: String) {}

    /**
     * Emulate network aliases with per-link exec-stream TCP tunnels (there is no bridge/subnet
     * on macOS 0.6.2; the only data path into a running sandbox is the exec channel). For each link:
     * add `127.0.0.1 <alias>` to /etc/hosts, then spawn an [ExecTunnel] that repeatedly serves the
     * in-guest `nc -l -p <guestPort>` listener and pumps bytes to `127.0.0.1:<targetHostPort>`.
     *
     * Four concerns, each its own step: reject duplicate guest ports, probe for `nc`, install the
     * `/etc/hosts` aliases, then spawn one tunnel per link.
     */
    override fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>) {
        if (links.isEmpty()) return
        handle as Handle
        requireNoDuplicateGuestPorts(links)
        requireAliasesAreValid(links)
        requireNcAvailable(handle)
        installHostsAliases(handle, links)
        links.forEach { handle.resources += ExecTunnel(msb, handle.id, it) }
    }

    private fun requireNoDuplicateGuestPorts(links: List<NetworkLink>) {
        links.groupBy { it.guestPort }.values.firstOrNull { it.size > 1 }?.let { dup ->
            throw UnsupportedByBackendException(
                "two siblings exposing the same guest port ${dup.first().guestPort} on one network", name)
        }
    }

    /**
     * Aliases are interpolated straight into `echo '127.0.0.1 $alias' >> /etc/hosts` inside
     * `sh -c` (see [installHostsAliases]), so a shell-metacharacter alias could break out of the
     * quoting. Validate against a permissive DNS-label charset before shelling out at all — this
     * is a fail-fast guard, not a full hostname grammar check.
     */
    private fun requireAliasesAreValid(links: List<NetworkLink>) {
        links.map { it.alias }.distinct().forEach { alias ->
            if (!ALIAS_CHARSET.matches(alias)) throw UnsupportedByBackendException(
                "network alias '$alias'", name,
                remedy = "use a valid DNS label instead (allowed: letters, digits, '.', '_', '-')")
        }
    }

    private fun requireNcAvailable(handle: Handle) {
        val probe = exec(handle, listOf("sh", "-c", "command -v nc"))
        if (probe.exitCode != 0) throw UnsupportedByBackendException(
            "network links (no nc/busybox in consumer image '${handle.spec.image}')", name,
            remedy = "run this test with RIGHTSIZE_BACKEND=docker instead")
    }

    private fun installHostsAliases(handle: Handle, links: List<NetworkLink>) {
        val hostsEntries = links.map { it.alias }.distinct()
            .joinToString("; ") { "echo '127.0.0.1 $it' >> /etc/hosts" }
        val r = exec(handle, listOf("sh", "-c", hostsEntries))
        check(r.exitCode == 0) { "failed to install /etc/hosts aliases in ${handle.id}: ${r.stderr}" }
    }

    override fun close() { startedNames.toList().forEach { silently(it) } }

    /** Best-effort stop+rm, retried once on msb's transient state-database error — the same
     * classifier ([isMsbStateDbError]) and combined-stdout+stderr check the reaper watchdog's
     * shell/PowerShell scripts run on their own stop+rm output (see docs/reaping.md). Without
     * this, a [removeByName] call racing another process's msb state-db migration (the
     * init-time sweep's own concurrent-run scenario) would silently drop the removal on its
     * one and only attempt, leaking the sandbox untracked once the caller discards its ledger
     * entry regardless of outcome. */
    private fun silently(name: String) {
        val stopOut = runCatching { invoke(MsbCommands.stop(name), STOP_TIMEOUT_SEC) }
        val rmOut = runCatching { invoke(MsbCommands.rm(name), STOP_TIMEOUT_SEC) }
        val combined = (stopOut.getOrNull()?.combinedOutput() ?: "") + (rmOut.getOrNull()?.combinedOutput() ?: "")
        if (isMsbStateDbError(combined)) runCatching { invoke(MsbCommands.rm(name), STOP_TIMEOUT_SEC) }
    }

    private fun ExecResult.combinedOutput() = stdout + stderr

    private fun invoke(args: List<String>, timeoutSec: Long): ExecResult {
        val proc = ProcessBuilder(listOf(msb.toString()) + args).start()
        // `msb exec` forwards host stdin to the guest and blocks until stdin hits EOF;
        // a ProcessBuilder pipe stays open forever, hanging the call. Signal EOF up front.
        runCatching { proc.outputStream.close() }
        val stdout = StringBuilder(); val stderr = StringBuilder()
        val tOut = drain(proc.inputStream) { stdout.appendLine(it) }
        val tErr = drain(proc.errorStream) { stderr.appendLine(it) }
        check(proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            "msb ${args.joinToString(" ")} timed out after ${timeoutSec}s and was force-killed — " +
                "the msb daemon may be overloaded or unresponsive; retry, or check `msb` directly"
        }
        // The process has already exited, so its pipes will EOF and these drain threads will
        // finish promptly — join without a bound rather than a fixed 2s cap, which could
        // truncate the tail of a large-output command that hadn't finished draining yet.
        tOut.join(); tErr.join()
        return ExecResult(proc.exitValue(), stdout.toString(), stderr.toString())
    }

    /** Drains [stream] on a daemon thread, one line per [onLine]; returns the thread for joining. */
    private fun drain(stream: InputStream, onLine: (String) -> Unit): Thread =
        Thread { stream.bufferedReader().forEachLine(onLine) }.apply { isDaemon = true; start() }
}

/** The one boot failure [MsbCliBackend] heals and retries — carries the `msb run` child's
 * combined output for the second-failure diagnostic. Internal to the boot path: never
 * escapes `start()`, which converts a repeat failure into a plain error naming the heal. */
internal class ImageCacheCorruptionException(val output: String) :
    RuntimeException("msb image cache corruption:\n$output")

/**
 * True if [output] (an early-exited `msb run` child's combined stdout/stderr) names msb's
 * image cache error: a manifest/layer index entry pointing at a cache file that isn't on
 * disk. Observed verbatim against a real msb 0.6.3 binary:
 *
 * ```
 * error: image error: cache error at /path/to/.microsandbox/cache/layers/sha256_<64hex>.tar.gz: No such file or directory (os error 2)
 * ```
 *
 * Root cause, reproduced by racing concurrent `msb run`/`msb pull` of images that share a
 * base layer against one fresh cache: two pulls converting the same shared blob race, and
 * the loser's read of the shared `.tar.gz` finds it already deleted by the winner's
 * post-conversion cleanup. Confirmed order-independent: across ten trials of three
 * concurrent pulls, seven reproduced the error, naming each image as the victim at least
 * once. Never reproduces sequentially.
 *
 * Deliberately a substring match on the stable parts of msb's wording ("cache error at",
 * "No such file") rather than the full sentence — the path and digest vary per host/image,
 * and msb has no structured/typed error for this.
 */
internal fun isImageCacheCorruption(output: String): Boolean =
    "cache error at" in output && "No such file" in output

/** The other boot failure [MsbCliBackend] retries — the spawned `msb run` child hit a
 * failure of msb's own state database, usually the startup-migration race (see
 * [isMsbStateDbError]). No heal step: the race is transient by construction, so a plainly
 * retried boot finds the schema already migrated. Internal to the boot path, like its
 * sibling above. */
internal class MsbStateDbException(val output: String) :
    RuntimeException("msb state-database error:\n$output")

/**
 * True if [output] (an early-exited `msb run` child's combined stdout/stderr) names a
 * failure of msb's own shared SQLite state database. Every msb invocation runs schema
 * migrations against it on startup, and two concurrent invocations can race them — the
 * loser dies before doing any work, with whatever wording matches the migration statement
 * it lost on. Observed verbatim against the real msb 0.6.3 Windows binary, one race,
 * three shapes:
 *
 * ```
 * error: database error: Execution Error: error returned from database: (code: 1) index idx_manifest_layers_unique already exists
 * error: database error: Execution Error: error returned from database: (code: 1) duplicate column name: kind
 * ```
 *
 * plus `UNIQUE constraint failed: seaql_migrations.version`. Chasing individual wordings
 * is a losing game — the stable part is msb's own `error: database error:` framing, which
 * is always msb's state database and never the workload's output.
 *
 * A boot is never inherently alone even under fully serialized tests: the attached
 * `msb run` child races this backend's own `msb ls` readiness polling (and, on Windows, an
 * active log poller). The migration race is transient by construction; for a
 * state-database failure that is NOT the race, the one-shot retry costs a moment and then
 * propagates the failure with both attempts' output.
 */
internal fun isMsbStateDbError(output: String): Boolean = "error: database error:" in output

/**
 * True if [stderr] (from `msb snapshot inspect <ref>`) names msb's own "no such snapshot"
 * miss, as opposed to some other inspect failure. Observed verbatim against the real msb
 * 0.6.6 binary:
 *
 * ```
 * error: snapshot not found: rz-ckpt-0123456789ab at /path/to/.microsandbox/snapshots/rz-ckpt-0123456789ab
 * ```
 *
 * Deliberately a substring match on the stable `error: snapshot not found:` framing rather
 * than the full line — the trailing path is host-specific and msb has no structured/typed
 * error for this, the same substring-classifier discipline [isImageCacheCorruption] and
 * [isMsbStateDbError] already use. Any non-zero exit whose stderr does NOT match this is a
 * genuine probe failure, not a miss, and [MsbCliBackend.hasCheckpoint] throws instead of
 * returning `false` for it.
 */
internal fun isCheckpointMiss(stderr: String): Boolean = "error: snapshot not found:" in stderr

/**
 * True if [stderr] (from `msb snapshot import`) names msb's own "already exists" outcome, as
 * opposed to some other import failure. Observed verbatim against the real msb 0.6.6 binary:
 *
 * ```
 * error: snapshot already exists: /path/to/.microsandbox/snapshots/sha256-b9c0448ee9d54e33
 * ```
 *
 * For a content-addressed archive this IS success — the artifact is already present on this
 * host, e.g. a re-run of the same import — so [MsbCliBackend.importCheckpoint] treats it as one
 * rather than as a genuine failure; any exit-nonzero output that does NOT match this wording is
 * a real import failure and surfaces with stderr instead.
 */
internal fun isSnapshotAlreadyExists(stderr: String): Boolean = "snapshot already exists:" in stderr

/**
 * Parses the digest-dir basename (e.g. `sha256-b9c0448ee9d54e33`) from `msb snapshot import`'s
 * own output. Verified against msb 0.6.6: on both an ordinary success and the
 * already-exists-as-success outcome ([isSnapshotAlreadyExists]), the last non-blank line ends
 * with the artifact's full path under `~/.microsandbox/snapshots/<digest-dir>` — this takes that
 * line's final whitespace-separated token as the path and returns its filename. `null` if
 * [output] has no non-blank line at all, or that line's last token is empty. `internal` for
 * direct unit-test access against captured sample output, without a real msb binary.
 */
internal fun parseImportedDigestDir(output: String): String? {
    val lastLine = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
    val token = lastLine.substringAfterLast(' ').trim()
    if (token.isEmpty()) return null
    return Path.of(token).fileName?.toString()
}
