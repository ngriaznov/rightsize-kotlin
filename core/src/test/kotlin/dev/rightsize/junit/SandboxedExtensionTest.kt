package dev.rightsize.junit

import dev.rightsize.GenericContainer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
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

    private fun runAndExpectNoFailures(testClass: Class<*>) {
        val listener = SummaryGeneratingListener()
        LauncherFactory.create().execute(
            LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(testClass)).build(),
            listener)
        assertEquals(0, listener.summary.totalFailureCount, listener.summary.failures.joinToString { it.exception.toString() })
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
}
