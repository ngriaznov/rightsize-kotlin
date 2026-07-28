# WireMock

`dev.rightsize.modules.WireMockContainer` — a WireMock server container for stubbing
HTTP dependencies in integration tests.

## Defaults

| | |
|---|---|
| Default image | `wiremock/wiremock:latest` — this image's floating reference (see below) |
| Exposed port | `8080` |
| Wait strategy | `Wait.forHttp("/__admin/health").forPort(8080)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins, so the version moves with WireMock's own releases instead of this
library's release cycle. The facts below were verified against
`wiremock/wiremock:3.13.2` specifically — pass that image explicitly to pin it:

```kotlin
WireMockContainer("wiremock/wiremock:3.13.2")
```

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

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`wiremock/wiremock`) before any port, wait-strategy, or backend work runs
— a mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
WireMockContainer(
    DockerImageName.parse("mycorp/wiremock-hardened:3.13.2")
        .asCompatibleSubstituteFor("wiremock/wiremock"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
