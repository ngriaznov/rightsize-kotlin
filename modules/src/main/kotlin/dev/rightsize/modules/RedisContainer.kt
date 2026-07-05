package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/** A single-node Redis container. */
class RedisContainer(image: String = "redis:8.6-alpine") : GenericContainer<RedisContainer>(image) {
    init { withExposedPorts(6379); waitingFor(Wait.forListeningPort()) }
    /** A `redis://` connection URI for the running container. */
    val uri: String get() = "redis://$host:${getMappedPort(6379)}"
}
