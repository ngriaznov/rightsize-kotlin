package dev.rightsize.core.reaper

import dev.rightsize.RunId
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.SandboxBackend
import java.io.OutputStream
import java.nio.file.Path

/**
 * The actual reaping-coordination logic, factored out of the [Reaper] singleton so it can be
 * unit-tested directly against a temp [cacheDir] and fake backends — the same
 * injectable-parameter pattern the rest of this package uses. [Reaper] holds exactly one
 * instance of this, built from real process state; this class itself never reads an
 * environment variable or the real cache dir.
 *
 * Only participates for a backend whose name maps to the ledger's own "msb"/"docker"
 * vocabulary (see [canonicalBackendId]) — a test double or future backend with any other name
 * has no defined ledger behavior, so every method here is a no-op for one. This is also what
 * keeps `FakeBackend`-based tests elsewhere (`GenericContainerTest`, etc.) free of any
 * filesystem/process side effects from routing through here: those fakes are named
 * "fake"/"port-conflict"/etc., never "msb"/"docker".
 */
class ReaperEngine(
    private val mode: ReaperMode,
    private val cacheDir: Path,
    private val pid: Long,
    private val startedIso: String,
) {
    @Volatile private var swept = false
    @Volatile private var ledger: RunLedger? = null
    @Volatile private var watchdogPipe: OutputStream? = null

    private fun participates(backend: SandboxBackend) =
        canonicalBackendId(backend.name).let { it == "msb" || it == "docker" }

    /** Runs the init-time sweep exactly once. Called from
     * [dev.rightsize.core.Backends.active] (via [Reaper]) right after a backend resolves. */
    fun onBackendResolved(backend: SandboxBackend) {
        if (mode == ReaperMode.OFF || !participates(backend) || swept) return
        synchronized(this) {
            if (swept) return
            swept = true
            runCatching { Sweeper.sweep(cacheDir, RunId.value, backend) }
        }
    }

    /** Ledger append, then (only on this process's very first sandbox) the watchdog spawn.
     * Called right before `backend.create(spec)`. */
    fun beforeCreate(backend: SandboxBackend, spec: ContainerSpec) {
        if (mode == ReaperMode.OFF || spec.keepAlive || !participates(backend)) return
        val l = ledgerFor(backend)
        l.beforeSandboxCreate(spec)   // ensures the run record exists on disk first
        if (mode == ReaperMode.ON) spawnWatchdogOnce(backend, l)
    }

    /** Called after a *successful* stop+remove — never for a remove that may have failed and
     * left the sandbox actually running, which would violate the ledger's superset invariant. */
    fun afterRemove(spec: ContainerSpec) {
        if (spec.keepAlive) return
        ledger?.afterSandboxRemoved(spec.name)
    }

    fun beforeNetworkCreate(backend: SandboxBackend, networkId: String) {
        if (mode == ReaperMode.OFF || !participates(backend)) return
        ledgerFor(backend).beforeNetworkCreate(networkId)
    }

    fun afterNetworkRemoved(networkId: String) {
        ledger?.afterNetworkRemoved(networkId)
    }

    /** Best-effort ledger cleanup at process shutdown — a backstop alongside the
     * last-entry-removed deletion [RunLedger] already does on its own. */
    fun onProcessExit() {
        runCatching { ledger?.deleteFiles() }
    }

    private fun ledgerFor(backend: SandboxBackend): RunLedger = ledger ?: synchronized(this) {
        ledger ?: run {
            val canonical = canonicalBackendId(backend.name)
            RunLedger(
                runId = RunId.value, cacheDir = cacheDir, pid = pid, startedIso = startedIso,
                backend = canonical,
                msbPath = if (canonical == "msb") backend.watchdogCommands.sandboxRemove.firstOrNull() else null,
            ).also { ledger = it }
        }
    }

    private fun spawnWatchdogOnce(backend: SandboxBackend, l: RunLedger) {
        if (watchdogPipe != null) return
        synchronized(this) {
            if (watchdogPipe != null) return
            watchdogPipe = runCatching {
                Watchdog.spawn(cacheDir, l.sandboxesFile, l.networksFile, l.recordFile, backend.watchdogCommands)
            }.getOrNull()
        }
    }
}
