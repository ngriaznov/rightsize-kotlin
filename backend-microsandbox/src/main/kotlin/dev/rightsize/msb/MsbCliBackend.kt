package dev.rightsize.msb

import dev.rightsize.RunId
import dev.rightsize.core.*
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
        const val EXEC_TIMEOUT_SEC = 120L
        const val LOGS_TIMEOUT_SEC = 30L
        const val ATTACHED_PROC_STOP_TIMEOUT_SEC = 10L
        const val READER_JOIN_TIMEOUT_MS = 2000L
        const val TAIL_LINES = 50
    }

    private val startedNames = ConcurrentHashMap.newKeySet<String>()
    private val windowsHost = Platform.current()?.isWindows == true
    init { Runtime.getRuntime().addShutdownHook(Thread { startedNames.forEach { silently(it) } }) }

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
     */
    override fun start(handle: SandboxHandle) {
        handle as Handle
        handle.attached = spawnAndAwaitRunning(handle)
        startedNames += handle.id
    }

    /**
     * Boots via [bootOnce]; on a first failure carrying msb's image-cache-corruption
     * signature, heals by removing just the affected image's cache entry
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
    private fun spawnAndAwaitRunning(handle: Handle): Process {
        val firstOutput = try {
            return bootOnce(handle)
        } catch (first: ImageCacheCorruptionException) {
            first.output
        }
        val heal = runCatching { invoke(MsbCommands.imageRemove(handle.spec.image), STOP_TIMEOUT_SEC) }
        try {
            return bootOnce(handle)
        } catch (second: ImageCacheCorruptionException) {
            error("msb run for sandbox ${handle.id} hit its image cache error twice in a row for image " +
                "'${handle.spec.image}', even after removing that image's cache entry (${describeHeal(heal)}) " +
                "and retrying — this is likely a deeper cache corruption than this backend's one-shot heal " +
                "covers; try clearing the msb image cache by hand (`msb image prune` or removing the cache " +
                "directory under MSB_HOME).\nfirst attempt:\n$firstOutput\nafter heal + retry:\n${second.output}")
        }
    }

    /**
     * One boot attempt: spawns the attached `msb run` child and waits for Running. On any
     * failure the child is reaped here (for the classified early-exit failures it has
     * already exited; a readiness timeout leaves it alive and it is force-killed), so a
     * failed attempt leaves no live process behind — the caller owns retry policy, never
     * cleanup.
     */
    private fun bootOnce(handle: Handle): Process {
        val (proc, tail) = spawnAttachedRun(handle)   // ATTACHED mode: -d never runs the ENTRYPOINT
        try {
            awaitRunning(handle, proc, tail)          // readiness = name Running in `msb ls`
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
     * Spawns `msb run`, draining its combined output to a tail kept for diagnostics only —
     * msb's own boot output (registry/pull errors, a crash before the sandbox exists), never
     * the workload's. [logs] never reads from this tail; it always shells out to `msb logs`.
     */
    private fun spawnAttachedRun(handle: Handle): Pair<Process, ConcurrentLinkedDeque<String>> {
        val proc = ProcessBuilder(listOf(msb.toString()) + MsbCommands.run(handle.spec))
            .redirectErrorStream(true).start()
        runCatching { proc.outputStream.close() }   // no host stdin to forward; avoid EOF-wait stalls
        val tail = ConcurrentLinkedDeque<String>()
        drain(proc.inputStream) { tail.addLast(it); if (tail.size > TAIL_LINES) tail.removeFirst() }
        return proc to tail
    }

    /** Polls until [handle] reaches Running, or fails fast if the `msb run` child exits first.
     * An early exit is classified from the child's combined output: the image-cache-corruption
     * signature throws [ImageCacheCorruptionException] (the one failure [spawnAndAwaitRunning]
     * heals and retries), a host-port bind conflict throws [PortBindConflictException], and
     * anything else surfaces the raw output. */
    private fun awaitRunning(handle: Handle, proc: Process, tail: ConcurrentLinkedDeque<String>) {
        val deadline = System.currentTimeMillis() + FIRST_RUN_PULL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val output = tail.joinToString("\n")
                if (isImageCacheCorruption(output)) throw ImageCacheCorruptionException(output)
                if (isPortBindConflict(output)) {
                    throw PortBindConflictException(
                        "msb run for sandbox ${handle.id} could not bind a host port: $output")
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

    /** Remove leftover rz-* sandboxes from crashed earlier runs (not this run's). */
    fun sweepOrphans() {
        val out = invoke(MsbCommands.ls(), LOGS_TIMEOUT_SEC).stdout
        Regex("\\brz-[0-9a-f]{8}-\\d+\\b").findAll(out)
            .map { it.value }.distinct()
            .filterNot { it.startsWith("rz-${RunId.value}-") }
            .forEach { silently(it) }
    }

    private fun silently(name: String) {
        runCatching { invoke(MsbCommands.stop(name), STOP_TIMEOUT_SEC) }
        runCatching { invoke(MsbCommands.rm(name), STOP_TIMEOUT_SEC) }
    }

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
