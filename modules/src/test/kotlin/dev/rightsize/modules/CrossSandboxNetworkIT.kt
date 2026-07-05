package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.Network
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@Tag("sandbox-it")
class CrossSandboxNetworkIT {
    @Test fun `container reaches sibling via network alias resolution`() {
        Network.newNetwork().use { net ->
            val server = GenericContainer("python:3.12-alpine")
                .withCommand("python", "-m", "http.server", "8888")
                .withExposedPorts(8888)
                .withNetwork(net).withNetworkAliases("configuration-stub")
                .waitingFor(Wait.forHttp("/").forPort(8888))
            server.start()
            val client = GenericContainer("alpine:3.19")
                .withNetwork(net)
                // retry loop: on msb the exec-tunnel is installed moments AFTER the workload boots
                .withCommand("sh", "-c",
                    "for i in $(seq 1 30); do wget -qO- -T 5 http://${net.resolve("configuration-stub", 8888)}/ " +
                        "&& echo FETCH-OK && break; sleep 2; done; sleep 60")
                .waitingFor(Wait.forLogMessage(".*FETCH-OK.*"))
            client.start()
            try { assertTrue(client.logs.contains("FETCH-OK")) }
            finally { client.stop(); server.stop() }
        }
    }
}
