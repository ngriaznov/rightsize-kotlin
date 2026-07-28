package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node Valkey container. Valkey is Redis's community fork and speaks the same RESP
 * protocol on the same default port, so this module's shape mirrors [RedisContainer] exactly,
 * including why readiness is anchored on a log line rather than a port probe: on a loaded host
 * msb's loopback forwarder can accept and hold a TCP connection in the window between Valkey
 * binding its socket and actually serving, which a bare listening-port check cannot see through.
 * Verified directly: a real boot logs `Ready to accept connections tcp`, and `valkey-cli PING`
 * against the running container returns `PONG`.
 *
 * ### Defaults to `valkey/valkey:latest` — this image's floating reference, now Debian-based
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins. This module previously pinned `valkey/valkey:9.1-alpine`; `latest` is the
 * Debian-based variant, not Alpine — functionally equivalent for everything verified above, just
 * a larger pull. Pass an explicit tag (Alpine or otherwise) to pin a specific version.
 *
 * ### `uri` deliberately uses the `redis://` scheme, not `valkey://`
 *
 * This is not a copy-paste mistake. Every client this module's tests and users reach for —
 * lettuce, Jedis, or a raw RESP connection over TCP — parses `redis://` and has no special
 * handling for a `valkey://` scheme, because Valkey's wire protocol is RESP, unchanged from
 * Redis. `redis://` is therefore the correct and only URI scheme to hand callers here.
 *
 * No env is required to boot this image, and no memory limit was needed beyond the default
 * during verification.
 */
class ValkeyContainer(image: DockerImageName) : GenericContainer<ValkeyContainer>(image.toString()) {
    /** Defaults to `valkey/valkey:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "valkey/valkey:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(6379)
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
    }
    /** A `redis://` connection URI for the running container (see the class doc for why not `valkey://`). */
    val uri: String get() = "redis://$host:${getMappedPort(6379)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "valkey/valkey"
    }
}
