package dev.rightsize.docker

import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rightsize.core.BackendProvider

class DockerBackendProvider : BackendProvider {
    override val name = "docker"
    override val priority = 10

    override fun isSupported(): Boolean = runCatching {
        val cfg = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val http = ZerodepDockerHttpClient.Builder()
            .dockerHost(cfg.dockerHost).sslConfig(cfg.sslConfig).build()
        DockerClientImpl.getInstance(cfg, http).pingCmd().exec()
        true
    }.getOrDefault(false)

    override fun unsupportedReason() = "no reachable Docker-API socket (Docker/Podman/Colima not running?)"

    override fun create(): DockerBackend = DockerBackend()
}
