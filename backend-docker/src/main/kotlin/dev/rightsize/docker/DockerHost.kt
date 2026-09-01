package dev.rightsize.docker

import java.net.URI

/**
 * Resolves the docker daemon endpoint this backend connects to when the caller hasn't pinned
 * one explicitly. `DOCKER_HOST` always wins when set; otherwise the default is the per-OS
 * socket the docker CLI itself defaults to: the Unix domain socket everywhere except Windows,
 * and Windows' named pipe there (`\\.\pipe\docker_engine`, spelled `npipe:////./pipe/docker_engine`
 * as a URI) — the same pipe Docker Desktop's WSL2 backend serves its API over.
 *
 * docker-java's own [com.github.dockerjava.core.DefaultDockerClientConfig] already resolves
 * to these exact two defaults internally (gated on `SystemUtils.IS_OS_WINDOWS`) when no host is
 * given, so [resolve] doesn't change what either platform connects to — it exists so that
 * choice is visible in this repo's own code and unit-testable via [env]/[osName] injection
 * (see [DockerHostTest]) rather than only provable on an actual Windows machine.
 */
internal object DockerHost {
    const val UNIX_DEFAULT = "unix:///var/run/docker.sock"
    const val WINDOWS_DEFAULT = "npipe:////./pipe/docker_engine"

    fun resolve(
        env: Map<String, String> = System.getenv(),
        osName: String = System.getProperty("os.name") ?: "",
    ): URI {
        env["DOCKER_HOST"]?.takeIf { it.isNotBlank() }?.let { return URI.create(it) }
        return URI.create(if (isWindows(osName)) WINDOWS_DEFAULT else UNIX_DEFAULT)
    }

    /** The same test Apache Commons Lang3's `SystemUtils.IS_OS_WINDOWS` uses — `os.name` is
     * always one of "Windows 10"/"Windows 11"/"Windows Server 2019", etc., never bare "Windows". */
    internal fun isWindows(osName: String): Boolean = osName.startsWith("Windows", ignoreCase = true)
}
