package dev.rightsize.docker

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rightsize.core.BackendProvider
import java.time.Duration

class DockerBackendProvider : BackendProvider {
    override val name = "docker"
    override val priority = 10

    private companion object {
        // Same order of magnitude as the rust lane's 2s detection budget: a live daemon answers
        // /version in milliseconds, so this only bites a daemon that accepts a connection (TCP
        // or the npipe/unix local-socket path) and then stalls before responding.
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)
    }

    // This backend only ever runs Linux containers, so a reachable daemon isn't enough: a
    // Windows-containers dockerd (the one GitHub's Windows runners bring up for their own CI
    // needs) answers the API just fine but can't run our images. GET /version's "Os" field
    // (docker-java's Version.operatingSystem) tells them apart without needing a container.
    override fun isSupported(): Boolean = isSupported(::daemonOs)

    /** Same [runCatching]-to-`false` budget the old `pingCmd()` probe had: [probeOs] now carries
     * its own bounded deadline ([PROBE_TIMEOUT], wired into the client [daemonOs] builds), and
     * any failure — connect error, timeout, malformed body — degrades to unsupported rather than
     * throwing. Internal so tests can substitute [probeOs] without a live daemon. */
    internal fun isSupported(probeOs: () -> String?): Boolean = runCatching {
        probeOs()?.equals("linux", ignoreCase = true) == true
    }.getOrDefault(false)

    private fun daemonOs(): String? =
        daemonOs(DefaultDockerClientConfig.createDefaultConfigBuilder().build())

    /** Split out from the zero-arg [daemonOs] so tests can point this exact client-build +
     * version-query path at a fixture daemon (e.g. a socket that accepts a connection and never
     * answers) instead of a live one, without touching `DOCKER_HOST`/context resolution.
     * Internal for that seam. */
    internal fun daemonOs(cfg: DockerClientConfig): String? {
        // Deliberately plain about dockerHost/context-resolution: see DockerHost's doc comment
        // and DockerContextResolutionTest for why forcing a host in `cfg` (even just to restate
        // the platform default) would silently defeat docker CLI context resolution and report
        // a reachable daemon unsupported. The timeouts below are orthogonal to that concern —
        // they bound how long a connection, once resolved, may take to answer — and match the
        // pattern DockerBackend's own client already uses.
        val http = ZerodepDockerHttpClient.Builder()
            .dockerHost(cfg.dockerHost).sslConfig(cfg.sslConfig)
            .connectionTimeout(PROBE_TIMEOUT).responseTimeout(PROBE_TIMEOUT).build()
        return DockerClientImpl.getInstance(cfg, http).versionCmd().exec().operatingSystem
    }

    override fun unsupportedReason() =
        "no reachable Docker-API socket serving a linux daemon (Docker/Podman/Colima not " +
            "running, or running a Windows-containers daemon)"

    override fun create(): DockerBackend = DockerBackend()
}
