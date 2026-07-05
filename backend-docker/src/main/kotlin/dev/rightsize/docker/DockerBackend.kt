package dev.rightsize.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.InternalServerErrorException
import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.ContainerNetwork
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import com.github.dockerjava.api.model.StreamType
import com.github.dockerjava.api.model.Volume
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import dev.rightsize.RunId
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.PortBindConflictException
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Fallback backend for hosts without a microVM runtime (Intel Macs, Windows, no-KVM).
 * Also serves as the correctness oracle for the contract suite. Uses native Docker
 * networks, so `installNetworkLinks` stays the interface's no-op default.
 */
class DockerBackend : SandboxBackend {
    override val name = "docker"
    override val supportsNativeNetworks = true

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val RESPONSE_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val STOP_TIMEOUT_SEC = 10
    }

    private val client: DockerClient by lazy {
        val cfg = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        DockerClientImpl.getInstance(
            cfg,
            ZerodepDockerHttpClient.Builder()
                .dockerHost(cfg.dockerHost).sslConfig(cfg.sslConfig)
                .connectionTimeout(CONNECT_TIMEOUT).responseTimeout(RESPONSE_TIMEOUT).build(),
        )
    }

    private class Handle(override val id: String, override val spec: ContainerSpec) : SandboxHandle

    override fun create(spec: ContainerSpec): SandboxHandle {
        pullIfMissing(spec.image)
        val ports = spec.ports.map {
            PortBinding(
                Ports.Binding.bindIpAndPort("127.0.0.1", it.hostPort),
                ExposedPort.tcp(it.guestPort),
            )
        }
        val binds = spec.mounts.map {
            Bind(it.hostPath.toString(), Volume(it.guestPath), if (it.readOnly) AccessMode.ro else AccessMode.rw)
        }
        val host = HostConfig.newHostConfig().withPortBindings(ports).withBinds(binds)
            .withExtraHosts("host.docker.internal:host-gateway")
        spec.memoryLimitMb?.let { host.withMemory(it * 1024 * 1024) }
        val cmd = client.createContainerCmd(spec.image)
            .withName(spec.name)
            .withEnv(spec.env.map { (k, v) -> "$k=$v" })
            .withExposedPorts(spec.ports.map { ExposedPort.tcp(it.guestPort) })
            .withHostConfig(host)
            .withLabels(mapOf("dev.rightsize.runId" to spec.runId))
        spec.command?.let { cmd.withCmd(it) }
        val id = cmd.exec().id
        spec.networkId?.let { netId ->
            client.connectToNetworkCmd().withContainerId(id).withNetworkId(ensureNetworkGetId(netId))
                .withContainerNetwork(ContainerNetwork().withAliases(spec.aliases)).exec()
        }
        return Handle(id, spec)
    }

    override fun start(handle: SandboxHandle) {
        try {
            client.startContainerCmd(handle.id).exec()
        } catch (e: InternalServerErrorException) {
            // Docker's daemon reports a host-port bind failure as a 500 with a message like
            // "driver failed programming external connectivity ... address already in use" or
            // "Bind for 0.0.0.0:PORT failed: port is already allocated" — there is no distinct
            // exception type for it, so classify by message and re-throw typed for the retry
            // loop in GenericContainer to catch without grepping strings itself.
            val m = e.message?.lowercase() ?: ""
            if ("already in use" in m || "already allocated" in m) {
                throw PortBindConflictException(
                    "docker could not bind a host port for ${handle.id}: ${e.message}", e)
            }
            throw e
        }
    }

    override fun stop(handle: SandboxHandle) {
        runCatching { client.stopContainerCmd(handle.id).withTimeout(STOP_TIMEOUT_SEC).exec() }
    }

    override fun remove(handle: SandboxHandle) {
        runCatching { client.removeContainerCmd(handle.id).withForce(true).exec() }
    }

    override fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult {
        val exec = client.execCreateCmd(handle.id).withCmd(*cmd.toTypedArray())
            .withAttachStdout(true).withAttachStderr(true).exec()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        client.execStartCmd(exec.id).exec(object : ResultCallback.Adapter<Frame>() {
            override fun onNext(f: Frame) {
                (if (f.streamType == StreamType.STDERR) err else out).writeBytes(f.payload)
            }
        }).awaitCompletion()
        val code = client.inspectExecCmd(exec.id).exec().exitCodeLong?.toInt() ?: -1
        return ExecResult(code, out.toString(), err.toString())
    }

    override fun logs(handle: SandboxHandle): String {
        val buf = StringBuilder()
        client.logContainerCmd(handle.id).withStdOut(true).withStdErr(true).withTail(1000)
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(f: Frame) {
                    buf.append(String(f.payload))
                }
            }).awaitCompletion()
        return buf.toString()
    }

    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit): AutoCloseable {
        val assembler = LineAssembler()
        val cb = client.logContainerCmd(handle.id).withStdOut(true).withStdErr(true)
            .withFollowStream(true).withTailAll()
            .exec(object : ResultCallback.Adapter<Frame>() {
                override fun onNext(f: Frame) = assembler.feed(String(f.payload)).forEach(consumer)
                override fun onComplete() { assembler.flush()?.let(consumer); super.onComplete() }
                override fun onError(throwable: Throwable) { assembler.flush()?.let(consumer); super.onError(throwable) }
            })
        return AutoCloseable { cb.close() }
    }

    private val networkIds = ConcurrentHashMap<String, String>()

    override fun ensureNetwork(networkId: String) {
        ensureNetworkGetId(networkId)
    }

    private fun ensureNetworkGetId(networkId: String): String = networkIds.computeIfAbsent(networkId) {
        client.listNetworksCmd().withNameFilter(it).exec().firstOrNull()?.id
            ?: client.createNetworkCmd().withName(it).exec().id
    }

    override fun removeNetwork(networkId: String) {
        networkIds.remove(networkId)?.let { runCatching { client.removeNetworkCmd(it).exec() } }
    }

    private fun pullIfMissing(image: String) {
        val present = runCatching { client.inspectImageCmd(image).exec() }.isSuccess
        if (!present) client.pullImageCmd(image).start().awaitCompletion()
    }

    override fun close() {
        runCatching {
            client.listContainersCmd()
                .withLabelFilter(mapOf("dev.rightsize.runId" to RunId.value))
                .withShowAll(true).exec()
                .forEach { c -> runCatching { client.removeContainerCmd(c.id).withForce(true).exec() } }
        }
    }

    /**
     * Reassembles complete lines out of a stream of frames whose boundaries are chunking
     * artifacts, not line breaks — a single log line can straddle two frames. [feed] returns only
     * the lines completed by this chunk, buffering any trailing partial line to prepend to the
     * next one. [flush] hands back that trailing fragment once at stream end (a workload's final
     * output line is often unterminated) — idempotent, since the terminal docker-java callback
     * (onComplete/onError) can fire more than once for the same stream close.
     */
    private class LineAssembler {
        private var pending = StringBuilder()
        private var flushedOnce = false

        fun feed(text: String): List<String> {
            val combined = pending.toString() + text
            val lines = combined.lines()
            // lines() always yields at least one element; the last element is either the
            // trailing partial line (no terminating '\n' yet) or "" (text ended in '\n').
            val complete = lines.dropLast(1)
            val tail = if (combined.endsWith("\n")) "" else lines.last()
            pending = StringBuilder(tail)
            return complete.filter { it.isNotEmpty() }
        }

        fun flush(): String? {
            if (flushedOnce) return null
            flushedOnce = true
            return pending.toString().takeIf { it.isNotEmpty() }
        }
    }
}
