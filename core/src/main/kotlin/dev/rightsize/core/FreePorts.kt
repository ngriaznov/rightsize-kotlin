package dev.rightsize.core

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Internal host-port allocator backing [dev.rightsize.GenericContainer]'s port-retry loop. Not
 * part of the backend SPI: backends receive already-chosen host ports via `ContainerSpec.ports`
 * and never allocate their own, so this stays module-internal.
 */
internal object FreePorts {
    private val issued = ConcurrentHashMap.newKeySet<Int>()

    fun allocate(): Int {
        repeat(100) {
            val port = ServerSocket(0).use { it.localPort }
            if (issued.add(port)) return port
        }
        error("Could not allocate a free TCP port after 100 attempts")
    }

    /**
     * The UDP counterpart of [allocate]: binding a TCP [ServerSocket] proves nothing about
     * whether a UDP port is free — the two protocols keep entirely independent OS port tables —
     * so a UDP host port must be probed with a UDP socket of its own. Same shape as [allocate]:
     * bind `127.0.0.1:0`, read back the OS-assigned port, release the socket immediately, retry
     * on an [issued] collision (shared with [allocate] — a port this process already handed out
     * for TCP is skipped for UDP too, purely to keep one process's own bookkeeping simple; the
     * OS itself would happily give out the same number on both protocols).
     */
    fun allocateUdp(): Int {
        repeat(100) {
            val port = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            if (issued.add(port)) return port
        }
        error("Could not allocate a free UDP port after 100 attempts")
    }

    fun release(port: Int) { issued.remove(port) }

    /**
     * Marks a host port as issued without going through [allocate]'s own-socket-bind dance —
     * for ports this process didn't pick itself but must still treat as unavailable, e.g. an
     * adopted reuse sandbox's already-bound ports (see `GenericContainer.tryAdopt`). Idempotent:
     * marking an already-issued port is a no-op. Never released by this process — the port stays
     * genuinely bound by the adopted sandbox for as long as it lives, which can outlast this JVM.
     */
    fun reserve(port: Int) { issued.add(port) }

    /** Test-only observability seam: a released port must not linger in the issued set. */
    internal fun issuedView(): Set<Int> = issued.toSet()
}
