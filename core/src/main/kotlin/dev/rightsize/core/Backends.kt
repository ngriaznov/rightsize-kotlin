package dev.rightsize.core

import dev.rightsize.core.reaper.Reaper
import java.util.ServiceLoader

object Backends {
    @Volatile private var cached: SandboxBackend? = null

    fun active(): SandboxBackend =
        cached ?: synchronized(this) {
            cached ?: resolve(
                ServiceLoader.load(BackendProvider::class.java).toList(),
                System.getenv("RIGHTSIZE_BACKEND"),
            ).also {
                cached = it
                // Init-time orphan-reaping sweep: once per process, right after a backend
                // resolves — mirrors the shutdown-hook registration just below. See
                // dev.rightsize.core.reaper.Reaper / docs/reaping.md.
                Reaper.onBackendResolved(it)
                Runtime.getRuntime().addShutdownHook(Thread { it.close(); Reaper.onProcessExit() })
            }
        }

    internal fun resolve(providers: List<BackendProvider>, requested: String?): SandboxBackend {
        require(providers.isNotEmpty()) {
            "No rightsize backends on the classpath — add rightsize-backend-microsandbox and/or rightsize-backend-docker"
        }
        if (requested != null) {
            val p = providers.find { it.name.equals(requested, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "RIGHTSIZE_BACKEND='$requested' — known backends are: ${providerNames(providers)}")
            check(p.isSupported()) { "Requested backend '${p.name}' unavailable: ${p.unsupportedReason()}" }
            return p.create()
        }
        val supported = providers.sortedByDescending { it.priority }.firstOrNull { it.isSupported() }
            ?: throw IllegalStateException(
                "No sandbox backend can run on this machine:\n" + unsupportedReasons(providers))
        return supported.create()
    }

    private fun providerNames(providers: List<BackendProvider>) = providers.joinToString { it.name }

    private fun unsupportedReasons(providers: List<BackendProvider>) =
        providers.joinToString("\n") { "  - ${it.name}: ${it.unsupportedReason()}" }
}
