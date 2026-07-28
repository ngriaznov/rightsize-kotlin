# Valkey

`dev.rightsize.modules.ValkeyContainer` — a single-node Valkey container. Valkey is
Redis's community fork and speaks the same RESP protocol on the same default port, so
this module's shape mirrors [Redis](redis.md) exactly.

## Defaults

| | |
|---|---|
| Default image | `valkey/valkey:latest` — this image's floating reference (see below) |
| Exposed port | `6379` |
| Wait strategy | `Wait.forLogMessage(".*Ready to accept connections.*", 1)` |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins. This module previously pinned `valkey/valkey:9.1-alpine`; `latest` is
the Debian-based variant, not Alpine — functionally equivalent for everything verified
below, just a larger pull. Pass an explicit tag (Alpine or otherwise) to pin a specific
version:

```kotlin
ValkeyContainer("valkey/valkey:9.1-alpine")
```

## Helpers

| Member | Returns |
|---|---|
| `uri: String` | A `redis://host:port` connection URI for the running container |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.ValkeyContainer
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValkeyContainerTest {
    @Test
    fun `set then get`() {
        val valkey = ValkeyContainer()
        valkey.start()
        try {
            RedisClient.create(valkey.uri).connect().use { conn ->
                conn.sync().set("k", "v")
                assertEquals("v", conn.sync().get("k"))
            }
        } finally {
            valkey.stop()
        }
    }
}
```

## Backend notes

**Readiness is anchored on a log line, not a port probe — the same reasoning as
[Redis](redis.md#backend-notes).** On a loaded host, msb's loopback forwarder can
accept and hold a TCP connection in the window between Valkey binding its socket and
actually serving, which a bare listening-port check cannot see through. Verified
directly: a real boot logs `Ready to accept connections tcp`, and `valkey-cli PING`
against the running container returns `PONG`.

**`uri` deliberately returns a `redis://` URI, not `valkey://`.** This is not a
copy-paste mistake carried over from the Redis module: every client this module's
tests and users reach for — lettuce, Jedis, or a raw RESP connection over TCP —
parses `redis://` and has no special handling for a `valkey://` scheme, because
Valkey's wire protocol is RESP, unchanged from Redis.

No env is required to boot this image, and no memory limit was needed beyond the
default during verification.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`valkey/valkey`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
ValkeyContainer(
    DockerImageName.parse("mycorp/valkey-hardened:9.1")
        .asCompatibleSubstituteFor("valkey/valkey"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
