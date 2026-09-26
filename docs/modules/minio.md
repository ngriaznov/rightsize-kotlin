# MinIO

`dev.rightsize.modules.MinIOContainer` — a single-node MinIO container, an
S3-compatible object store. Defaults to a `testuser`/`testpassword` root
credential pair.

## Defaults

| | |
|---|---|
| Default image | `pgsty/minio:latest` — this image's floating reference (see below) |
| Exposed ports | `9000` (S3 API — what the helpers use), `9001` (console, exposed but not wrapped by a helper) |
| Command | `server /data --console-address :9001` |
| Env | `MINIO_ROOT_USER=testuser`, `MINIO_ROOT_PASSWORD=testpassword` |
| Wait strategy | `Wait.forHttp("/minio/health/live").forPort(9000)` |

With no image given, this module tracks the image's `latest` tag rather than a version
this library pins, so the version moves with that image's releases instead of this
library's release cycle. Pass an image explicitly to pin it:

```kotlin
MinIOContainer("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
```

**Why `pgsty/minio`:** MinIO no longer publishes public images. Docker Hub's
`minio/minio` was removed, and as of September 2026 `quay.io/minio/minio`, this
module's previous default, refuses anonymous pulls (HTTP 401). `pgsty/minio` is
Pigsty's community build of MinIO from source, published on Docker Hub for
linux/amd64 and linux/arm64 with upstream's image layout: the same entrypoint and env
defaults, and the `mc` client bundled. Images from `quay.io/minio/minio` and
`minio/minio` are still accepted (see [Compatibility checking](#compatibility-checking)).

The backend notes below were verified against `minio/minio:RELEASE.2025-09-07T16-13-09Z`.
Readiness, auth enforcement, and the `mc` round-trip were verified again by this
module's integration test against `pgsty/minio:RELEASE.2026-08-04T00-00-00Z` (what
`latest` pointed at) on msb 0.7.3; the memory spike was not repeated.

## Helpers

| Member | Returns |
|---|---|
| `endpointUrl: String` | The S3 API's base URI — point any S3-compatible client at this with `username`/`password` |
| `username` / `password: String` | The configured root credentials (default `testuser`/`testpassword`) |
| `withUsername(username: String): MinIOContainer` | Overrides `MINIO_ROOT_USER` |
| `withPassword(password: String): MinIOContainer` | Overrides `MINIO_ROOT_PASSWORD` — must be at least 8 characters |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.MinIOContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class MinIOContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `bundled mc round-trips a bucket write and read`() {
        val minio = MinIOContainer()
        minio.start()
        try {
            val mcHost = "MC_HOST_local=http://${minio.username}:${minio.password}@127.0.0.1:9000"
            minio.execInContainer("sh", "-c", "$mcHost mc mb local/roundtrip")
            minio.execInContainer("sh", "-c", "printf 'hello minio' > /srv/hello.txt && $mcHost mc cp /srv/hello.txt local/roundtrip/hello.txt")
            val cat = minio.execInContainer("sh", "-c", "$mcHost mc cat local/roundtrip/hello.txt")
            assertEquals(0, cat.exitCode)
            assertEquals("hello minio", cat.stdout.trim())

            val live = http.send(
                HttpRequest.newBuilder(URI("${minio.endpointUrl}/minio/health/live")).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(200, live.statusCode())
        } finally {
            minio.stop()
        }
    }
}
```

## Backend notes

**The default entrypoint does not serve — a command is required.** Booting this
image with no command produces no listening S3 API at all; `server /data
--console-address :9001` is required to make it actually serve. This module sets
that command unconditionally.

**Credentials default to `testuser`/`testpassword`, not the house `test`/`test`
pair used by [ClickHouse](clickhouse.md) and friends.** MinIO rejects a root
password shorter than 8 characters at startup, so the two-character `test`/`test`
pair can't be reused here. `testuser`/`testpassword` is the shortest pair that both
clears that floor and stays obviously a test credential.

**Readiness is protocol-aware and answers correctly on the first poll.**
`Wait.forHttp("/minio/health/live").forPort(9000)` returned `200` on the first poll
after boot in verification — no restart/double-boot race to work around, unlike the
Postgres/MySQL/MariaDB entrypoints.

**Round-trip and auth were verified with the bundled `mc` client — no S3 SDK in this
repo.** `mc mb` (make bucket) followed by `mc cp` (write) and `mc cat` (read back)
against the configured credentials returned the exact bytes written, all run through
`exec` on the started container using the `mc` binary the image already bundles.
`mc cp` uploads a file written into the guest first rather than piping bytes into
`mc pipe` over stdin: an exec'd `mc pipe` under this backend either dumps its
goroutines and exits non-zero or hangs outright, both observed directly, while
`mc cp` needs no stdin and round-trips reliably. Separately, an anonymous `GET /`
against the S3 API returned `AccessDenied` rather than serving, confirming auth is
actually enforced rather than merely configured.

**Memory:** a verification spike ran `minio/minio` at 1024 MB with no issues. Whether
any floor is needed at all under this backend's default allocation is not yet
established, so this module sets no `withMemoryLimit` override; callers who hit
memory pressure can call it themselves.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`minio/minio`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. The check is
registry-agnostic (the registry host, e.g. `quay.io`, is stripped before comparing), so
both `quay.io/minio/minio:<tag>` and a bare `minio/minio:<tag>` are accepted
identically. `pgsty/minio` (any tag or digest) is accepted too, with no
`asCompatibleSubstituteFor` call needed: it is this module's own default, so the module
declares it a substitute for `minio/minio` itself. Only a genuinely different
repository is rejected. To use a differently-named image on purpose (a
private mirror, a hardened rebuild), wrap it with the escape hatch:

```kotlin
MinIOContainer(
    DockerImageName.parse("mycorp/minio-hardened:RELEASE.2025-09-07T16-13-09Z")
        .asCompatibleSubstituteFor("minio/minio"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
