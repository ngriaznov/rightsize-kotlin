package dev.rightsize.msb

import java.nio.file.Files
import java.nio.file.Path

/**
 * [krunInstallName] is the exact filename `msb` resolves the library under: it probes
 * `../lib/` next to its own binary for `libkrunfw.so.<version>` on Linux and
 * `libkrunfw.<abi>.dylib` on macOS — never the release-asset name — so the provisioner
 * installs the downloaded asset under this name. The embedded libkrunfw version/ABI is
 * part of the pinned msb release; re-verify both names when bumping the pin.
 */
enum class Platform(val msbAsset: String, val krunAsset: String, val krunInstallName: String) {
    DARWIN_ARM64("msb-darwin-aarch64", "libkrunfw-darwin-aarch64.dylib", "libkrunfw.5.dylib"),
    LINUX_X64("msb-linux-x86_64", "libkrunfw-linux-x86_64.so", "libkrunfw.so.5.5.0"),
    LINUX_ARM64("msb-linux-aarch64", "libkrunfw-linux-aarch64.so", "libkrunfw.so.5.5.0");

    companion object {
        fun current(): Platform? {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            return when {
                os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> DARWIN_ARM64
                os.contains("linux") && (arch == "amd64" || arch == "x86_64") -> LINUX_X64
                os.contains("linux") && (arch == "aarch64" || arch == "arm64") -> LINUX_ARM64
                else -> null
            }
        }
        fun virtualizationAvailable(): Boolean {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("mac") -> true                       // Apple Silicon checked by current()
                os.contains("linux") -> Files.isReadable(Path.of("/dev/kvm")) && Files.isWritable(Path.of("/dev/kvm"))
                else -> false
            }
        }
    }
}
