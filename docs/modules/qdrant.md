# Qdrant

`dev.rightsize.modules.QdrantContainer` — a single-node Qdrant container, the vector
database, queried over its REST interface.

## Defaults

| | |
|---|---|
| Default image | `qdrant/qdrant:latest` — this image's floating reference (see below) |
| Exposed ports | `6333` (REST interface — what the helper uses), `6334` (gRPC, exposed but not wrapped by a helper) |
| Wait strategy | `Wait.forHttp("/readyz").forPort(6333)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins, so the version moves with Qdrant's own releases instead of this
library's release cycle. This module's own facts below were captured against
`qdrant/qdrant:v1.18.3` specifically — note the `v` prefix Qdrant's own version tags
carry, unlike `latest` — pass that image explicitly to pin it:

```kotlin
QdrantContainer("qdrant/qdrant:v1.18.3")
```

## Helpers

| Member | Returns |
|---|---|
| `restUrl: String` | The REST interface's base URI — collection/point/search calls all go here |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.QdrantContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class QdrantContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `creates a collection, upserts a point, and searches for it`() {
        val qdrant = QdrantContainer()
        qdrant.start()
        try {
            val createCollection = http.send(
                HttpRequest.newBuilder(URI("${qdrant.restUrl}/collections/roundtrip"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""{"vectors":{"size":4,"distance":"Dot"}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, createCollection.statusCode())

            val upsert = http.send(
                HttpRequest.newBuilder(URI("${qdrant.restUrl}/collections/roundtrip/points?wait=true"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(
                        """{"points":[{"id":1,"vector":[1.0,0.0,0.0,0.0],"payload":{"city":"Berlin"}}]}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, upsert.statusCode())

            val search = http.send(
                HttpRequest.newBuilder(URI("${qdrant.restUrl}/collections/roundtrip/points/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"vector":[1.0,0.0,0.0,0.0],"limit":1}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, search.statusCode())
            assertTrue(search.body().contains("\"id\":1"))
        } finally {
            qdrant.stop()
        }
    }
}
```

## Backend notes

**Readiness answers correctly on the very first poll.** `GET /readyz` on the REST
port returned `200` on the first poll after boot in direct verification — no
retry/backoff story to document here, unlike several of this library's JVM-backed
modules. This module leaves the default wait-strategy timeout untouched.

**No memory limit needed.** Verified directly with no `withMemoryLimit` set: a full
create-collection/upsert/search round-trip completed in a guest reporting roughly
480 MB total. Callers who hit memory pressure on a constrained host can call
`withMemoryLimit` themselves.

**Verified with a real collection/upsert/search round-trip, no client dependency.**
Creating a collection (vector size 4, `Dot` distance), upserting a point, and
searching against it returned the expected point back — verified through plain HTTP
calls (`java.net.http.HttpClient`); this module and its tests pull in no Qdrant
client.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`qdrant/qdrant`) before any port, wait-strategy, or backend work runs —
a mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
QdrantContainer(
    DockerImageName.parse("mycorp/qdrant-hardened:v1.18.3")
        .asCompatibleSubstituteFor("qdrant/qdrant"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
