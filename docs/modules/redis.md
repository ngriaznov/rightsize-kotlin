# Redis

`dev.rightsize.modules.RedisContainer` — a single-node Redis container.

## Defaults

| | |
|---|---|
| Default image | `redis:8.6-alpine` |
| Exposed port | `6379` |
| Wait strategy | `Wait.forListeningPort()` |

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

None — Redis boots and serves cleanly on both backends with no special handling.
Redis Cluster's `cluster-announce-ip` setup pattern (common in Testcontainers suites)
stays entirely user-land via `execInContainer("redis-cli", "config", "set", ...)` — no
module-level support needed for it.
