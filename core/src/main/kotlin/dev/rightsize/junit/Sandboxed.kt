package dev.rightsize.junit

import dev.rightsize.GenericContainer
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

class SandboxedExtension : BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    private companion object {
        const val STATIC_KEY = "static"
        const val INSTANCE_KEY = "instance"
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
}
