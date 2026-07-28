# ArangoDB

`dev.rightsize.modules.ArangoContainer` — a single-node ArangoDB container. Auth is
disabled by default.

## Defaults

| | |
|---|---|
| Default image | `arangodb:latest` — this image's floating reference (see below) |
| Exposed port | `8529` |
| Env | `ARANGO_NO_AUTH=1` |
| Wait strategy | `Wait.forHttp("/_api/version").forPort(8529).forStatusCode(200)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins, so the version moves with ArangoDB's own releases instead of this
library's release cycle. This module previously pinned `arangodb:3.11`; pass that
image explicitly to pin it:

```kotlin
ArangoContainer("arangodb:3.11")
```

## Helpers

| Member | Returns |
|---|---|
| `endpoint: String` | The HTTP API base URI (`http://host:port`) for the running container |
| `withRootPassword(pw: String): ArangoContainer` | Enables auth with the given root password instead of the default no-auth setup — clears `ARANGO_NO_AUTH` and sets `ARANGO_ROOT_PASSWORD` |

## Example

```kotlin
package dev.rightsize.modules

import com.arangodb.ArangoDB
import dev.rightsize.modules.ArangoContainer
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ArangoContainerTest {
    @Test
    fun `queries the version endpoint`() {
        val arango = ArangoContainer()
        arango.start()
        try {
            val db = ArangoDB.Builder().host("127.0.0.1", arango.getMappedPort(8529)).build()
            assertNotNull(db.version.version)
            db.shutdown()
        } finally {
            arango.stop()
        }
    }
}
```

## Backend notes

None specific — ArangoDB's entrypoint boots cleanly under attached-mode supervision on
the microsandbox backend (see [How It Works](../how-it-works.md)). Note that
microsandbox pulls single-arch image layers (arm64 on Apple Silicon), which made the
observed pull noticeably smaller than a typical multi-arch pull of the same image
(roughly 203 MB vs. an estimated ~600 MB multi-arch equivalent, measured against
`arangodb:3.11`) — see the platform comparison on the [Home](../index.md#why-rightsize)
page.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`arangodb`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
ArangoContainer(
    DockerImageName.parse("mycorp/arangodb-hardened:3.11")
        .asCompatibleSubstituteFor("arangodb"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
