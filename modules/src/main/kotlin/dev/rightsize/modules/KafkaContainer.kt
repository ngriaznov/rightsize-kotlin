package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.wait.Wait

/** A single-node Kafka broker (KRaft mode, no ZooKeeper). */
class KafkaContainer(image: String = "apache/kafka:4.0.0") : GenericContainer<KafkaContainer>(image) {
    init {
        withExposedPorts(9092)
        withEnv("KAFKA_NODE_ID", "1")
        withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
        withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9091")
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9091")
        withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
        // The apache/kafka image defaults KAFKA_HEAP_OPTS to -Xmx1G, which exceeds the
        // microsandbox microVM's default RAM and aborts the JVM ("insufficient memory").
        // A single-node KRaft dev broker runs comfortably in a 256M heap; harmless on
        // the Docker backend, which is not memory-constrained here.
        withEnv("KAFKA_HEAP_OPTS", "-Xmx256M -Xms256M")
        waitingFor(Wait.forLogMessage(".*Kafka Server started.*"))
    }
    // Rewrites the advertised listener to carry the mapped host port; see
    // [RedpandaContainer.customizeSpec] for why this needs the `mapped` callback.
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec =
        spec.copy(env = spec.env + ("KAFKA_ADVERTISED_LISTENERS" to "PLAINTEXT://127.0.0.1:${mapped(9092)}"))
    /** The `PLAINTEXT://` bootstrap-servers address for the running broker. */
    val bootstrapServers: String get() = "PLAINTEXT://$host:${getMappedPort(9092)}"
}
