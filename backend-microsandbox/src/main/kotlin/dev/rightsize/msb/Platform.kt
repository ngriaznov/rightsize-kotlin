package dev.rightsize.msb

import java.nio.file.Files
import java.nio.file.Path

/**
 * [krunInstallName] is the exact filename `msb` resolves the library under: it probes
 * `../lib/` next to its own binary for `libkrunfw.so.<version>` on Linux,
 * `libkrunfw.<abi>.dylib` on macOS, and `libkrunfw.dll` on Windows — never the
 * release-asset name — so the provisioner installs the downloaded asset under this
 * name. The embedded libkrunfw version/ABI is part of the pinned msb release;
 * re-verify both names when bumping the pin. On Windows the release asset itself
 * already carries an architecture-qualified name
 * (`libkrunfw-windows-x86_64.dll`/`libkrunfw-windows-aarch64.dll`); msb still expects
 * the sibling file at a fixed unversioned `libkrunfw.dll`, matching install.ps1's
 * bundle layout, so [krunInstallName] renames it on install exactly as the Linux/macOS
 * targets rename their assets to a canonical library name.
 */
enum class Platform(val msbAsset: String, val krunAsset: String, val krunInstallName: String) {
    DARWIN_ARM64("msb-darwin-aarch64", "libkrunfw-darwin-aarch64.dylib", "libkrunfw.5.dylib"),
    LINUX_X64("msb-linux-x86_64", "libkrunfw-linux-x86_64.so", "libkrunfw.so.5.5.0"),
    LINUX_ARM64("msb-linux-aarch64", "libkrunfw-linux-aarch64.so", "libkrunfw.so.5.5.0"),
    WINDOWS_X64("msb-windows-x86_64.exe", "libkrunfw-windows-x86_64.dll", "libkrunfw.dll"),
    WINDOWS_ARM64("msb-windows-aarch64.exe", "libkrunfw-windows-aarch64.dll", "libkrunfw.dll");

    /** `true` for the two Windows platform rows; drives the `.exe` binary suffix and the
     * no-execute-bit install/validity checks in [MsbProvisioner]. */
    val isWindows: Boolean get() = this == WINDOWS_X64 || this == WINDOWS_ARM64

    /** The installed binary's basename: `msb.exe` on Windows, suffixless `msb` elsewhere.
     * The release asset itself is never installed under its own name (see [msbAsset] vs.
     * this) — installation always renames to this canonical name, matching how
     * [krunInstallName] renames the krun asset. */
    val msbBinaryName: String get() = if (isWindows) "msb.exe" else "msb"

    companion object {
        fun current(): Platform? {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            return when {
                os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> DARWIN_ARM64
                os.contains("linux") && (arch == "amd64" || arch == "x86_64") -> LINUX_X64
                os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> LINUX_ARM64
                os.contains("win") && (arch == "amd64" || arch == "x86_64") -> WINDOWS_X64
                os.contains("win") && (arch == "aarch64" || arch == "arm64") -> WINDOWS_ARM64
                else -> null
            }
        }

        /**
         * On Windows this is an attempt-and-report probe, not a capability check: it returns
         * true for any detected Windows platform and lets a real WHP failure surface at first
         * boot with msb's own actionable error (`msb doctor` names the exact precondition,
         * e.g. "Windows Hypervisor Platform is not enabled"). This mirrors GitHub-hosted
         * Windows runners, which ship with WHP already enabled (confirmed empirically — see
         * the Windows spike findings), so a pre-boot probe would mostly duplicate a check msb
         * already does better. Unlike the Linux `/dev/kvm` check (a cheap, reliable read/write
         * probe with no msb involved) there is no equally cheap no-spawn WHP signal on Windows,
         * so this trades a slightly worse failure-UX edge case (a WHP-less Windows host reports
         * microsandbox supported, then fails at boot) for not shelling out to `msb doctor` on
         * every resolution call.
         */
        fun virtualizationAvailable(): Boolean {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> true                       // Apple Silicon checked by current()
                os.contains("linux") -> Files.isReadable(Path.of("/dev/kvm")) && Files.isWritable(Path.of("/dev/kvm"))
                os.contains("win") -> current() != null           // attempt-and-report; see doc above
                else -> false
            }
        }
    }
}
