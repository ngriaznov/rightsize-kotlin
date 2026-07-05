package dev.rightsize.modules

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Proves the QuickStart cluster actually WORKS (schema round-trip through the controller), not
 * merely that it pings — plus a broker health check. Boots are slow (a four-JVM cluster on a
 * laptop; observed 60-120s on both backends), so this is patient rather than fast.
 *
 * The controller's `/health` Jersey endpoint answers 200 slightly before its Helix-backed
 * `TableConfig`/`Schema` management subsystem finishes registering with ZooKeeper — a genuine
 * Pinot QuickStart race, observed directly: `/health` returned 200 at ~8s into one boot, yet an
 * immediate schema POST still failed with `{"code":500,"error":"java.util.concurrent.TimeoutException"}`.
 * `/health` is still the right wait signal (it's the only one that doesn't require this test's own
 * schema-specific knowledge), so the schema POST retries with a short backoff rather than trusting
 * the first attempt — the same shape as [MongoDBContainer]'s post-start `rs.initiate` retry for an
 * analogous "the process answers before the subsystem is ready" race.
 */
@Tag("sandbox-it")
class PinotModuleIT {
    // HTTP/1.1 explicitly: the JDK client defaults to attempting an h2c upgrade, and Pinot's
    // Grizzly-based controller/broker servers don't speak HTTP/2 — left on the default, POSTs to
    // /schemas intermittently surfaced as a 500 java.util.concurrent.TimeoutException server-side
    // (curl, which never offers the h2c upgrade, never reproduced this).
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test fun `controller schema round-trip and broker health`() {
        val pinot = PinotContainer()
        pinot.start()
        try {
            val schemaJson = """
                {"schemaName":"rightsizeTestSchema","dimensionFieldSpecs":[{"name":"col1","dataType":"STRING"}]}
            """.trimIndent()
            val postResp = retryUntil200("schema POST") {
                http.send(
                    HttpRequest.newBuilder(URI("${pinot.controllerUrl}/schemas"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(schemaJson))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            assertEquals(200, postResp.statusCode(), "schema POST failed: ${postResp.body()}")

            val getResp = retryUntil200("schema GET") {
                http.send(
                    HttpRequest.newBuilder(URI("${pinot.controllerUrl}/schemas/rightsizeTestSchema")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            assertEquals(200, getResp.statusCode())
            assertTrue(getResp.body().contains("rightsizeTestSchema"), "unexpected schema body: ${getResp.body()}")

            val controllerHealth = retryUntil200("controller /health") {
                http.send(
                    HttpRequest.newBuilder(URI("${pinot.controllerUrl}/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            assertEquals(200, controllerHealth.statusCode())

            // The broker is the last of the four JVMs to finish registering with the cluster, so
            // its /health (and even its TCP listener) can still be flaky briefly after the
            // controller's own /health already reports 200 — retry it the same way.
            val brokerHealth = retryUntil200("broker /health") {
                http.send(
                    HttpRequest.newBuilder(URI("${pinot.brokerUrl}/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            assertEquals(200, brokerHealth.statusCode(), "broker not healthy: ${brokerHealth.body()}")
        } finally {
            pinot.stop()
        }
    }

    /**
     * Retries [attempt] every 2s up to 60s until it returns HTTP 200 (swallowing transient
     * connection failures — including a bare EOF, seen when a JVM's listener is up but the
     * component behind it hasn't finished registering with the cluster — along the way); returns
     * the last observed result once the deadline passes. Fails loudly (via the caller's own
     * assertion) rather than silently if it never succeeds — this is a bounded retry, not a "wait
     * forever."
     */
    private fun retryUntil200(what: String, attempt: () -> HttpResponse<String>): HttpResponse<String> {
        val budget = Duration.ofSeconds(60)
        val deadline = System.currentTimeMillis() + budget.toMillis()
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            val result = runCatching(attempt)
            result.onSuccess { if (it.statusCode() == 200) return it }
            result.onFailure { lastFailure = it }
            Thread.sleep(2000)
        }
        return runCatching(attempt).getOrElse {
            throw AssertionError("'$what' never succeeded within ${budget.seconds}s", lastFailure ?: it)
        }
    }
}
