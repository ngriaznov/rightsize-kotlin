package dev.rightsize.junit

import dev.rightsize.GenericContainer
import dev.rightsize.core.diagnostics.Diagnostics
import org.junit.jupiter.api.extension.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Marks a JUnit 5 test class whose [Container]-annotated fields should be started and stopped
 * automatically — the rightsize equivalent of Testcontainers' `@Testcontainers`. Static fields
 * are started once in `beforeAll`/stopped in `afterAll`; instance fields are started in
 * `beforeEach`/stopped in `afterEach`. A field already running when the extension sees it (e.g.
 * started in an `init` block) is left alone: never restarted, never stopped by this extension.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(SandboxedExtension::class)
annotation class Sandboxed

/**
 * Marks a `GenericContainer` field (static or instance, declared on the test class or any
 * superclass) for [Sandboxed] to manage — the rightsize equivalent of Testcontainers' `@Container`.
 * A field of any other type is silently ignored rather than treated as an error.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Container

class SandboxedExtension :
    BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback,
    AfterTestExecutionCallback, TestWatcher {
    private companion object {
        const val STATIC_KEY = "static"
        const val INSTANCE_KEY = "instance"
        const val DIAGNOSTICS_KEY = "diagnosticsSnapshot"
    }

    private val ns = ExtensionContext.Namespace.create("dev.rightsize.junit")

    private fun containerFields(type: Class<*>, static: Boolean): List<Field> =
        generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { it.isAnnotationPresent(Container::class.java) }
            .filter { Modifier.isStatic(it.modifiers) == static }
            .filter { GenericContainer::class.java.isAssignableFrom(it.type) }
            .onEach { it.isAccessible = true }
            .toList()

    private fun startAll(fields: List<Field>, instance: Any?): List<GenericContainer<*>> =
        fields.mapNotNull { f ->
            val c = f.get(instance) as GenericContainer<*>
            if (!c.isRunning) { c.start(); c } else null   // pre-started (init blocks) stay caller-managed
        }

    /** Stores the containers *this extension* started under [key], so only those get stopped later. */
    private fun ExtensionContext.storeStarted(key: String, containers: List<GenericContainer<*>>) {
        getStore(ns).put(key, containers)
    }

    /** Retrieves the containers this extension previously started under [key], if any. */
    @Suppress("UNCHECKED_CAST")
    private fun ExtensionContext.startedFrom(key: String): List<GenericContainer<*>> =
        getStore(ns).get(key) as? List<GenericContainer<*>> ?: emptyList()

    override fun beforeAll(ctx: ExtensionContext) {
        val started = startAll(containerFields(ctx.requiredTestClass, static = true), null)
        ctx.storeStarted(STATIC_KEY, started)
    }

    override fun afterAll(ctx: ExtensionContext) {
        ctx.startedFrom(STATIC_KEY).forEach { it.stop() }
    }

    override fun beforeEach(ctx: ExtensionContext) {
        val started = ctx.requiredTestInstances.allInstances.flatMap { inst ->
            startAll(containerFields(inst.javaClass, static = false), inst)
        }
        ctx.storeStarted(INSTANCE_KEY, started)
    }

    override fun afterEach(ctx: ExtensionContext) {
        ctx.startedFrom(INSTANCE_KEY).forEach { it.stop() }
    }

    /**
     * Snapshots [Diagnostics.report] right after the test method finishes, before this
     * extension's own [afterEach] stops (and deregisters from [dev.rightsize.core.diagnostics.LiveContainers])
     * the `@Container` fields that just ran the test. JUnit 5 runs every extension's
     * `afterTestExecution` callbacks before any extension's `afterEach` callbacks, so this is the
     * last point at which the failing test's own containers are still guaranteed to be live —
     * [testFailed] (a [TestWatcher] callback) only fires after all `afterEach` callbacks have
     * already completed, by which point they would otherwise already be gone.
     */
    override fun afterTestExecution(ctx: ExtensionContext) {
        if (ctx.executionException.isPresent) {
            ctx.getStore(ns).put(DIAGNOSTICS_KEY, runCatching { Diagnostics.report() }.getOrNull())
        }
    }

    /** Prints [Diagnostics.report] to `System.err` exactly once per failed test — never on a
     * pass. Prefers the snapshot [afterTestExecution] captured before this extension's own
     * [afterEach] tore the failing test's containers down; falls back to a live report for a
     * failure this extension never reached an `afterTestExecution` phase for (e.g. one thrown
     * from `beforeAll`/`beforeEach` itself). Guarded by [runCatching] so a diagnostics failure
     * (or simply nothing running) can never mask the real test failure that triggered this
     * callback. */
    override fun testFailed(ctx: ExtensionContext, cause: Throwable) {
        val snapshot = runCatching { ctx.getStore(ns).get(DIAGNOSTICS_KEY) as? String }.getOrNull()
        runCatching { System.err.println(snapshot ?: Diagnostics.report()) }
    }
}
