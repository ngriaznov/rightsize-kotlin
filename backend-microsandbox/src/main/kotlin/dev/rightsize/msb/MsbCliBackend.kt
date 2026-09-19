package dev.rightsize.msb

import dev.rightsize.RunId
import dev.rightsize.core.*
import dev.rightsize.core.checkpoint.CheckpointRegistry
import dev.rightsize.core.reaper.Reaper
import dev.rightsize.core.reuse.SandboxNameCollisionException
import dev.rightsize.nextSandboxName
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
 *
 * [checkpointRegistryDir] is a third, narrower seam in the same spirit: the directory
 * [resolveWorkloadArgv]'s captured-command lookup and [createCheckpoint]'s captured-command
 * persistence construct a [CheckpointRegistry] against, defaulted to the real
 * [CacheDir.resolve] so production behavior is unchanged. Without it, a fake-`msb`-binary unit
 * test would read/write the developer's real `~/.cache/rightsize` (or whatever `test` task
 * pins `RIGHTSIZE_CACHE_DIR` to) instead of its own isolated temp directory; tests reach it via
 * [forHost], same as the other two seams.
 *
 * [checkpointRebootAlreadyExistsBudgetMs] is a fourth seam, same spirit as
 * [restoreReadinessBudgetMs]: the wall-clock budget [restoreRetryingFreshName] bounds its
 * whole fresh-name-per-attempt retry by — for BOTH its callers, [createCheckpoint]'s own reboot
 * AND [start]'s ordinary `GenericContainer.fromCheckpoint(cp).start()` restore (see that method's
 * own doc) — defaulted to the real [CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_BUDGET_MS] (~30s) so
 * production behavior is unchanged. Without it, red-proofing "the collision/access-denied never
 * clears" would mean a unit test actually blocking for that real budget; tests reach it via
 * [forHost] to shrink it, exactly like [restoreReadinessBudgetMs]. The field keeps its
 * checkpoint-reboot-flavored name from when it had only one caller; both callers share the same
 * budget rather than each getting a seam of its own, since the policy — and the CI evidence
 * behind it — is identical for either restore path.
 *
 * [createCheckpoint]'s reboot restores under a FRESHLY GENERATED sandbox name (via
 * [dev.rightsize.nextSandboxName], the same `rz-<runId>-<n>` generator/counter an ordinary boot
 * uses), never the checkpointed container's own pre-checkpoint name — see that method's doc for
 * why (msb's Windows-only sandbox-directory-retention lag, which a same-name restore cannot
 * outrun no matter how long it waits, but a fresh name sidesteps outright). [awaitNameReleased]
 * predates that fix and still runs, unchanged, as defense in depth against a name collision that
 * is now vanishingly unlikely (a freshly generated name colliding with something else live)
 * rather than the near-certainty a same-name restore faced. [restoreRetryingFreshName]
 * (the renamed, restructured successor of what used to be a same-name-only retry, and — as of
 * this policy's extension to the ordinary restore path — shared by both callers rather than
 * `createCheckpoint`'s own private helper) stays live for the same residual reason, but no
 * longer assumes the fresh name never needs replacing: a real `msb restore` failure AFTER msb
 * validates the artifact — the Windows access-denied errno chief among them — can still leave
 * the fresh name itself behind as a stopped sandbox record, so this now mints ANOTHER fresh name
 * per failed attempt rather than retrying the same one forever (see that method's own doc — this
 * is the fix for the CI collision on already-fresh names, not dead code kept per the class's own
 * don't-remove-working-defenses posture).
 *
 * [restoreBroker] is a fifth seam, the same injectable-with-a-production-default shape as the
 * four above but function-typed rather than a plain value: [restoreRetryingFreshName]'s
 * own escalation path (POLICY v2 -- see that method's doc) launches every restore attempt from
 * the first [RestoreAccessDeniedException] onward through a WMI-based broker instead of a direct
 * spawn, and this is what a unit test replaces with a stub returning a scripted [BrokerOutcome]
 * rather than actually shelling out to `powershell.exe`/WMI -- meaningless on a POSIX test host,
 * and exactly the kind of real-Windows-only branch [windowsHost] exists to make testable at all
 * (see that seam's own doc). Root cause: `msb restore`'s detached spawn on Windows always passes
 * `CREATE_BREAKAWAY_FROM_JOB` (see [createCheckpoint]'s own doc on this), and a Windows job
 * object that denies breakaway rights -- exactly what wraps a Gradle test worker or a cargo-test
 * binary under a CI runner -- makes that spawn fail with `ERROR_ACCESS_DENIED` deterministically,
 * not transiently, so no amount of retrying a direct spawn ever clears it. A process created via
 * `Invoke-CimMethod -ClassName Win32_Process -MethodName Create` is parented by the WMI provider
 * host (`WmiPrvSE.exe`), outside this JVM's own job hierarchy entirely, so the identical `msb
 * restore` invocation launched that way succeeds where a direct spawn is denied. This same
 * escalation covers an ordinary `GenericContainer.fromCheckpoint(cp).start()` restore too, not
 * only `createCheckpoint`'s own reboot — the job-object denial is a property of THIS JVM's own
 * process tree, not of which caller happened to trigger the restore.
 * [defaultRestoreBroker] is the production implementation; see its own doc and [BrokerOutcome]'s
 * for the mechanics.
 */
class MsbCliBackend private constructor(
    internal val msb: Path,
    private val windowsHost: Boolean,
    private val restoreReadinessBudgetMs: Long = FIRST_RUN_PULL_TIMEOUT_MS,
    private val checkpointRegistryDir: Path = CacheDir.resolve(),
    private val checkpointRebootAlreadyExistsBudgetMs: Long = CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_BUDGET_MS,
    private val restoreBroker: RestoreBroker = ::defaultRestoreBroker,
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

    /**
     * [spec] is `var` (not the `val` every other [SandboxHandle] implementation in this codebase
     * uses) so [createCheckpoint]'s fresh-name reboot can rewrite it in place — [id] is a computed
     * property reading straight off the current [spec], so mutating [spec] alone keeps the
     * `Handle.id == spec.name` invariant every downstream caller (exec/logs/stop/rm/state — see
     * [findRunning]'s own doc) already relies on, with no second field to fall out of sync.
     * `@Volatile` matches [attached]'s own annotation: cheap visibility for a field a
     * `checkpoint()` call can rewrite on one thread while another reads it (`logs`/`exec`/...).
     */
    internal class Handle(@Volatile override var spec: ContainerSpec) : SandboxHandle {
        override val id: String get() = spec.name
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
         * optionally to a shrunk [restoreReadinessBudgetMs] or a stubbed [restoreBroker] — the
         * test-only seams described on this class. `internal`, so Kotlin mangles its name in the
         * bytecode and it never becomes a Java-callable entry point either.
         */
        internal fun forHost(
            msb: Path,
            windowsHost: Boolean,
            restoreReadinessBudgetMs: Long = FIRST_RUN_PULL_TIMEOUT_MS,
            checkpointRegistryDir: Path = CacheDir.resolve(),
            checkpointRebootAlreadyExistsBudgetMs: Long = CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_BUDGET_MS,
            restoreBroker: RestoreBroker = ::defaultRestoreBroker,
        ) = MsbCliBackend(
            msb, windowsHost, restoreReadinessBudgetMs, checkpointRegistryDir,
            checkpointRebootAlreadyExistsBudgetMs, restoreBroker)
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
        // A restore boot (GenericContainer.fromCheckpoint(cp).start()) goes through the SAME
        // fresh-name-per-attempt walk + broker escalation createCheckpoint's own reboot uses
        // (see restoreRetryingFreshName's own doc) rather than spawnAndAwaitRunning directly —
        // required because spawnAndAwaitRunning itself no longer retries a restore's
        // RestoreAccessDeniedException at all (see its own doc: that retry always collided with
        // the stopped record the failed attempt left behind). An ordinary image boot
        // (checkpointRef == null) is completely untouched: still the exact same direct call this
        // was before restoreRetryingFreshName existed. No re-keying step is needed here on
        // success — unlike GenericContainer.checkpoint()'s own LiveContainers fix-up — because
        // LiveContainers.register(h, ...)/network-linking/wait-strategy in GenericContainer.start()
        // all run AFTER this method returns, reading straight off this SAME Handle object's
        // current (possibly renamed) spec; there is no earlier registration under the original
        // name for anything to fall out of sync with.
        handle.attached =
            if (handle.spec.checkpointRef != null) restoreRetryingFreshName(handle, handle.spec)
            else spawnAndAwaitRunning(handle, handle.spec)
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
     * Returns the supervising child [Process] for an ordinary `run` boot. For a `restore` boot
     * (`spec.checkpointRef != null`) the `restore` process ITSELF has already exited by the time
     * this returns (see [awaitRestoreRunning]) — but this no longer returns `null` for that case:
     * once the sandbox reaches Running, [spawnWorkloadExecChild] spawns the `msb exec` session
     * that actually revives the checkpointed workload (upstream's restore boots the sandbox idle
     * — see that method's doc), and ITS process is what's returned, becoming [Handle.attached]'s
     * new supervising child for the boot. The one caller still assigns the result straight to
     * [Handle.attached], which is `null`-safe everywhere it's read ([stop]) for every OTHER
     * `null`-attached case (e.g. [findRunning]'s adopted handles).
     *
     * [useBroker] is POLICY v2's own escalation flag (see [restoreRetryingFreshName]'s doc):
     * `false` (every caller except that method, once it has escalated) is a direct spawn,
     * unchanged from before this parameter existed. `true` is threaded straight through to every
     * [bootOnce] call this method itself makes — including its own internal retries (the
     * install-lock poll, the state-db one-shot retry) — so once a restore has escalated, EVERY
     * attempt launched from here, not just the fresh-name loop's own top-level one, goes through
     * the broker; see [bootOnce]'s own doc for what that actually changes.
     *
     * A [RestoreAccessDeniedException] is never retried HERE, by either caller — see that catch's
     * own doc below for why this method always propagates it raw, unlike every other classified
     * failure this method itself heals/retries inline.
     */
    private fun spawnAndAwaitRunning(handle: Handle, spec: ContainerSpec, useBroker: Boolean = false): Process? {
        val firstOutput = try {
            return bootOnce(handle, spec, useBroker)
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
                    return bootOnce(handle, spec, useBroker)
                } catch (again: MsbInstallLockException) {
                    last = again
                }
            }
            error("msb run for sandbox ${handle.id} was refused for ${INSTALL_LOCK_RETRY_BUDGET_MS / 1000}s " +
                "by msb's install-operation lock — both observed occurrences cleared within seconds, so a " +
                "lock held this long looks like a genuinely stuck msb install on this host.\n${last.output}")
        } catch (denied: RestoreAccessDeniedException) {
            // Windows-only in practice (see isRestoreAccessDenied's doc): msb's deferred file-handle
            // release on the just-written snapshot artifact can still be in flight the instant
            // `restore` tries to read it, right after the source sandbox's own teardown — OR, a
            // distinct root cause with the exact same wording (see restoreRetryingFreshName's own
            // POLICY v2 paragraph): msb's detached restore spawn is denied outright by a Windows
            // job object that grants no breakaway rights. EITHER way, retrying THIS attempt again
            // under the SAME name/spec is never safe: a restore failure past msb's own artifact
            // validation can leave [spec]'s name behind as a stopped sandbox record, and a same-name
            // retry into that gap then collides with its own leftover instead of ever clearing the
            // actual transient (the exact CI failure this class's fresh-name-per-attempt policy
            // fixes). BOTH callers of this method — [createCheckpoint]'s own reboot and, as of this
            // policy's extension to the ordinary path, [start] itself — now own this retry one layer
            // up, via [restoreRetryingFreshName]'s fresh-name walk, so this always rethrows the raw,
            // unretried exception rather than attempting anything here.
            throw denied
        } catch (race: MsbStateDbException) {
            // Usually the startup-migration race, transient by construction (see
            // [isMsbStateDbError]): the winning msb invocation's migration commits and a
            // retried boot finds the schema in place. No heal step, one retry, second
            // failure propagates — the same one-shot policy as the image-cache heal below.
            Thread.sleep(STATE_DB_RETRY_DELAY_MS)
            try {
                return bootOnce(handle, spec, useBroker)
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
            return bootOnce(handle, spec, useBroker)
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
     *   still-live child. [useBroker] is meaningless for this branch — only a restore is ever
     *   brokered (see [restoreRetryingFreshName]'s own doc) — and is ignored here.
     * - set, [useBroker] `false` (the default, and the only value for every caller except
     *   [restoreRetryingFreshName] once it has escalated — see [spawnAndAwaitRunning]'s
     *   own doc): spawns `msb restore ...` DIRECTLY and waits for IT (a short-lived activation
     *   launcher, not a supervising child — see [awaitRestoreRunning]'s doc) to exit, then polls
     *   [handle] to Running the same way.
     * - set, [useBroker] `true`: delegates to [bootOnceBrokered] instead — the exact same restore
     *   invocation, launched through POLICY v2's WMI-based broker instead of a direct spawn.
     *
     * Either restore path returns `null`... except it doesn't: by the time the restore is
     * confirmed Running, [spawnWorkloadExecChild] spawns the `msb exec` session that actually
     * revives the checkpointed workload (upstream's restore boots the sandbox idle — see that
     * method's doc), and ITS process is what's returned, becoming [Handle.attached]'s new
     * supervising child for the boot — identically for a direct or a brokered restore, since
     * [bootOnceBrokered] ends in the exact same [spawnWorkloadExecChild] call this direct path
     * does (see that method's own doc for why this is deliberate: the brokered sandbox itself is
     * every bit as detached-with-no-attached-child as a direct restore already was).
     *
     * On any failure the DIRECT path's process is reaped here (for the classified early-exit/
     * nonzero-exit failures it has already exited; a readiness timeout leaves it alive and it is
     * force-killed), so a failed attempt leaves no live process behind — the caller owns retry
     * policy, never cleanup. [bootOnceBrokered] has no such live child of its own to reap: the
     * WMI-created process was never this JVM's child in the first place.
     */
    private fun bootOnce(handle: Handle, spec: ContainerSpec, useBroker: Boolean = false): Process? {
        if (spec.checkpointRef != null && useBroker) return bootOnceBrokered(handle, spec)
        val (proc, tail, drainer) = spawnAttachedRun(spec)   // ATTACHED run, or a detached restore launch
        try {
            return if (spec.checkpointRef != null) {
                awaitRestoreRunning(handle, proc, tail, drainer)   // readiness = name Running in `msb ls`
                spawnWorkloadExecChild(handle, spec)               // upstream restore boots idle — revive it
            } else {
                awaitRunning(handle, proc, tail, drainer)          // readiness = name Running in `msb ls`
                proc
            }
        } catch (t: Throwable) {
            if (proc.isAlive) proc.destroyForcibly()
            throw t
        }
    }

    /**
     * The brokered counterpart of [bootOnce]'s direct restore path — POLICY v2's escalation (see
     * [restoreRetryingFreshName]'s doc). Launches `msb restore ...` via [restoreBroker]
     * instead of a direct [spawnAttachedRun], then classifies/polls [handle] to Running exactly
     * like the direct path does, reusing the SAME predicates: [classifyRestoreExit] is the exact
     * classification [awaitRestoreRunning] itself runs against a direct attempt's exit code and
     * output, and [pollRestoreRunning] is the exact ls-poll loop [awaitRestoreRunning] runs once
     * activation is known to have succeeded — a brokered restore is classified/awaited no
     * differently than a direct one once launched, only the launch mechanism itself differs.
     *
     * [BrokerOutcome.BrokerFailure] (the broker INFRASTRUCTURE itself failing — never a restore
     * classification, since no restore attempt was ever actually launched) falls back to an
     * ordinary DIRECT spawn for this one attempt — POLICY v2 §5: the broker must never become a
     * new single point of failure. This is the only place a "brokered" attempt can still end up
     * launching directly; [restoreRetryingFreshName]'s own `brokered` flag is untouched
     * by this fallback, so the NEXT attempt (a fresh name, if this one also fails) tries the
     * broker again rather than giving up on it permanently over one infrastructure hiccup.
     *
     * Ends in the exact same [spawnWorkloadExecChild] call [bootOnce]'s direct path ends in — see
     * that method's own doc: this is what gives the brokered path the identical
     * handle/attached-state shape the direct restore path produces, never a bespoke shape of its
     * own. The brokered sandbox is DETACHED with no attached child in THIS JVM for the restore
     * itself, exactly like a direct restore already was ([awaitRestoreRunning]'s own doc: `msb
     * restore` is detached-by-design) — [spawnWorkloadExecChild]'s `msb exec` session is what
     * fills [Handle.attached] either way.
     */
    private fun bootOnceBrokered(handle: Handle, spec: ContainerSpec): Process? {
        when (val outcome = restoreBroker(msb, MsbCommands.restore(spec), spec.name)) {
            is BrokerOutcome.BrokerFailure -> {
                val (proc, tail, drainer) = spawnAttachedRun(spec)
                try {
                    awaitRestoreRunning(handle, proc, tail, drainer)
                } catch (t: Throwable) {
                    if (proc.isAlive) proc.destroyForcibly()
                    throw t
                }
            }
            is BrokerOutcome.Completed -> {
                val deadline = System.currentTimeMillis() + restoreReadinessBudgetMs
                classifyRestoreExit(handle, outcome.exitCode, outcome.output)
                pollRestoreRunning(handle, deadline)
            }
            BrokerOutcome.LaunchedUnconfirmed -> {
                // No exit-code file appeared within the broker script's own bound, but CIM
                // reported the process launched — restore is activation-gated regardless of how
                // it was launched, so this skips straight to the same ls-poll a direct exit-0
                // restore goes through, rather than treating the missing file as failure.
                pollRestoreRunning(handle, System.currentTimeMillis() + restoreReadinessBudgetMs)
            }
        }
        return spawnWorkloadExecChild(handle, spec)
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
        classifyRestoreExit(handle, exitCode, output)
        pollRestoreRunning(handle, deadline)
    }

    /**
     * The exit-code/output classification half of [awaitRestoreRunning], factored out so
     * [bootOnceBrokered] can apply the EXACT same predicates to a brokered restore's own
     * exit-code/output pair (see [BrokerOutcome.Completed]) — a restore invocation is classified
     * identically regardless of whether it was launched by a direct spawn or POLICY v2's broker;
     * only the launch mechanism differs. A zero [exitCode] is not itself classified as anything —
     * it just returns, exactly as [awaitRestoreRunning]'s own `if (exitCode != 0)` guard did
     * before this was extracted.
     */
    private fun classifyRestoreExit(handle: Handle, exitCode: Int, output: String) {
        if (exitCode == 0) return
        if (isImageCacheCorruption(output)) throw ImageCacheCorruptionException(output)
        if (isMsbStateDbError(output)) throw MsbStateDbException(output)
        if (isMsbInstallLockActive(output)) throw MsbInstallLockException(output)
        if (isRestoreAccessDenied(output)) throw RestoreAccessDeniedException(output)
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

    /**
     * The ls-poll half of [awaitRestoreRunning], factored out so [bootOnceBrokered] can run the
     * EXACT same poll once a brokered restore's own activation is known to have succeeded (a
     * [BrokerOutcome.Completed] exit 0, or a [BrokerOutcome.LaunchedUnconfirmed] outcome — see
     * that sealed class's own doc) — bounded by [deadlineMs], an absolute
     * `System.currentTimeMillis()` value the caller computes from [restoreReadinessBudgetMs]
     * itself (this method takes no opinion on how much of that budget the launch step already
     * spent, matching [awaitRestoreRunning]'s own original shared-deadline math for the direct
     * path, and giving the brokered path a full budget of its own for the phase THIS method
     * covers, since the broker's own bounded exit-code wait is a separate concern already spent
     * before this is ever called).
     */
    private fun pollRestoreRunning(handle: Handle, deadlineMs: Long) {
        var seen = false
        while (System.currentTimeMillis() < deadlineMs) {
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

    /**
     * Revives a restored sandbox's workload once it reaches Running: confirmed empirically
     * against the real msb 0.7.1 binary, `msb restore` brings the sandbox up with only `agentd`
     * inside — the captured command never re-runs on its own, `msb start` on the same sandbox
     * boots equally idle, and `msb run` has no snapshot option at 0.7.1 — so this backend has to
     * start the workload itself, exactly once, right here, or every restored container silently
     * breaks the checkpoint contract (workload running, ports served, wait strategies
     * satisfiable, logs flowing).
     *
     * Spawns `msb exec [-e K=V]... <name> -- <argv>` ([MsbCommands.execWorkload]) as an ATTACHED
     * child — [resolveWorkloadArgv] decides [argv] (see its own doc for the (a)/(b)/(c) priority)
     * and throws [CheckpointMissingWorkloadCommandException] before ever spawning anything when
     * neither an explicit command nor a captured one is available, rather than booting a sandbox
     * that LOOKS started but never runs anything. `-e` pairs come from [spec]'s own env — the
     * restore step that brought the sandbox up never got to pass it (see [MsbCommands.restore]'s
     * doc: `restore` has no env flag at all), so this exec session is the checkpoint's env's only
     * way back in.
     *
     * This process becomes the boot's new supervising attached child (the slot a plain restore
     * left `null` before this existed — see [bootOnce]'s doc): its own stdout/stderr are what
     * msb's own log capture records for THIS sandbox from here on (confirmed empirically — an
     * exec session's output lands in `exec.log`, served by `msb logs`/`-f`), so unlike every
     * other process this backend spawns, its combined-output tail below is used ONLY for a quick
     * early-exit diagnosis, never relied on as the workload's log source.
     *
     * A brief [WORKLOAD_EXEC_EARLY_EXIT_GRACE_MS] window (mirroring [awaitRunning]'s own
     * early-exit poll, at the same [READINESS_POLL_MS] interval) catches an immediately-broken
     * workload command without imposing a real wait — this is not a readiness check (the SANDBOX
     * is already Running; [GenericContainer]'s own wait strategy runs after this returns and is
     * what actually judges the workload) or a hang, just a chance for an instant crash-loop to
     * announce itself. Nonzero exit inside that window is always a classified failure surfacing
     * [argv] and the exec's own output; a clean exit 0 is classified exactly like an ordinary
     * attached run's own fast exit — [isCleanFastExit] rescues it when the sandbox's state
     * backs up "genuinely finished" (mirroring `stop()`/`wait`-strategy semantics for a container
     * whose command legitimately completes fast), the same failure path as an attached run's own
     * early exit otherwise. Surviving the window returns the still-live process, same shape
     * [awaitRunning] hands back for an ordinary boot.
     */
    private fun spawnWorkloadExecChild(handle: Handle, spec: ContainerSpec): Process {
        val argv = resolveWorkloadArgv(spec)
            ?: throw CheckpointMissingWorkloadCommandException(spec.checkpointRef!!)
        val proc = ProcessBuilder(listOf(msb.toString()) + MsbCommands.execWorkload(handle.id, spec.env, argv))
            .redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }
        val tail = ConcurrentLinkedDeque<String>()
        val drainer = drain(proc.inputStream) { tail.addLast(it); if (tail.size > TAIL_LINES) tail.removeFirst() }
        val deadline = System.currentTimeMillis() + WORKLOAD_EXEC_EARLY_EXIT_GRACE_MS
        while (System.currentTimeMillis() < deadline && proc.isAlive) {
            Thread.sleep(READINESS_POLL_MS)
        }
        if (!proc.isAlive) {
            runCatching { drainer.join(5_000) }
            val output = tail.joinToString("\n")
            if (proc.exitValue() == 0 && isCleanFastExit(handle)) return proc
            error("msb exec for sandbox ${handle.id}'s revived workload (${argv.joinToString(" ")}) exited " +
                "(code ${proc.exitValue()}) almost immediately after starting — the checkpoint's workload " +
                "command looks broken; output:\n$output")
        }
        return proc
    }

    /**
     * Decides the argv a restore revives, in priority order:
     * 1. [ContainerSpec.command] itself, when the checkpoint had an explicit one — the ordinary
     *    case: `GenericContainer.fromCheckpoint(cp)` pre-populates it from
     *    [dev.rightsize.core.CheckpointSpec.command], and `checkpoint()`'s own re-boot (
     *    [createCheckpoint]) passes the source container's own unchanged spec, which already
     *    carries whatever command it was started with.
     * 2. A workload cmdline CAPTURED AT CHECKPOINT TIME (see [captureWorkloadCmdline]) — only
     *    reachable when (1) is absent (the container ran its image's default entrypoint), read
     *    back from [dev.rightsize.core.checkpoint.CheckpointRegistry.readCapturedCommand] against
     *    [ContainerSpec.checkpointRef] — the SAME ref [createCheckpoint] persisted it under,
     *    whether or not that checkpoint was ever given a name (the store is keyed by ref alone;
     *    see its own doc for why).
     * 3. Neither present: `null`, which [spawnWorkloadExecChild] turns into
     *    [CheckpointMissingWorkloadCommandException] rather than booting an idle sandbox — an old
     *    registry (pre-dating capture support), a checkpoint whose capture failed at the time, or
     *    one made without ever going through this backend's own `createCheckpoint` (e.g. an
     *    imported archive) all end up here identically: nothing to revive with.
     */
    private fun resolveWorkloadArgv(spec: ContainerSpec): List<String>? {
        spec.command?.let { return it }
        val ref = spec.checkpointRef ?: return null
        return CheckpointRegistry(checkpointRegistryDir).readCapturedCommand(ref)
    }

    /**
     * Guest-side half of requirement (2)/(b) above: before [createCheckpoint] stops the source
     * sandbox, and ONLY when [ContainerSpec.command] is absent (an explicit command already
     * covers (a); there is nothing to discover), execs [WORKLOAD_CMDLINE_CAPTURE_SCRIPT] — a
     * small busybox-compatible `sh` script that walks every `/proc/[0-9]*` entry's own `stat`
     * file for the first process whose parent is PID 1 (field 4 == `1`) and whose own name isn't
     * `init.krun` (msb's guest agent/init) or a bracketed kernel-thread name, then prints that process's
     * `/proc/<pid>/cmdline` verbatim — NUL-separated argv, exactly how the kernel stores it — and
     * parses the result with [parseNulSeparatedCmdline].
     *
     * NEVER throws and NEVER fails the checkpoint on a miss: an exec failure, a nonzero exit (no
     * matching process found — [WORKLOAD_CMDLINE_CAPTURE_SCRIPT] itself exits 1 then), or empty/
     * unparseable output all fold into `null` here, exactly as a checkpoint that predates capture
     * support would look to a later restore ([CheckpointMissingWorkloadCommandException] surfaces
     * the gap then, not here) — [createCheckpoint] is what's responsible for not persisting a
     * `null` result.
     */
    private fun captureWorkloadCmdline(handle: Handle): List<String>? {
        val result = runCatching {
            exec(handle, listOf("sh", "-c", WORKLOAD_CMDLINE_CAPTURE_SCRIPT))
        }.getOrNull() ?: return null
        if (result.exitCode != 0) return null
        return parseNulSeparatedCmdline(result.stdout).takeIf { it.isNotEmpty() }
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
     * `GenericContainer`'s own bookkeeping touch) rather than duplicating its cleanup. This
     * method's OWN reboot step below is a different matter — see the fresh-name paragraph.
     *
     * Restoring is NOT `msb start`. Upstream, `msb start` is `Sandbox::start_detached`, and on
     * Windows the detached spawn passes `CREATE_BREAKAWAY_FROM_JOB` — a flag that fails with
     * `ERROR_ACCESS_DENIED` whenever msb runs inside a Windows job object that doesn't grant
     * breakaway rights, which is exactly the case when this JVM is itself a child of a Gradle
     * test process or a CI runner's job. That denial is deterministic, not transient, so no
     * retry ever clears it. `msb restore` (unlike `msb start`) passes no creation flags and
     * works everywhere, so the resume step here is instead: remove the stopped sandbox (its
     * disk state now lives entirely in the snapshot) and boot a fresh sandbox from that
     * snapshot under a FRESH generated name, keeping the same ports/memory limit (NOT env —
     * see [MsbCommands.restore]'s doc), via [spawnAndAwaitRunning] — the exact boot path [start]
     * itself uses, just fed a COPY of [handle]'s own spec with `name` rewritten and
     * `checkpointRef` set to the EFFECTIVE ref (`spawnAttachedRun` then routes to
     * `MsbCommands.restore`, emitting `restore <ref> --name <freshName>` in place of an ordinary
     * `run <image>` boot — never `--disk-only`, which msb 0.7.1 rejects for a disk-scope
     * snapshot; see that method's doc). [Handle.attached] ends up holding the revived workload's
     * `msb exec` child once this returns successfully (see [spawnWorkloadExecChild]'s doc) — a
     * plain `msb restore` boot is childless by design (see [awaitRestoreRunning]'s doc), but this
     * backend no longer leaves it that way: upstream's restore brings the sandbox up idle, so
     * [spawnAndAwaitRunning] itself starts the workload before returning.
     *
     * **Fresh name, not the original — and a FRESH name again on every retry, never the same one
     * twice.** msb's own existence check for a restore target is "DB record present OR on-disk
     * directory present", and on Windows the just-`rm`'d sandbox's directory has been observed on
     * CI outliving its DB record by seconds under load — a lag [awaitNameReleased] (which only
     * proves the DB record gone) was added to wait out, but a same-name restore issued into that
     * gap can still refuse "already exists" for as long as the directory lingers, which is not a
     * fixed bound. A restore under a FRESHLY GENERATED name — minted via
     * [dev.rightsize.nextSandboxName], the exact `rz-<runId>-<n>` generator/counter an ordinary
     * boot already uses, never a second naming scheme — never collides with the just-removed
     * sandbox's own lingering directory at all, sidestepping the lag entirely rather than racing
     * it. [awaitNameReleased] stays exactly as it was: harmless, dormant defense in depth (a
     * freshly generated name colliding with something else live is now vanishingly unlikely, not
     * the near-certainty a same-name restore risked), never removed.
     *
     * A fresh name is not immune to msb's OWN validated-then-failed restore, though: `msb restore
     * <name>` validates the snapshot artifact FIRST (an integrity failure exits 1 with no sandbox
     * record left at all), but a failure AFTER validation — a block-device open returning access
     * denied chief among them, Windows' `Access is denied. (os error 5)` — can still leave THAT
     * fresh name behind as a stopped sandbox record, and any retry under the SAME name then
     * collides with its own leftover exactly the way a same-name restore of the ORIGINAL name
     * used to (this was the actual CI failure the fresh-name fix above didn't yet cover: attempt 1
     * hit the access-denied error after msb had already created the record, and the old code's
     * same-name retry then burned its whole budget colliding with it). [restoreRetryingFreshName]
     * is what closes that gap: on EITHER msb's already-exists refusal ([SandboxNameCollisionException])
     * or the Windows access-denied signature ([RestoreAccessDeniedException]) — including from a
     * fresh name, not just the original — it best-effort `rm`s the just-failed name and mints
     * ANOTHER fresh name from the same generator before retrying, rather than ever retrying the
     * same name twice. The sandbox's NAME across a checkpoint was always an implementation detail,
     * never a documented contract (ports/env/memory/disk state all still carry over unchanged);
     * this is a behavior note, not an API break — see the CHANGELOG.
     *
     * Each attempt's name is what [handle]'s own `id`/`spec` are rewritten to IN PLACE before that
     * attempt runs (so every subsequent operation against [handle] — `GenericContainer.checkpoint()`'s
     * own post-return `stop()`/`exec()`/`logs()`, and this reboot's own
     * [awaitRestoreRunning]/[spawnWorkloadExecChild] steps — already target the name actually being
     * tried, whether or not that attempt ultimately succeeds), and it is what's appended to the
     * reaper's run ledger via [Reaper.beforeCreate] — BEFORE that attempt's own restore call, the
     * exact same append-before-create discipline [Reaper.beforeCreate]'s own doc describes for an
     * ordinary [SandboxBackend.create], repeated for every attempt (crash-safety: a process dying
     * mid-attempt leaves that attempt's name findable by the ledger's own not-found-tolerant
     * end-of-run sweep regardless of which attempt was live when it died). [startedNames] gets the
     * WINNING name only, once [restoreRetryingFreshName] actually returns — see that
     * method's own doc for why a classified (retried) failure needs no [startedNames] entry of its
     * own (its sandbox, if any, is already best-effort `rm`'d before the next attempt even starts)
     * while an UNCLASSIFIED failure (e.g. [CheckpointMissingWorkloadCommandException] after a
     * restore that itself succeeded) leaves its attempt's name tracked there regardless, for this
     * backend's own shutdown-hook/[close] cleanup net to reap a genuinely live orphan.
     *
     * The OLD (pre-checkpoint) name's ledger entry is deliberately left as-is throughout: it is
     * never removed here (this reboot bypasses the public [remove] the ledger's
     * `afterSandboxRemoved` call is normally paired with), so it is picked up by the ledger's own
     * not-found-tolerant sweep instead — attempting to reap a name that is already gone is exactly
     * what that sweep already tolerates for a concurrently-cleaned-up sandbox. Same for every
     * FAILED attempt's own name along the way: its best-effort `rm` above already covers the
     * common case, and the sweep is the backstop for whatever that `rm` itself missed.
     * `LiveContainers`' own re-keying is `GenericContainer`'s concern, not this backend's — see
     * `GenericContainer.checkpoint()`'s own doc.
     *
     * When [handle]'s own [ContainerSpec.command] is unset (the container ran its image's
     * default entrypoint), THIS method captures a workload cmdline from the guest — via
     * [captureWorkloadCmdline] — before [stop] ever runs, so the re-boot below has something to
     * revive with (see [resolveWorkloadArgv]'s priority order). A capture failure never fails the
     * checkpoint itself: it just means nothing is persisted, and a LATER restore of this same ref
     * throws [CheckpointMissingWorkloadCommandException] instead of booting silently idle — this
     * method's own immediate re-boot below hits that exact same wall if capture failed here, since
     * it goes through the identical [spawnAndAwaitRunning] path a separate restore would.
     *
     * `capabilities.checkpointRestartsWorkload = true` is exactly why: the rebooted workload
     * starts from scratch, so `GenericContainer.checkpoint()` re-applies the container's own
     * wait strategy before returning — now against a workload THIS re-boot has already revived,
     * not the idle sandbox upstream's restore alone would leave behind.
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
        // Guest-side capture, BEFORE stop — only when there's no explicit command to fall back
        // on; see captureWorkloadCmdline's/resolveWorkloadArgv's docs. A miss (null) is not an
        // error here: it just means nothing gets persisted below, and a later restore of this
        // ref surfaces CheckpointMissingWorkloadCommandException instead of booting idle.
        val capturedCommand = if (handle.spec.command == null) captureWorkloadCmdline(handle) else null
        val oldName = handle.id   // captured before stop/rm/rename below ever touch it
        stop(handle)
        val refPath = Path.of(ref)
        val destDir = if (refPath.isAbsolute) refPath.parent else null
        val snapshotName = if (refPath.isAbsolute) refPath.fileName.toString() else ref
        destDir?.let { Files.createDirectories(it) }
        val snap = invoke(MsbCommands.snapshotCreate(oldName, snapshotName, destDir), SNAPSHOT_TIMEOUT_SEC)
        if (snap.exitCode != 0) {
            error("msb snapshot create --from-sandbox $oldName $snapshotName failed (exit ${snap.exitCode}): " +
                "${snap.stderr.trim().ifEmpty { snap.stdout.trim() }} — sandbox $oldName is left " +
                "stopped; resume it by hand with `msb start $oldName`.")
        }
        val effectiveRef = parseSnapshotCreateArtifactPath(snap.stdout)
            ?: error("msb snapshot create --from-sandbox $oldName $snapshotName succeeded but its " +
                "output did not end with an absolute artifact path: '${snap.stdout.trim()}' — sandbox " +
                "$oldName is left stopped; resume it by hand with `msb start $oldName`.")
        // Persisted against the EFFECTIVE ref (not the input hint) — the exact ref a later
        // restore's spec.checkpointRef carries, whether this checkpoint is ever given a name or
        // not (CheckpointRegistry.writeCapturedCommand is keyed by ref alone; see its own doc).
        // Best-effort: a write failure here must not fail the checkpoint (it already succeeded);
        // the same gap it would otherwise leave is exactly what CheckpointMissingWorkloadCommandException
        // surfaces at restore time instead of a silent idle boot.
        if (capturedCommand != null) {
            runCatching { CheckpointRegistry(checkpointRegistryDir).writeCapturedCommand(effectiveRef, capturedCommand) }
        }
        invoke(MsbCommands.rm(oldName), STOP_TIMEOUT_SEC)
        // Windows-only in practice (same deferred-teardown lag family as RestoreAccessDeniedException's
        // file-handle release, see awaitNameReleased's own doc): `rm` above can return before the
        // sandbox record/name is actually released. Kept as dormant defense in depth even though the
        // fresh-name reboot below no longer depends on it (see this method's own class-level doc
        // paragraph on why) — never removed, per this class's don't-remove-working-defenses posture.
        awaitNameReleased(oldName)
        // Fresh generated name for the reboot's FIRST attempt — see this method's own doc for why
        // never oldName. Every retry beyond this first attempt mints its own fresh name too, from
        // inside restoreRetryingFreshName itself.
        val freshName = nextSandboxName()
        val freshSpec = handle.spec.copy(name = freshName, checkpointRef = effectiveRef)
        // Ledger append BEFORE the restore attempt, exactly like an ordinary create (see
        // Reaper.beforeCreate's own doc) — the old name's entry is deliberately left alone, for
        // the ledger's own not-found-tolerant sweep to pick up (see this method's own doc).
        // restoreRetryingFreshName repeats this same append for every later retry.
        Reaper.beforeCreate(this, freshSpec)
        // Rewritten in place before the reboot is even attempted, so every subsequent operation —
        // including this reboot's own awaitRestoreRunning/spawnWorkloadExecChild — already targets
        // the name actually being tried, whether or not that attempt ultimately succeeds (see this
        // method's own doc: a caller reading handle.id out of a caught exception still sees the
        // name actually attempted). Rewritten again the same way for every later retry.
        handle.spec = freshSpec
        // Tracked BEFORE the reboot is even attempted, same as the ledger append above — see this
        // method's own doc for why an unclassified failure (e.g. spawnWorkloadExecChild throwing
        // after a genuinely successful restore) needs this. oldName drops out in the same breath:
        // its sandbox was already `rm`'d above (see [invoke]'s call a few lines up), regardless of
        // how the reboot below turns out.
        startedNames -= oldName
        startedNames += freshName
        try {
            handle.attached = restoreRetryingFreshName(handle, freshSpec)
        } catch (e: Exception) {
            error("re-booting sandbox ${handle.id} from checkpoint $effectiveRef failed: ${e.message} — the " +
                "sandbox was removed but its state is preserved in checkpoint $effectiveRef, restorable via " +
                "GenericContainer.fromCheckpoint.")
        }
        return effectiveRef
    }

    /**
     * Sits between [createCheckpoint]'s own `rm` and its restore re-boot: polls `msb ls --format
     * json` until [name] is no longer listed at all, bounded by [CHECKPOINT_NAME_RELEASE_BUDGET_MS].
     * Confirmed against real Windows msb CI lanes (rust and kotlin alike): `msb rm <name>` can
     * return before the sandbox record/name is fully released on Windows — the same deferred-
     * teardown lag family as [RestoreAccessDeniedException]'s snapshot-file-handle release — and a
     * `restore` issued into that gap fails outright with msb's own `error: sandbox already exists:
     * sandbox '<name>' already exists; remove it, start the stopped sandbox, or recreate with
     * .replace()`, never even reaching the sandbox it's trying to recreate. Unix frees the name
     * synchronously with `rm` returning, so production callers there always clear this on the very
     * first poll below; the budget only ever gets spent on Windows.
     *
     * Checks first, sleeps [READINESS_POLL_MS] after — the same interval [awaitRunning]/
     * [awaitRestoreRunning] already poll at — so a name that's already free never pays even one
     * poll's delay. A name STILL listed once the budget elapses is treated as a genuinely stuck
     * teardown, not a race worth waiting out further: this throws naming the stuck sandbox rather
     * than looping forever or leaving the restore's own confusing already-exists error to surface
     * as the checkpoint's failure message instead.
     *
     * A FAILED probe never counts as a confirmed release. [invoke] only throws on a hard
     * timeout ([check]'s own `proc.waitFor` guard) — never on a nonzero `msb ls` exit code, which
     * it returns as an ordinary [ExecResult] like any other — so a nonzero exit is checked here
     * explicitly. And [MsbLsJson.isListed] (unlike [MsbLsJson.statusOf], whose `null` is
     * deliberately ambiguous between "not listed" and "couldn't parse" for callers that treat
     * both the same way) reports that ambiguity back as its own `null` rather than folding it into
     * "absent", so a transient daemon hiccup on `msb ls` itself — plausible precisely because
     * teardown is still in flight — polls again instead of reading as the name being free. Only an
     * exit-0, successfully-parsed listing that omits [name] is a confirmed release; anything else
     * (a nonzero exit, unparseable stdout, or the name still present) keeps polling.
     *
     * [createCheckpoint] now reboots under a freshly generated name rather than [name] itself, so
     * this wait no longer gates that reboot's own success the way it did when the restore target
     * WAS [name] — a fresh name can never collide with [name]'s own lingering directory. Kept
     * running regardless, as dormant defense in depth (see [createCheckpoint]'s own doc): still a
     * meaningful signal in its own right (whether msb's teardown of the source sandbox actually
     * completed), and removing it would be removing working code for no behavioral gain.
     */
    private fun awaitNameReleased(name: String) {
        val deadline = System.currentTimeMillis() + CHECKPOINT_NAME_RELEASE_BUDGET_MS
        while (true) {
            val ls = invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC)
            val confirmedAbsent = ls.exitCode == 0 && MsbLsJson.isListed(ls.stdout, name) == false
            if (confirmedAbsent) return
            if (System.currentTimeMillis() >= deadline) {
                error("sandbox $name still appeared in `msb ls` (or `msb ls` itself never confirmed it " +
                    "absent) ${CHECKPOINT_NAME_RELEASE_BUDGET_MS / 1000}s after `msb rm $name` — msb's own " +
                    "teardown of this name never completed, so restoring a fresh sandbox under it would only " +
                    "hit msb's own already-exists error; the sandbox's disk state is preserved in the " +
                    "just-created snapshot, restorable via GenericContainer.fromCheckpoint once the stuck " +
                    "name clears.")
            }
            Thread.sleep(READINESS_POLL_MS)
        }
    }

    /**
     * Bounded-BUDGET, FRESH-NAME-PER-ATTEMPT retry of [spawnAndAwaitRunning] for a `msb restore`
     * boot, for the two classified msb restore failures a Windows deferred-teardown lag (or the
     * POLICY v2 job-object denial below) can produce: msb's "sandbox already exists" refusal
     * ([SandboxNameCollisionException]) and its deferred snapshot-file-release access-denied error
     * ([RestoreAccessDeniedException]) — [spawnAndAwaitRunning] itself never retries either of
     * these (see its own doc on the [RestoreAccessDeniedException] catch), so THIS loop is what
     * classifies and retries them, for both this backend's restore boots:
     *
     * - [createCheckpoint]'s own reboot, whose [initialSpec] is ALREADY a freshly generated name
     *   (the source sandbox is gone by then — its own stopped/removed/no-live-handle-left doc
     *   explains why even the FIRST attempt can't reuse the pre-checkpoint name).
     * - [start]'s ordinary `GenericContainer.fromCheckpoint(cp).start()` restore, whose
     *   [initialSpec] is [handle]'s own ALREADY-LIVE, ALREADY-LEDGER-TRACKED original name (minted
     *   and `Reaper.beforeCreate`-appended by `GenericContainer.createStartedContainer` before this
     *   backend's `start()` is ever called) — this loop's first iteration attempts THAT name
     *   directly, unretried inline, exactly like an ordinary boot always has; only a CLASSIFIED
     *   failure on that first attempt ever mints a fresh name at all.
     *
     * Both callers share every mechanic below identically — the only difference between them is
     * which name attempt 1 is made under, which is exactly [initialSpec]'s own job.
     *
     * **Never retries the same name twice.** The CI failure this exists to fix: `msb restore
     * <name>` validates the snapshot artifact FIRST (an integrity failure exits 1 with no sandbox
     * record left behind at all), but a failure AFTER validation — the access-denied errno chief
     * among them — can still leave [name] itself behind as a STOPPED sandbox record, and any retry
     * under that SAME name then collides with its own leftover for as long as msb's teardown of it
     * takes (msb's own existence check is "DB record present OR on-disk directory present", and the
     * directory has been observed on Windows CI outliving the DB record by seconds under load — see
     * [awaitNameReleased]'s own doc for the same lag family). The old same-name retry burned its
     * whole budget colliding with exactly that leftover — for the ordinary `start()` path, that was
     * literally [spawnAndAwaitRunning]'s own now-removed inline cascade. So on EITHER classified
     * failure here, the just-failed name is best-effort `msb rm`'d (result ignored — whether there
     * was genuinely a record to remove is not this method's concern) and dropped from
     * [startedNames] (its own cleanup already attempted; a no-op the first time through for the
     * ordinary `start()` caller, whose attempt-1 name was never added there in the first place —
     * see [start]'s own doc), then a FRESH name is minted from the same
     * [dev.rightsize.nextSandboxName] generator/counter ordinary boots use, tracked in the reaper
     * ledger via [Reaper.beforeCreate] and in [startedNames] — both BEFORE that next attempt's own
     * restore call, the same append/track-before-create discipline an ordinary [create]/[start]
     * already uses (crash-safety: a process dying mid-retry still leaves the in-flight attempt's
     * name findable by the ledger's own not-found-tolerant end-of-run sweep) — and [handle.spec] is
     * rewritten to it in place before that next attempt runs. This is also the ENTIRE re-keying
     * story for a winning fresh name: [Handle.id] reads straight off this mutated [Handle.spec], so
     * every subsequent operation against [handle] — `exec`/`logs`/`stop`/`remove`, and (for
     * [createCheckpoint]'s own caller) `GenericContainer.checkpoint()`'s own `LiveContainers`
     * re-key — already targets the winner with no separate registry to fix up. For the ordinary
     * `start()` path specifically, `GenericContainer.start()` doesn't even need [checkpoint]'s own
     * `LiveContainers` fix-up: it registers `LiveContainers`/links networks/runs the wait strategy
     * only AFTER `backend.start(handle)` (this whole call) has already returned, reading the SAME,
     * by-then-already-mutated [handle] object — there is no earlier registration under the original
     * name for anything to fall out of sync with, unlike `checkpoint()`'s case where a PRIOR
     * `start()` call already registered the pre-checkpoint name before this reboot ever ran.
     *
     * Same wall-clock-BUDGET shape [spawnAndAwaitRunning]'s own install-lock retry uses
     * ([INSTALL_LOCK_RETRY_BUDGET_MS]/[INSTALL_LOCK_RETRY_DELAY_MS]) — a deadline, not an attempt
     * count, because how many distinct names a Windows teardown lag costs can't be sized up front
     * any more than how long it lasts can. [checkpointRebootAlreadyExistsBudgetMs] (production
     * default [CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_BUDGET_MS], ~30s) and
     * [CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS] are the SAME constants the old same-name
     * retry used, now bounding total retry time across as many distinct names as the budget allows
     * rather than repeated attempts under one — the SAME budget for either caller (see the class
     * doc's own paragraph on this seam). Exhaustion re-throws the last classified exception seen,
     * naming whichever attempt was live when the budget ran out.
     *
     * Any OTHER exception — including [CheckpointMissingWorkloadCommandException] from
     * [spawnWorkloadExecChild] after a restore that itself succeeded, or anything
     * [spawnAndAwaitRunning]'s own cascade already retried and converted to a generic failure
     * (install-lock, state-db, image-cache) — propagates immediately, unretried: that attempt's
     * name is deliberately left in [startedNames]/the ledger, since a live sandbox may already
     * exist under it for this backend's own cleanup net to reap.
     *
     * **POLICY v2 — the job-free broker escalation.** [RestoreAccessDeniedException] has a second,
     * distinct root cause from the deferred-file-release lag its own doc describes, discovered by
     * a live diagnostic campaign against msb-windows CI: msb's detached spawn on Windows always
     * passes `CREATE_BREAKAWAY_FROM_JOB` (see [createCheckpoint]'s own doc), and when THIS JVM
     * itself sits inside a Windows job object that does not grant breakaway rights — exactly what
     * wraps a Gradle test worker or a cargo-test binary under a CI runner's own job — that spawn is
     * denied `ERROR_ACCESS_DENIED` deterministically, not transiently, so no retry BUDGET, only a
     * different LAUNCH MECHANISM, ever clears it. `msb restore`'s own `--trace` output confirms
     * every stage up to `process_launch` succeeds first (the artifact resolves, the DB record is
     * inserted — hence the leftover stopped record this method's own already-exists handling
     * already covers — the disk grows), and only the spawn itself is denied; nothing is locked, no
     * file handle is held, the denial is the job object. This is a property of THIS JVM's own
     * process tree, not of which caller triggered the restore, so it applies identically whether
     * the denied attempt came from [createCheckpoint]'s reboot or an ordinary `start()`.
     *
     * The fix does not replace the fresh-name walk above — a job-object denial and a genuine
     * deferred-release race share the exact same [RestoreAccessDeniedException] wording, and this
     * method doesn't and can't tell which one it hit — it ESCALATES the loop's own LAUNCH mechanism
     * once that classification is seen at all: the FIRST attempt of any walk ([brokered] starts
     * `false`) is always a direct spawn, zero change to a healthy (non-job-object) environment.
     * From the first attempt that throws [RestoreAccessDeniedException] on a [windowsHost] onward —
     * [brokered] latches `true` and never resets for the rest of this walk — every subsequent
     * attempt's [spawnAndAwaitRunning] call passes `useBroker = true`, launching its `msb restore`
     * through [restoreBroker] (see [bootOnceBrokered]) instead of a direct spawn. Non-Windows never
     * brokers regardless of what this backend's own classifier matches — [windowsHost] gates
     * escalation exactly like every other Windows-only branch this class has (see the class doc).
     * Everything else about this loop — the fresh name per attempt, the best-effort `rm` of the
     * just-failed name, the shared wall-clock budget, the ledger/[startedNames] bookkeeping — is
     * completely unchanged; POLICY v2 only ever changes HOW an attempt is launched, never the
     * retry/naming policy around it.
     */
    private fun restoreRetryingFreshName(handle: Handle, initialSpec: ContainerSpec): Process? {
        val deadline = System.nanoTime() + checkpointRebootAlreadyExistsBudgetMs * 1_000_000
        var attemptSpec = initialSpec
        // POLICY v2: latches true on the first Windows RestoreAccessDeniedException this walk
        // hits and never resets — see this method's own doc paragraph above.
        var brokered = false
        while (true) {
            try {
                return spawnAndAwaitRunning(handle, attemptSpec, useBroker = brokered)
            } catch (e: Exception) {
                if (e !is SandboxNameCollisionException && e !is RestoreAccessDeniedException) throw e
                if (windowsHost && e is RestoreAccessDeniedException) brokered = true
                val failedName = attemptSpec.name
                runCatching { invoke(MsbCommands.rm(failedName), STOP_TIMEOUT_SEC) }
                startedNames -= failedName
                if (System.nanoTime() >= deadline) throw e
                Thread.sleep(CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS)
                val nextName = nextSandboxName()
                attemptSpec = attemptSpec.copy(name = nextName)
                Reaper.beforeCreate(this, attemptSpec)
                handle.spec = attemptSpec
                startedNames += nextName
            }
        }
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
     * Five concerns, each its own step: reject any UDP link outright, reject duplicate guest
     * ports, probe for `nc`, install the `/etc/hosts` aliases, then spawn one tunnel per link.
     */
    override fun installNetworkLinks(handle: SandboxHandle, links: List<NetworkLink>) {
        if (links.isEmpty()) return
        handle as Handle
        requireNoUdpLinks(links)
        requireNoDuplicateGuestPorts(links)
        requireAliasesAreValid(links)
        requireNcAvailable(handle)
        installHostsAliases(handle, links)
        links.forEach { handle.resources += ExecTunnel(msb, handle.id, it) }
    }

    /**
     * msb has no direct guest-to-guest networking at all — this backend's `Network` emulation is
     * a TCP exec-tunnel relay (see [ExecTunnel]), which has no UDP equivalent, so a UDP-tagged
     * link is rejected outright, before the duplicate-port/alias/`nc` checks below even run (a
     * UDP link can't be "fixed" by clearing any of those) — same
     * unsupported-with-remedy [UnsupportedByBackendException] shape as [requireNcAvailable]'s own
     * gap-naming guard. The remedy names both msb-compatible escape hatches: switch to the docker
     * backend for real container-to-container UDP, or — staying on msb — publish the UDP service
     * on a host port (`withExposedUdpPorts` + `getMappedUdpPort`) and have the consumer dial that
     * instead of a network alias. See docs/concepts/networking.md's UDP section.
     */
    private fun requireNoUdpLinks(links: List<NetworkLink>) {
        val udpLink = links.firstOrNull { it.protocol == PortProtocol.UDP } ?: return
        throw UnsupportedByBackendException(
            "container-to-container UDP network links (guest port ${udpLink.guestPort} on alias " +
                "'${udpLink.alias}') — microsandbox has no guest-to-guest networking to emulate this over",
            name,
            remedy = "run this test with RIGHTSIZE_BACKEND=docker for real container-to-container UDP, " +
                "or publish the UDP service on a host port with withExposedUdpPorts(...) + " +
                "getMappedUdpPort(...) and have the consumer dial that instead of a network alias",
        )
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
// spawnWorkloadExecChild's brief early-exit grace window — long enough to catch an immediately
// broken workload command, short enough to never look like a hang; the wait strategy that runs
// right after is what actually judges whether the workload came up.
private const val WORKLOAD_EXEC_EARLY_EXIT_GRACE_MS = 1_000L
// createCheckpoint's post-rm name-release wait (see awaitNameReleased's doc): a short wall-clock
// budget, not an attempt count, since presence/absence in `msb ls` is a state to observe, not a
// command to retry — READINESS_POLL_MS above is reused as the poll cadence, so this is the only
// new tuning knob the wait needs. Unix always clears this on the first poll; the budget is spent
// only on the Windows lag it exists for.
private const val CHECKPOINT_NAME_RELEASE_BUDGET_MS = 3_000L
// createCheckpoint's own fresh-name-per-attempt reboot retry (see
// restoreRetryingFreshName's doc): same wall-clock-budget shape as
// INSTALL_LOCK_RETRY_BUDGET_MS/INSTALL_LOCK_RETRY_DELAY_MS above, kept as its own constants since
// it classifies different msb failures than that pair does. msb 0.7.1's restore refuses "already
// exists" while EITHER the sandbox's DB record OR its on-disk directory still exists, and on
// Windows the directory has outlived the DB record — what awaitNameReleased's `ls` poll actually
// proves absent — by more than 3.5s under CI load; the same Windows deferred-teardown lag can also
// surface as a restore that fails AFTER msb validates the artifact (the access-denied errno) while
// still leaving a sandbox record behind under the name just tried. Either way this budget bounds
// total retry time across as many freshly minted names as it takes, not attempts under one name —
// an order of magnitude bigger than the observed lag, not a handful of attempts at a few hundred
// milliseconds.
private const val CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_BUDGET_MS = 30_000L
private const val CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS = 2_000L
// POLICY v2's broker escalation (see restoreRetryingFreshName's own doc and
// defaultRestoreBroker's): how long the broker SCRIPT itself (running on the far side of the WMI
// call) polls for the nested restore invocation's own exit-code file before giving up and letting
// this JVM's own subsequent msb-ls poll decide success instead (BrokerOutcome.LaunchedUnconfirmed).
private const val BROKER_EC_WAIT_BUDGET_SEC = 30
// How long THIS JVM waits for the broker script process itself (`powershell -NoProfile -File
// <script>`) to exit — comfortably above BROKER_EC_WAIT_BUDGET_SEC, since that's a bound the
// script polls INSIDE its own run, plus margin for powershell's own startup and the WMI round
// trip; a timeout here is broker INFRASTRUCTURE trouble (BrokerOutcome.BrokerFailure), not a
// restore classification.
private const val BROKER_PROCESS_TIMEOUT_SEC = 45L

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

/** The other boot failure [MsbCliBackend] retries for a `restore` boot specifically — the
 * spawned `msb restore` invocation hit Windows' deferred snapshot-file-release access-denied
 * signature (see [isRestoreAccessDenied]). Internal to the boot path, like its siblings above;
 * [MsbCliBackend.spawnAndAwaitRunning] owns the bounded retry policy. */
internal class RestoreAccessDeniedException(val output: String) :
    RuntimeException("msb restore access-denied (Windows deferred file release):\n$output")

/**
 * True if [output] (an `msb restore` invocation's combined stdout/stderr) carries Windows' own
 * deferred-file-release access-denied errno, immediately following [MsbCliBackend.createCheckpoint]'s
 * own stop -> snapshot create -> rm teardown of the source sandbox: msb's own docs describe
 * deferred file-handle release on Windows after a write, and a `restore` invocation reading the
 * just-written snapshot artifact a moment later can still race that release. Captured verbatim
 * from a windows-2025 hosted CI runner:
 *
 * ```
 * error: io error: Access is denied. (os error 5)
 * ```
 *
 * Deliberately requires BOTH the literal "Access is denied" phrase (English-locale Windows
 * wording rendered through FormatMessage — see [isSnapshotSaveAccessDenied]'s own note on why
 * that text alone is not relied on) AND either "io error" or "(os error 5)" (the locale-
 * independent framing Rust itself appends), the same conservative AND-of-two-markers discipline
 * [isSnapshotSaveAccessDenied] uses for its own, different msb subcommand — so a genuinely
 * unrelated permission denial is never misclassified as this specific, known-transient release
 * lag. This signature and [isSnapshotSaveAccessDenied]'s are deliberately kept as two separate
 * classifiers even though both key off the same Windows errno: they cover two different msb
 * subcommands (`restore` vs `snapshot save`) with two different remedies (a bounded retry of the
 * whole invocation here vs [salvageStagedArchive]'s salvage-by-rename there, since a `restore`
 * that fails this way has written nothing itself to salvage).
 */
internal fun isRestoreAccessDenied(output: String): Boolean =
    "Access is denied" in output && ("io error" in output || "(os error 5)" in output)

/**
 * POLICY v2's own broker seam ([MsbCliBackend.restoreBroker]) — launches `msb restore <argv>`
 * for a sandbox named [name] via [defaultRestoreBroker] in production, or a test's own stub. See
 * [MsbCliBackend]'s class doc (the `restoreBroker` paragraph) for why this exists at all, and
 * [BrokerOutcome] for what it returns. [argv] is the restore invocation's OWN argv (as
 * [MsbCommands.restore] builds it — `["restore", ref, "--name", name, ...]`), never including
 * [msb] itself; [msb] is passed separately since the broker script's own `&`-invocation needs it
 * quoted independently of the rest of [argv].
 */
internal typealias RestoreBroker = (msb: Path, argv: List<String>, name: String) -> BrokerOutcome

/**
 * Outcome of one [RestoreBroker] invocation. [MsbCliBackend.bootOnceBrokered] handles all three
 * variants; see its own doc for exactly how.
 */
internal sealed class BrokerOutcome {
    /**
     * The broker captured a definitive exit code and the restore invocation's own redirected
     * combined output (stdout+stderr) — classified by [MsbCliBackend.classifyRestoreExit] exactly
     * like a direct attempt's own exit/output would be, then (on a classified success) the same
     * `msb ls` poll to Running any exit-0 restore goes through
     * ([MsbCliBackend.pollRestoreRunning]).
     */
    data class Completed(val exitCode: Int, val output: String) : BrokerOutcome()

    /**
     * No exit-code file appeared within the broker script's own bound, but WMI's `Invoke-CimMethod`
     * itself reported the process launched (`ReturnValue` 0) — `msb restore` is activation-gated
     * (see [MsbCommands.restore]'s own doc: it exits once activation succeeds, well before the
     * sandbox itself necessarily reaches Running), so [MsbCliBackend.restoreRetryingFreshName]'s
     * own caller polls `msb ls` to Running afterward REGARDLESS of how the restore was launched —
     * this outcome just skips straight to that same poll rather than treating a missing exit-code
     * file as either a success or a failure on its own.
     */
    object LaunchedUnconfirmed : BrokerOutcome()

    /**
     * The broker INFRASTRUCTURE itself failed — `powershell.exe` missing or unreachable, the
     * script/temp-file write itself throwing, or WMI's own `Invoke-CimMethod` refusing to create
     * anything at all (a non-zero `ReturnValue`) — never a restore-attempt classification, since no
     * restore attempt was ever actually launched for this to classify. [reason] is diagnostic only,
     * never parsed. [MsbCliBackend.bootOnceBrokered] falls back to an ordinary DIRECT spawn for
     * this one attempt on this outcome (POLICY v2 §5: the broker must never become a new single
     * point of failure), without un-escalating the reboot's own `brokered` flag for later attempts.
     */
    data class BrokerFailure(val reason: String) : BrokerOutcome()
}

/**
 * Escapes [s] for embedding inside a PowerShell SINGLE-quoted string literal (`'...'`): doubling
 * an embedded single quote is PowerShell's own, only escape mechanism in that context (see
 * `about_Quoting_Rules`) — nothing else (backtick, `$`, double quotes) is special inside single
 * quotes, which is exactly why every interpolated token [buildBrokerScript] embeds is wrapped this
 * way rather than in a double-quoted literal. Used for every path/token POLICY v2's broker script
 * interpolates — the msb binary's own path, each argv token (including the checkpoint ref and the
 * sandbox name), and the broker's own scratch-file paths — even ones this backend itself
 * generated (sandbox names from [dev.rightsize.nextSandboxName] are already safe), per POLICY v2's
 * own "escape everything, trust nothing" posture (see [MsbCliBackend.restoreRetryingFreshName]'s
 * doc, POLICY v2 §4).
 */
internal fun powerShellSingleQuoted(s: String): String = "'" + s.replace("'", "''") + "'"

/**
 * Builds the PowerShell script POLICY v2's broker escalation runs via `powershell -NoProfile -File
 * <script>` (see [defaultRestoreBroker], which writes this to a unique temp file and runs it, and
 * [MsbCliBackend.restoreRetryingFreshName]'s own doc for the policy this implements).
 *
 * The script's job is exactly POLICY v2 §3: launch `<msb> <argv>` (the restore invocation this
 * backend would otherwise spawn directly) via `Invoke-CimMethod -ClassName Win32_Process
 * -MethodName Create` — WMI's own process-creation method, whose created child is parented by the
 * WMI provider host (`WmiPrvSE.exe`) rather than this JVM, so it escapes this JVM's own Windows job
 * object entirely, breakaway rights or not — then report back what happened:
 *
 * 1. The `CommandLine` WMI creates is itself a NESTED `powershell.exe -NoProfile -Command
 *    "& <quotedMsb> <quotedArgv> *> <quotedOutFile>; \`$LASTEXITCODE | Out-File ... <quotedEcFile>"`
 *    invocation — WMI's created process is what escapes the job, and this inner hop is what lets
 *    THAT process redirect msb's own combined output to [outFile] and report its own exit code to
 *    [ecFile], since `Invoke-CimMethod` itself neither waits for the child nor captures anything
 *    from it. Every interpolated token — [msb]'s path, each of [argv], [outFile], [ecFile] — is
 *    independently wrapped via [powerShellSingleQuoted]; the backtick before `$LASTEXITCODE` is
 *    what keeps THIS outer script's own double-quoted `CommandLine` literal from prematurely
 *    interpolating it before WMI ever sees the value (the nested `powershell -Command` is what's
 *    meant to evaluate it, once msb itself has actually exited).
 * 2. Prints the CIM call's own `ReturnValue`/`ProcessId` — [defaultRestoreBroker] parses
 *    `ReturnValue` as the broker-infrastructure signal (POLICY v2 §3b): non-zero means WMI itself
 *    refused to create anything, a [BrokerOutcome.BrokerFailure], never a restore classification.
 * 3. Polls (bounded, [BROKER_EC_WAIT_BUDGET_SEC]) for [ecFile] to appear, then prints both
 *    [ecFile]'s and [outFile]'s content between markers [defaultRestoreBroker] parses (POLICY v2
 *    §3c) — an [ecFile] that never appears within the bound is not itself a failure: the caller's
 *    OWN subsequent `msb ls` poll is what decides success from there (see
 *    [BrokerOutcome.LaunchedUnconfirmed]'s own doc).
 */
internal fun buildBrokerScript(msb: Path, argv: List<String>, outFile: Path, ecFile: Path): String {
    val quotedMsb = powerShellSingleQuoted(msb.toString())
    val quotedArgv = argv.joinToString(" ") { powerShellSingleQuoted(it) }
    val quotedOut = powerShellSingleQuoted(outFile.toString())
    val quotedEc = powerShellSingleQuoted(ecFile.toString())
    val innerCommand = "& $quotedMsb $quotedArgv *> $quotedOut"
    return """
        |${'$'}cimArgs = @{ CommandLine = "powershell.exe -NoProfile -Command `"$innerCommand; `${'$'}LASTEXITCODE | Out-File -FilePath $quotedEc -Encoding ascii -NoNewline`"" }
        |${'$'}result = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments ${'$'}cimArgs
        |Write-Output "CIM_RETURN=${'$'}(${'$'}result.ReturnValue)"
        |Write-Output "CIM_PID=${'$'}(${'$'}result.ProcessId)"
        |${'$'}deadline = (Get-Date).AddSeconds($BROKER_EC_WAIT_BUDGET_SEC)
        |while ((Get-Date) -lt ${'$'}deadline -and -not (Test-Path $quotedEc)) {
        |  Start-Sleep -Milliseconds 300
        |}
        |if (Test-Path $quotedEc) {
        |  Write-Output "EC_FILE_START"
        |  Get-Content -Path $quotedEc -Raw
        |  Write-Output "EC_FILE_END"
        |} else {
        |  Write-Output "EC_FILE_MISSING"
        |}
        |if (Test-Path $quotedOut) {
        |  Write-Output "OUT_FILE_START"
        |  Get-Content -Path $quotedOut -Raw
        |  Write-Output "OUT_FILE_END"
        |}
        |""".trimMargin()
}

/**
 * Production [RestoreBroker]: POLICY v2's job-free broker (see [MsbCliBackend]'s class doc and
 * [MsbCliBackend.restoreRetryingFreshName]'s own doc for the mechanics and when this is
 * invoked at all — only ever from that method's escalation, itself gated on [MsbCliBackend]'s own
 * `windowsHost`, so this never runs on a POSIX host in practice). Writes [buildBrokerScript]'s
 * output to a unique temp file — under `RUNNER_TEMP` when that env var names an existing
 * directory (keeps the broker's own scratch files inside whatever cleanup a CI runner already
 * gives its own temp dir; never required for correctness), `java.io.tmpdir` otherwise — runs it
 * via `powershell -NoProfile -File <script>`, and classifies the result:
 *
 * - the script itself failing to write, or `powershell` itself failing to start or exiting past
 *   [BROKER_PROCESS_TIMEOUT_SEC], is [BrokerOutcome.BrokerFailure] — broker infrastructure never
 *   got as far as attempting a restore at all.
 * - a parsed `CIM_RETURN` other than `0` (including unparseable) is also
 *   [BrokerOutcome.BrokerFailure]: WMI itself refused to create the process.
 * - an `ecFile` that appeared within the script's own [BROKER_EC_WAIT_BUDGET_SEC] bound is
 *   [BrokerOutcome.Completed], the parsed exit code paired with the `outFile` content as the
 *   restore's own combined output.
 * - `CIM_RETURN=0` but no parseable `ecFile` content is [BrokerOutcome.LaunchedUnconfirmed].
 *
 * Unique temp file names per call (the script itself, [outFile], [ecFile], named from [name] and
 * [System.nanoTime] together) — never reused across attempts, the same per-attempt-fresh
 * discipline [MsbCliBackend.restoreRetryingFreshName] already applies to sandbox names
 * themselves. Best-effort cleanup afterward in a `finally`: a leftover scratch file is nowhere
 * near the concern a leftover SANDBOX record is (see that method's own doc), so a cleanup failure
 * here is swallowed rather than surfaced.
 */
private fun defaultRestoreBroker(msb: Path, argv: List<String>, name: String): BrokerOutcome {
    val tmpDir = System.getenv("RUNNER_TEMP")
        ?.let { runCatching { Path.of(it) }.getOrNull() }
        ?.takeIf { runCatching { Files.isDirectory(it) }.getOrDefault(false) }
        ?: Path.of(System.getProperty("java.io.tmpdir"))
    val unique = "rz-broker-$name-${System.nanoTime()}"
    val script = tmpDir.resolve("$unique.ps1")
    val outFile = tmpDir.resolve("$unique.out.txt")
    val ecFile = tmpDir.resolve("$unique.ec.txt")
    try {
        try {
            Files.writeString(script, buildBrokerScript(msb, argv, outFile, ecFile))
        } catch (e: Exception) {
            return BrokerOutcome.BrokerFailure("failed to write the broker script: ${e.message}")
        }
        val output = try {
            val proc = ProcessBuilder("powershell", "-NoProfile", "-File", script.toString())
                .redirectErrorStream(true).start()
            runCatching { proc.outputStream.close() }
            val exited = proc.waitFor(BROKER_PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return BrokerOutcome.BrokerFailure(
                    "the broker script did not exit within ${BROKER_PROCESS_TIMEOUT_SEC}s")
            }
            proc.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            return BrokerOutcome.BrokerFailure("failed to launch the broker script: ${e.message}")
        }
        val cimReturn = Regex("CIM_RETURN=(-?\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        if (cimReturn != 0) {
            return BrokerOutcome.BrokerFailure(
                "WMI Win32_Process.Create did not report success (CIM_RETURN=$cimReturn): $output")
        }
        val ecContent = Regex("EC_FILE_START\\r?\\n(.*?)\\r?\\nEC_FILE_END", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.get(1)?.trim()
        val outContent = Regex("OUT_FILE_START\\r?\\n(.*?)\\r?\\nOUT_FILE_END", RegexOption.DOT_MATCHES_ALL)
            .find(output)?.groupValues?.get(1) ?: ""
        val exitCode = ecContent?.toIntOrNull()
        return if (exitCode != null) BrokerOutcome.Completed(exitCode, outContent) else BrokerOutcome.LaunchedUnconfirmed
    } finally {
        runCatching { Files.deleteIfExists(script) }
        runCatching { Files.deleteIfExists(outFile) }
        runCatching { Files.deleteIfExists(ecFile) }
    }
}

/**
 * A small busybox-compatible POSIX `sh` script ([MsbCliBackend.captureWorkloadCmdline] execs it
 * via `sh -c`) that walks every `/proc/[0-9]*` entry's own `stat` file for the first process
 * whose parent is PID 1 (the `stat` file's 4th whitespace-separated field, per `man proc`, AFTER
 * the `(comm)` parenthesized group — `comm` itself can contain spaces or parens, so this strips
 * from the first `(` to the LAST `)` before splitting the remainder on whitespace, the standard
 * shell-safe way to parse this file) whose own name is neither `init.krun` (msb's guest
 * agent/init — never the workload) nor a bracketed kernel-thread name (`[kworker/0:1]` and
 * similar — a kernel thread's own `comm` is literally wrapped in brackets), then prints that
 * process's own `/proc/<pid>/cmdline` verbatim: NUL-separated argv, exactly the shape the kernel stores it in,
 * parsed back out by [parseNulSeparatedCmdline]. No match at all (nothing but kernel threads and
 * the agent parented at 1 — should not happen for a running workload, but the script must never
 * hang or crash if it does) exits 1 with no output, which [captureWorkloadCmdline] folds into a
 * plain miss (`null`), the same as any other capture failure.
 *
 * Pure `sh` builtins only (`for`, `case`, parameter expansion, `set --`) — no `awk`/`grep`/`cut`,
 * so this runs unmodified in a minimal busybox-ash guest image with nothing else installed.
 */
internal val WORKLOAD_CMDLINE_CAPTURE_SCRIPT = """
    for d in /proc/[0-9]*; do
      [ -r "${'$'}d/stat" ] || continue
      stat=$(cat "${'$'}d/stat" 2>/dev/null) || continue
      rest=${'$'}{stat##*) }
      comm=${'$'}{stat#*(}
      comm=${'$'}{comm%)*}
      set -- ${'$'}rest
      ppid="${'$'}2"
      [ "${'$'}ppid" = "1" ] || continue
      case "${'$'}comm" in
        init.krun|\[*) continue ;;
      esac
      [ -r "${'$'}d/cmdline" ] || continue
      cat "${'$'}d/cmdline"
      exit 0
    done
    exit 1
""".trimIndent()

/**
 * Parses `cat /proc/<pid>/cmdline`'s own output — NUL-separated argv, no trailing NUL guaranteed
 * — as captured by [MsbCliBackend.captureWorkloadCmdline] and relayed through
 * [MsbCliBackend.invoke]'s line-buffered draining (which appends its own trailing `\n` after the
 * NUL-delimited blob, since the guest output itself carries no newline for `forEachLine` to stop
 * at). Splits on `\u0000` first, then trims any stray `\n`/`\r` off each token (only ever
 * possible on the LAST one, from that appended trailing newline) and drops empty tokens (the
 * blank tail after a trailing NUL, and the wholly-blank result of empty/unparseable input alike)
 * — an empty result either way is what tells [MsbCliBackend.captureWorkloadCmdline] to treat the
 * capture as a miss rather than an empty argv.
 */
internal fun parseNulSeparatedCmdline(output: String): List<String> =
    output.split('\u0000').map { it.trim('\n', '\r') }.filter { it.isNotEmpty() }

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
