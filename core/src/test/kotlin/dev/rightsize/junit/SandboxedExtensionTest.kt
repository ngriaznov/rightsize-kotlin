package dev.rightsize.junit

import dev.rightsize.FakeBackend
import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.platform.engine.discovery.DiscoverySelectors
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.listeners.SummaryGeneratingListener

// A GenericContainer that fakes start/stop so no backend is needed.
// Relies on start/stop/isRunning being `open` on GenericContainer.
class RecordingContainer : GenericContainer<RecordingContainer>("fake:latest") {
    companion object { val events = mutableListOf<String>() }
    private var running = false
    override val isRunning get() = running
    override fun start() { running = true; events += "start" }
    override fun stop() { running = false; events += "stop" }
}

/** Already running the moment it's constructed — mimics a caller-managed container from an init block. */
class PreStartedContainer : GenericContainer<PreStartedContainer>("fake:latest") {
    companion object { val events = mutableListOf<String>() }
    private var running = true
    init { events += "init-start" }
    override val isRunning get() = running
    override fun start() { running = true; events += "start" }
    override fun stop() { running = false; events += "stop" }
}

/** Base class carrying the @Container field for the superclass-field-walk test below. */
open class BaseWithContainer {
    @Container val fromBase = RecordingContainer()
}

/** Immediately ready — lets a real [GenericContainer] (backed by [FakeBackend]) start without
 * ever touching a network, so it exercises the real [GenericContainer.start]/[GenericContainer.stop]
 * live-container-registry bookkeeping that a hand-faked [RecordingContainer] bypasses entirely. */
private object InstantlyReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

class SandboxedExtensionTest {
    // Nested (non-`inner`) so Gradle's JUnit Platform test task does not auto-discover
    // it as a top-level container (only `@Nested` classes are), avoiding double-counting.
    // The Launcher run below targets it explicitly via selectClass.
    @Sandboxed
    class SampleSandboxedTest {
        companion object {
            @JvmStatic @Container val shared = RecordingContainer()
        }
        @Container val perTest = RecordingContainer()
        @Test fun one() { assertTrue(true) }
        @Test fun two() { assertTrue(true) }
    }

    // A static AND an instance container that are already running before the extension sees
    // them — both must be left alone (not started, not stopped).
    @Sandboxed
    class PreStartedSandboxedTest {
        companion object {
            @JvmStatic @Container val staticPreStarted = PreStartedContainer()
        }
        @Container val instancePreStarted = PreStartedContainer()
        @Test fun one() { assertTrue(true) }
    }

    // The @Container field lives on a superclass, not the test class itself.
    @Sandboxed
    class SubclassSandboxedTest : BaseWithContainer() {
        @Test fun one() { assertTrue(true) }
    }

    // A @Container field of a non-GenericContainer type must be silently filtered, never
    // cause a ClassCastException during field discovery/start.
    @Sandboxed
    class NonContainerFieldSandboxedTest {
        @Container val notAContainer: String = "foo"
        @Test fun one() { assertTrue(true) }
    }

    // An INSTANCE @Container field (started in beforeEach, stopped in this extension's own
    // afterEach) whose one test fails on demand — the primary use case for the diagnostics
    // hook: the report testFailed emits must still describe this instance's own container, even
    // though by the time testFailed runs (after afterEach) it has already been stopped.
    // `shouldFail` defaults to false because Gradle's JUnit Platform test task independently
    // discovers and runs every nested class with @Test methods on the classpath (same as the
    // other Sample*/PreStarted* classes below) — unconditionally throwing here would fail that
    // independent run too; only the controlling test below opts in.
    @Sandboxed
    class FailingSandboxedTest {
        companion object {
            val backend = FakeBackend()
            @Volatile var shouldFail = false
        }
        @Container val marker: GenericContainer<*> =
            GenericContainer("diag-marker:latest").withBackend(backend).waitingFor(InstantlyReady)
        @Test fun boom() { if (shouldFail) throw RuntimeException("boom") }
    }

    private fun runAndExpectNoFailures(testClass: Class<*>) {
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(testClass)).build(),
            listener)
        assertEquals(0, listener.summary.totalFailureCount, listener.summary.failures.joinToString { it.exception.toString() })
    }

    private fun runAndExpectOneFailure(testClass: Class<*>) {
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(testClass)).build(),
            listener)
        assertEquals(1, listener.summary.totalFailureCount, "expected exactly one failing test")
    }

    @Test fun `static container started once, instance per test`() {
        RecordingContainer.events.clear()
        runAndExpectNoFailures(SampleSandboxedTest::class.java)
        // 1 shared start + 2 per-test starts
        assertEquals(3, RecordingContainer.events.count { it == "start" })
    }

    @Test fun `pre-started static and instance containers are not started again and not stopped`() {
        PreStartedContainer.events.clear()
        runAndExpectNoFailures(PreStartedSandboxedTest::class.java)
        // The static field's init-block may or may not have fired again during this run
        // (companion objects init once per classloader, independent of the extension), so assert
        // the extension's actual contract rather than the class-init timing: it must never call
        // start() (both fields already report isRunning==true) and never call stop() on them.
        assertFalse(PreStartedContainer.events.contains("start"), "already-running container must not be (re)started")
        assertFalse(PreStartedContainer.events.contains("stop"), "caller-managed container must not be stopped by the extension")
    }

    @Test fun `container field declared on a superclass is discovered and started`() {
        RecordingContainer.events.clear()
        runAndExpectNoFailures(SubclassSandboxedTest::class.java)
        assertEquals(listOf("start", "stop"), RecordingContainer.events)
    }

    @Test fun `non-GenericContainer Container field is filtered without a ClassCastException`() {
        // Discovery/start must not throw for a String field annotated @Container; the class runs clean.
        runAndExpectNoFailures(NonContainerFieldSandboxedTest::class.java)
    }

    private fun captureStderr(block: () -> Unit): String {
        val captured = java.io.ByteArrayOutputStream()
        val original = System.err
        System.setErr(java.io.PrintStream(captured))
        try { block() } finally { System.setErr(original) }
        return captured.toString()
    }

    // testFailed(context, cause), called directly (outside the real JUnit lifecycle), only reads
    // context.getStore(ns).get(...) for a diagnostics snapshot — this stub backs exactly that one
    // Store.get(key) call with an always-empty store (so testFailed falls back to a live report)
    // and errors on any other ExtensionContext/Store method, so an accidental new dependency on
    // other context state fails loudly here instead of silently passing.
    private fun contextWithEmptyDiagnosticsStore(): ExtensionContext {
        val storeHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            if (method.name == "get") null
            else error("SandboxedExtension.testFailed must not call ExtensionContext.Store.${method.name}")
        }
        val store = java.lang.reflect.Proxy.newProxyInstance(
            ExtensionContext.Store::class.java.classLoader, arrayOf(ExtensionContext.Store::class.java), storeHandler,
        ) as ExtensionContext.Store
        val ctxHandler = java.lang.reflect.InvocationHandler { _, method, _ ->
            if (method.name == "getStore") store
            else error("SandboxedExtension.testFailed must not call ExtensionContext.${method.name}")
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            ExtensionContext::class.java.classLoader, arrayOf(ExtensionContext::class.java), ctxHandler,
        ) as ExtensionContext
    }

    @Test fun `testFailed prints the diagnostics report to stderr exactly once per failed test`() {
        val output = captureStderr {
            SandboxedExtension().testFailed(contextWithEmptyDiagnosticsStore(), RuntimeException("boom"))
        }
        val occurrences = Regex("== rightsize diagnostics:").findAll(output).count()
        assertEquals(1, occurrences, "expected exactly one diagnostics report in stderr, got:\n$output")
    }

    @Test fun `the success path (no testFailed call) emits no diagnostics report`() {
        val output = captureStderr { runAndExpectNoFailures(SampleSandboxedTest::class.java) }
        assertFalse(output.contains("== rightsize diagnostics:"), "success path must not print a report: $output")
    }

    @Test fun `testFailed's report describes the failing test's own instance containers, started in beforeEach and already stopped by this extension's afterEach`() {
        FailingSandboxedTest.shouldFail = true
        try {
            val output = captureStderr { runAndExpectOneFailure(FailingSandboxedTest::class.java) }
            assertTrue(output.contains("diag-marker:latest"),
                "expected the failing test's own @Container field in the diagnostics report, got:\n$output")
        } finally {
            FailingSandboxedTest.shouldFail = false
        }
    }
}
