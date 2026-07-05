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
import java.util.Base64

/** Real HTTP Cypher transaction round-trip for [Neo4jContainer]. */
@Tag("sandbox-it")
class Neo4jModuleIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `CREATE then MATCH round-trips over the HTTP Cypher transaction endpoint`() {
        val neo4j = Neo4jContainer(); neo4j.start()
        try {
            fun commit(statement: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI("${neo4j.httpUrl}/db/neo4j/tx/commit"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth(neo4j.username, neo4j.password))
                    .POST(HttpRequest.BodyPublishers.ofString("""{"statements":[{"statement":"$statement"}]}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val create = commit("CREATE (n:Test {name: 'hello'}) RETURN n.name AS name")
            assertEquals(200, create.statusCode(), "CREATE failed: ${create.body()}")
            assertTrue(create.body().contains("\"errors\":[]"), "CREATE reported errors: ${create.body()}")

            val match = commit("MATCH (n:Test {name: 'hello'}) RETURN n.name AS name")
            assertEquals(200, match.statusCode(), "MATCH failed: ${match.body()}")
            assertTrue(match.body().contains("\"hello\""), "MATCH did not return the created node: ${match.body()}")
        } finally { neo4j.stop() }
    }

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
}
