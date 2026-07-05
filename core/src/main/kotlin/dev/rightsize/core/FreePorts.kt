package dev.rightsize.core

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

    fun release(port: Int) { issued.remove(port) }

    /** Test-only observability seam: a released port must not linger in the issued set. */
    internal fun issuedView(): Set<Int> = issued.toSet()
}
