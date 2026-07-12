package dev.rightsize.core.reuse

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * File-backed store for [ReuseRecord]s, one file per hash: `<cacheDir>/reuse/<hash>.json` (see
 * docs/reuse.md). [write] is tmp-in-directory-then-atomic-rename — the same crash-safe idiom
 * `dev.rightsize.core.reaper.RunLedger.writeAtomic` and `MsbProvisioner`'s install already use —
 * so a reader never observes a partially written record.
 */
class ReuseRegistry(cacheDir: Path) {
    private val dir = cacheDir.resolve("reuse")

    fun file(hash: String): Path = dir.resolve("$hash.json")

    /** `null` for "no record" and "record present but unparseable" alike — both mean the same
     * thing to a caller deciding whether to adopt: don't trust it. */
    fun read(hash: String): ReuseRecord? {
        val f = file(hash)
        if (!Files.exists(f)) return null
        return runCatching { Files.readString(f) }.getOrNull()?.let(ReuseRecord::parse)
    }

    fun write(hash: String, record: ReuseRecord) {
        Files.createDirectories(dir)
        val tmp = Files.createTempFile(dir, hash, ".tmp")
        Files.writeString(tmp, record.toJson())
        Files.move(tmp, file(hash), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Best-effort: a record that's already gone (raced away by another process, or never
     * written) is not an error. */
    fun delete(hash: String) {
        runCatching { Files.deleteIfExists(file(hash)) }
    }
}
