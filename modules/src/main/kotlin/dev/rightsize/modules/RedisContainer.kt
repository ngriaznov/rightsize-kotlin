package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node Redis container. Readiness is anchored on Redis's own
 * "Ready to accept connections" log line rather than a TCP probe: on a loaded
 * host the port forwarder can accept and hold a connection in the window
 * between Redis binding its socket and actually serving, which a bare
 * listening-port check cannot see through.
 *
 * ### Defaults to `redis:latest` — this image's floating reference, now Debian-based
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins. This module previously pinned `redis:8.6-alpine`; `latest` is the Debian-based
 * variant, not Alpine — functionally equivalent for everything this module exercises, just a
 * larger pull. Pass an explicit tag (Alpine or otherwise) to pin a specific version.
 */
class RedisContainer(image: DockerImageName) : GenericContainer<RedisContainer>(image.toString()) {
    /** Defaults to `redis:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "redis:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(6379)
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
    }
    /** A `redis://` connection URI for the running container. */
    val uri: String get() = "redis://$host:${getMappedPort(6379)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "redis"
    }
}
