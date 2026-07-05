package dev.rightsize.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import dev.rightsize.core.Backends
import dev.rightsize.core.UnsupportedByBackendException

/** Real round-trips for [FlinkContainer]. */
@Tag("sandbox-it")
class FlinkModuleIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `bare JobManager answers REST overview`() {
        val flink = FlinkContainer(); flink.start()
        try {
            val resp = http.send(
                HttpRequest.newBuilder(URI("${flink.restUrl}/overview")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode(), "overview failed: ${resp.body()}")
            assertTrue(resp.body().contains("\"taskmanagers\""), "unexpected overview body: ${resp.body()}")
        } finally { flink.stop() }
    }

    @Test fun `withTaskManager registers a slot-bearing TaskManager (docker only)`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Backends.active().name != "microsandbox",
            "withTaskManager() is docker-only — see FlinkContainer's class doc for the msb incompatibility",
        )
        val flink = FlinkContainer().withTaskManager(); flink.start()
        try {
            val deadline = System.currentTimeMillis() + 60_000
            var body = ""
            while (System.currentTimeMillis() < deadline) {
                val resp = http.send(
                    HttpRequest.newBuilder(URI("${flink.restUrl}/taskmanagers")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                body = resp.body()
                if (resp.statusCode() == 200 && body.contains("\"id\"")) break
                Thread.sleep(1000)
            }
            assertTrue(body.contains("\"id\""), "TaskManager never registered: $body")
        } finally { flink.stop() }
    }

    @Test fun `withTaskManager throws UnsupportedByBackendException on microsandbox`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            Backends.active().name == "microsandbox",
            "this test only asserts the msb-specific guard",
        )
        val flink = FlinkContainer()
        assertThrows(UnsupportedByBackendException::class.java) { flink.withTaskManager() }
    }
}
