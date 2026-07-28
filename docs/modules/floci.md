# Floci

`dev.rightsize.modules.FlociContainer` — a [floci.io](https://floci.io) cloud
emulator: one native Quarkus image per cloud provider, each speaking that
provider's REST APIs against an in-memory backing store. One module class covers
all three variants; pick one with the `aws()`/`azure()`/`gcp()` factory functions
rather than the constructor directly — each factory pins the provider's own image
and guest port.

## Defaults

| | |
|---|---|
| Default images | `floci/floci:latest` (AWS), `floci/floci-az:latest` (Azure), `floci/floci-gcp:latest` (GCP) — each variant's own floating reference, see below |
| Exposed ports | `4566` (AWS), `4577` (Azure), `4588` (GCP) — one per variant |
| Wait strategy | `Wait.forHttp("/health").forPort(<variant port>)` |

With no image given, each factory tracks its own image's `latest` tag rather than a
version this library pins, so the version moves with that variant's own releases
instead of this library's release cycle. Every fact below was verified against
`floci/floci:1.5.30`, `floci/floci-az:0.8.0`, and `floci/floci-gcp:0.4.0` specifically
— pass one of those explicitly to pin the measured version:

```kotlin
FlociContainer.aws("floci/floci:1.5.30")
FlociContainer.azure("floci/floci-az:0.8.0")
FlociContainer.gcp("floci/floci-gcp:0.4.0")
```

## Helpers

| Member | Returns |
|---|---|
| `FlociContainer.aws(image: String = "floci/floci:latest"): FlociContainer` | The AWS emulator — S3, DynamoDB, SQS, etc. |
| `FlociContainer.azure(image: String = "floci/floci-az:latest"): FlociContainer` | The Azure emulator |
| `FlociContainer.gcp(image: String = "floci/floci-gcp:latest"): FlociContainer` | The GCP emulator |
| `endpointUrl: String` | This variant's REST endpoint — the base URI for every emulated API call |

There's no constructor to call directly — always go through one of the three
factory functions, which pin the right port (and check the right repository) for
that provider.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.FlociContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FlociContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `AWS variant S3 create-bucket, put, and get round-trips with no request signing`() {
        val floci = FlociContainer.aws()
        floci.start()
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
        } finally {
            floci.stop()
        }
    }
}
```

## Backend notes

**`/health` works uniformly across all three variants — the AWS-flavored
`/_localstack/health` doesn't carry over.** The AWS variant ships a
LocalStack-compatible `/_localstack/health` endpoint, but the Azure and GCP variants
do not carry that path (Azure: `501`; GCP: `404`). All three, however, answer a
plain `GET /health` with `200` and a small JSON status body the moment the embedded
Quarkus HTTP listener is up — verified directly against real boots of
`floci/floci:1.5.30`, `floci/floci-az:0.8.0`, and `floci/floci-gcp:0.4.0`. `/health`
is pinned as the one wait path that works across all three variants; no log-wait
fallback was needed.

**No signing needed — verified against the AWS variant's S3 surface.** The AWS
variant's S3-shaped REST endpoints accept unsigned requests with no
`Authorization` header at all: `PUT /<bucket>`, `PUT /<bucket>/<key>`, and
`GET /<bucket>/<key>` all round-trip successfully with a bare `HttpClient` call —
no SigV4, no AWS SDK dependency required. This module's IT exercises that plain-REST
path rather than pulling in the AWS SDK.

No `withMemoryLimit` override is needed for any variant — all three images are
native (GraalVM) Quarkus binaries that settle at roughly 11–27 MiB RSS (`docker
stats`, real boot), and each boots and answers `/health` under msb's default
microVM RAM with no memory-ladder escalation.

## Compatibility checking

Each factory checks the image passed to it against its own repository — `aws` expects
`floci/floci`, `azure` expects `floci/floci-az`, `gcp` expects `floci/floci-gcp` —
before any port, wait-strategy, or backend work runs, so passing an AWS-shaped image to
`azure()` (or vice versa) fails fast with a typed `IncompatibleImageException` naming
both repositories, rather than booting the wrong emulator. To use a differently-named
image on purpose (a private mirror, a hardened rebuild), wrap it with the escape hatch:

```kotlin
FlociContainer.aws(
    DockerImageName.parse("mycorp/floci-hardened:1.5.30")
        .asCompatibleSubstituteFor("floci/floci"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
