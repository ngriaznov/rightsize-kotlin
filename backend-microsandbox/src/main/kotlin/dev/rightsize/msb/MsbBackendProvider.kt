package dev.rightsize.msb

import dev.rightsize.core.BackendProvider
import dev.rightsize.core.SandboxBackend

class MsbBackendProvider : BackendProvider {
    override val name = "microsandbox"
    override val priority = 20
    override fun isSupported() = Platform.current() != null && Platform.virtualizationAvailable()
    override fun unsupportedReason() = when {
        Platform.current() == null ->
            "no msb build for ${System.getProperty("os.name")}/${System.getProperty("os.arch")} (Intel Mac/Windows: use docker backend)"
        else -> "/dev/kvm is not accessible (need KVM, or run on Apple Silicon macOS)"
    }
    override fun create(): SandboxBackend =
        MsbCliBackend(MsbProvisioner.ensureInstalled()).also { it.sweepOrphans() }
}
