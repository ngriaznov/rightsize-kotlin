package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.SandboxBackend
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

    /**
     * Internal-only, REF-keyed companion store to the named-checkpoint entries above: persists
     * the workload command a checkpointing backend recovers from the guest before stopping a
     * container whose [dev.rightsize.core.ContainerSpec.command] was never set (the container ran
     * its image's default entrypoint — see the microsandbox backend's `createCheckpoint`, the only
     * caller of [writeCapturedCommand] today, since Docker's checkpoint never stops the workload in
     * the first place). Keyed by [ref] ALONE, never by a checkpoint's `name` — a checkpoint made
     * WITHOUT one (a bare `GenericContainer.checkpoint()`, restored later via a fresh
     * `GenericContainer.fromCheckpoint(cp)`) still needs its captured command recoverable by ref
     * alone, since that path never touches [write]'s name-keyed entries at all.
     *
     * Lives under [dir]`/.workload-cmdline`, filed under `sha256(ref)` — [ref] itself (an absolute
     * snapshot artifact path on msb) is not a safe/portable filename component on every host
     * filesystem, and hashing sidesteps that without an escaping scheme — never under [dir] itself,
     * so an entry here NEVER shows up in [list], is NEVER returned by [find]/[findByRef], and adds
     * no new key to a named checkpoint's own `<name>.json` — the public `Checkpoint`/`CheckpointSpec`
     * shape and every existing registry file's format are completely untouched by this store, which
     * is what makes it purely additive: an old build that has never heard of this method reads and
     * writes exactly the files it always has, and a directory holding only pre-upgrade `<name>.json`
     * files behaves as if `readCapturedCommand` always returns `null`, the same "not captured" answer
     * a checkpoint predating this feature should give.
     *
     * Both methods are best-effort by design (the write side is meant to be wrapped in
     * `runCatching` by its one caller: a capture failure must never fail the checkpoint itself) —
     * [writeCapturedCommand] silently does nothing further than what [Files] itself throws for
     * (propagated, not swallowed, so a caller choosing not to catch it still finds out), and
     * [readCapturedCommand] returns `null` for "never captured" and "captured but now unreadable/
     * corrupt" alike, exactly [read]'s own "missing or unparseable means the same thing" contract.
     */
    fun writeCapturedCommand(ref: String, argv: List<String>) {
        val target = workloadCmdlineFile(ref)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, "workload-cmdline", ".tmp")
        Files.writeString(tmp, encodeCapturedCommand(ref, argv))
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** `null` for "no capture on file for [ref]" and "the file is there but unparseable" alike —
     * see [writeCapturedCommand]'s doc. The caller (a checkpoint-restoring backend) treats both
     * exactly like a checkpoint that predates workload capture. */
    fun readCapturedCommand(ref: String): List<String>? {
        val f = workloadCmdlineFile(ref)
        if (!Files.exists(f)) return null
        return runCatching { Files.readString(f) }.getOrNull()?.let { decodeCapturedCommand(it, ref) }
    }

    private fun workloadCmdlineFile(ref: String): Path =
        dir.resolve(".workload-cmdline").resolve("${sha256Hex(ref)}.json")

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Minimal hand-rolled JSON, same rationale as [CheckpointRecord]'s own (no JSON library
     * dependency in `core`): `{"ref":"<ref>","command":["a","b",...]}` — `ref` is redundant with
     * the sha256-named file it lives in, kept only so a leftover file is self-describing for
     * anyone inspecting the cache dir by hand. */
    private fun encodeCapturedCommand(ref: String, argv: List<String>): String = buildString {
        append("{\"ref\":").append(jsonStringLiteral(ref))
        append(",\"command\":[")
        argv.forEachIndexed { i, a -> if (i > 0) append(','); append(jsonStringLiteral(a)) }
        append("]}")
    }

    /** `null` on anything that doesn't parse as a `command` array of strings — a corrupt or
     * foreign file at the hashed path is treated as "no capture on file", never crashes the
     * restore it's consulted from. [ref] isn't cross-checked against the file's own `"ref"` value
     * (a sha256 filename collision is astronomically unlikely, and this store has no other
     * caller-visible identity to defend); it's parsed only so [encodeCapturedCommand]'s round trip
     * is exercised in full by any test reading these files back. Extraction tracks `[`/`]` depth
     * outside quoted strings (same reasoning as [CheckpointRecord]'s own `extractBalanced`) rather
     * than a naive `[^\]]*`-style regex, so a captured argument containing a literal `]` is never
     * mistaken for the array's own closing bracket. */
    private fun decodeCapturedCommand(text: String, ref: String): List<String>? {
        val m = Regex("\"command\"\\s*:\\s*\\[").find(text) ?: return null
        var depth = 0
        var i = m.range.last
        var inString = false
        var end = -1
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            i++
        }
        if (end < 0) return null
        val body = text.substring(m.range.last + 1, end)
        if (body.isBlank()) return emptyList()
        val quoted = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        return quoted.findAll(body).map { unescapeJsonStringLiteral(it.groupValues[1]) }.toList()
    }

    private fun jsonStringLiteral(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unescapeJsonStringLiteral(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> { append('"'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    else -> { append(c); i++ }
                }
            } else { append(c); i++ }
        }
    }
}
