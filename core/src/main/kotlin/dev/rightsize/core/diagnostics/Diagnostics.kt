package dev.rightsize.core.diagnostics

/**
 * Public entry point for describing this process's currently-live containers — image, state,
 * host, mapped ports, and a tail of recent logs for each — meant for pasting into a bug report
 * or printing automatically on test failure (Kotlin: [dev.rightsize.junit.SandboxedExtension];
 * Rust: `DiagnosticsGuard`; Node: `onTestFailed`/`registerDiagnostics`).
 *
 * The output format is a cross-language contract: Kotlin, Rust, and Node render byte-identical
 * text for the same inputs (see each language's golden-format test). Do not change the format
 * here without updating the other two implementations and their golden tests.
 */
object Diagnostics {
    private const val LOG_TAIL_LINES = 50

    /** Renders the report for whatever is currently registered in [LiveContainers]. */
    fun report(): String = render(LiveContainers.snapshot())

    /** The pure rendering logic behind [report], factored out so the golden-format tests can
     * exercise it against fixed fake entries instead of the process-wide registry. */
    internal fun render(entries: List<LiveContainers.Entry>): String {
        if (entries.isEmpty()) return "== rightsize diagnostics: no running containers =="
        val header = "== rightsize diagnostics: ${entries.size} running container(s) =="
        return (listOf(header) + entries.map { renderSection(it) }).joinToString("\n")
    }

    private fun renderSection(entry: LiveContainers.Entry): String {
        val spec = entry.handle.spec
        val ports = spec.ports.joinToString(", ") { "${it.guestPort}->${it.hostPort}" }
        val lines = mutableListOf(
            "-- ${spec.name} (${spec.image}) --",
            "state: running   host: ${entry.host}   ports: $ports",
        )
        lines += renderLogs(entry)
        return lines.joinToString("\n")
    }

    /** Last [LOG_TAIL_LINES] lines of `logs`, each indented two spaces; degrades to a single
     * `logs: unavailable (<reason>)` line instead of throwing when the backend's `logs` call
     * fails (e.g. the sandbox died mid-test). */
    private fun renderLogs(entry: LiveContainers.Entry): List<String> {
        val text = try {
            entry.backend.logs(entry.handle)
        } catch (e: Exception) {
            return listOf("logs: unavailable (${e.message ?: e.toString()})")
        }
        // A trailing newline splits into a spurious empty final element — drop exactly one, the
        // way a text file's own trailing newline is conventionally not itself a blank line.
        val allLines = text.lines().let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
        return listOf("last $LOG_TAIL_LINES log lines:") + allLines.takeLast(LOG_TAIL_LINES).map { "  $it" }
    }
}
