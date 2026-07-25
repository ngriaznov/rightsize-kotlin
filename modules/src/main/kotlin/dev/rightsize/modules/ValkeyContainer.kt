package dev.rightsize.modules

import dev.rightsize.GenericContainer
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
 * ### `uri` deliberately uses the `redis://` scheme, not `valkey://`
 *
 * This is not a copy-paste mistake. Every client this module's tests and users reach for —
 * lettuce, node-redis, or a raw RESP connection over TCP — parses `redis://` and has no special
 * handling for a `valkey://` scheme, because Valkey's wire protocol is RESP, unchanged from
 * Redis. `redis://` is therefore the correct and only URI scheme to hand callers here.
 *
 * No env is required to boot this image, and no memory limit was needed beyond the default
 * during verification.
 */
class ValkeyContainer(image: String = "valkey/valkey:9.1-alpine") : GenericContainer<ValkeyContainer>(image) {
    init { withExposedPorts(6379); waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1)) }
    /** A `redis://` connection URI for the running container (see the class doc for why not `valkey://`). */
    val uri: String get() = "redis://$host:${getMappedPort(6379)}"
}
