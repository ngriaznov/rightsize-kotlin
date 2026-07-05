# WireMock

`dev.rightsize.modules.WireMockContainer` — a WireMock server container for stubbing
HTTP dependencies in integration tests.

## Defaults

| | |
|---|---|
| Default image | `wiremock/wiremock:3.13.2` |
| Exposed port | `8080` |
| Wait strategy | `Wait.forHttp("/__admin/health").forPort(8080)` |

## Helpers

| Member | Returns |
|---|---|
| `baseUrl: String` | The stub server's base URI — mount stubbed paths under this |
| `adminUrl: String` | The `/__admin` management API's base URI (stub CRUD, request journal, health) |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.WireMockContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class WireMockContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `stubs and serves a GET`() {
        val wm = WireMockContainer()
        wm.start()
        try {
            val stub = """
                {"request":{"method":"GET","urlPath":"/hello"},
                 "response":{"status":200,"body":"world"}}
            """.trimIndent()
            val postResp = http.send(
                HttpRequest.newBuilder(URI("${wm.adminUrl}/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(stub))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(201, postResp.statusCode(), "stub creation failed: ${postResp.body()}")

            val getResp = http.send(
                HttpRequest.newBuilder(URI("${wm.baseUrl}/hello")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, getResp.statusCode())
            assertEquals("world", getResp.body())
        } finally {
            wm.stop()
        }
    }
}
```

## Backend notes

**Readiness uses the dedicated health endpoint, verified against a real boot.**
WireMock 3.x ships `/__admin/health` (unlike some older 2.x builds, where
`/__admin/mappings` was the only reliable 200 to poll). Verified directly:

```
$ curl http://127.0.0.1:<port>/__admin/health
{"status":"healthy","message":"Wiremock is ok","version":"3.13.2","uptimeInSeconds":9,...}
```

so this module waits on `/__admin/health` rather than falling back to
`/__admin/mappings`.

No `withMemoryLimit` override is needed — WireMock's embedded-Jetty JVM boots
comfortably on microsandbox's default ~450 MB microVM RAM (observed ~5.5s
integration-test round-trip). It's a small embedded-server JVM, not a JVM-heavy
cluster like [Pinot](pinot.md) — no memory-ladder escalation was needed the way Pinot
or [Spring Cloud Config](spring-cloud-config.md) required.
