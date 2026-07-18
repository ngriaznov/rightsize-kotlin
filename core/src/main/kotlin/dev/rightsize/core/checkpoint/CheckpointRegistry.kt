package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.SandboxBackend
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed store for named [Checkpoint]s, one file per name: `<cacheDir>/checkpoints/<name>.json`
 * (see docs/checkpoints.md's "Reusing checkpoints across runs" section). [write] is
 * tmp-in-directory-then-atomic-rename — the same crash-safe idiom
 * [dev.rightsize.core.reuse.ReuseRegistry]/`dev.rightsize.core.reaper.RunLedger.writeAtomic`
 * already use, so a reader never observes a partially written record.
 *
 * Also home to the actual `Checkpoint.find`/`list`/`remove` policy those companion functions
 * delegate to, constructed here against an explicit [cacheDir] and [SandboxBackend] rather than
 * `CacheDir.resolve()`/`Backends.active()` directly — the same split
 * [dev.rightsize.core.reaper.Sweeper] uses against `Reaper`'s real-process wiring, so unit tests
 * exercise the whole find/list/remove policy against fakes and a temp directory.
 */
class CheckpointRegistry(cacheDir: Path) {
    private val dir = cacheDir.resolve("checkpoints")

    /** Every other method funnels through this to turn [name] into a path — [validateCheckpointName]
     * runs here too (not just at `GenericContainer.checkpoint(name)`'s write-path call site), so
     * `find`/`remove`/`read`/`write`/`delete` all reject a non-matching (e.g. path-traversal-shaped,
     * `"../secret"`) [name] at this single boundary, in depth alongside any validation a caller
     * already did — defense in depth, since a caller-side check is only as good as every call site
     * actually running it (see docs/checkpoints.md's path-traversal note). */
    fun file(name: String): Path {
        validateCheckpointName(name)
        return dir.resolve("$name.json")
    }

    /** `null` for "no record" and "record present but unparseable" alike — both mean the same
     * thing to a caller deciding whether to trust it. */
    fun read(name: String): CheckpointRecord? {
        val f = file(name)
        if (!Files.exists(f)) return null
        return runCatching { Files.readString(f) }.getOrNull()?.let(CheckpointRecord::parse)
    }

    fun write(name: String, record: CheckpointRecord) {
        // Resolved (and thus validated, via [file]) before any filesystem write, so an invalid
        // name never leaves a stray temp file behind in [dir].
        val target = file(name)
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, name, ".tmp")
        Files.writeString(tmp, record.toJson())
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Best-effort: a record that's already gone (raced away by another process, or never
     * written) is not an error. */
    fun delete(name: String) {
        runCatching { Files.deleteIfExists(file(name)) }
    }

    /**
     * `Checkpoint.find(name)`'s actual logic (see docs/checkpoints.md): no entry -> `null`. A
     * corrupt entry is treated the same as absent, with the bad file best-effort removed (via
     * [delete], itself a no-op when there's nothing to remove — so this costs nothing extra on
     * the plain "no entry" path either). An entry recorded under a DIFFERENT [backend] than the
     * one passed in is returned WITHOUT probing — the existing restore-time mismatch gate
     * (`CheckpointBackendMismatchException`, checked at `GenericContainer.start()`) stays the
     * authority on whether it's actually restorable, and this must never force-resolve a backend
     * the host may not even have. Otherwise the artifact is probed via
     * [SandboxBackend.hasCheckpoint]: gone means the entry is stale, so it's deleted here too and
     * `null` is returned. A probe FAILURE (as opposed to a definite "gone", which
     * [SandboxBackend.hasCheckpoint]'s own contract requires distinguishing) is not caught here —
     * it propagates, since only "definitely absent" is allowed to resolve to `null`.
     */
    fun find(backend: SandboxBackend, name: String): Checkpoint? {
        val record = read(name)
        if (record == null) { delete(name); return null }
        if (!record.backend.equals(backend.name, ignoreCase = true)) return record.toCheckpoint()
        if (!backend.hasCheckpoint(record.ref)) { delete(name); return null }
        return record.toCheckpoint()
    }

    /** `Checkpoint.list()`'s actual logic: registry contents only, no artifact probing — a stale
     * entry (backend-side artifact gone) can still show up here; only [find]/[remove] resolve
     * staleness. A corrupt entry is skipped, not surfaced. */
    fun list(): List<Checkpoint> {
        if (!Files.isDirectory(dir)) return emptyList()
        val files = runCatching {
            Files.list(dir).use { it.filter { p -> p.toString().endsWith(".json") }.toList() }
        }.getOrDefault(emptyList())
        return files
            .mapNotNull { f -> runCatching { Files.readString(f) }.getOrNull()?.let(CheckpointRecord::parse) }
            .map { it.toCheckpoint() }
    }

    /**
     * Reverse lookup: the full record of the entry (if any) whose [CheckpointRecord.ref] and
     * [CheckpointRecord.backend] equal [ref]/[backendName] — used by
     * [dev.rightsize.core.checkpoint.CheckpointArchiver.exportArchive] to recover a checkpoint's
     * `name`/`createdIso` when exporting a bare `Checkpoint`, which carries neither itself. Same
     * no-artifact-probing posture as [list] (this is metadata-only, not a staleness check); `null`
     * when nothing matches, including the ordinary case of an unnamed checkpoint that was never
     * registered under any name at all.
     */
    fun findByRef(ref: String, backendName: String): CheckpointRecord? {
        if (!Files.isDirectory(dir)) return null
        val files = runCatching {
            Files.list(dir).use { it.filter { p -> p.toString().endsWith(".json") }.toList() }
        }.getOrDefault(emptyList())
        return files.firstNotNullOfOrNull { f ->
            runCatching { Files.readString(f) }.getOrNull()?.let(CheckpointRecord::parse)
                ?.takeIf { it.ref == ref && it.backend.equals(backendName, ignoreCase = true) }
        }
    }

    /**
     * `Checkpoint.remove(name)`'s actual logic: best-effort backend-artifact removal (via
     * [SandboxBackend.removeCheckpoint], itself best-effort/idempotent on both real backends)
     * plus deleting the registry file. The artifact-removal call is gated on [record]'s `backend`
     * matching [backend] — the same same-backend gate [find] applies — since the active backend
     * has no business operating on a ref format it didn't create (an msb snapshot name means
     * nothing to `docker rmi`, and vice versa). When they differ, the removal call is skipped
     * outright and that artifact is left behind under its original backend (see
     * docs/checkpoints.md's cross-run section for the manual cleanup one-liner). The registry
     * entry is deleted either way. "Not found" anywhere is success — returns whether a registry
     * entry actually existed, so a caller can tell an already-clean name from one it just
     * cleaned; either way, calling this again on the same [name] is always safe.
     */
    fun remove(backend: SandboxBackend, name: String): Boolean {
        val record = read(name)
        if (record != null && record.backend.equals(backend.name, ignoreCase = true)) {
            runCatching { backend.removeCheckpoint(record.ref) }
        }
        delete(name)
        return record != null
    }
}
