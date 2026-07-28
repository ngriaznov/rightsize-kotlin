package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node Redpanda broker (Kafka API-compatible) with its schema registry enabled.
 *
 * ### Defaults to `redpandadata/redpanda:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with Redpanda's own releases instead of this library's
 * release cycle. This module previously pinned `redpandadata/redpanda:v24.2.4` — note the `v`
 * prefix Redpanda's own version tags carry, unlike `latest` — pass that image explicitly to pin
 * it: `RedpandaContainer("redpandadata/redpanda:v24.2.4")`.
 */
class RedpandaContainer(image: DockerImageName) : GenericContainer<RedpandaContainer>(image.toString()) {
    /** Defaults to `redpandadata/redpanda:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "redpandadata/redpanda:latest") : this(DockerImageName.parse(image))

    private companion object {
        // The alias siblings resolve INTERNAL through — native docker networks, or the msb
        // exec-tunnel alias emulation (best-effort; mirage has no sibling Kafka consumers).
        const val INTERNAL_ALIAS = "redpanda"
        const val EXPECTED_REPOSITORY = "redpandadata/redpanda"
    }

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(9092, 9093, 8081)
        waitingFor(Wait.forLogMessage(".*Successfully started Redpanda.*"))
    }

    /**
     * The advertised listener must carry the *mapped host port*, known only now (ports were
     * allocated before boot). This is why `customizeSpec` takes `mapped` — a one-shot rewrite the
     * instant before create, so the broker advertises an address host clients can actually reach.
     * EXTERNAL advertises that mapped host port for host JVM clients; INTERNAL advertises the
     * fixed alias:port siblings resolve on the container network. See [KafkaContainer.customizeSpec]
     * for the same trick applied to a single advertised listener.
     */
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec {
        val cmd = listOf(
            "redpanda", "start", "--mode", "dev-container", "--smp", "1",
            "--kafka-addr", "EXTERNAL://0.0.0.0:9092,INTERNAL://0.0.0.0:9093",
            "--advertise-kafka-addr",
            "EXTERNAL://127.0.0.1:${mapped(9092)},INTERNAL://$INTERNAL_ALIAS:9093",
            "--schema-registry-addr", "0.0.0.0:8081",
        )
        return spec.copy(command = cmd)
    }

    /** The `PLAINTEXT://` bootstrap-servers address for the running broker (EXTERNAL listener). */
    val bootstrapServers: String get() = "PLAINTEXT://$host:${getMappedPort(9092)}"
    /** The schema registry's base URI for the running broker. */
    val schemaRegistryUrl: String get() = "http://$host:${getMappedPort(8081)}"
}
