# Memcached

`dev.rightsize.modules.MemcachedContainer` — a single-node Memcached container,
ready-checked with a protocol-level `version` probe rather than a bare port check.

## Defaults

| | |
|---|---|
| Default image | `memcached:latest` — this image's floating reference (see below) |
| Exposed port | `11211` |
| Wait strategy | Custom (`MemcachedResponds`, see below) |

With no image given, this module tracks upstream's `latest` tag rather than a version
this library pins. This module previously pinned `memcached:1.6-alpine`; `latest` is
the Debian-based variant, not Alpine — functionally equivalent for the `version` probe
below, just a larger pull. Pass an explicit tag (Alpine or otherwise) to pin a specific
version:

```kotlin
MemcachedContainer("memcached:1.6-alpine")
```

## Helpers

| Member | Returns |
|---|---|
| `address: String` | The `host:port` address of the running container |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.MemcachedContainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket

class MemcachedContainerTest {
    @Test
    fun `responds to a version request`() {
        val mc = MemcachedContainer()
        mc.start()
        try {
            Socket("127.0.0.1", mc.getMappedPort(11211)).use { s ->
                s.getOutputStream().write("version\r\n".toByteArray())
                val line = s.getInputStream().bufferedReader().readLine()
                assertTrue(line.startsWith("VERSION"))
            }
        } finally {
            mc.stop()
        }
    }
}
```

## Backend notes

Memcached logs nothing on startup, and the userland proxy on either backend can bind
the host port and start accepting connections before the server inside is actually
serving requests — so a bare `Wait.forListeningPort()` isn't reliable here. This
module's wait strategy instead sends the memcached text-protocol `version` command and
waits for a `VERSION`-prefixed reply, which only a fully-initialized server produces.
See [Wait Strategies](../concepts/wait-strategies.md#writing-a-custom-wait-strategy-abstractwaitstrategy)
for the full source of `MemcachedResponds`, which is a good template if you're writing
a wait strategy of your own for a text-protocol server.

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`memcached`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
MemcachedContainer(
    DockerImageName.parse("mycorp/memcached-hardened:1.6")
        .asCompatibleSubstituteFor("memcached"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
