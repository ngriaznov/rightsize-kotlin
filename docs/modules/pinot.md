# Apache Pinot

`dev.rightsize.modules.PinotContainer` — a single-container Apache Pinot "QuickStart"
cluster: controller, broker, server, minion, and an embedded ZooKeeper, all as one
process tree inside one image, started with `QuickStart -type EMPTY` — a clean cluster
with no demo tables. This module is a real-cluster smoke fixture, not a
data-loading harness.

## Defaults

| | |
|---|---|
| Default image | `apachepinot/pinot:1.5.1` |
| Exposed ports | `9000` (controller REST API), `8000` (broker query port — see below) |
| Command | `QuickStart -type EMPTY` |
| Memory limit | `withMemoryLimit(4096)` — measured, not guessed; see below |
| Wait strategy | `Wait.forHttp("/health").forPort(9000).withStartupTimeout(Duration.ofSeconds(180))` |

## Helpers

| Member | Returns |
|---|---|
| `controllerUrl: String` | The controller's REST base URI (schema/table/segment admin) |
| `brokerUrl: String` | The broker's query base URI |

## Example

This module's own integration test proves the cluster actually *works* — a schema
round-trip through the controller, plus a broker health check — not merely that it
pings. It also demonstrates the retry pattern you should copy for anything that talks
to Pinot's controller/broker right after boot; see the note below for why.

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.PinotContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PinotContainerTest {
    // HTTP/1.1 explicitly: the JDK client's default h2c-upgrade attempt against Pinot's
    // Grizzly-based servers intermittently surfaces as a server-side 500 on POST.
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private fun retryUntil200(attempt: () -> HttpResponse<String>): HttpResponse<String> {
        val deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis()
        while (System.currentTimeMillis() < deadline) {
            runCatching(attempt).getOrNull()?.let { if (it.statusCode() == 200) return it }
            Thread.sleep(2000)
        }
        return attempt()
    }

    @Test
    fun `controller schema round-trip and broker health`() {
        val pinot = PinotContainer()
        pinot.start()
        try {
            val schemaJson = """{"schemaName":"testSchema","dimensionFieldSpecs":[{"name":"col1","dataType":"STRING"}]}"""
            val postResp = retryUntil200 {
                http.send(
                    HttpRequest.newBuilder(URI("${pinot.controllerUrl}/schemas"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(schemaJson))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
            }
            assertEquals(200, postResp.statusCode())

            val brokerHealth = retryUntil200 {
                http.send(HttpRequest.newBuilder(URI("${pinot.brokerUrl}/health")).GET().build(), HttpResponse.BodyHandlers.ofString())
            }
            assertTrue(brokerHealth.statusCode() == 200, "broker not healthy: ${brokerHealth.body()}")
        } finally {
            pinot.stop()
        }
    }
}
```

## Backend notes

### Ports: empirically verified, not the QuickStart docs' assumption

The controller REST API is on `9000` as documented — but the broker's **query** port
is **8000**, not the commonly assumed `8099`. Confirmed from a real `QuickStart -type
EMPTY` boot log:

```
StartControllerCommand ... -controllerPort 9000 ...
INFO: Started listener bound to [0.0.0.0:9000]
StartBrokerCommand ... -brokerPort 8000 -brokerGrpcPort 8010 ...
INFO: Started listener bound to [0.0.0.0:8000]
```

`8099` is never opened by QuickStart at all — this module exposes `8000` and names the
helper `brokerUrl` accordingly.

### Memory: measured against the image's real 4 GiB heap request

QuickStart runs a ZooKeeper, controller, broker, server, and minion JVM all in one
container, and the image itself bakes in `JAVA_OPTS=-Xms4G -Xmx4G` for the QuickStart
driver JVM alone — before any of the four sub-JVMs it spawns have taken anything. An
earlier plan called for `withMemoryLimit(2048)` by analogy with
[Spring Cloud Config](spring-cloud-config.md)'s single-JVM fix; that analogy badly
under-shot. Measured directly:

| Memory limit | Result |
|---|---|
| 2048 MB | OOM-killed — timed out at 180s waiting for `/health` |
| 2560 MB | OOM-killed |
| 3072 MB | Boots; `/health` returns 200 within ~15s — but settles at ~99% of the limit, and under that pressure the controller's Helix-backed schema/table-config RPCs intermittently time out with a 500 even though `/health` reports 200 |
| **4096 MB** | Boots cleanly; settles at ~73–75%; schema POST succeeded on every attempt across a 60s repeated-POST probe |

`withMemoryLimit(4096)` is the shipped default — the lowest round number with real
headroom above the image's own request, not merely enough to dodge the OOM killer.
Verified stable on both backends.

### The controller answers `/health` before it's really ready — retry, don't trust the first response

The controller's `/health` endpoint can return `200` slightly before its Helix-backed
schema/table-config subsystem finishes registering with ZooKeeper — a genuine
QuickStart race, observed directly: `/health` returned `200` at ~8s into one boot, yet
an immediate schema `POST` still failed with a `TimeoutException`. `/health` is still
the right *wait strategy* signal (it's the only one that doesn't require
schema-specific knowledge up front), but any actual interaction with the controller or
broker right after `start()` returns should retry with a short backoff rather than
trusting the first response — exactly the shape [MongoDB](mongodb.md)'s post-start
`rs.initiate` retry uses for its own "the process answers before the subsystem is
ready" race. The example above does this with `retryUntil200`.

A four-JVM cluster booting cold on a laptop is also just legitimately slow — expect
60–120 seconds, which is why this module's wait strategy is configured with a 180s
timeout rather than the 60s default.
