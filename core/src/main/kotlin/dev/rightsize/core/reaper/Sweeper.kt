package dev.rightsize.core.reaper

import dev.rightsize.core.SandboxBackend
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The init-time sweep (see docs/reaping.md): for every run record json file under [cacheDir]'s
 * `runs/` directory except
 * [ownRunId], reaps that run's sandboxes/networks through [backend] if the run is dead —
 * never touches its own run, a still-alive run, or anything not literally listed in a dead
 * run's `.sandboxes`/`.networks` files. Runs once per process, from
 * `Backends.active()`/`Reaper`; this function itself is stateless and safe to call more than
 * once (idempotent — a record it already reaped is simply gone on the next call).
 *
 * A dead run's own recorded `backend` field is only reaped by a process running that SAME
 * backend (`canonicalBackendId(backend.name)` — see [canonicalBackendId]'s doc for why that's
 * not simply [SandboxBackend.name]): a docker process cannot remove msb sandboxes, so a
 * cross-backend leftover is left in place for a process on that backend to sweep later.
 */
object Sweeper {
    private val UNPARSEABLE_GRACE: Duration = Duration.ofHours(1)

    fun sweep(cacheDir: Path, ownRunId: String, backend: SandboxBackend, now: Instant = Instant.now()) {
        val runsDir = cacheDir.resolve("runs")
        if (!Files.isDirectory(runsDir)) return
        val recordFiles = runCatching {
            Files.list(runsDir).use { it.filter { p -> p.fileName.toString().endsWith(".json") }.toList() }
        }.getOrDefault(emptyList())
        recordFiles.forEach { recordFile ->
            val runId = recordFile.fileName.toString().removeSuffix(".json")
            if (runId != ownRunId) sweepOne(runsDir, runId, recordFile, backend, now)
        }
    }

    private fun sweepOne(runsDir: Path, runId: String, recordFile: Path, backend: SandboxBackend, now: Instant) {
        val text = runCatching { Files.readString(recordFile) }.getOrNull() ?: return
        val record = RunRecord.parse(text)
        if (record == null) {
            handleUnparseable(runsDir, runId, recordFile, now)
            return
        }
        if (!record.backend.equals(canonicalBackendId(backend.name), ignoreCase = true)) return
        if (Liveness.isAlive(record.pid, record.startedIso)) return
        reap(runsDir, runId, backend)
    }

    /** A record that fails to parse might just be mid-write by its still-alive owner (the
     * write is atomic-by-rename, but a reader could in principle observe a stale directory
     * listing on some filesystem/timing edge case) — young files are left alone. Anything
     * older than [UNPARSEABLE_GRACE] is presumed abandoned (crash mid-write, or hand-edited)
     * and cleaned up; there's nothing else usable to reap from it (no readable sandbox list). */
    private fun handleUnparseable(runsDir: Path, runId: String, recordFile: Path, now: Instant) {
        val age = runCatching { Duration.between(Files.getLastModifiedTime(recordFile).toInstant(), now) }
            .getOrDefault(Duration.ZERO)
        if (age < UNPARSEABLE_GRACE) return
        deleteRunFiles(runsDir, runId)
    }

    private fun reap(runsDir: Path, runId: String, backend: SandboxBackend) {
        readLines(runsDir.resolve("$runId.sandboxes")).forEach { name ->
            runCatching { backend.removeByName(name) }
        }
        readLines(runsDir.resolve("$runId.networks")).forEach { net ->
            runCatching { backend.removeNetwork(net) }
        }
        deleteRunFiles(runsDir, runId)
    }

    private fun readLines(file: Path): List<String> =
        runCatching { Files.readAllLines(file).filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun deleteRunFiles(runsDir: Path, runId: String) {
        listOf("$runId.json", "$runId.sandboxes", "$runId.networks")
            .forEach { runCatching { Files.deleteIfExists(runsDir.resolve(it)) } }
    }
}
