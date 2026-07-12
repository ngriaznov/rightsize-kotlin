package dev.rightsize.core.reuse

/**
 * One reuse sandbox's registry entry (`<cache-dir>/reuse/<hash>.json` — see docs/reuse.md),
 * written atomically after a reuse container first starts successfully and passes its wait
 * strategy. Hand-rolled JSON for the same reason [dev.rightsize.core.reaper.RunRecord] is: no
 * JSON library dependency in `core`, and the shape is flat and simple enough that a tiny
 * tolerant reader/writer is cheaper than adding one just for this.
 */
data class ReuseRecord(
    val name: String,
    val image: String,
    /** guest port -> host port, exactly as `GenericContainer.getMappedPort` would report them
     * for the container that wrote this record. */
    val ports: Map<Int, Int>,
    val createdIso: String,
    val backend: String,
) {
    fun toJson(): String = buildString {
        append("{\"name\":").append(jsonString(name))
        append(",\"image\":").append(jsonString(image))
        append(",\"ports\":{")
        ports.entries.sortedBy { it.key }.forEachIndexed { i, (guest, host) ->
            if (i > 0) append(',')
            append('"').append(guest).append("\":").append(host)
        }
        append("},\"createdIso\":").append(jsonString(createdIso))
        append(",\"backend\":").append(jsonString(backend))
        append('}')
    }

    companion object {
        /** Null on anything that doesn't parse as a valid record (garbage text, a missing
         * required field, an unparseable `ports` object) — the reuse adopt path treats a null
         * parse exactly like a missing file: fall back to fresh create. */
        fun parse(text: String): ReuseRecord? {
            val name = extractString(text, "name") ?: return null
            val image = extractString(text, "image") ?: return null
            val createdIso = extractString(text, "createdIso") ?: return null
            val backend = extractString(text, "backend") ?: return null
            val ports = extractPorts(text) ?: return null
            return ReuseRecord(name, image, ports, createdIso, backend)
        }

        private val STRING = { key: String -> Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"") }
        private val PORTS_OBJECT = Regex("\"ports\"\\s*:\\s*\\{([^}]*)\\}")
        private val PORT_ENTRY = Regex("\"(\\d+)\"\\s*:\\s*(\\d+)")

        private fun extractString(text: String, key: String): String? =
            STRING(key).find(text)?.groupValues?.get(1)?.let(::unescape)

        private fun extractPorts(text: String): Map<Int, Int>? {
            val body = PORTS_OBJECT.find(text)?.groupValues?.get(1) ?: return null
            if (body.isBlank()) return emptyMap()
            val entries = PORT_ENTRY.findAll(body)
                .mapNotNull { m ->
                    val guest = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val host = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                    guest to host
                }.toList()
            return entries.toMap()
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
