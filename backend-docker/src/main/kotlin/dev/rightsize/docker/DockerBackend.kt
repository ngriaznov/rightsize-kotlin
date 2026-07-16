package dev.rightsize.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.exception.ConflictException
import com.github.dockerjava.api.exception.InternalServerErrorException
import com.github.dockerjava.api.exception.NotFoundException
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
import dev.rightsize.core.BackendCapabilities
import dev.rightsize.core.ContainerCopyException
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.PortBindConflictException
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import dev.rightsize.core.WatchdogCommands
import dev.rightsize.core.reuse.SandboxNameCollisionException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Fallback backend for hosts without a microVM runtime (Intel Macs, Windows, no-KVM).
 * Also serves as the correctness oracle for the contract suite. Uses native Docker
 * networks, so `installNetworkLinks` stays the interface's no-op default.
 */
class DockerBackend : SandboxBackend {
    override val name = "docker"
    override val supportsNativeNetworks = true
    // Containers share the host kernel (not hardware-isolated); a running container can be
    // committed to an image without disturbing it, so checkpoint-style capture is possible here
    // and never restarts the workload — see GenericContainer.checkpoint() and
    // BackendCapabilities' doc comment.
    override val capabilities = BackendCapabilities(
        hardwareIsolated = false, checkpoint = true, checkpointRestartsWorkload = false)

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val RESPONSE_TIMEOUT: Duration = Duration.ofMinutes(10)
        const val STOP_TIMEOUT_SEC = 10
        // `docker cp`'s own timeout (see copyToContainer/copyFromContainer) — generous for a
        // large directory copy, same order of magnitude as MsbCliBackend's EXEC_TIMEOUT_SEC.
        const val COPY_TIMEOUT_SEC = 120L
        // Reuse container names are "rz-reuse-<12hex-of-hash>" — see docs/reuse.md.
        const val REUSE_NAME_PREFIX = "rz-reuse-"
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

    /**
     * `dev.rightsize.runId` labels a normal, own-run container so [close]'s shutdown-time
     * sweep (and, on the ledger's sweep path, the reaper) can find every container this
     * process started. A `keepAlive` container (container reuse, see docs/reuse.md) must stay
     * out of that own-run cleanup path entirely: it gets `dev.rightsize.reuse=<12hex>` instead
     * of the run-id label, so [close]'s `dev.rightsize.runId` label filter structurally never
     * matches it. The 12-hex value is the reuse-name suffix (`rz-reuse-<12hex>`) — a `keepAlive`
     * spec whose name doesn't follow that shape is a caller bug this rejects rather than
     * silently mislabeling.
     */
    internal fun labelsFor(spec: ContainerSpec): Map<String, String> =
        if (spec.keepAlive) mapOf("dev.rightsize.reuse" to reuseHashOf(spec.name))
        else mapOf("dev.rightsize.runId" to spec.runId)

    private fun reuseHashOf(name: String): String {
        require(name.startsWith(REUSE_NAME_PREFIX)) {
            "keepAlive ContainerSpec.name must be '$REUSE_NAME_PREFIX<12hex>', got '$name'"
        }
        return name.removePrefix(REUSE_NAME_PREFIX)
    }

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
            .withLabels(labelsFor(spec))
        spec.command?.let { cmd.withCmd(it) }
        val id = try {
            cmd.exec().id
        } catch (e: ConflictException) {
            // The daemon's 409 for "a container already uses this name" — the shape reuse's
            // fresh-create path retries as an adopt (see SandboxNameCollisionException's doc).
            throw SandboxNameCollisionException("docker container name '${spec.name}' is already in use", e)
        }
        spec.networkId?.let { netId ->
            client.connectToNetworkCmd().withContainerId(id).withNetworkId(ensureNetworkGetId(netId))
                .withContainerNetwork(ContainerNetwork().withAliases(spec.aliases)).exec()
        }
        return Handle(id, spec)
    }

    /**
     * Reuse's adopt path (see docs/reuse.md): resolves [name] to a live daemon id via a name
     * filter (docker-java's default `listContainersCmd` already excludes stopped containers, so
     * a hit here is necessarily running), narrowed to an exact name match — the filter itself is
     * substring/regex, not equality, same caveat [removeByName] already documents. The returned
     * handle's `spec` is a minimal reconstruction: the original creating spec is not recoverable
     * from a bare name/id, and nothing downstream of adopt (exec/logs/stop) needs more than the
     * daemon id this handle carries.
     */
    override fun findRunning(name: String): SandboxHandle? {
        val c = runCatching {
            client.listContainersCmd().withNameFilter(listOf(name)).exec()
                .firstOrNull { it.names?.any { n -> n.trimStart('/') == name } == true }
        }.getOrNull() ?: return null
        val spec = ContainerSpec(name = name, image = c.image ?: "", runId = RunId.value, keepAlive = true)
        return Handle(c.id, spec)
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

    /**
     * The reaper's init-time sweep only has a name (from another, dead process's ledger), not
     * a live [SandboxHandle]/daemon id — resolve name to id via a name filter first, same as
     * [close]'s own label-based leftover sweep does for its run. A name filter can match more
     * than exactly [name] (docker's filter is a substring/regex match, not an equality check),
     * so results are narrowed to an exact name match before removing anything.
     */
    override fun removeByName(name: String) {
        runCatching {
            client.listContainersCmd().withNameFilter(listOf(name)).withShowAll(true).exec()
                .filter { c -> c.names?.any { it.trimStart('/') == name } == true }
                .forEach { c -> runCatching { client.removeContainerCmd(c.id).withForce(true).exec() } }
        }
    }

    /** `docker rm -f`/`docker network rm` — no separate stop step, since force-remove already
     * covers a running container; argv-shaped for the reaper watchdog script (a plain OS
     * process spawned before this JVM's first sandbox, with no [DockerBackend] of its own). */
    override val watchdogCommands = WatchdogCommands(
        sandboxRemove = listOf("docker", "rm", "-f"),
        networkRemove = listOf("docker", "network", "rm"),
    )

    /**
     * Backs `GenericContainer.checkpoint()` via the engine's commit endpoint (docker-java's
     * `commitCmd`, `POST /commit?container=...&repo=...&tag=...`) — the container's current
     * filesystem becomes a new, ordinary image the daemon otherwise treats no differently from
     * a pulled one, and the container itself is left running undisturbed
     * (`capabilities.checkpointRestartsWorkload = false`). [ref] is always `repo:tag` shaped
     * (minted by `GenericContainer.checkpoint` as `rightsize/checkpoint:<12-hex>`); [repoAndTag]
     * does the split.
     */
    override fun createCheckpoint(handle: SandboxHandle, ref: String) {
        val (repo, tag) = repoAndTag(ref)
        client.commitCmd(handle.id).withRepository(repo).withTag(tag).exec()
    }

    /** `internal`, not `private`: unit-tested directly in [DockerBackendTest] without a daemon —
     * everything else about [createCheckpoint] needs a real one. */
    internal fun repoAndTag(imageRef: String): Pair<String, String> {
        val idx = imageRef.indexOf(':')
        require(idx > 0) { "imageRef must be 'repo:tag', got '$imageRef'" }
        return imageRef.substring(0, idx) to imageRef.substring(idx + 1)
    }

    /** Best-effort `docker rmi -f` of a checkpoint's image tag — "not found" is success, same
     * contract as [removeByName]. Checkpoint images are never auto-pruned (see
     * docs/checkpoints.md); this exists so tests can keep shared CI state clean. */
    override fun removeCheckpoint(ref: String) {
        runCatching { client.removeImageCmd(ref).withForce(true).exec() }
    }

    /**
     * `docker image inspect <ref>` — a [NotFoundException] means the tag genuinely doesn't exist
     * (`false`); any OTHER failure (daemon unreachable, a malformed ref, etc.) is not caught here
     * and propagates instead, per the SPI contract (see docs/checkpoints.md) — unlike
     * [removeCheckpoint]'s best-effort `runCatching`, a probe failure must never look like a
     * definite "gone".
     */
    override fun hasCheckpoint(ref: String): Boolean = probeExists { client.inspectImageCmd(ref).exec() }

    /**
     * [hasCheckpoint]'s NotFoundException-only narrowing, pulled out from behind the real daemon
     * call so it's unit-testable without a live `DockerClient` (see [DockerBackendTest]) —
     * [inspect] is expected to throw [NotFoundException] for "genuinely absent" and let any other
     * exception (daemon unreachable, etc.) propagate untouched. `internal`, not `private`, purely
     * for that direct test access.
     */
    internal fun probeExists(inspect: () -> Unit): Boolean = try {
        inspect()
        true
    } catch (e: NotFoundException) {
        false
    }

    /**
     * Runtime copy (see docs/copy.md), both directions: shells out to the `docker` CLI's own
     * `cp` subcommand rather than driving the daemon's raw archive-extract API directly.
     * `docker cp` implements `cp -r`-style destination-naming semantics (rename-on-extract for a
     * nonexistent destination) client-side, ahead of the daemon call — reimplementing that
     * correctly via `copyArchiveToContainerCmd`/`copyArchiveFromContainerCmd` would mean
     * hand-rolling the same logic against a raw tar stream. The docker CLI is already a hard
     * requirement for this backend (the reaper's watchdog commands shell out to it too), so this
     * adds no new external dependency.
     */
    override fun copyToContainer(handle: SandboxHandle, hostPath: Path, containerPath: String) =
        runDockerCli("cp", hostPath.toString(), "${handle.id}:$containerPath")

    override fun copyFromContainer(handle: SandboxHandle, containerPath: String, hostPath: Path) =
        runDockerCli("cp", "${handle.id}:$containerPath", hostPath.toString())

    /** Runs a `docker` CLI invocation to completion, draining both streams concurrently (same
     * shape as `MsbCliBackend`'s own `invoke` helper) to avoid deadlocking on a full pipe buffer.
     * Throws [ContainerCopyException] carrying stderr on a non-zero exit or a timeout — a failed
     * copy must never look like a silent success. */
    private fun runDockerCli(vararg args: String) {
        val proc = ProcessBuilder("docker", *args).start()
        val stderr = StringBuilder()
        val tOut = drain(proc.inputStream) {}
        val tErr = drain(proc.errorStream) { stderr.appendLine(it) }
        if (!proc.waitFor(COPY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ContainerCopyException("docker ${args.joinToString(" ")} timed out after ${COPY_TIMEOUT_SEC}s and was force-killed")
        }
        tOut.join(); tErr.join()
        if (proc.exitValue() != 0) {
            throw ContainerCopyException(
                "docker ${args.joinToString(" ")} failed (exit ${proc.exitValue()}): ${stderr.toString().trim()}")
        }
    }

    /** Drains [stream] on a daemon thread, one line per [onLine]; returns the thread for joining. */
    private fun drain(stream: InputStream, onLine: (String) -> Unit): Thread =
        Thread { stream.bufferedReader().forEachLine(onLine) }.apply { isDaemon = true; start() }

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

    /** `internal`, not `private`: [DockerBackendIT] uses this to get the daemon-assigned id
     * directly, to prove [removeNetwork]'s cross-instance fallback actually removed the
     * network at the daemon level (a fresh instance re-resolving the name afterward must get
     * a brand-new id) rather than merely dropping it from one instance's [networkIds] cache. */
    internal fun ensureNetworkGetId(networkId: String): String = networkIds.computeIfAbsent(networkId) {
        client.listNetworksCmd().withNameFilter(it).exec().firstOrNull()?.id
            ?: client.createNetworkCmd().withName(it).exec().id
    }

    /**
     * The reaper's init-time sweep calls this for a network from a *different*, dead process's
     * ledger — [networkIds] only ever holds ids this same [DockerBackend] instance created via
     * [ensureNetworkGetId], so a sweep-driven call here would silently no-op forever without a
     * daemon-level fallback. Falls back to a name filter, narrowed to an exact name match (same
     * defensive narrowing [removeByName] does for containers — docker's name filter is
     * substring/regex, not equality) when the id isn't cached locally.
     */
    override fun removeNetwork(networkId: String) {
        val id = networkIds.remove(networkId) ?: runCatching {
            client.listNetworksCmd().withNameFilter(networkId).exec()
                .firstOrNull { it.name == networkId }?.id
        }.getOrNull()
        id?.let { runCatching { client.removeNetworkCmd(it).exec() } }
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
