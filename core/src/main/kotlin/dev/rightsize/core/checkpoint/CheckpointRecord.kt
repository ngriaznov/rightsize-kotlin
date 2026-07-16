package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointSpec

/**
 * One named checkpoint's registry entry (`<cache-dir>/checkpoints/<name>.json` — see
 * docs/checkpoints.md's "Reusing checkpoints across runs" section), written atomically only
 * after the backend checkpoint itself succeeds (see `GenericContainer.checkpoint(name)`). Field
 * names and nesting are pinned identically across the three rightsize libraries
 * (parity-testable): `name`, `ref`, `backend`, `createdIso`, and a nested `spec` object mirroring
 * [CheckpointSpec] itself (`env`, `command`, `exposedPorts`, `memoryLimitMb`). Hand-rolled JSON
 * for the same reason [dev.rightsize.core.reaper.RunRecord]/[dev.rightsize.core.reuse.ReuseRecord]
 * are: no JSON library dependency in `core`, and the shape is simple enough that a tiny tolerant
 * reader/writer is cheaper than adding one just for this.
 */
data class CheckpointRecord(
    val name: String,
    val ref: String,
    val backend: String,
    val createdIso: String,
    val spec: CheckpointSpec,
) {
    fun toCheckpoint(): Checkpoint = Checkpoint(ref = ref, backend = backend, spec = spec)

    fun toJson(): String = buildString {
        append("{\"name\":").append(jsonString(name))
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
        /** `null` on anything that doesn't parse as a valid record: a missing top-level field, a
         * missing/malformed `spec` object, or a malformed field inside it. `find`/`list` both
         * treat a `null` parse exactly like a missing file. */
        fun parse(text: String): CheckpointRecord? {
            val name = extractString(text, "name") ?: return null
            val ref = extractString(text, "ref") ?: return null
            val backend = extractString(text, "backend") ?: return null
            val createdIso = extractString(text, "createdIso") ?: return null
            val specText = extractObject(text, "spec") ?: return null
            val env = extractEnv(specText) ?: return null
            val (_, command) = extractCommand(specText) ?: return null
            val exposedPorts = extractExposedPorts(specText) ?: return null
            val (_, memoryLimitMb) = extractNullableLong(specText, "memoryLimitMb") ?: return null
            return CheckpointRecord(
                name, ref, backend, createdIso,
                CheckpointSpec(env = env, command = command, exposedPorts = exposedPorts, memoryLimitMb = memoryLimitMb),
            )
        }

        private val STRING = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"") }
        private val COMMAND_NULL = Regex("\"command\"\\s*:\\s*null\\b")
        private val QUOTED = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
        private val STRING_PAIR = Regex("\"((?:\\\\.|[^\"\\\\])*)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

        private fun extractString(text: String, key: String): String? =
            STRING(key).find(text)?.groupValues?.get(1)?.let(::unescape)

        /**
         * Pulls the raw `{...}` substring following `"key":`, tracking brace depth rather than a
         * `[^}]*`-style regex — `spec`'s own nested `env` object means a naive non-nested match
         * would truncate at `env`'s closing brace instead of `spec`'s. Returns `null` if [key]
         * isn't present, or its value isn't an object at all. Delegates the actual scan to
         * [extractBalanced], the same string-aware scanner [extractArray] uses for `[...]`.
         */
        private fun extractObject(text: String, key: String): String? = extractBalanced(text, key, '{', '}')

        /**
         * Pulls the raw `[...]` substring following `"key":`, tracking bracket depth the same
         * string-aware way [extractObject] tracks brace depth — a literal `]`/`[` inside a
         * `command` argument must not be miscounted as structural, same reasoning as
         * [extractObject]'s doc comment. Returns `null` if [key] isn't present, or its value isn't
         * an array at all.
         */
        private fun extractArray(text: String, key: String): String? = extractBalanced(text, key, '[', ']')

        /**
         * Shared scan behind [extractObject]/[extractArray]: finds `"key":` followed by [open],
         * then walks forward counting [open]/[close] to find the matching [close] — but ONLY
         * outside quoted strings (toggling [inString] on an unescaped `"`, and skipping the
         * character right after a `\` while inside one). Without that string-awareness, a literal
         * `}`/`{`/`]`/`[` inside an env value or command argument would be miscounted as
         * structural, truncating (or over-extending) the match — exactly the bug a hand-rolled
         * depth counter must not have, since [spec]'s own nested `env` object already rules out a
         * naive `[^}]*`-style regex for the outer object.
         */
        private fun extractBalanced(text: String, key: String, open: Char, close: Char): String? {
            val m = Regex("\"${Regex.escape(key)}\"\\s*:\\s*${Regex.escape(open.toString())}").find(text) ?: return null
            val start = m.range.last   // index of the opening delimiter itself
            var depth = 0
            var i = start
            var inString = false
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when (c) {
                        '\\' -> i++   // skip the escaped character entirely, whatever it is
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
            val body = obj.substring(1, obj.length - 1)   // strip the outer '{'/'}'
            if (body.isBlank()) return emptyMap()
            return STRING_PAIR.findAll(body).associate { m -> unescape(m.groupValues[1]) to unescape(m.groupValues[2]) }
        }

        private fun extractExposedPorts(specText: String): List<Int>? {
            val arr = extractArray(specText, "exposedPorts") ?: return null
            val body = arr.substring(1, arr.length - 1)   // strip the outer '['/']'
            if (body.isBlank()) return emptyList()
            return body.split(',').map { it.trim().toIntOrNull() ?: return null }
        }

        /**
         * `command` is the one field whose valid value is itself nullable, so "found but null"
         * and "missing/malformed" can't both collapse to a bare `null` return — the boolean half
         * of the pair only ever comes back `true`; its presence is what distinguishes the two
         * from the caller's `?: return null`.
         */
        private fun extractCommand(specText: String): Pair<Boolean, List<String>?>? {
            if (COMMAND_NULL.containsMatchIn(specText)) return true to null
            val arr = extractArray(specText, "command") ?: return null
            val body = arr.substring(1, arr.length - 1)   // strip the outer '['/']'
            val list = if (body.isBlank()) emptyList() else QUOTED.findAll(body).map { unescape(it.groupValues[1]) }.toList()
            return true to list
        }

        /** Same found/value sentinel pair as [extractCommand], for `memoryLimitMb`. */
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
