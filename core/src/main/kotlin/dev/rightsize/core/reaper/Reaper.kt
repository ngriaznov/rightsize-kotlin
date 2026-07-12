package dev.rightsize.core.reaper

import dev.rightsize.core.CacheDir
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.SandboxBackend

/**
 * Process-wide reaping entry point — a thin holder of one real-process [ReaperEngine]
 * (`RIGHTSIZE_REAPER`, the real cache dir, this JVM's own pid/start time). All the actual
 * logic lives in [ReaperEngine], unit-tested directly against temp directories and fake
 * backends; this object exists only so [dev.rightsize.core.Backends] and
 * [dev.rightsize.GenericContainer]/[dev.rightsize.Network] have one process-wide instance to
 * call into. See docs/reaping.md for the mechanism.
 */
object Reaper {
    private val engine = ReaperEngine(
        ReaperMode.from(System.getenv("RIGHTSIZE_REAPER")),
        CacheDir.resolve(),
        ProcessHandle.current().pid(),
        Liveness.currentProcessStartedIso(),
    )

    fun onBackendResolved(backend: SandboxBackend) = engine.onBackendResolved(backend)
    fun beforeCreate(backend: SandboxBackend, spec: ContainerSpec) = engine.beforeCreate(backend, spec)
    fun afterRemove(spec: ContainerSpec) = engine.afterRemove(spec)
    fun beforeNetworkCreate(backend: SandboxBackend, networkId: String) = engine.beforeNetworkCreate(backend, networkId)
    fun afterNetworkRemoved(networkId: String) = engine.afterNetworkRemoved(networkId)
    fun onProcessExit() = engine.onProcessExit()
}
