# Neo4j

`dev.rightsize.modules.Neo4jContainer` — a single-node Neo4j Community container,
queried over its HTTP Cypher transaction endpoint (`/db/neo4j/tx/commit`) — no bolt
driver dependency needed, matching the house convention for HTTP-first modules
([ClickHouse](clickhouse.md), [Pinot](pinot.md)). The bolt port (7687) is still
exposed and its URI available via `boltUrl` for callers who do want a real driver.

## Defaults

| | |
|---|---|
| Default image | `neo4j:5-community` |
| Exposed ports | `7474` (HTTP — what the helpers use), `7687` (bolt, exposed but not wrapped by a helper) |
| Env | `NEO4J_AUTH=neo4j/rightsize-test` |
| Memory limit | `withMemoryLimit(1024)` — see below |
| Wait strategy | `Wait.forLogMessage(".*Started\\..*", 1).withStartupTimeout(Duration.ofSeconds(120))` |

## Helpers

| Member | Returns |
|---|---|
| `httpUrl: String` | The HTTP interface's base URI — Cypher transactions via `POST {httpUrl}/db/neo4j/tx/commit` |
| `boltUrl: String` | The bolt interface's URI, for callers using a real bolt driver instead of the HTTP helpers |
| `username: String` | The fixed admin username (`neo4j` — the image has no env var to change it) |
| `password: String` | The configured admin password (default `rightsize-test`) |
| `withPassword(password: String): Neo4jContainer` | Overrides `NEO4J_AUTH`'s password half (the image requires ≥8 chars) |

Call `withPassword` before `start()`. There's no `withUsername` — the username is
fixed by the image at `neo4j`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.Neo4jContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

class Neo4jContainerTest {
    private val http = HttpClient.newHttpClient()

    private fun basicAuth(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    @Test
    fun `CREATE then MATCH round-trips over the HTTP Cypher transaction endpoint`() {
        val neo4j = Neo4jContainer()
        neo4j.start()
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
        } finally {
            neo4j.stop()
        }
    }
}
```

## Backend notes

**`Started.` is the exact log line, verified against a real boot.** Captured
verbatim from `neo4j:5-community`:

```
... INFO  Bolt enabled on 0.0.0.0:7687.
... INFO  HTTP enabled on 0.0.0.0:7474.
... INFO  Remote interface available at http://localhost:7474/
... INFO  Started.
```

`Started.` is logged only after both connectors are already listening, so it's both
accurate and simpler than a two-port HTTP/bolt race — pinned as the wait strategy
over `forHttp`.

**The image refuses passwords under 8 characters** — `neo4j`/`neo4j` is rejected at
boot — so this module defaults to `neo4j`/`rightsize-test`, giving zero-configuration
use of `httpUrl` plus basic auth. Call `withPassword` before `start()` to override it
with your own (still ≥8-character) password.

**Memory needed the ladder — but the failure mode is different from an OOM kill.**
At msb's default ~450 MB microVM RAM, the server logs
`ERROR Invalid memory configuration - exceeds physical memory. Check the configured
values for server.memory.pagecache.size and server.memory.heap.max_size` and shuts
itself down cleanly (`INFO Stopped.`) rather than hanging or getting killed by the
kernel — Neo4j's own memory-recommendation calculator sizes the page cache and heap
off total visible RAM and refuses to start if the sums don't fit. A real docker boot
with no memory cap sits at ~468 MiB RSS (`docker stats`), just over that default
budget. Retried with `-m 1024M`: boots clean, the HTTP Cypher endpoint answers within
the startup timeout. This module ships with `withMemoryLimit(1024)` for exactly this
reason, the same number as [Keycloak](keycloak.md).
