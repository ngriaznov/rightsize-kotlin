package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node MongoDB container running as a one-member replica set (required for
 * transactions/change streams). [containerIsStarted] initiates the replica set and waits for a
 * primary to be elected before [start] returns, so [connectionString] is always usable
 * immediately after `start()`.
 *
 * ### Defaults to `mongo:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with MongoDB's own releases instead of this library's
 * release cycle. This module previously pinned `mongo:8.0`; pass that image explicitly to
 * pin it: `MongoDBContainer("mongo:8.0")`.
 */
class MongoDBContainer(image: DockerImageName) : GenericContainer<MongoDBContainer>(image.toString()) {
    /** Defaults to `mongo:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "mongo:latest") : this(DockerImageName.parse(image))

    private companion object {
        /**
         * 180s, not the 60s this module used while it pinned `mongo:8.0`. A loaded Windows CI
         * runner was observed failing `rs.initiate` at the 60s mark against the floating default
         * (`mongo:latest`, 8.2.12 at the time), on a run whose whole suite took 28 minutes. This
         * matches the budget [MySQLContainer] and [ClickHouseContainer] already carry for the
         * same reason: a first-boot sequence that is comfortable locally and marginal on a
         * contended runner.
         */
        const val REPLICA_SET_TIMEOUT_MS = 180_000L
        const val POLL_INTERVAL_MS = 500L
        const val EXPECTED_REPOSITORY = "mongo"
    }

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(27017)
        withCommand("mongod", "--replSet", "docker-rs", "--bind_ip_all")
        waitingFor(Wait.forListeningPort())
    }

    override fun containerIsStarted() {
        initiateReplicaSet()   // retry through the proxy-accepts-before-mongod-listens race
        awaitPrimaryElected()
    }

    private fun initiateReplicaSet() = pollUntil("rs.initiate to succeed") {
        execInContainer("mongosh", "--quiet", "--eval",
            "try { rs.status() } catch (e) { rs.initiate() }").exitCode == 0
    }

    private fun awaitPrimaryElected() = pollUntil("a PRIMARY to be elected") {
        execInContainer("mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary")
            .stdout.trim().endsWith("true")
    }

    /** Polls [cond] every [POLL_INTERVAL_MS] up to [REPLICA_SET_TIMEOUT_MS]; fails naming [what] on timeout. */
    private inline fun pollUntil(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + REPLICA_SET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(cond).getOrDefault(false)) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        error("Mongo replica set on $host:${getMappedPort(27017)} did not reach '$what' within " +
            "${REPLICA_SET_TIMEOUT_MS / 1000}s")
    }

    /** A `mongodb://` connection string for the running container's `test` database. */
    val connectionString: String get() = "mongodb://$host:${getMappedPort(27017)}/test?directConnection=true"
    /** Alias for [connectionString]; the container is always a (single-node) replica set. */
    val replicaSetUrl: String get() = connectionString
}
