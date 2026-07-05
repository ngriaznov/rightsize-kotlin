package dev.rightsize.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Real round-trips for [FlociContainer]'s AWS, Azure, and GCP variants. */
@Tag("sandbox-it")
class FlociModuleIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `AWS variant S3 create-bucket, put, and get round-trips with no request signing`() {
        val floci = FlociContainer.aws(); floci.start()
        try {
            val put = http.send(
                HttpRequest.newBuilder(URI("${floci.endpointUrl}/rightsize-test-bucket"))
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(200, put.statusCode(), "create-bucket failed")

            val putObj = http.send(
                HttpRequest.newBuilder(URI("${floci.endpointUrl}/rightsize-test-bucket/hello.txt"))
                    .PUT(HttpRequest.BodyPublishers.ofString("hello world")).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(200, putObj.statusCode(), "put-object failed")

            val getObj = http.send(
                HttpRequest.newBuilder(URI("${floci.endpointUrl}/rightsize-test-bucket/hello.txt")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getObj.statusCode(), "get-object failed")
            assertEquals("hello world", getObj.body())
        } finally { floci.stop() }
    }

    @Test fun `Azure variant answers health`() {
        val floci = FlociContainer.azure(); floci.start()
        try {
            val resp = http.send(
                HttpRequest.newBuilder(URI("${floci.endpointUrl}/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode(), "health check failed: ${resp.body()}")
            assertTrue(resp.body().contains("\"status\":\"UP\""), "unexpected health body: ${resp.body()}")
        } finally { floci.stop() }
    }

    @Test fun `GCP variant answers health`() {
        val floci = FlociContainer.gcp(); floci.start()
        try {
            val resp = http.send(
                HttpRequest.newBuilder(URI("${floci.endpointUrl}/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode(), "health check failed: ${resp.body()}")
            assertTrue(resp.body().contains("\"services\""), "unexpected health body: ${resp.body()}")
        } finally { floci.stop() }
    }
}
