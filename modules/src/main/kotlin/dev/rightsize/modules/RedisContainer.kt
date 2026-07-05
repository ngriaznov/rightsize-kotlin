package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/**
 * A single-node Redis container. Readiness is anchored on Redis's own
 * "Ready to accept connections" log line rather than a TCP probe: on a loaded
 * host the port forwarder can accept and hold a connection in the window
 * between Redis binding its socket and actually serving, which a bare
 * listening-port check cannot see through.
 */
class RedisContainer(image: String = "redis:8.6-alpine") : GenericContainer<RedisContainer>(image) {
    init { withExposedPorts(6379); waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)) }
    /** A `redis://` connection URI for the running container. */
    val uri: String get() = "redis://$host:${getMappedPort(6379)}"
}
