# Spring Cloud Config

`dev.rightsize.modules.SpringCloudConfigContainer` — a Spring Cloud Config Server
container, ready-checked via its actuator health endpoint.

## Defaults

| | |
|---|---|
| Default image | `hyness/spring-cloud-config-server:latest` |
| Exposed port | `8888` |
| Memory limit | `withMemoryLimit(1024)` — see below |
| Wait strategy | `Wait.forHttp("/actuator/health").forPort(8888)` |

## Helpers

| Member | Returns |
|---|---|
| `uri: String` | The config server's base URI |

## Example

The default `EnvironmentRepository` in this image needs a real git remote configured,
which no test fixture should depend on — set `SPRING_PROFILES_ACTIVE=native` to serve
config from the classpath instead:

```kotlin
package dev.rightsize.modules

import dev.rightsize.core.wait.Wait
import dev.rightsize.modules.SpringCloudConfigContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

class SpringCloudConfigContainerTest {
    @Test
    fun `config server serves actuator health`() {
        val server = SpringCloudConfigContainer()
            // "native" profile serves config from the classpath — no external git repo needed.
            // Without it the default git EnvironmentRepository refuses to start.
            .withEnv("SPRING_PROFILES_ACTIVE", "native")
            .apply {
                // JVM Spring Boot image is a slow first pull; be generous.
                waitingFor(Wait.forHttp("/actuator/health").forPort(8888).withStartupTimeout(Duration.ofSeconds(180)))
            }
        server.start()
        try {
            val conn = URI("${server.uri}/actuator/health").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            assertEquals(200, conn.responseCode)
            assertTrue(conn.inputStream.bufferedReader().readText().contains("UP"))
            conn.disconnect()
        } finally {
            server.stop()
        }
    }
}
```

## Backend notes

**`withMemoryLimit(1024)` exists specifically for this image.** It's built on a Paketo
buildpack, and Paketo's memory calculator sizes the JVM's fixed regions (heap +
metaspace + thread stacks) to around 688 MB — over microsandbox's default microVM RAM
of roughly 450 MB. Without a raised limit, the JVM fails to launch under the
microsandbox backend; it boots fine under Docker, whose containers aren't
memory-constrained by default, which is exactly why this class of failure is easy to
miss if you only ever test on Docker. See
[Files & Memory](../concepts/files-and-memory.md#when-you-actually-need-this) for the
general pattern and [Pinot](pinot.md) for a much larger version of the same problem.

**First boot is genuinely slow.** A JVM Spring Boot image on a first (cold) pull is one
of the slower boots in the module catalog — the example above raises the wait timeout
to 180 seconds for this reason.
