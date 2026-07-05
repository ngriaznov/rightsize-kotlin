package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.AbstractWaitStrategy
import dev.rightsize.core.wait.WaitTarget
import java.net.InetSocketAddress
import java.net.Socket

/** A single-node Memcached container, ready-checked with a protocol-level `version` probe. */
class MemcachedContainer(image: String = "memcached:1.6-alpine") : GenericContainer<MemcachedContainer>(image) {
    init { withExposedPorts(11211); waitingFor(MemcachedResponds()) }
    /** The `host:port` address of the running container. */
    val address: String get() = "$host:${getMappedPort(11211)}"

    /**
     * Memcached logs nothing on startup and the docker userland proxy binds the host
     * port before the server inside is accepting, so a bare TCP-connect wait can pass
     * while the first real client connection still gets a dead stream. This strategy
     * proves readiness by speaking the protocol: it sends `version` and expects `VERSION`.
     * Extends the public [AbstractWaitStrategy] instead of hand-rolling the deadline/poll-
     * interval loop that [dev.rightsize.core.wait.PollingWait] already implements.
     */
    private class MemcachedResponds : AbstractWaitStrategy() {
        override fun what() = "a VERSION reply"
        override fun isReady(target: WaitTarget): Boolean {
            val port = target.mappedPort(target.exposedGuestPorts.first())
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(target.host, port), 1000)
                    s.soTimeout = 1000
                    s.getOutputStream().write("version\r\n".toByteArray())
                    val line = s.getInputStream().bufferedReader().readLine()
                    line != null && line.startsWith("VERSION")
                }
            } catch (_: Exception) { false }
        }
    }
}
