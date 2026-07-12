package dev.rightsize.core.reaper

/**
 * One run's ownership-ledger metadata (`runs/<run-id>.json` — see docs/reaping.md), written
 * atomically before the process's first sandbox is created. The schema is a flat object
 * shared byte-for-byte across all three rightsize libraries (a Kotlin sweep must be able to
 * read a record a Node or Rust process wrote, and vice versa), so this is hand-rolled JSON
 * rather than a library dependency: core has none today, and the shape is simple enough
 * (four flat fields, no nesting) that a tiny tolerant reader/writer is cheaper than adding
 * one just for this.
 */
data class RunRecord(
    val pid: Long,
    val startedIso: String,
    val backend: String,
    val msbPath: String? = null,
) {
    fun toJson(): String = buildString {
        append("{\"pid\":").append(pid)
        append(",\"startedIso\":\"").append(escape(startedIso)).append('"')
        append(",\"backend\":\"").append(escape(backend)).append('"')
        if (msbPath != null) append(",\"msbPath\":\"").append(escape(msbPath)).append('"')
        append('}')
    }

    companion object {
        /** Null on anything that doesn't parse as a valid record (garbage text, or missing
         * one of the three required fields) — the sweep treats a null parse as unparseable. */
        fun parse(text: String): RunRecord? {
            val pid = extractNumber(text, "pid") ?: return null
            val startedIso = extractString(text, "startedIso") ?: return null
            val backend = extractString(text, "backend") ?: return null
            val msbPath = extractString(text, "msbPath")
            return RunRecord(pid, startedIso, backend, msbPath)
        }

        private val NUMBER = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)") }
        private val STRING = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"") }

        private fun extractString(text: String, key: String): String? =
            STRING(key).find(text)?.groupValues?.get(1)?.let(::unescape)

        private fun extractNumber(text: String, key: String): Long? =
            NUMBER(key).find(text)?.groupValues?.get(1)?.toLongOrNull()

        private fun escape(s: String): String = buildString {
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
    }
}
