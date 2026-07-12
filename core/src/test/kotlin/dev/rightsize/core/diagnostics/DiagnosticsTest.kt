package dev.rightsize.core.diagnostics

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.NetworkLink
import dev.rightsize.core.PortBinding
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** A backend whose [logs] either returns fixed text or throws, for exercising
 * [Diagnostics.render] without a real sandbox. */
private class FakeDiagBackend(private val logsText: String? = null, private val logsFailure: Exception? = null) :
    SandboxBackend {
    override val name = "fake"
    override val supportsNativeNetworks = false
    override fun create(spec: ContainerSpec): SandboxHandle = error("not needed")
    override fun start(handle: SandboxHandle) {}
    override fun stop(handle: SandboxHandle) {}
    override fun remove(handle: SandboxHandle) {}
    override fun exec(handle: SandboxHandle, cmd: List<String>) = ExecResult(0, "", "")
    override fun logs(handle: SandboxHandle): String = logsFailure?.let { throw it } ?: logsText.orEmpty()
    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit) = AutoCloseable {}
    override fun ensureNetwork(networkId: String) {}
    override fun removeNetwork(networkId: String) {}
}

private fun handleOf(spec: ContainerSpec): SandboxHandle = object : SandboxHandle {
    override val id = spec.name
    override val spec = spec
}

private fun entry(name: String, image: String, hostPort: Int, guestPort: Int, logs: String) =
    LiveContainers.Entry(
        handle = handleOf(ContainerSpec(
            name = name, image = image, runId = "ab12cd34",
            ports = listOf(PortBinding(hostPort = hostPort, guestPort = guestPort)),
        )),
        backend = FakeDiagBackend(logsText = logs),
        host = "127.0.0.1",
    )

class DiagnosticsTest {
    @Test fun `zero running containers`() {
        assertEquals("== rightsize diagnostics: no running containers ==", Diagnostics.render(emptyList()))
    }

    @Test fun `golden format for the two-container fixture`() {
        val redis = entry(
            name = "rz-ab12cd34-redis", image = "redis:7-alpine", hostPort = 49213, guestPort = 6379,
            logs = "Ready to accept connections\nStarted redis",
        )
        val postgres = entry(
            name = "rz-ab12cd34-postgres", image = "postgres:16-alpine", hostPort = 49214, guestPort = 5432,
            logs = "database system is ready to accept connections",
        )
        val expected = """
            == rightsize diagnostics: 2 running container(s) ==
            -- rz-ab12cd34-redis (redis:7-alpine) --
            state: running   host: 127.0.0.1   ports: 6379->49213
            last 50 log lines:
              Ready to accept connections
              Started redis
            -- rz-ab12cd34-postgres (postgres:16-alpine) --
            state: running   host: 127.0.0.1   ports: 5432->49214
            last 50 log lines:
              database system is ready to accept connections
        """.trimIndent()
        assertEquals(expected, Diagnostics.render(listOf(redis, postgres)))
    }

    @Test fun `log tail keeps only the last 50 lines`() {
        val lines = (1..60).map { "line $it" }
        val e = entry("rz-ab12cd34-redis", "redis:7-alpine", 49213, 6379, lines.joinToString("\n"))
        val rendered = Diagnostics.render(listOf(e))
        val tail = rendered.lineSequence().drop(4).toList()   // header + name + state + "last 50 log lines:"
        assertEquals(50, tail.size)
        assertEquals("  line 11", tail.first())
        assertEquals("  line 60", tail.last())
    }

    @Test fun `a trailing blank line from a final newline is not rendered as an extra log line`() {
        val e = entry("rz-ab12cd34-redis", "redis:7-alpine", 49213, 6379, "one\ntwo\n")
        val rendered = Diagnostics.render(listOf(e))
        assertFalse(rendered.endsWith("\n  "), "must not render an empty indented trailing line: [$rendered]")
        assertTrue(rendered.endsWith("  two"))
    }

    @Test fun `a failing logs call degrades instead of throwing`() {
        val failure = RuntimeException("connection refused")
        val e = LiveContainers.Entry(
            handle = handleOf(ContainerSpec(
                name = "rz-ab12cd34-redis", image = "redis:7-alpine", runId = "ab12cd34",
                ports = listOf(PortBinding(hostPort = 49213, guestPort = 6379)),
            )),
            backend = FakeDiagBackend(logsFailure = failure),
            host = "127.0.0.1",
        )
        val expected = """
            == rightsize diagnostics: 1 running container(s) ==
            -- rz-ab12cd34-redis (redis:7-alpine) --
            state: running   host: 127.0.0.1   ports: 6379->49213
            logs: unavailable (connection refused)
        """.trimIndent()
        assertEquals(expected, Diagnostics.render(listOf(e)))
    }

    @Test fun `report() reads the process-local live-container registry`() {
        val spec = ContainerSpec(
            name = "rz-report-entrypoint-1", image = "redis:7-alpine", runId = "report1",
            ports = listOf(PortBinding(hostPort = 49999, guestPort = 6379)),
        )
        val h = handleOf(spec)
        LiveContainers.register(h, FakeDiagBackend(logsText = "hi"), "127.0.0.1")
        try {
            assertTrue(Diagnostics.report().contains("rz-report-entrypoint-1"))
        } finally {
            LiveContainers.deregister(spec.name)
        }
        assertFalse(Diagnostics.report().contains("rz-report-entrypoint-1"))
    }
}
