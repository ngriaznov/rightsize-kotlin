# Elasticsearch

`dev.rightsize.modules.ElasticsearchContainer` — a single-node Elasticsearch container,
queried over its REST interface. Unlike every other module in this library, it has **no
no-arg constructor**: an explicit image is required.

## No floating tag exists for this image

Elastic publishes no `:latest` (or `:9`, or `:8`) tag for this image — all three are
`404` on Docker Hub. Only fully-qualified version tags are published (e.g. `9.4.4`,
`8.19.19`). Every other module in this library defaults to a floating reference so its
no-arg constructor tracks upstream; this one can't, so `ElasticsearchContainer` takes
the image as a required constructor argument instead of a default:

```kotlin
ElasticsearchContainer("elasticsearch:9.4.4")
```

`9.4.4` is the version this module's own facts below were verified against.

## Defaults

| | |
|---|---|
| Constructor | `ElasticsearchContainer(image: String)` / `ElasticsearchContainer(image: DockerImageName)` — required, no default |
| Exposed ports | `9200` (REST interface — what the helper uses), `9300` (transport, exposed but not wrapped — a single node never uses inter-node traffic) |
| Env | `discovery.type=single-node`, `xpack.security.enabled=false`, `ES_JAVA_OPTS=-Xms512m -Xmx512m` |
| Memory limit | `2560` MB |
| Wait strategy | `Wait.forHttp("/").forPort(9200)`, 300s startup timeout |

## Helpers

| Member | Returns |
|---|---|
| `restUrl: String` | The REST interface's base URI — index/search/cluster calls all go here |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.ElasticsearchContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ElasticsearchContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `indexes a document and searches for it`() {
        val es = ElasticsearchContainer("elasticsearch:9.4.4")
        es.start()
        try {
            // ?refresh=true forces the document into the very next search, instead of waiting
            // for Elasticsearch's own periodic refresh interval.
            val index = http.send(
                HttpRequest.newBuilder(URI("${es.restUrl}/books/_doc/1?refresh=true"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("""{"title":"Snow Crash"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, index.statusCode())

            val search = http.send(
                HttpRequest.newBuilder(URI("${es.restUrl}/books/_search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"query":{"match":{"title":"Snow Crash"}}}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, search.statusCode())
            assertTrue(search.body().contains("Snow Crash"))
        } finally {
            es.stop()
        }
    }
}
```

## Backend notes

**Single-node cluster health is `yellow` forever — never wait for `green`.** A
one-node cluster has nowhere to place replica shards, so `GET /_cluster/health`
reports `yellow` indefinitely; a readiness check waiting for `green` would hang until
its startup timeout on every single run. This module's wait strategy checks plain
connectivity (`GET /` returning `200`) instead, which is reachable well before shard
allocation could ever finish.

**Memory — a JVM with Lucene's off-heap needs on top of the configured heap.** With
the `512m`/`512m` heap this module sets, a real boot-to-ready cycle used roughly
1.1 GB of a 2.48 GB guest — the gap over the heap setting is Lucene's off-heap
memory-mapped segments and the JVM's own non-heap regions. 2560 MB was verified
stable end to end and is this module's default.

**Readiness was verified at 27s on a quiet machine; the startup timeout is 300s.**
`GET /` on the REST port returns `200` once the node is serving. The timeout is set
well above the observed figure since a heavier JVM under contention on a loaded CI
runner needs more headroom than a quiet-machine measurement alone would suggest.

**Verified with a real index/search round-trip, no client dependency.**
`PUT /books/_doc/1?refresh=true` followed by `GET /books/_search` returned the
indexed document back, through plain HTTP calls (`java.net.http.HttpClient`) — this
module and its tests pull in no Elasticsearch client.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`elasticsearch`) before any port, wait-strategy, or backend work runs —
a mismatched image (say, `ElasticsearchContainer("mysql:8")`) fails fast with a typed
`IncompatibleImageException` naming both repositories, rather than degrading into a
bare wait-strategy timeout. To use a differently-named image on purpose (a private
mirror, a hardened rebuild), wrap it with the escape hatch:

```kotlin
ElasticsearchContainer(
    DockerImageName.parse("mycorp/elasticsearch-hardened:9.4.4")
        .asCompatibleSubstituteFor("elasticsearch"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
