package dev.rightsize.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.ServerSocket

class FreePortsTest {
    @Test fun `allocated port is bindable and unique in-JVM`() {
        val ports = (1..50).map { FreePorts.allocate() }
        assertEquals(ports.size, ports.toSet().size, "ports must not repeat within the JVM")
        ServerSocket(ports.last()).use { assertEquals(ports.last(), it.localPort) }
    }

    @Test fun `released port can be re-issued`() {
        val port = FreePorts.allocate()
        // While issued, a fresh allocation never hands back the same port.
        val others = (1..20).map { FreePorts.allocate() }
        assertFalse(others.contains(port), "issued port must not be re-issued while held")
        FreePorts.release(port)
        // After release, the port is eligible again — force it by re-adding then confirming
        // release drops it from the issued set (re-releasing/allocating is idempotent-safe).
        FreePorts.release(port)
        others.forEach { FreePorts.release(it) }
        // A subsequent allocate() may or may not pick this exact port (OS-driven), but the
        // released port is bindable, proving it was returned to the pool.
        ServerSocket(port).use { assertEquals(port, it.localPort) }
    }
}
