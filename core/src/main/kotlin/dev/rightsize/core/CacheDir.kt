package dev.rightsize.core

import java.nio.file.Path

/**
 * Resolves the rightsize cache root — originally the msb provisioner's own concern
 * ([Path] under which the msb toolchain is installed), promoted to core because the reaper's
 * ledger needs the same directory even in a docker-only process that never touches
 * `backend-microsandbox` at all. Behavior is unchanged from the provisioner's original rule:
 * `RIGHTSIZE_CACHE_DIR` wins outright if set; otherwise `~/.cache/rightsize` on macOS/Linux,
 * `%LOCALAPPDATA%\rightsize` on Windows (falling back to `user.home\AppData\Local` if
 * `LOCALAPPDATA` is unset, e.g. some minimal CI shells).
 */
object CacheDir {
    /** Real-environment, real-host-OS convenience overload for production call sites. */
    fun resolve(): Path = resolve(System.getenv(), isWindowsHost())

    /** Explicit [env]/[isWindows] overload so callers (and tests) don't depend on the actual
     * host OS or process environment — mirrors the injectable-parameter pattern
     * `MsbProvisioner.ensureInstalled` already uses for the same reason. */
    fun resolve(env: Map<String, String>, isWindows: Boolean): Path {
        env["RIGHTSIZE_CACHE_DIR"]?.let { return Path.of(it) }
        return if (isWindows) {
            val localAppData = env["LOCALAPPDATA"]
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            Path.of(localAppData, "rightsize")
        } else {
            Path.of(System.getProperty("user.home"), ".cache", "rightsize")
        }
    }

    private fun isWindowsHost(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
