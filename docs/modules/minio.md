# MinIO

`dev.rightsize.modules.MinIOContainer` — a single-node MinIO container, an
S3-compatible object store. Defaults to a `testuser`/`testpassword` root
credential pair.

## Defaults

| | |
|---|---|
| Default image | `minio/minio:RELEASE.2025-09-07T16-13-09Z` |
| Exposed ports | `9000` (S3 API — what the helpers use), `9001` (console, exposed but not wrapped by a helper) |
| Command | `server /data --console-address :9001` |
| Env | `MINIO_ROOT_USER=testuser`, `MINIO_ROOT_PASSWORD=testpassword` |
| Wait strategy | `Wait.forHttp("/minio/health/live").forPort(9000)` |

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

**Memory:** a verification spike ran this image at 1024 MB with no issues. Whether
any floor is needed at all under this backend's default allocation is not yet
established, so this module sets no `withMemoryLimit` override; callers who hit
memory pressure can call it themselves.
