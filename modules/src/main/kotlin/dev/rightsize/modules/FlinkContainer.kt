package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.Network
import dev.rightsize.core.UnsupportedByBackendException
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * An Apache Flink **JobManager**, optionally paired with a companion **TaskManager** via
 * [withTaskManager] for a real session cluster that can actually run jobs (a bare JobManager has
 * zero task slots and can only accept/reject submissions, never execute them).
 *
 * ### Topology
 *
 * A real Flink session cluster is two processes that must find each other over a **persistent
 * bidirectional RPC connection** (Pekko/Akka remoting): the TaskManager dials the JobManager's
 * RPC port (6123) at boot and *stays connected* — registration is not a one-shot request/response,
 * it is the first message on a connection the TaskManager keeps open for as long as it runs,
 * carrying heartbeats and slot offers/task deployments both ways for the cluster's whole lifetime.
 * [withTaskManager] puts both containers on one internally-created [Network], aliases this
 * JobManager as `"jobmanager"`, and sets `FLINK_PROPERTIES=jobmanager.rpc.address: jobmanager` on
 * **both** containers (the image's own env-driven config mechanism) — not just the TaskManager.
 * Verified directly: setting it on the TaskManager alone leaves the JobManager's own Pekko actor
 * system bound under its container hostname rather than the alias, so every registration attempt
 * from the TaskManager (correctly dialing `pekko.tcp://flink@jobmanager:6123`) gets silently
 * dropped as a non-local recipient (`dropping message ... arriving at
 * [pekko.tcp://flink@jobmanager:6123] inbound addresses are [pekko.tcp://flink@&lt;container-id&gt;:6123]`)
 * — the JobManager must be told its own address is the alias too.
 *
 * ### Backend support — full on docker, JobManager-only on msb
 *
 * **Docker**: verified end-to-end — the TaskManager registers with the JobManager
 * (`Successful registration at resource manager ...` in its own log) and `GET /taskmanagers` on
 * the JobManager's REST port shows one slot-bearing TM within seconds of both containers starting.
 *
 * **microsandbox**: [withTaskManager] throws [UnsupportedByBackendException] before ever booting
 * anything. The actual blocker is more basic than a Pekko/tunnel incompatibility: msb's
 * `installNetworkLinks` requires `nc`/busybox *inside the consumer image* to serve the tunnel's
 * in-guest listener, and the official `flink:1.20.5` image is a bare JRE + Flink install with
 * neither — the attempt failed immediately with `UnsupportedByBackendException: network links
 * (no nc/busybox in consumer image 'flink:1.20.5')`, thrown from `MsbCliBackend.requireNcAvailable`
 * before a single byte of Pekko traffic could be exchanged. Whether Pekko's persistent-connection
 * RPC registration would work over the tunnel's single-connection-at-a-time model was never
 * reached or tested — the missing `nc`/busybox prerequisite stops the attempt before that question
 * is even in play. A bare JobManager (REST `/overview` only, no TM) works fine on msb — it needs
 * no network-link emulation at all, just the ordinary published-port HTTP path — so this module
 * still supports msb for JobManager-only use; only [withTaskManager] is gated.
 *
 * ### Memory — JVM, ladder applies to both roles
 *
 * A JobManager settles around ~310 MiB RSS and a TaskManager around ~375 MiB RSS at rest on
 * docker with no cap (`docker stats`, real boot) — both comfortably over msb's ~450 MB default
 * *individually*, and this module runs the JobManager on msb too (see above), so
 * `withMemoryLimit(1024)` is this module's default for both roles, matching the family's
 * established single-JVM floor ([KeycloakContainer], [Neo4jContainer]).
 *
 * No control characters were found in the image's baked env (checked via `docker image
 * inspect`).
 */
class FlinkContainer(image: String = "flink:1.20.5") : GenericContainer<FlinkContainer>(image) {
    private val jobManagerAlias = "jobmanager"
    private var network: Network? = null
    private var taskManager: GenericContainer<*>? = null
    private val taskManagerImage = image

    init {
        withExposedPorts(REST_PORT, RPC_PORT)
        withCommand("jobmanager")
        withMemoryLimit(1024)
        waitingFor(Wait.forHttp("/overview").forPort(REST_PORT).withStartupTimeout(Duration.ofSeconds(120)))
    }

    /**
     * Adds a companion TaskManager, giving the cluster real task slots. **Docker only** — throws
     * [UnsupportedByBackendException] on microsandbox (see the class doc's backend-support
     * section); the remedy is to run this test under the docker backend instead. Must be called
     * before [start].
     */
    fun withTaskManager(): FlinkContainer {
        if (backend.name == "microsandbox") throw UnsupportedByBackendException(
            "Flink TaskManager topology (network links; no nc/busybox in the flink image)", backend.name,
            remedy = "run this test with RIGHTSIZE_BACKEND=docker instead; JobManager-only " +
                "(no withTaskManager()) is still supported on microsandbox",
        )
        val net = network ?: Network.newNetwork().also { network = it }
        withNetwork(net).withNetworkAliases(jobManagerAlias)
        // The JobManager must ALSO be told its own rpc.address is the alias, not its container
        // hostname — otherwise its Pekko actor system binds as "flink@<container-id>" while the
        // TaskManager (below) dials "flink@jobmanager", and every registration attempt is
        // silently dropped as a non-local recipient (reproduced directly; see the class doc).
        withEnv("FLINK_PROPERTIES", "jobmanager.rpc.address: $jobManagerAlias")
        taskManager = TaskManagerContainer(taskManagerImage, net, jobManagerAlias)
        return this
    }

    override fun start() {
        super.start()
        taskManager?.start()
    }

    override fun stop() {
        taskManager?.stop()
        super.stop()
        // The Network was created internally by withTaskManager(), not handed in by the caller
        // (unlike the CrossSandboxNetworkIT.Network.newNetwork().use{} pattern) — so this module
        // owns closing it too, or a repeated-boot test/process leaks one docker network per run.
        network?.close()
    }

    /** The JobManager REST base URI (`/overview`, `/taskmanagers`, job submission, etc.). */
    val restUrl: String get() = "http://$host:${getMappedPort(REST_PORT)}"

    /** The companion started by [withTaskManager]; a plain [GenericContainer] needs no helpers of its own. */
    private class TaskManagerContainer(image: String, net: Network, jobManagerAlias: String) :
        GenericContainer<TaskManagerContainer>(image) {
        init {
            withCommand("taskmanager")
            withNetwork(net)
            withEnv("FLINK_PROPERTIES", "jobmanager.rpc.address: $jobManagerAlias")
            withMemoryLimit(1024)
        }
    }

    private companion object {
        const val REST_PORT = 8081
        const val RPC_PORT = 6123
    }
}
