package dev.rightsize.msb

import dev.rightsize.core.BackendProvider
import dev.rightsize.core.SandboxBackend

class MsbBackendProvider : BackendProvider {
    override val name = "microsandbox"
    override val priority = 20
    override fun isSupported() = Platform.current() != null && Platform.virtualizationAvailable()
    override fun unsupportedReason() = when {
        Platform.current() == null ->
            "no msb build for ${System.getProperty("os.name")}/${System.getProperty("os.arch")} (Intel Mac: use docker backend)"
        Platform.current()?.isWindows == true ->
            "Windows Hypervisor Platform is not enabled (run `msb doctor --fix` in an elevated " +
                "terminal, which may require a reboot), or use the docker backend"
        else -> "/dev/kvm is not accessible (need KVM, or run on Apple Silicon macOS)"
    }
    // Orphan reaping is now the ledger-based sweep in dev.rightsize.core.reaper.Reaper,
    // triggered once per process from Backends.active() right after this resolves — see
    // docs/reaping.md. The old liveness-blind msb-ls-scan sweep this used to run here is gone.
    override fun create(): SandboxBackend = MsbCliBackend(MsbProvisioner.ensureInstalled())
}
