package dev.rightsize

import dev.rightsize.core.BackendCapabilities
import dev.rightsize.core.IsolationRequiredException
import dev.rightsize.core.diagnostics.LiveContainers
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private object ReadyImmediately2 : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A [FakeBackend] whose [capabilities] is settable, for requireIsolation gating tests. */
private class IsolationFakeBackend(hardwareIsolated: Boolean) : FakeBackend() {
    override val capabilities = BackendCapabilities(hardwareIsolated = hardwareIsolated, checkpoint = false)
}

class GenericContainerDiagnosticsIsolationTest {
    // -- live-container registry --

    @Test fun `a successful start registers the container, stop deregisters it`() {
        val backend = FakeBackend()
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately2)
            .withExposedPorts(6379)
        c.start()
        val name = backend.created.single().name
        assertTrue(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "expected $name to be registered after a successful start")
        c.stop()
        assertFalse(LiveContainers.snapshot().any { it.handle.spec.name == name },
            "expected $name to be deregistered after stop")
    }

    @Test fun `a container that never started is never registered`() {
        val backend = FakeBackend()
        val before = LiveContainers.snapshot().size
        // Constructing (without start()) must not register anything.
        GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately2)
        assertEquals(before, LiveContainers.snapshot().size)
    }

    // -- requireIsolation --

    @Test fun `withRequireIsolation on a non-isolated backend throws before any create call`() {
        val backend = IsolationFakeBackend(hardwareIsolated = false)
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately2)
            .withRequireIsolation()
        val e = assertThrows(IsolationRequiredException::class.java) { c.start() }
        assertTrue(backend.created.isEmpty(), "no sandbox should be created when isolation is required but unavailable")
        assertTrue(e.message!!.contains("fake"), "message should name the active backend: ${e.message}")
        assertTrue(e.message!!.contains("RIGHTSIZE_BACKEND=microsandbox"), "message should give the remedy: ${e.message}")
    }

    @Test fun `withRequireIsolation on a hardware-isolated backend starts normally`() {
        val backend = IsolationFakeBackend(hardwareIsolated = true)
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately2)
            .withRequireIsolation()
        c.start()
        assertTrue(c.isRunning)
        assertEquals(1, backend.created.size)
        c.stop()
    }

    @Test fun `without withRequireIsolation a non-isolated backend starts normally`() {
        val backend = IsolationFakeBackend(hardwareIsolated = false)
        val c = GenericContainer("redis:8.6-alpine").withBackend(backend).waitingFor(ReadyImmediately2)
        c.start()
        assertTrue(c.isRunning)
        c.stop()
    }
}
