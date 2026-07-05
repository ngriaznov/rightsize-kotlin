# Memcached

`dev.rightsize.modules.MemcachedContainer` — a single-node Memcached container,
ready-checked with a protocol-level `version` probe rather than a bare port check.

## Defaults

| | |
|---|---|
| Default image | `memcached:1.6-alpine` |
| Exposed port | `11211` |
| Wait strategy | Custom (`MemcachedResponds`, see below) |

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
