# Redis

`dev.rightsize.modules.RedisContainer` — a single-node Redis container.

## Defaults

| | |
|---|---|
| Default image | `redis:latest` — this image's floating reference (see below) |
| Exposed port | `6379` |
| Wait strategy | `Wait.forLogMessage(".*Ready to accept connections.*", 1)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins. This module previously pinned `redis:8.6-alpine`; `latest` is the
Debian-based variant, not Alpine — functionally equivalent for everything this module
exercises, just a larger pull. Pass an explicit tag (Alpine or otherwise) to pin a
specific version:

```kotlin
RedisContainer("redis:8.6-alpine")
```

## Helpers

| Member | Returns |
|---|---|
| `uri: String` | A `redis://host:port` connection URI for the running container |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.RedisContainer
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RedisContainerTest {
    @Test
    fun `set then get`() {
        val redis = RedisContainer()
        redis.start()
        try {
            RedisClient.create(redis.uri).connect().use { conn ->
                conn.sync().set("k", "v")
                assertEquals("v", conn.sync().get("k"))
            }
        } finally {
            redis.stop()
        }
    }
}
```

## Backend notes

Readiness is anchored on Redis's own "Ready to accept connections" log line rather than
a TCP probe: on a loaded host the port forwarder can accept and hold a connection in the
window between Redis binding its socket and actually serving, which a bare
listening-port check cannot see through.

Redis Cluster's `cluster-announce-ip` setup pattern (common in Testcontainers suites)
stays entirely user-land via `execInContainer("redis-cli", "config", "set", ...)` — no
module-level support needed for it.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`redis`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
RedisContainer(
    DockerImageName.parse("mycorp/redis-hardened:8.6")
        .asCompatibleSubstituteFor("redis"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
