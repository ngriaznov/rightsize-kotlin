package dev.rightsize.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.time.Duration

/** Real round-trips for the Elasticsearch and Qdrant modules. */
@Tag("sandbox-it")
class ElasticsearchQdrantIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun post(uri: String, body: String) = http.send(
        HttpRequest.newBuilder(URI(uri)).header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun put(uri: String, body: String) = http.send(
        HttpRequest.newBuilder(URI(uri)).header("Content-Type", "application/json")
            .PUT(BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun get(uri: String) = http.send(
        HttpRequest.newBuilder(URI(uri)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test fun elasticsearch() {
        val es = ElasticsearchContainer("elasticsearch:9.4.4")
        es.start()
        try {
            // ?refresh=true forces the document into the very next search rather than waiting
            // for Elasticsearch's own periodic refresh interval.
            val index = put("${es.restUrl}/books/_doc/1?refresh=true", """{"title":"Snow Crash"}""")
            assertEquals(201, index.statusCode(), "index failed: ${index.body()}")

            val search = post("${es.restUrl}/books/_search", """{"query":{"match":{"title":"Snow Crash"}}}""")
            assertEquals(200, search.statusCode(), "search failed: ${search.body()}")
            assertTrue(search.body().contains("Snow Crash"), "expected the indexed document back, got: ${search.body()}")
            assertTrue(search.body().contains("\"_id\":\"1\""), "expected doc id 1 in hits, got: ${search.body()}")
        } finally { es.stop() }
    }

    @Test fun qdrant() {
        val qdrant = QdrantContainer("qdrant/qdrant:v1.18.3")
        qdrant.start()
        try {
            val createCollection = put(
                "${qdrant.restUrl}/collections/roundtrip",
                """{"vectors":{"size":4,"distance":"Dot"}}""")
            assertEquals(200, createCollection.statusCode(), "create collection failed: ${createCollection.body()}")

            val upsert = put(
                "${qdrant.restUrl}/collections/roundtrip/points?wait=true",
                """{"points":[{"id":1,"vector":[1.0,0.0,0.0,0.0],"payload":{"city":"Berlin"}}]}""")
            assertEquals(200, upsert.statusCode(), "upsert failed: ${upsert.body()}")

            // Querying with the same vector against Dot distance is a self dot-product: exactly 1.0.
            val search = post(
                "${qdrant.restUrl}/collections/roundtrip/points/search",
                """{"vector":[1.0,0.0,0.0,0.0],"limit":1}""")
            assertEquals(200, search.statusCode(), "search failed: ${search.body()}")
            assertTrue(search.body().contains("\"id\":1"), "expected point id 1 back, got: ${search.body()}")
            val score = Regex("\"score\":([0-9.eE+-]+)").find(search.body())
                ?.groupValues?.get(1)?.toDouble()
                ?: error("no score in response: ${search.body()}")
            assertEquals(1.0, score, 0.0001, "expected the self dot-product score, got $score")
        } finally { qdrant.stop() }
    }
}
