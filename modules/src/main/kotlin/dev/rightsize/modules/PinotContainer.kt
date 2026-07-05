package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-container Apache Pinot "QuickStart" cluster: controller + broker + server + minion +
 * an embedded ZooKeeper, all as one process tree inside one image, started with `QuickStart -type
 * EMPTY` (a clean cluster with no demo tables — this module is a real-cluster smoke fixture, not
 * a data-loading harness).
 *
 * ### Ports — empirically verified, not the QuickStart docs' assumption
 *
 * The controller REST API is on 9000 as documented. The broker's **query** port is **8000**, not
 * 8099 — confirmed from a real `QuickStart -type EMPTY` boot log:
 *
 * ```
 * StartControllerCommand ... -controllerPort 9000 ...
 * INFO: Started listener bound to [0.0.0.0:9000]
 * StartBrokerCommand ... -brokerPort 8000 -brokerGrpcPort 8010 ...
 * INFO: Started listener bound to [0.0.0.0:8000]
 * ```
 *
 * `curl http://<host>:8000/health` returns `503` while the broker is still registering with the
 * cluster and `200` once it's live; 8099 is not opened by QuickStart at all. This module exposes
 * 8000 and names the helper `brokerUrl` accordingly.
 *
 * ### Memory — measured, not assumed
 *
 * QuickStart runs a ZooKeeper + controller + broker + server + minion JVM in one container, and
 * the image itself bakes in `JAVA_OPTS=-Xms4G -Xmx4G` (`docker inspect`'d directly) — the
 * QuickStartRunner driver JVM alone wants a 4 GiB heap before the four sub-JVMs it spawns take
 * anything. An analogy with SpringCloudConfig's single-JVM 1024 MB floor (double it for a
 * cluster: 2048) badly under-shoots. Measured directly:
 *
 * ```
 * docker run --memory=2048m ... apachepinot/pinot:1.5.1 QuickStart -type EMPTY
 *   -> OOMKilled=true (timed out at 180s waiting for /health, container reaped by the kernel)
 * docker run --memory=2560m ... (same image/command) -> OOMKilled=true
 * docker run --memory=3072m ... (same image/command)
 *   -> OOMKilled=false; controller/broker /health both 200 within ~15s; BUT `docker stats`
 *      settles at ~99% of the 3 GiB limit — and under that pressure the controller's Helix-backed
 *      schema/table-config RPCs intermittently time out (`{"code":500,"error":
 *      "java.util.concurrent.TimeoutException"}` on a schema POST) even though /health reports
 *      200. Reproduced repeatedly at 3072m; NOT reproduced at 4096m (`docker stats` settles at
 *      ~73-75% of the limit, comfortable headroom; schema POST succeeded on every attempt across
 *      a 60s repeated-POST probe).
 * ```
 *
 * So [withMemoryLimit] is **4096 MB** — the lowest round number that leaves real headroom above
 * the image's own 4 GiB heap request, not merely enough to avoid the OOM killer. Verified stable
 * on both Docker and microsandbox.
 */
class PinotContainer(image: String = "apachepinot/pinot:1.5.1") : GenericContainer<PinotContainer>(image) {
    init {
        withExposedPorts(CONTROLLER_PORT, BROKER_PORT)
        withCommand("QuickStart", "-type", "EMPTY")
        // Four JVMs (ZK, controller, broker, server/minion) in one container, image bakes in
        // -Xmx4G — see the class doc for the measured 2048/3072 MB failures and the 4096 MB fix.
        withMemoryLimit(4096)
        // A four-JVM cluster booting cold on a laptop is legitimately slow (observed 60-120s);
        // the controller's REST listener is up well before the whole cluster has stabilized, but
        // it's the only readiness signal that's both meaningful and doesn't require polling the
        // broker separately before every test can proceed.
        waitingFor(Wait.forHttp("/health").forPort(CONTROLLER_PORT).withStartupTimeout(Duration.ofSeconds(180)))
    }

    /** The controller's REST base URI (schema/table/segment admin). */
    val controllerUrl: String get() = "http://$host:${getMappedPort(CONTROLLER_PORT)}"
    /** The broker's query base URI (port 8000 — see the class doc on the 8099 assumption). */
    val brokerUrl: String get() = "http://$host:${getMappedPort(BROKER_PORT)}"

    private companion object {
        const val CONTROLLER_PORT = 9000
        const val BROKER_PORT = 8000
    }
}
