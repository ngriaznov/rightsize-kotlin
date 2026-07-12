package dev.rightsize.core.reaper

import dev.rightsize.core.ContainerSpec
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * The ownership ledger for one process's sandboxes/networks — see docs/reaping.md. Three files
 * under `<cacheDir>/runs/`: `<runId>.json` (this run's identity, written atomically before the
 * first sandbox), `<runId>.sandboxes` and `<runId>.networks` (one name per line, appended
 * before creation and removed after a successful stop/remove — always a superset of what's
 * actually live). Deletes all three once both lists are empty (the clean-shutdown case) and
 * transparently recreates them on the next sandbox, matching the cross-language contract.
 *
 * One instance per process in production (see `Reaper`); tests construct their own against a
 * temp [cacheDir] with an injected [pid]/[startedIso], the same injectable-parameter pattern
 * `MsbProvisioner.ensureInstalled` uses. The only concurrency contract this class assumes is
 * "single process, single instance, many threads" (guarded here with an in-process lock) —
 * per the spec, only the owning process ever writes its own ledger files.
 */
class RunLedger(
    val runId: String,
    cacheDir: Path,
    private val pid: Long,
    private val startedIso: String,
    private val backend: String,
    private val msbPath: String?,
) {
    private val lock = Any()
    private val runsDir = cacheDir.resolve("runs")
    val recordFile: Path = runsDir.resolve("$runId.json")
    val sandboxesFile: Path = runsDir.resolve("$runId.sandboxes")
    val networksFile: Path = runsDir.resolve("$runId.networks")

    private var recordWritten = false
    private val sandboxNames = mutableListOf<String>()
    private val networkIds = mutableListOf<String>()

    /** Appends [ContainerSpec.name] before the backend `create` call. A `keepAlive` spec is
     * never tracked at all — not even the run record is written for it alone — so a reuse
     * sandbox stays structurally invisible to any sweep. */
    fun beforeSandboxCreate(spec: ContainerSpec) {
        if (spec.keepAlive) return
        synchronized(lock) {
            ensureRecordWritten()
            sandboxNames += spec.name
            appendLine(sandboxesFile, spec.name)
        }
    }

    /** Removes [name] after a successful stop+remove; a name not currently tracked (already
     * removed, or a `keepAlive` spec that was never added) is a no-op. Deletes the ledger's
     * files once both the sandboxes and networks lists are empty. */
    fun afterSandboxRemoved(name: String) {
        synchronized(lock) {
            if (!sandboxNames.remove(name)) return
            rewrite(sandboxesFile, sandboxNames)
            cleanupIfEmpty()
        }
    }

    /** Appends [networkId] before the backend `ensureNetwork` call; idempotent for a network
     * already tracked (containers on the same network each call this once per `start()`). */
    fun beforeNetworkCreate(networkId: String) {
        synchronized(lock) {
            if (networkId in networkIds) return
            ensureRecordWritten()
            networkIds += networkId
            appendLine(networksFile, networkId)
        }
    }

    /** Removes [networkId] after a successful `removeNetwork`; see [afterSandboxRemoved]. */
    fun afterNetworkRemoved(networkId: String) {
        synchronized(lock) {
            if (!networkIds.remove(networkId)) return
            rewrite(networksFile, networkIds)
            cleanupIfEmpty()
        }
    }

    /** Best-effort deletion of all three files regardless of remaining entries — the
     * process-exit backstop alongside the last-entry-removed deletion above (the backend's own
     * `close()` has already force-removed anything left by the time this runs). Resets this
     * instance so a later sandbox on the same process re-writes a fresh record. */
    fun deleteFiles() {
        synchronized(lock) {
            Files.deleteIfExists(sandboxesFile)
            Files.deleteIfExists(networksFile)
            Files.deleteIfExists(recordFile)
            recordWritten = false
            sandboxNames.clear()
            networkIds.clear()
        }
    }

    private fun cleanupIfEmpty() {
        if (sandboxNames.isEmpty() && networkIds.isEmpty()) deleteFiles()
    }

    private fun ensureRecordWritten() {
        if (recordWritten) return
        Files.createDirectories(runsDir)
        writeAtomic(recordFile, RunRecord(pid, startedIso, backend, msbPath).toJson())
        recordWritten = true
    }

    private fun appendLine(file: Path, line: String) {
        Files.createDirectories(runsDir)
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    /** Rewrites [file] to hold exactly [lines] (used after removing one entry); an empty
     * result deletes the file rather than leaving a zero-byte one behind. */
    private fun rewrite(file: Path, lines: List<String>) {
        if (lines.isEmpty()) { Files.deleteIfExists(file); return }
        writeAtomic(file, lines.joinToString("") { "$it\n" })
    }

    /** tmp-in-[runsDir]-then-[StandardCopyOption.ATOMIC_MOVE] — same idiom
     * `MsbProvisioner`'s install already uses for a crash-safe write, and required by the
     * spec for the run record specifically ("written atomically (tmp + rename)"). */
    private fun writeAtomic(file: Path, content: String) {
        Files.createDirectories(runsDir)
        val tmp = Files.createTempFile(runsDir, file.fileName.toString(), ".tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
