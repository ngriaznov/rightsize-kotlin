# RabbitMQ

`dev.rightsize.modules.RabbitMQContainer` — a single-node RabbitMQ container with the
management plugin enabled. Defaults to the image's own `guest`/`guest` credential pair.

## Defaults

| | |
|---|---|
| Default image | `rabbitmq:management` — not `:latest`; see below |
| Exposed ports | `5672` (AMQP), `15672` (management UI/API) |
| Env | `RABBITMQ_DEFAULT_USER=guest`, `RABBITMQ_DEFAULT_PASS=guest` |
| Wait strategy | `Wait.forLogMessage(".*Server startup complete.*", times = 1)` |

### Why `rabbitmq:management`, not `rabbitmq:latest`

Every other module in this library floats to a plain `:latest` tag. This one does not:
plain `rabbitmq:latest` does not carry the management plugin at all, and this module is
built around it — `managementUrl`, the readiness log line below, and the whole point of
reaching for this module over a bare RabbitMQ image all depend on it being installed.
`rabbitmq:management` is RabbitMQ's own floating tag for "latest release, management
plugin included", so this module still tracks upstream releases the same way the rest
of the catalog does; it just floats to a different tag. This module previously pinned
`rabbitmq:4-management-alpine` — the version the facts below (the captured log excerpt,
the 4.x `transient_nonexcl_queues` behavior) were verified against, and also an Alpine
base, unlike the Debian-based `rabbitmq:management`; pass that image explicitly to pin
it:

```kotlin
RabbitMQContainer("rabbitmq:4-management-alpine")
```

## Helpers

| Member | Returns |
|---|---|
| `amqpUrl: String` | An `amqp://` URL (with embedded credentials) for the running container's AMQP listener |
| `managementUrl: String` | The management UI/API base URI |
| `username` / `password: String` | The configured management/AMQP credentials (default `guest`/`guest`) |
| `withUsername(username: String): RabbitMQContainer` | Overrides `RABBITMQ_DEFAULT_USER` |
| `withPassword(password: String): RabbitMQContainer` | Overrides `RABBITMQ_DEFAULT_PASS` |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import dev.rightsize.modules.RabbitMQContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class RabbitMQContainerTest {
    @Test
    fun `publishes and receives a message`() {
        val rabbit = RabbitMQContainer()
        rabbit.start()
        try {
            val factory = ConnectionFactory()
            factory.setUri(rabbit.amqpUrl)
            factory.newConnection().use { conn ->
                conn.createChannel().use { ch ->
                    // RabbitMQ 4.x rejects non-durable, non-exclusive queues by default —
                    // see "A 4.x behavior change" below.
                    ch.queueDeclare("q1", true, false, false, null)
                    ch.basicPublish("", "q1", null, "hello".toByteArray())

                    var delivery: GetResponse? = null
                    val deadline = System.currentTimeMillis() + 5000
                    while (delivery == null && System.currentTimeMillis() < deadline) {
                        delivery = ch.basicGet("q1", true)
                        if (delivery == null) Thread.sleep(100)
                    }
                    assertNotNull(delivery, "never received the published message")
                    assertEquals("hello", String(delivery!!.body))
                }
            }
        } finally {
            rabbit.stop()
        }
    }
}
```

Note the small poll loop around `basicGet` — a fresh publish can briefly race the
broker's own enqueue, so a single immediate `basicGet` isn't guaranteed to see it yet.

## Backend notes

**Readiness is unambiguous — no double-boot to worry about.** Unlike the SQL modules,
RabbitMQ's `"Server startup complete"` line (unchanged from the 3.x series into 4.x)
appears exactly once per boot, so `times = 1` (the default) is correct with no
false-match trap to anchor against. No memory-limit override is needed either — an
Erlang VM, not a JVM, boots comfortably under microsandbox's default ~450 MB microVM
RAM (observed ~5.5s round-trip on both backends in this module's own integration test).

**A RabbitMQ 4.x behavior change that bites naive client code (not this module's
concern, but worth knowing).** RabbitMQ 4.x deprecates
`transient_nonexcl_queues`, and — per the broker's own startup warning — a client that
declares a **non-durable, non-exclusive** queue (`durable=false, exclusive=false`) can
be rejected outright with `reply-code=541 INTERNAL_ERROR`, reproduced directly against
this module's pinned image. Declare durable, non-exclusive queues (or exclusive
transient ones) from your own test/client code — the example above does exactly that
(`queueDeclare("q1", true, false, false, null)`).

## Compatibility checking

Passing an explicit image checks its repository against the one this module
understands (`rabbitmq`) before any port, wait-strategy, or backend work runs — a
mismatched image fails fast with a typed `IncompatibleImageException` naming both
repositories, rather than degrading into a bare wait-strategy timeout. To use a
differently-named image on purpose (a private mirror, a hardened rebuild), wrap it
with the escape hatch:

```kotlin
RabbitMQContainer(
    DockerImageName.parse("mycorp/rabbitmq-hardened:4-management")
        .asCompatibleSubstituteFor("rabbitmq"))
```

See [Core Concepts](../concepts/containers.md) for `DockerImageName` itself.
