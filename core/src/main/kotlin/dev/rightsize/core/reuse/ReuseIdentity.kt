package dev.rightsize.core.reuse

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The reuse-relevant subset of a container's configuration — deliberately narrower than
 * [dev.rightsize.core.ContainerSpec]: reuse identity is about what a container fundamentally
 * *is* (image, env, command, exposed ports, memory limit, mounted file content), never how this
 * one `start()` happened to invoke it (no name, no host ports, no `runId`, no `networkId`). Two
 * containers with an equal [ReuseIdentitySpec] are, by definition, interchangeable for reuse.
 */
data class ReuseIdentitySpec(
    val image: String,
    val env: Map<String, String>,
    val command: List<String>,
    val exposedPorts: List<Int>,
    val memoryLimitMb: Long?,
    val copies: List<CopyEntry>,
) {
    /** A mounted file's guest destination plus a content hash — so identity busts when the
     * bytes a mount would copy in change, even if [guestPath] and every other field don't. */
    data class CopyEntry(val guestPath: String, val sha256: String)
}

/**
 * Computes the cross-language reuse identity hash (see docs/reuse.md): a SHA-256 over a
 * canonical JSON rendering of a [ReuseIdentitySpec], stable key order, no whitespace, so the
 * same logical spec hashes identically whether it was built by this library's Kotlin, the
 * sibling Rust library, or the sibling Node library. The exact rendering is pinned by a fixed
 * cross-language test vector (see `BackendContractTest`'s reuse hash-vector test) — changing
 * [canonicalJson]'s output for any input, even just field order or number formatting, breaks
 * that pin and desynchronizes reuse identity from the other two libraries.
 */
object ReuseIdentity {
    private const val NAME_PREFIX = "rz-reuse-"

    /** `sha256(canonicalJson(spec))`, lowercase hex. */
    fun hash(spec: ReuseIdentitySpec): String = sha256Hex(canonicalJson(spec))

    /** `rz-reuse-<first 12 hex chars of hash>` — the sandbox name a reuse container is created
     * or adopted under (`rz-` is this repo's own container name prefix, matching every other
     * sandbox name minted by [dev.rightsize.RunId]). */
    fun name(hash: String): String = NAME_PREFIX + hash.take(12)

    /**
     * Streams [path]'s bytes through SHA-256 rather than reading it into memory whole — a
     * mounted file copied into a container can be arbitrarily large, and identity computation
     * runs on every `start()` of a reuse container, not just its first.
     */
    fun sha256OfFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHexString()
    }

    /** Stable key order per field: `image`, `env` (keys sorted), `command` (declaration order —
     * unlike `env`/`exposedPorts`/`copies`, argv order is itself meaningful), `exposedPorts`
     * (sorted ascending), `memoryLimitMb` (`null` or a bare integer), `copies` (sorted by
     * `guestPath`). No whitespace anywhere — a single byte of formatting drift would change the
     * hash for an otherwise-identical spec. */
    internal fun canonicalJson(spec: ReuseIdentitySpec): String = buildString {
        append("{\"image\":").append(jsonString(spec.image))
        append(",\"env\":{")
        spec.env.toSortedMap().entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(jsonString(k)).append(':').append(jsonString(v))
        }
        append("},\"command\":[")
        spec.command.forEachIndexed { i, c -> if (i > 0) append(','); append(jsonString(c)) }
        append("],\"exposedPorts\":[")
        spec.exposedPorts.sorted().forEachIndexed { i, p -> if (i > 0) append(','); append(p) }
        append("],\"memoryLimitMb\":").append(spec.memoryLimitMb?.toString() ?: "null")
        append(",\"copies\":[")
        spec.copies.sortedBy { it.guestPath }.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"guestPath\":").append(jsonString(c.guestPath))
                .append(",\"sha256\":").append(jsonString(c.sha256)).append('}')
        }
        append("]}")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).toHexString()

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}
