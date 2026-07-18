package dev.rightsize.core.checkpoint

import dev.rightsize.core.CheckpointSpec

/**
 * The `checkpoint.json` member of a checkpoint archive (see docs/checkpoints.md's "Moving
 * checkpoints between machines" section) — [CheckpointRecord]'s registry-entry shape plus a
 * format version ([rightsizeArchive], pinned at `1`) and a nullable [name]: an archive of an
 * UNNAMED checkpoint carries `name: null`, unlike a registry entry, which is always named.
 * Pinned identically across the three rightsize libraries. Hand-rolled JSON for the same reason
 * [CheckpointRecord] is: no JSON library dependency in `core`, and the shape is simple enough
 * that a tiny tolerant reader/writer is cheaper than adding one just for this.
 */
data class CheckpointArchiveRecord(
    val rightsizeArchive: Int,
    val name: String?,
    val ref: String,
    val backend: String,
    val createdIso: String,
    val spec: CheckpointSpec,
) {
    fun toJson(): String = buildString {
        append("{\"rightsizeArchive\":").append(rightsizeArchive)
        append(",\"name\":").append(name?.let(::jsonString) ?: "null")
        append(",\"ref\":").append(jsonString(ref))
        append(",\"backend\":").append(jsonString(backend))
        append(",\"createdIso\":").append(jsonString(createdIso))
        append(",\"spec\":{\"env\":{")
        spec.env.toSortedMap().entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(jsonString(k)).append(':').append(jsonString(v))
        }
        append("},\"command\":")
        val cmd = spec.command
        if (cmd == null) {
            append("null")
        } else {
            append('[')
            cmd.forEachIndexed { i, c -> if (i > 0) append(','); append(jsonString(c)) }
            append(']')
        }
        append(",\"exposedPorts\":[")
        spec.exposedPorts.forEachIndexed { i, p -> if (i > 0) append(','); append(p) }
        append("],\"memoryLimitMb\":").append(spec.memoryLimitMb?.toString() ?: "null")
        append("}}")
    }

    companion object {
        /** `null` on anything that doesn't parse as a valid manifest: a missing top-level field,
         * a missing/malformed `spec` object, or a malformed field inside it — the same
         * "unparseable is treated like absent" posture [CheckpointRecord.parse] has.
         * [CheckpointArchiver] is the one that decides what a `null` parse means for `importFrom`
         * (a typed [MalformedCheckpointArchiveException]), not this function. */
        fun parse(text: String): CheckpointArchiveRecord? {
            val rightsizeArchive = extractNumber(text, "rightsizeArchive") ?: return null
            val (_, name) = extractNullableName(text) ?: return null
            val ref = extractString(text, "ref") ?: return null
            val backend = extractString(text, "backend") ?: return null
            val createdIso = extractString(text, "createdIso") ?: return null
            val specText = extractObject(text, "spec") ?: return null
            val env = extractEnv(specText) ?: return null
            val (_, command) = extractCommand(specText) ?: return null
            val exposedPorts = extractExposedPorts(specText) ?: return null
            val (_, memoryLimitMb) = extractNullableLong(specText, "memoryLimitMb") ?: return null
            return CheckpointArchiveRecord(
                rightsizeArchive, name, ref, backend, createdIso,
                CheckpointSpec(env = env, command = command, exposedPorts = exposedPorts, memoryLimitMb = memoryLimitMb),
            )
        }

        private val NUMBER = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)") }
        private val STRING = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"") }
        private val NAME_NULL = Regex("\"name\"\\s*:\\s*null\\b")
        private val COMMAND_NULL = Regex("\"command\"\\s*:\\s*null\\b")
        private val QUOTED = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        private val STRING_PAIR = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

        private fun extractNumber(text: String, key: String): Int? =
            NUMBER(key).find(text)?.groupValues?.get(1)?.toIntOrNull()

        private fun extractString(text: String, key: String): String? =
            STRING(key).find(text)?.groupValues?.get(1)?.let(::unescape)

        /** Same found/value sentinel pair as [extractCommand]/[extractNullableLong] below —
         * `name`'s valid value is itself nullable, so "found but null" and "missing/malformed"
         * can't both collapse to a bare `null` return. */
        private fun extractNullableName(text: String): Pair<Boolean, String?>? {
            if (NAME_NULL.containsMatchIn(text)) return true to null
            val s = extractString(text, "name") ?: return null
            return true to s
        }

        /** Pulls the raw `{...}` substring following `"key":`, tracking brace depth
         * string-awarely — see [CheckpointRecord.extractObject]'s doc for why a naive
         * `[^}]*`-style regex would truncate at `spec`'s own nested `env` object. */
        private fun extractObject(text: String, key: String): String? = extractBalanced(text, key, '{', '}')

        private fun extractArray(text: String, key: String): String? = extractBalanced(text, key, '[', ']')

        private fun extractBalanced(text: String, key: String, open: Char, close: Char): String? {
            val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*${Regex.escape(open.toString())}").find(text) ?: return null
            val start = m.range.last
            var depth = 0
            var i = start
            var inString = false
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
                        open -> depth++
                        close -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
                    }
                }
                i++
            }
            return null
        }

        private fun extractEnv(specText: String): Map<String, String>? {
            val obj = extractObject(specText, "env") ?: return null
            val body = obj.substring(1, obj.length - 1)
            if (body.isBlank()) return emptyMap()
            return STRING_PAIR.findAll(body).associate { m -> unescape(m.groupValues[1]) to unescape(m.groupValues[2]) }
        }

        private fun extractExposedPorts(specText: String): List<Int>? {
            val arr = extractArray(specText, "exposedPorts") ?: return null
            val body = arr.substring(1, arr.length - 1)
            if (body.isBlank()) return emptyList()
            return body.split(',').map { it.trim().toIntOrNull() ?: return null }
        }

        private fun extractCommand(specText: String): Pair<Boolean, List<String>?>? {
            if (COMMAND_NULL.containsMatchIn(specText)) return true to null
            val arr = extractArray(specText, "command") ?: return null
            val body = arr.substring(1, arr.length - 1)
            val list = if (body.isBlank()) emptyList() else QUOTED.findAll(body).map { unescape(it.groupValues[1]) }.toList()
            return true to list
        }

        private fun extractNullableLong(specText: String, key: String): Pair<Boolean, Long?>? {
            val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(null|-?\\d+)").find(specText) ?: return null
            val raw = m.groupValues[1]
            return true to (if (raw == "null") null else raw.toLong())
        }

        private fun unescape(s: String): String = buildString {
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

        private fun jsonString(s: String): String = buildString {
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
    }
}
