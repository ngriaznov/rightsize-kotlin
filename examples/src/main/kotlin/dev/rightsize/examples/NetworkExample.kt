package dev.rightsize.examples

/*
 * Two-container Network example — a consumer reaching a stub server by alias.
 *
 * Shows how to wire two containers onto the same Network so one can address the other by name,
 * on either backend. A WireMock container stubs a single HTTP endpoint; a second, plain Alpine
 * container reaches it as "config-stub:8080" (the alias, not a mapped host port) and fetches the
 * stub over that link.
 *
 * Run it with:
 *   ./gradlew :examples:runNetwork
 *
 * Force a specific backend:
 *   RIGHTSIZE_BACKEND=microsandbox ./gradlew :examples:runNetwork
 *   RIGHTSIZE_BACKEND=docker       ./gradlew :examples:runNetwork
 */

import dev.rightsize.GenericContainer
import dev.rightsize.Network
import dev.rightsize.core.wait.Wait
import dev.rightsize.modules.WireMockContainer
import java.net.HttpURLConnection
import java.net.URI

fun main() {
    Network.newNetwork().use { net ->
        val stub = WireMockContainer()
            .withNetwork(net).withNetworkAliases("config-stub")
        stub.start()
        try {
            // Stub a config endpoint via WireMock's admin API, reached here over its mapped host
            // port — the consumer container below will instead reach it over the network alias.
            val mapping = """{"request":{"method":"GET","urlPath":"/config"},
                "response":{"status":200,"body":"feature-flags: []"}}"""
            val conn = URI("${stub.adminUrl}/mappings").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { it.write(mapping.toByteArray()) }
            check(conn.responseCode == 201) { "failed to register WireMock stub: HTTP ${conn.responseCode}" }
            conn.disconnect()

            // The consumer never learns the mapped host port; it dials the alias rightsize
            // installed on the network, exactly as it would reach a sibling service in production.
            val consumer = GenericContainer("alpine:3.19")
                .withNetwork(net)
                .withCommand(
                    "sh", "-c",
                    "for i in $(seq 1 30); do " +
                        "wget -qO- -T 5 http://${net.resolve("config-stub", 8080)}/config " +
                        "&& echo FETCH-OK && break; sleep 2; done; sleep 60",
                )
                .waitingFor(Wait.forLogMessage(".*FETCH-OK.*"))
            consumer.start()
            try {
                check("feature-flags" in consumer.logs) { "consumer never saw the stubbed response body" }
                println("Consumer fetched config via alias 'config-stub' -> feature-flags: []")
            } finally {
                consumer.stop()
            }
        } finally {
            stub.stop()
        }
    }
}
