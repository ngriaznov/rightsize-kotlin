package dev.rightsize.msb

import dev.rightsize.RunId
import dev.rightsize.core.*
import dev.rightsize.core.reuse.SandboxNameCollisionException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [windowsHost] is the host-OS switch for the two paths that behave differently there
 * ([followLogs]'s polling variant and [exportCheckpoint]'s salvage of a save that failed its
 * fsync). It exists so tests can drive those branches from a POSIX machine — a real Windows
 * host is CI-only, so without this seam they would ship with no unit coverage at all.
 *
 * [restoreReadinessBudgetMs] is a second, narrower seam: the same wall-clock budget
 * [awaitRestoreRunning] bounds a checkpoint restore's post-activation `msb ls` poll by,
 * defaulted to the real [FIRST_RUN_PULL_TIMEOUT_MS] so production behavior is unchanged.
 * Without it, red-proofing "the sandbox never reaches Running" would mean a unit test
 * actually blocking for that real ten-minute budget; tests reach it via [forHost] to shrink
 * it instead. It never touches the ordinary attached-run path ([awaitRunning] reads
 * [FIRST_RUN_PULL_TIMEOUT_MS] directly, unseamed, exactly as before) or restore's own
 * process-exit wait, which shares this same budget rather than getting a third constant.
 *
 * The seam is deliberately kept off the public surface entirely. The two-argument
 * constructor is `private` — `internal` would still emit a public constructor into the
 * bytecode and so remain callable from Java — and tests reach it through [forHost], whose
 * name Kotlin mangles for the same reason. `MsbCliBackend(Path)` stays the only public
 * entry point, exactly as before.
 */
class MsbCliBackend private constructor(
    internal val msb: Path,
    private val windowsHost: Boolean,
    private val restoreReadinessBudgetMs: Long = FIRST_RUN_PULL_TIMEOUT_MS,
) : SandboxBackend {
    constructor(msb: Path) : this(msb, Platform.current()?.isWindows == true)

    override val name = "microsandbox"
    override val supportsNativeNetworks = false   // networks emulated via exec-tunnels
    // Each sandbox is its own microVM (hardware-isolated); checkpoint is backed by msb's disk
    // snapshot primitives (stop -> snapshot create -> rm -> msb restore), which restarts the
    // workload, hence checkpointRestartsWorkload = true (see docs/checkpoints.md and
    // BackendCapabilities' doc). checkpointRestoreOverridable = false: msb restore of a
    // disk-scope snapshot has no -e/--env flag and no command override at all (see
    // MsbCommands.restore's doc).
    override val capabilities = BackendCapabilities(
        hardwareIsolated = true, checkpoint = true, checkpointRestartsWorkload = true,
        checkpointRestoreOverridable = false)

    internal class Handle(override val spec: ContainerSpec) : SandboxHandle {
        override val id = spec.name
        @Volatile var attached: Process? = null
        val resources = CopyOnWriteArrayList<AutoCloseable>()  // tunnels etc.
    }

    // The companion holds ONLY the test seam. An internal companion object is a public
    // class in the bytecode, so anything else placed in it — even members that were
    // package-private while the companion itself was — would become Java-reachable; the
    // tuning constants therefore live as file-private top-level declarations below the
    // class, where no bytecode-public accessor is ever emitted for them.
    internal companion object {
        /**
         * Builds a backend pinned to [windowsHost] rather than to the real platform, and
         * optionally to a shrunk [restoreReadinessBudgetMs] — the two test-only seams described
         * on this class. `internal`, so Kotlin mangles its name in the bytecode and it never
         * becomes a Java-callable entry point either.
         */
        internal fun forHost(
            msb: Path,
            windowsHost: Boolean,
            restoreReadinessBudgetMs: Long = FIRST_RUN_PULL_TIMEOUT_MS,
        ) = MsbCliBackend(msb, windowsHost, restoreReadinessBudgetMs)
    }

    private val startedNames = ConcurrentHashMap.newKeySet<String>()
    init { Runtime.getRuntime().addShutdownHook(Thread { startedNames.forEach { silently(it) } }) }

    /** Test-only view of the own-run-cleanup tracking set — asserts that [start] does (or, for
     * `keepAlive`, does not) register a sandbox without exposing [startedNames] itself. */
    internal fun trackedNames(): Set<String> = startedNames.toSet()

    override fun create(spec: ContainerSpec): SandboxHandle = Handle(spec)

    /**
     * ATTACHED-mode supervision when `handle.spec.checkpointRef` is unset (detached `-d` mode
     * never starts the image ENTRYPOINT — confirmed empirically against the real binary). The
     * `msb run` child lives as long as the sandbox; readiness = name Running in `msb ls --format
     * json`. Workload logs come from `msb logs`, not this process's stdout.
     *
     * A workload that completes before this backend's poll ever samples Running (e.g. a short
     * build or script — see [isCleanFastExit]) is not a failed boot: the child still exits 0,
     * this backend just never observed the in-between Running state. That sandbox is started
     * and already finished — [Handle.attached] is the child's already-exited [Process], and
     * `stop`/state-reporting surfaces treat it as not running, same as any other Stopped
     * sandbox, because it genuinely is one.
     *
     * When `handle.spec.checkpointRef` IS set (a `GenericContainer.fromCheckpoint(cp).start()`
     * call), this instead boots via a `msb restore` invocation, which is childless-by-design —
     * see [awaitRestoreRunning]'s doc. [Handle.attached] ends up `null` for that boot, same as
     * for any other sandbox this backend never launched a supervising child for (e.g.
     * [findRunning]'s adopted handles); [stop] already treats a `null` [Handle.attached] as
     * nothing to reap.
     *
     * A first boot attempt that exits before Running with msb's image-cache-corruption
     * signature (see [isImageCacheCorruption]) is healed by removing the affected image's
     * cache entry and retried exactly once — see [spawnAndAwaitRunning]. The failed attempt
     * never reached Running, so [Handle.attached] and [startedNames] (both populated only
     * after [spawnAndAwaitRunning] returns) carry no state from it to double-register, and
     * its child (when the boot had one) has already been reaped by [bootOnce].
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
     * Boots via [bootOnce], retrying two classified transient failures once each — the same
     * retry/heal wrapper around ordinary `run` boots AND `restore` boots alike (requirement:
     * boot-transient classification applies to a restore's output the same way it does for
     * run; only [bootOnce]'s internal supervision shape differs by boot kind). [spec] is
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
     *
     * Returns the supervising child [Process] for an ordinary `run` boot, or `null` for a
     * `restore` boot (`spec.checkpointRef != null`) — that process has already exited by the
     * time this returns (see [awaitRestoreRunning]), so there is nothing left to hand back;
     * callers assign the result straight to [Handle.attached], which is `null`-safe everywhere
     * it's read ([stop]).
     */
    private fun spawnAndAwaitRunning(handle: Handle, spec: ContainerSpec): Process? {
        val firstOutput = try {
            return bootOnce(handle, spec)
        } catch (locked: MsbInstallLockException) {
            // msb refuses `run` outright while its internal install lock is held (see
            // [isMsbInstallLockActive]). The message names a deadline ~30 minutes out, but
            // both captured occurrences cleared within the same test run — boots seconds
            // later succeeded — so this polls briefly rather than trusting the deadline.
            // The budget expiring surfaces the last refusal as-is: at that point the lock
            // is genuinely long-lived and waiting here would only hide it.
            val deadline = System.nanoTime() + INSTALL_LOCK_RETRY_BUDGET_MS * 1_000_000
            var last = locked
            while (System.nanoTime() < deadline) {
                Thread.sleep(INSTALL_LOCK_RETRY_DELAY_MS)
                try {
                    return bootOnce(handle, spec)
                } catch (again: MsbInstallLockException) {
                    last = again
                }
            }
            error("msb run for sandbox ${handle.id} was refused for ${INSTALL_LOCK_RETRY_BUDGET_MS / 1000}s " +
                "by msb's install-operation lock — both observed occurrences cleared within seconds, so a " +
                "lock held this long looks like a genuinely stuck msb install on this host.\n${last.output}")
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
     * One boot attempt, dispatched by `spec.checkpointRef`:
     * - unset: spawns the attached `msb run` child and waits for [handle] to reach Running via
     *   [awaitRunning] — unchanged from before restore got its own supervision. Returns the
     *   still-live child.
     * - set: spawns `msb restore ...` and waits for IT (a short-lived activation launcher, not a
     *   supervising child — see [awaitRestoreRunning]'s doc) to exit, then polls [handle] to
     *   Running the same way. Returns `null`: by the time [awaitRestoreRunning] returns
     *   successfully the process has already exited, so there is nothing live to hand back.
     *
     * On any failure the process is reaped here (for the classified early-exit/nonzero-exit
     * failures it has already exited; a readiness timeout leaves it alive and it is
     * force-killed), so a failed attempt leaves no live process behind — the caller owns retry
     * policy, never cleanup.
     */
    private fun bootOnce(handle: Handle, spec: ContainerSpec): Process? {
        val (proc, tail, drainer) = spawnAttachedRun(spec)   // ATTACHED run, or a detached restore launch
        try {
            return if (spec.checkpointRef != null) {
                awaitRestoreRunning(handle, proc, tail, drainer)   // readiness = name Running in `msb ls`
                null
            } else {
                awaitRunning(handle, proc, tail, drainer)          // readiness = name Running in `msb ls`
                proc
            }
        } catch (t: Throwable) {
            if (proc.isAlive) proc.destroyForcibly()
            throw t
        }
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
     * Spawns `msb run` (or, for a checkpoint restore, `msb restore ...` — see
     * [MsbCommands.restore]'s doc) for [spec], draining its combined output to a tail kept for
     * diagnostics only — msb's own boot output (registry/pull errors, a crash before the sandbox
     * exists), never the workload's. [logs] never reads from this tail; it always shells out to
     * `msb logs`. What the caller does with the returned [Process] differs by boot kind: [awaitRunning]
     * treats it as a supervising child that lives as long as the sandbox; [awaitRestoreRunning]
     * treats it as a short-lived launcher and waits for it to exit before polling separately.
     */
    private fun spawnAttachedRun(spec: ContainerSpec): Triple<Process, ConcurrentLinkedDeque<String>, Thread> {
        val argv = if (spec.checkpointRef != null) MsbCommands.restore(spec) else MsbCommands.run(spec)
        val proc = ProcessBuilder(listOf(msb.toString()) + argv)
            .redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }   // no host stdin to forward; avoid EOF-wait stalls
        val tail = ConcurrentLinkedDeque<String>()
        val drainer = drain(proc.inputStream) { tail.addLast(it); if (tail.size > TAIL_LINES) tail.removeFirst() }
        return Triple(proc, tail, drainer)
    }

    /** Polls until [handle] reaches Running, or fails fast if the `msb run` child exits first.
     * An early exit is classified from the child's combined output: the image-cache-corruption
     * signature throws [ImageCacheCorruptionException] (the one failure [spawnAndAwaitRunning]
     * heals and retries), a host-port bind conflict throws [PortBindConflictException], a clean
     * exit 0 that passes [isCleanFastExit]'s post-mortem check returns normally (see that
     * method's doc), and anything else surfaces the raw output. */
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
                if (isMsbInstallLockActive(output)) throw MsbInstallLockException(output)
                if (isPortBindConflict(output)) {
                    throw PortBindConflictException(
                        "msb run for sandbox ${handle.id} could not bind a host port: $output")
                }
                if (isNameCollision(output)) {
                    throw SandboxNameCollisionException(
                        "sandbox named ${handle.id} already exists: $output")
                }
                if (proc.exitValue() == 0 && isCleanFastExit(handle)) return
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

    /**
     * The `restore` counterpart of [awaitRunning] — necessary because `msb restore` is NOT an
     * attached supervising child the way `msb run` is. Per `restore.rs`'s own doc ("Restore a
     * snapshot into a new **detached** sandbox") and confirmed empirically against the real
     * msb 0.7.1 binary: [proc] activates the sandbox and exits — typically within seconds, with
     * little or no stdout — once activation succeeds, while the sandbox keeps booting in the
     * background toward Running on its own. A nonzero exit unambiguously means the restore
     * itself failed (msb's own contract for this command, unlike `run`'s exit-before-Running
     * ambiguity — there is no `restore`-side equivalent of [isCleanFastExit] to rescue a clean
     * exit here, because a clean exit is always just "activation succeeded", never "the
     * workload already finished").
     *
     * Two phases share one deadline ([restoreReadinessBudgetMs] — the same budget [awaitRunning]
     * itself bounds its own wait by, made injectable so tests don't block on the real ten-minute
     * value; see the class doc):
     *
     * 1. Wait for [proc] to exit. Non-exit within the deadline is itself a boot failure (the
     *    caller, [bootOnce], force-kills it on any throw from here, same as an attached-run
     *    readiness timeout). A nonzero exit is classified from the combined output through the
     *    exact same boot-transient signatures [awaitRunning] uses — image-cache corruption,
     *    msb's state-database race, its install lock, a host-port bind conflict, a name
     *    collision — so [spawnAndAwaitRunning]'s retry/heal wrapper covers a restore boot
     *    exactly like it always has an ordinary one (boot-transient classification applies to
     *    the restore process's output the same way it does for `run`; only this method's
     *    supervision shape differs). Anything else surfaces the raw output.
     * 2. Once [proc] has exited 0, [handle] has no supervising child left AT ALL — activation
     *    succeeded, but the sandbox may still be `Starting` in the background. Poll `msb ls` on
     *    the remaining budget, same interval as [awaitRunning]'s own poll ([READINESS_POLL_MS]):
     *    `Running` returns normally. The sandbox reads `Stopped`, or drops out of `msb ls`
     *    entirely after having been seen at least once — [seen] is what tells a boot that
     *    crashed immediately after activation apart from one that just hasn't been picked up by
     *    `msb ls` yet — is a boot failure, surfaced with `msb logs <name> --source system`
     *    diagnostics (the same system-log channel [isCleanFastExit] reads) rather than the
     *    generic message, since a genuinely crashed restore boot has exactly the same
     *    diagnostic shape as a crashed attached one.
     *
     * Never returns a [Process]: unlike [awaitRunning], by the time this returns successfully
     * [proc] has already exited, so [bootOnce] hands [Handle.attached] `null` for this boot
     * kind rather than a value from here.
     */
    private fun awaitRestoreRunning(
        handle: Handle, proc: Process, tail: ConcurrentLinkedDeque<String>, drainer: Thread,
    ) {
        val deadline = System.currentTimeMillis() + restoreReadinessBudgetMs
        if (!proc.waitFor(maxOf(deadline - System.currentTimeMillis(), 0L), TimeUnit.MILLISECONDS)) {
            error("msb restore for sandbox ${handle.id} did not exit within ${restoreReadinessBudgetMs / 1000}s " +
                "— msb itself may be unresponsive; last output:\n${tail.joinToString("\n")}")
        }
        // Same reasoning as awaitRunning's own join: the drainer thread may not have consumed
        // the process's final buffered output yet.
        runCatching { drainer.join(5_000) }
        val output = tail.joinToString("\n")
        val exitCode = proc.exitValue()
        if (exitCode != 0) {
            if (isImageCacheCorruption(output)) throw ImageCacheCorruptionException(output)
            if (isMsbStateDbError(output)) throw MsbStateDbException(output)
            if (isMsbInstallLockActive(output)) throw MsbInstallLockException(output)
            if (isPortBindConflict(output)) {
                throw PortBindConflictException(
                    "msb restore for sandbox ${handle.id} could not bind a host port: $output")
            }
            if (isNameCollision(output)) {
                throw SandboxNameCollisionException(
                    "sandbox named ${handle.id} already exists: $output")
            }
            error("msb restore for sandbox ${handle.id} failed (exit $exitCode): $output")
        }
        var seen = false
        while (System.currentTimeMillis() < deadline) {
            val status = MsbLsJson.statusOf(invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC).stdout, handle.id)
            if (status == "Running") return
            if (status == "Stopped" || (seen && status == null)) {
                val systemLog = invoke(MsbCommands.logsSystem(handle.id), LOGS_TIMEOUT_SEC).stdout
                error("msb restore for sandbox ${handle.id} exited 0 (restore activation succeeded) but the " +
                    "sandbox ${if (status == "Stopped") "stopped" else "disappeared from `msb ls`"} before " +
                    "reaching Running — system log:\n$systemLog")
            }
            if (status != null) seen = true
            Thread.sleep(READINESS_POLL_MS)
        }
        error("Sandbox ${handle.id} did not reach Running within ${restoreReadinessBudgetMs / 1000}s after " +
            "msb restore exited 0 — this can mean a slow disk-snapshot boot or msb itself being unresponsive; " +
            "system log:\n${invoke(MsbCommands.logsSystem(handle.id), LOGS_TIMEOUT_SEC).stdout}")
    }

    /**
     * Post-mortem classification for an attached `msb run` child that exited 0 before this
     * backend's poll loop ever observed [handle] Running. msb 0.6.16 reworked the sandbox
     * lifecycle so Running is no longer guaranteed to be observable for a workload that
     * finishes quickly — the old surfacing was itself a race a fast host happened to win, and a
     * container whose command completes fast (a short build or script) must not fail [start]
     * just because this backend's poll lost that race.
     *
     * Distinguishes that from a genuinely dead boot — msb 0.6.10 through 0.6.13's Windows
     * agentless-death failures also exited the attached child with code 0, but the guest agent
     * never came up — by requiring BOTH signals a clean completion leaves behind: [handle]'s
     * status is `Stopped` in `msb ls --format json`, AND the system log carries the
     * boot-completion marker the guest agent writes only once it has actually come up (`msb
     * logs <name> --source system`; see [hasSandboxStartedMarker]). Either signal missing — the
     * state, most cheaply checked, is looked at first — leaves this a failure, unchanged from
     * before this classification existed; this method only ever turns a failure into a success,
     * never the reverse.
     */
    private fun isCleanFastExit(handle: Handle): Boolean {
        val status = MsbLsJson.statusOf(invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC).stdout, handle.id)
        if (status != "Stopped") return false
        val systemLog = invoke(MsbCommands.logsSystem(handle.id), LOGS_TIMEOUT_SEC).stdout
        return hasSandboxStartedMarker(systemLog)
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
     * retry ever clears it. `msb restore` (unlike `msb start`) passes no creation flags and
     * works everywhere, so the resume step here is instead: remove the stopped sandbox (its
     * disk state now lives entirely in the snapshot) and boot a fresh sandbox from that
     * snapshot under the SAME name/ports/memory limit (NOT env — see [MsbCommands.restore]'s
     * doc), via [spawnAndAwaitRunning] — the exact boot path [start] itself uses, just fed
     * [handle]'s own spec with `checkpointRef` set to the EFFECTIVE ref (`spawnAttachedRun`
     * then routes to `MsbCommands.restore`, emitting `restore <ref> --name <name>` in place of
     * an ordinary `run <image>` boot — never `--disk-only`, which msb 0.7.1 rejects for a
     * disk-scope snapshot; see that method's doc). Unlike [start]'s ordinary boot, a restore
     * boot is childless by design (see [awaitRestoreRunning]'s doc): [Handle.attached] ends up
     * `null` here, not swapped to a new child — there is no supervising process left to hold
     * once [spawnAndAwaitRunning] returns successfully for a restore, and [stop] already treats
     * a `null` [Handle.attached] as nothing to reap. `id`/`spec` — the ledger-relevant identity
     * — are untouched.
     *
     * `capabilities.checkpointRestartsWorkload = true` is exactly why: the rebooted workload
     * starts from scratch, so `GenericContainer.checkpoint()` re-applies the container's own
     * wait strategy before returning.
     *
     * Failure handling has three distinct shapes:
     * - `snapshot create` fails: the sandbox is left STOPPED, never removed, and this throws
     *   naming the failed step and the by-hand remedy (`msb start <name>` — safe here, since a
     *   human running it from an interactive shell isn't inside the restrictive job object that
     *   makes it fail in CI). No "restart it for the caller" best-effort — that restart was
     *   exactly the broken call this method no longer makes.
     * - `snapshot create` succeeds but its stdout can't be parsed for the resulting artifact
     *   path (see below): same as above — the sandbox is left stopped, `rm`/reboot never run,
     *   and this throws quoting the unparsed output, since minting a ref this backend could
     *   never restore/remove/inspect again would be worse than surfacing the parse failure.
     * - the snapshot succeeds and parses but the re-boot from it fails, after the stopped
     *   sandbox has already been removed: this throws naming the ref and that its state is still
     *   recoverable via `GenericContainer.fromCheckpoint`, since the original sandbox is gone but
     *   the snapshot survives it.
     *
     * [ref] is a HINT, not necessarily the returned effective ref: a bare snapshot name, or an
     * absolute path (see `GenericContainer.mintCheckpointRef` — the msb backend mints hint paths
     * under the checkpoint cache dir). A path hint's parent directory is created up front and
     * passed to `snapshot create` as `--dest-dir`, with the path's basename as the snapshot
     * NAME — but as of msb 0.7.1, `--from-sandbox` writes a DISK-scope snapshot's artifact at
     * `<dest-dir>/<source-sandbox>/snap_<32-hex digest>`, a path msb decides on its own, NOT at
     * `<dest-dir>/<name>` (verified empirically against the real 0.7.1 binary) — [name] only
     * ever shows up in msb's own index and in `snapshot inspect` output. This method therefore
     * CAPTURES the real artifact path from `snapshot create`'s own stdout — msb prints the
     * snapshot ID, then the absolute artifact path as its LAST stdout line on success — via
     * [parseSnapshotCreateArtifactPath], and that captured path, not [ref], is what flows into
     * `restore`'s positional argument, what this method returns, and what a caller must use for
     * every later `hasCheckpoint`/`removeCheckpoint` call against this checkpoint. Parsing is
     * defensive: the last non-blank line must parse as an absolute path, or this throws quoting
     * the raw output (see the failure list above).
     *
     * A [ContainerSpec.tmpfsRootMb] container is refused before [stop] even runs — its root disk
     * lives in guest memory and there is nothing durable to snapshot.
     */
    override fun createCheckpoint(handle: SandboxHandle, ref: String): String {
        handle as Handle
        if (handle.spec.tmpfsRootMb != null) throw TmpfsRootCheckpointException()
        stop(handle)
        val refPath = Path.of(ref)
        val destDir = if (refPath.isAbsolute) refPath.parent else null
        val snapshotName = if (refPath.isAbsolute) refPath.fileName.toString() else ref
        destDir?.let { Files.createDirectories(it) }
        val snap = invoke(MsbCommands.snapshotCreate(handle.id, snapshotName, destDir), SNAPSHOT_TIMEOUT_SEC)
        if (snap.exitCode != 0) {
            error("msb snapshot create --from-sandbox ${handle.id} $snapshotName failed (exit ${snap.exitCode}): " +
                "${snap.stderr.trim().ifEmpty { snap.stdout.trim() }} — sandbox ${handle.id} is left " +
                "stopped; resume it by hand with `msb start ${handle.id}`.")
        }
        val effectiveRef = parseSnapshotCreateArtifactPath(snap.stdout)
            ?: error("msb snapshot create --from-sandbox ${handle.id} $snapshotName succeeded but its " +
                "output did not end with an absolute artifact path: '${snap.stdout.trim()}' — sandbox " +
                "${handle.id} is left stopped; resume it by hand with `msb start ${handle.id}`.")
        invoke(MsbCommands.rm(handle.id), STOP_TIMEOUT_SEC)
        try {
            handle.attached = spawnAndAwaitRunning(handle, handle.spec.copy(checkpointRef = effectiveRef))
        } catch (e: Exception) {
            error("re-booting sandbox ${handle.id} from checkpoint $effectiveRef failed: ${e.message} — the " +
                "sandbox was removed but its state is preserved in checkpoint $effectiveRef, restorable via " +
                "GenericContainer.fromCheckpoint.")
        }
        return effectiveRef
    }

    /**
     * Best-effort `msb snapshot rm <ref> -f` — "not found" is success, same contract as
     * [removeByName]. Snapshot artifacts are never auto-pruned (see docs/checkpoints.md); this
     * exists so tests can keep shared CI state clean.
     *
     * [ref] flows through VERBATIM — never reduced to a basename. msb 0.7.1 resolves `snapshot
     * rm` reliably only against the snapshot's own artifact path; a bare name does not resolve
     * (verified empirically against the real binary — see [MsbCommands.snapshotRemove]'s doc),
     * so passing a basename here (this method's pre-0.7.1 behavior) would silently no-op against
     * the real snapshot store. This also covers msb's own refusal to remove the NEWEST (head)
     * snapshot of a source sandbox while an older sibling still exists — this method never
     * inspects the exit code (best-effort, same as every other call here), so that refusal is
     * neither retried nor worked around with automatic head rotation; it simply leaves the
     * snapshot in place, same as any other removal failure. See docs/checkpoints.md's Cleanup
     * section.
     *
     * If [refPath] is still there afterward as a leftover directory (the index lost track of it
     * independently, or msb's own removal didn't take the directory with it), it's removed by
     * hand, best-effort — same as the rm call itself, but only after confirming [refPath] is
     * actually a checkpoint artifact ([isCheckpointArtifactDir]'s shape) rather than an
     * arbitrary directory some caller-controlled [ref] happens to point at — [ref] is never
     * validated upstream of this method, and `deleteRecursively()` on an unchecked path is a
     * data-loss trap.
     */
    override fun removeCheckpoint(ref: String) {
        runCatching { invoke(MsbCommands.snapshotRemove(ref), STOP_TIMEOUT_SEC) }
        val refPath = Path.of(ref)
        if (refPath.isAbsolute && isCheckpointArtifactDir(refPath)) {
            runCatching { refPath.toFile().deleteRecursively() }
        }
    }

    /**
     * True if [path] has the on-disk shape a checkpoint artifact directory [createCheckpoint]
     * itself may have written: a directory, containing `snapshot.json`, whose basename starts
     * with `rz-ckpt-` (the shape a path ref's ARTIFACT had before msb 0.7.1's `--from-sandbox`
     * relocated it — still checked for a leftover from an artifact this library minted under an
     * earlier msb pin) or with `snap_` (the artifact basename msb 0.7.1 itself mints — see
     * [MsbCliBackend.createCheckpoint]'s stdout-parsing doc). Guards [removeCheckpoint]'s
     * `deleteRecursively()` against recursively wiping an arbitrary populated directory a
     * caller-supplied [ref] happens to name; requiring `snapshot.json` alongside the basename
     * shape is deliberately conservative — a directory that merely LOOKS like a checkpoint
     * artifact by name but isn't one (no `snapshot.json`) is left untouched.
     */
    private fun isCheckpointArtifactDir(path: Path): Boolean =
        Files.isDirectory(path) &&
            Files.exists(path.resolve("snapshot.json")) &&
            (path.fileName?.toString()?.let { it.startsWith("rz-ckpt-") || it.startsWith("snap_") } == true)

    /**
     * `msb snapshot inspect <ref>`, which exits 0 when the snapshot exists. A non-zero exit is
     * only "genuinely gone" — and thus only resolves to `false` — when stderr carries msb's own
     * miss framing (see [isCheckpointMiss]); msb has no separate structured error to distinguish
     * that from any other inspect failure (a corrupted state database, a permission failure, a
     * transient hiccup), so per the SPI contract those must never fold into `false` — they throw
     * instead, carrying stderr (falling back to stdout when empty), the same
     * substring-classifier discipline [isImageCacheCorruption]/[isMsbStateDbError] already use.
     * Unlike [removeCheckpoint]'s best-effort `runCatching`, a probe failure here is never
     * swallowed: [Checkpoint.find]'s stale-entry cleanup calls this to decide whether to delete a
     * registry entry, and folding a probe failure into `false` would let it permanently orphan a
     * live checkpoint.
     *
     * Every [ref] shape reaches msb the same way now, verbatim — no filesystem shortcut for a
     * path ref. Before msb 0.7.1, a path ref's artifact sat exactly at [ref] and a plain
     * filesystem check sufficed without spawning msb at all; as of 0.7.1, a `--dest-dir` (path
     * hint) snapshot is tracked in msb's OWN index just like any other (see
     * [MsbCommands.snapshotCreate]'s doc), so an inspect call is both correct and — per msb 0.7.1
     * resolving `inspect` reliably only against the artifact's own path (see
     * [MsbCommands.snapshotInspect]'s doc) — necessary: every ref this backend mints already IS
     * that path.
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
     * machines" section): `msb snapshot save <ref> <dest>` writes [ref]'s `.tar.zst` artifact
     * to [dest], byte-for-byte what [importCheckpoint]'s `snapshot load` reads back.
     *
     * On Windows, a failure carrying the access-denied errno is finished by hand instead of
     * surfaced: msb 0.6.7/0.6.8 writes the whole archive to a staging file next to [dest] and
     * then fsyncs it through a read-only handle, which can never succeed on Windows, so every
     * save fails there one step short of renaming the finished archive into place (see
     * [isSnapshotSaveAccessDenied] for the mechanics). [salvageStagedArchive] performs exactly
     * that rename, and only when the staging directory holds exactly one candidate. Anything
     * else — a non-Windows host, any other stderr, an unrecognizable staging directory —
     * surfaces msb's own error unchanged.
     *
     * This needs no version check against the pinned msb release and carries no removal
     * condition: it can only run when msb fails this specific way, so a fixed msb simply stops
     * reaching it.
     */
    override fun exportCheckpoint(ref: String, dest: Path) {
        val r = invoke(MsbCommands.snapshotExport(ref, dest), SNAPSHOT_EXPORT_TIMEOUT_SEC)
        if (r.exitCode == 0) return
        if (windowsHost && isSnapshotSaveAccessDenied(r.stderr) && salvageStagedArchive(dest)) return
        error("msb snapshot save $ref $dest failed (exit ${r.exitCode}): " +
            "${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
    }

    /**
     * Backs `Checkpoint.importFrom`: `msb snapshot load <src> --dest <checkpoints-dir>` unpacks
     * [src] into THIS library's own checkpoints directory — `CacheDir.resolve().resolve
     * ("checkpoints")`, the very directory [MsbCommands.snapshotCreate]'s own `--dest-dir` writes
     * under (minted by `GenericContainer.mintCheckpointRef`) — rather than msb's own default
     * `~/.microsandbox/snapshots/` store a bare `snapshot load` would otherwise target, so an
     * imported artifact and a locally created one now live under the same rightsize-owned tree.
     * [checkpointsDir] is created up front (mirroring [createCheckpoint]'s own `--dest-dir`
     * handling) in case this is the first checkpoint operation on this machine.
     *
     * [ref] (the archive's originally recorded ref, possibly from a wholly different machine) is
     * NOT what msb restores from and plays no part in the result — [ref] itself is unused beyond
     * being part of this method's signature (the SPI contract every backend shares; docker's own
     * [ref] IS its effective ref, unlike this one). On an ORDINARY success, msb 0.7.1 nests the
     * unpacked artifact under a fresh group/snapshot directory of its own choosing beneath
     * [checkpointsDir], discarding the original snapshot name entirely, and prints that full
     * absolute path as the LAST line of stdout (a `group msb-<hex>: head snap_<digest> (...)`
     * line, then a digest line, then the artifact path — verified empirically against the real
     * binary). [parseSnapshotLoadArtifactPath] parses that last line the same
     * defensively-absolute-path way [parseSnapshotCreateArtifactPath] already does for `snapshot
     * create` — a bad parse here would otherwise silently mint a ref nothing could ever
     * restore/remove/inspect again, so this throws quoting the raw output instead.
     *
     * Importing an archive whose content already exists fails with msb's own `error: snapshot
     * already exists: <path>` on stderr — for a content-addressed archive that IS success (the
     * artifact is already present), so [isSnapshotAlreadyExists] treats it as one rather than a
     * genuine failure; any OTHER non-zero exit surfaces with stderr. What this ALREADY-EXISTS
     * outcome prints on msb 0.7.1 is NOT independently verified — the only real evidence in this
     * codebase of msb's already-exists shape is [isSnapshotAlreadyExists]'s own doc comment,
     * observed verbatim against msb 0.6.8, where the artifact path lived ONLY on stderr (stdout
     * was not captured/verified that way at all). It is plausible 0.7.1 still prints the full
     * `group ...`/digest/path summary to stdout before failing, matching the ordinary-success
     * shape — but it is equally plausible the command short-circuits on the duplicate-content
     * check before ever printing that summary, leaving stdout empty or partial. Rather than bet
     * on either shape, this tries [r.stdout] first (via [parseSnapshotLoadArtifactPath], the
     * bare-artifact-path-as-a-whole-line shape) and, only on the already-exists outcome, falls
     * back to [r.stderr] via [parseSnapshotAlreadyExistsArtifactPath] — a DIFFERENT parse shape,
     * because msb's already-exists stderr is a sentence with the path as its trailing token
     * (`error: snapshot already exists: <path>`, per [isSnapshotAlreadyExists]'s doc), not a
     * bare path line on its own. This mirrors the pre-0.7.1 code's own `val output = if
     * (alreadyExists) r.stderr else r.stdout` branch, just tried as a fallback rather than
     * assumed outright, since which stream carries the path on 0.7.1's already-exists outcome has
     * not been confirmed against the real binary. Only if NEITHER stream parses does this throw.
     * The pre-0.7.1 approach of cross-referencing `msb snapshot list --format json` by a parsed
     * digest-dir basename no longer applies — every ref this library mints today, from both
     * [createCheckpoint] and this method, is already the absolute path msb itself reported, so
     * there is nothing left to look up.
     */
    override fun importCheckpoint(src: Path, ref: String): String {
        val checkpointsDir = CacheDir.resolve().resolve("checkpoints")
        Files.createDirectories(checkpointsDir)
        val r = invoke(MsbCommands.snapshotImport(src, checkpointsDir), SNAPSHOT_IMPORT_TIMEOUT_SEC)
        val alreadyExists = r.exitCode != 0 && isSnapshotAlreadyExists(r.stderr)
        if (r.exitCode != 0 && !alreadyExists) {
            error("msb snapshot load $src --dest $checkpointsDir failed (exit ${r.exitCode}): " +
                "${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
        }
        val fromStdout = parseSnapshotLoadArtifactPath(r.stdout)
        val fromStderr = if (alreadyExists) parseSnapshotAlreadyExistsArtifactPath(r.stderr) else null
        return fromStdout ?: fromStderr
            ?: error("msb snapshot load $src --dest $checkpointsDir " +
                (if (alreadyExists)
                    "reported the snapshot already exists, but neither its stdout nor its stderr ended " +
                        "with an absolute artifact path: stdout='${r.stdout.trim()}' stderr='${r.stderr.trim()}'"
                 else
                    "succeeded, but its stdout did not end with an absolute artifact path: '${r.stdout.trim()}'"))
    }

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

    /**
     * Runs [cmd] in the guest, retrying while msb reports it cannot reach the guest agent's
     * endpoint yet (see [isAgentEndpointNotReady]). [awaitRunning]'s readiness check is the
     * sandbox process reaching Running in `msb ls`, which says nothing about the in-guest agent
     * having created the endpoint this method's `msb exec` connects to — a caller that execs
     * immediately after [start] returns can arrive before that endpoint exists. The retry closes
     * that window here rather than leaving every caller to rediscover it. Only that one
     * signature is retried: a guest command's own non-zero exit returns on the first attempt,
     * unchanged.
     */
    override fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult {
        val argv = MsbCommands.exec(handle.id, cmd)
        val deadline = System.currentTimeMillis() + AGENT_ENDPOINT_RETRY_BUDGET_MS
        while (true) {
            val result = invoke(argv, timeoutSec = EXEC_TIMEOUT_SEC)
            if (result.exitCode == 0 || !isAgentEndpointNotReady(result.stderr) ||
                System.currentTimeMillis() >= deadline) {
                return result
            }
            Thread.sleep(AGENT_ENDPOINT_RETRY_DELAY_MS)
        }
    }

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

// [MsbCliBackend]'s tuning constants — file-private top level rather than companion members,
// so nothing bytecode-public is emitted for them (see the note on the companion).

// Permissive DNS-label charset: aliases are interpolated into a `sh -c` /etc/hosts
// echo, so this exists to reject shell-breaking characters, not to enforce a strict
// hostname grammar.
private val ALIAS_CHARSET = Regex("[A-Za-z0-9._-]+")
private const val FIRST_RUN_PULL_TIMEOUT_MS = 600_000L   // first run may pull the image
private const val READINESS_POLL_MS = 300L
private const val STOP_TIMEOUT_SEC = 60L
// Before retrying a boot that hit msb's state-database error — enough for a winning
// concurrent invocation's migration transaction to commit; the retry's own `msb run`
// startup dwarfs this either way.
private const val STATE_DB_RETRY_DELAY_MS = 500L
// msb's install-operation lock (see [isMsbInstallLockActive]): observed clearing within
// seconds despite its message's ~30-minute deadline, so poll a short budget and give up
// loudly after it — a lock outliving this really is stuck.
private const val INSTALL_LOCK_RETRY_BUDGET_MS = 30_000L
private const val INSTALL_LOCK_RETRY_DELAY_MS = 2_000L
private const val EXEC_TIMEOUT_SEC = 120L
// How long exec keeps retrying while the guest agent's endpoint has not appeared yet,
// and how long it pauses between attempts — see [isAgentEndpointNotReady]. Zero cost on
// the ordinary path, where the first attempt connects.
private const val AGENT_ENDPOINT_RETRY_BUDGET_MS = 30_000L
private const val AGENT_ENDPOINT_RETRY_DELAY_MS = 250L
private const val LOGS_TIMEOUT_SEC = 30L
private const val COPY_TIMEOUT_SEC = 120L
// `msb snapshot create` writes a full disk image (sparse — a tiny alpine snapshot was
// observed at 3.9 MB on disk despite a "4 GiB" nominal size) — generous but bounded.
private const val SNAPSHOT_TIMEOUT_SEC = 180L
// `msb snapshot save`/`import` (see exportCheckpoint/importCheckpoint) — same order of
// magnitude as SNAPSHOT_TIMEOUT_SEC, the artifact they move is the same payload.
private const val SNAPSHOT_EXPORT_TIMEOUT_SEC = 300L
private const val SNAPSHOT_IMPORT_TIMEOUT_SEC = 300L
private const val ATTACHED_PROC_STOP_TIMEOUT_SEC = 10L
private const val READER_JOIN_TIMEOUT_MS = 2000L
private const val TAIL_LINES = 50

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
 * True if `output` (an `msb run` invocation's combined output) is msb refusing to run
 * anything while its internal install lock is held. Captured verbatim from windows-2025
 * hosted runners, once in each sibling CI lane, mid-suite with ordinary boots on both
 * sides of the failure:
 *
 * ```
 * error: runtime error: microsandbox install operation in progress until
 * 2026-07-31 20:55:04.779845600; retry after it completes
 * error: runtime error: another microsandbox install operation is in progress
 * until 2026-08-01 19:26:19.025098100
 * ```
 *
 * Two phrasings, one condition — msb words the refusal differently depending on which
 * side holds the lock, so the match tolerates the optional "is".
 *
 * The deadline in the message reads ~30 minutes out, but both occurrences cleared within
 * the same run — boots seconds later succeeded — so the boot path polls briefly (see
 * `spawnAndAwaitRunning`) instead of failing on the first refusal or trusting the
 * deadline. Matches on the stable phrase only; the timestamp varies per occurrence.
 */
internal fun isMsbInstallLockActive(output: String): Boolean =
    "install operation in progress" in output || "install operation is in progress" in output

/** Boot-path classified failure for [isMsbInstallLockActive] — internal to the boot path,
 * like its two siblings above; `spawnAndAwaitRunning` owns the retry policy. */
internal class MsbInstallLockException(val output: String) :
    RuntimeException("msb install lock active:\n$output")

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
 * True if [output] (`msb logs <name> --source system`'s stdout) carries the boot-completion
 * marker line msb's guest agent writes only once it has actually come up. This is the second
 * of the two signals [MsbCliBackend.isCleanFastExit]'s post-mortem classification requires —
 * state `Stopped` is the first — to tell a workload that ran to completion apart from a
 * genuinely dead boot: msb 0.6.10 through 0.6.13's Windows agentless-death failures also
 * exited the attached `msb run` child with code 0, but the guest agent never came up in those,
 * so this marker was never written. Requiring both signals is what keeps a genuinely dead boot
 * from being misclassified as a clean fast exit.
 */
internal fun hasSandboxStartedMarker(output: String): Boolean = "--- sandbox started ---" in output

/**
 * True if [stderr] (an `msb exec` invocation's stderr) says msb could not reach the guest
 * agent's endpoint at all, as distinct from the guest command itself failing.
 *
 * A sandbox reaches Running in `msb ls` before the in-guest agent has necessarily created the
 * endpoint `msb exec` connects to — Running is a statement about the sandbox process, not about
 * that endpoint. The two are ordinarily separated by enough wall-clock that nothing notices:
 * every wait strategy blocks on a log line, an HTTP probe, or a port before anyone execs. A
 * caller that execs immediately after [MsbCliBackend.start] returns has no such gap, and on
 * Windows — where the endpoint is a named pipe rather than a unix socket — loses the race
 * outright:
 *
 * ```
 * error: agent client error: connect \\.\pipe\msb-agent-e7779577a75cc1f89f66c534458bf8fd: The
 * system cannot find the file specified. (os error 2)
 * ```
 *
 * Captured verbatim from a windows-2025 hosted runner exec'ing into a sandbox restored from a
 * checkpoint archive. Matches on msb's own `agent client error` framing plus `connect`, so a
 * failure *after* the connection is established — a real agent error worth surfacing — is never
 * mistaken for this, the same substring-classifier discipline [isImageCacheCorruption] and
 * [isMsbStateDbError] already use.
 */
internal fun isAgentEndpointNotReady(stderr: String): Boolean =
    "agent client error" in stderr && "connect" in stderr

/**
 * True if [stderr] (from `msb snapshot inspect <ref>`) names msb's own "no such snapshot"
 * miss, as opposed to some other inspect failure. Observed verbatim against the real msb
 * 0.6.8 binary:
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
 * True if [stderr] (from `msb snapshot save`) carries Windows' access-denied errno — which on
 * msb 0.6.7/0.6.8 means the archive was written in full and then failed the fsync that follows
 * it. Captured verbatim from a windows-2025 hosted runner:
 *
 * ```
 * error: io error: Access is denied. (os error 5)
 * ```
 *
 * msb's `save_snapshot` writes the archive to a staging file beside the destination, reopens
 * that file READ-ONLY, calls `sync_all` on the handle, and only then renames it onto the
 * destination. On Windows `sync_all` is `FlushFileBuffers`, which requires write access on the
 * handle, so it returns error 5 on every single save and the rename is never reached; on Unix
 * fsync of a read-only descriptor is legal, so only Windows sees it. msb 0.6.6 wrote straight to
 * the destination with no fsync step at all, which is why this appeared with 0.6.7.
 *
 * Matches the `(os error 5)` suffix rather than the message text: the text is Windows' own,
 * rendered through FormatMessage and therefore localized ("Access is denied." only on an English
 * host), while the suffix is appended by Rust itself and reads identically in every locale.
 * Deliberately narrow in the other direction too — see [salvageStagedArchive], which additionally
 * requires an unambiguous staging file before this classification is acted on at all.
 */
internal fun isSnapshotSaveAccessDenied(stderr: String): Boolean = "(os error 5)" in stderr

/**
 * Finishes the rename a fsync-failed `msb snapshot save` never got to: moves the one staging
 * file sitting beside [dest] onto [dest], returning true if it did.
 *
 * msb names that file `.<destination file name>.tmp.<msb's pid>.<unix nanos>` in the
 * destination's own directory, and removes it only when WRITING the archive fails — a write that
 * succeeded and then failed its fsync leaves the complete, valid archive behind under that name,
 * which is what makes salvaging it correct rather than a guess. The move performed here is the
 * same one msb's own `replace_archive` would have performed on the next line.
 *
 * Exactly one candidate is required. Checkpoint archives are always exported into a fresh,
 * uniquely named staging directory this library creates and owns for that one artifact, so a
 * single candidate is the normal case; none means the failure was not the one being worked
 * around, and several mean the directory is not what this assumes, where choosing between them
 * would be a guess. Both of those leave every file exactly where it is and report false, so the
 * caller surfaces msb's original error. A failure to list the directory or to perform the move
 * is reported the same way, for the same reason.
 */
internal fun salvageStagedArchive(dest: Path): Boolean {
    val parent = dest.toAbsolutePath().parent ?: return false
    val prefix = ".${dest.fileName ?: return false}.tmp."
    val staged = runCatching {
        Files.list(parent).use { entries ->
            entries.filter { it.fileName.toString().startsWith(prefix) && Files.isRegularFile(it) }
                .toList()
        }
    }.getOrElse { return false }.singleOrNull() ?: return false
    return runCatching { Files.move(staged, dest, StandardCopyOption.REPLACE_EXISTING) }.isSuccess
}

/**
 * True if [stderr] (from `msb snapshot load`) names msb's own "already exists" outcome, as
 * opposed to some other import failure. Observed verbatim against the real msb 0.6.8 binary:
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
 * Parses the absolute snapshot artifact path `msb snapshot create` prints as its own LAST
 * stdout line on success (msb 0.7.1: the snapshot ID line, then the artifact path — verified
 * empirically against the real binary). This IS the checkpoint ref [MsbCliBackend.createCheckpoint]
 * returns. Delegates to [parseTrailingAbsolutePathLine] — the shared parsing core this and
 * [parseSnapshotLoadArtifactPath] both use.
 */
internal fun parseSnapshotCreateArtifactPath(output: String): String? = parseTrailingAbsolutePathLine(output)

/**
 * Parses the absolute snapshot artifact path `msb snapshot load` prints as its own LAST stdout
 * line on an ORDINARY success (msb 0.7.1: a `group msb-<hex>: head snap_<digest> (...)` line, a
 * digest line, then the artifact path — verified empirically against the real binary). This IS
 * the checkpoint ref [MsbCliBackend.importCheckpoint] returns — unlike msb's pre-0.7.1 shape (a
 * bare digest-DIR basename, resolved separately via `snapshot list`, now dead code and removed),
 * the whole line is the ref: an absolute path nested under this library's own checkpoints
 * directory (see [MsbCommands.snapshotImport]'s `--dest`), msb's own choice of group/snapshot
 * naming beneath it. [MsbCliBackend.importCheckpoint] also tries this same parse against stdout
 * on the already-exists-as-success outcome FIRST, in case 0.7.1 still prints the same summary
 * there — but falls back to [parseSnapshotAlreadyExistsArtifactPath] against stderr if it
 * doesn't, since that outcome's own stdout shape isn't independently confirmed (see
 * [MsbCliBackend.importCheckpoint]'s doc). Delegates to [parseTrailingAbsolutePathLine] — the
 * shared parsing core this and [parseSnapshotCreateArtifactPath] both use.
 */
internal fun parseSnapshotLoadArtifactPath(output: String): String? = parseTrailingAbsolutePathLine(output)

/**
 * Parses the absolute snapshot artifact path from msb's already-exists STDERR sentence (`error:
 * snapshot already exists: <path>` — observed verbatim against the real msb 0.6.8 binary, see
 * [isSnapshotAlreadyExists]'s doc), used by [MsbCliBackend.importCheckpoint] as the fallback ref
 * source when the already-exists outcome's stdout doesn't itself parse via
 * [parseSnapshotLoadArtifactPath]. UNLIKE that function, the path here is not the WHOLE last
 * line — it's a sentence with the path as its trailing whitespace-separated token — so this
 * takes the last non-blank line's final token (the same `substringAfterLast(' ')` shape the
 * pre-0.7.1 `parseImportedDigestDir` used) and requires THAT token, on its own, to parse as an
 * absolute [Path]. Returns `null` — never throws — for blank input, an empty trailing token, or
 * a token that parses but isn't absolute; [MsbCliBackend.importCheckpoint] turns a `null` here
 * (with a `null` stdout parse too) into a typed error quoting both raw streams.
 */
internal fun parseSnapshotAlreadyExistsArtifactPath(stderr: String): String? {
    val lastLine = stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
    val token = lastLine.substringAfterLast(' ').trim()
    if (token.isEmpty()) return null
    val parsed = runCatching { Path.of(token) }.getOrNull() ?: return null
    return token.takeIf { parsed.isAbsolute }
}

/**
 * Shared parsing core for [parseSnapshotCreateArtifactPath] and [parseSnapshotLoadArtifactPath]:
 * both `msb snapshot create` and `msb snapshot load` print the resulting artifact's own absolute
 * path as their LAST stdout line on success, with unrelated informational lines (an ID, a group,
 * a digest) ahead of it — msb never pads that final line with anything else, so requiring the
 * WHOLE line (not just its last whitespace-separated token) to parse as an absolute [Path] is
 * exactly right and never wrongly truncates a path containing a space.
 *
 * Defensive by construction, since a bad parse here would otherwise silently mint a ref nothing
 * could ever restore/remove/inspect again: trims [output], takes the last non-blank line, and
 * requires that whole line to parse as an ABSOLUTE [Path]. Returns `null` — never throws — for
 * blank output, a line [Path.of] itself rejects, or a line that parses but isn't absolute (a
 * relative path, or any other unexpected shape); both callers turn a `null` into a typed error
 * quoting the raw output, via the same `?: error(...)` shape.
 */
private fun parseTrailingAbsolutePathLine(output: String): String? {
    val lastLine = output.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
    val parsed = runCatching { Path.of(lastLine) }.getOrNull() ?: return null
    return lastLine.takeIf { parsed.isAbsolute }
}
