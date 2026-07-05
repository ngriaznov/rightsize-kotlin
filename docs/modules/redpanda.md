# Redpanda

`dev.rightsize.modules.RedpandaContainer` — a single-node Redpanda broker
(Kafka-API-compatible) with its schema registry enabled.

## Defaults

| | |
|---|---|
| Default image | `docker.redpanda.com/redpandadata/redpanda:latest` |
| Exposed ports | `9092` (Kafka API), `9093` (internal listener), `8081` (schema registry) |
| Wait strategy | `Wait.forLogMessage(".*Successfully started Redpanda.*")` |

## Helpers

| Member | Returns |
|---|---|
| `bootstrapServers: String` | The `PLAINTEXT://` bootstrap-servers address for the running broker (EXTERNAL listener, host-reachable) |
| `schemaRegistryUrl: String` | The schema registry's base URI |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.RedpandaContainer
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties

class RedpandaContainerTest {
    @Test
    fun `produce then consume a record`() {
        val redpanda = RedpandaContainer()
        redpanda.start()
        try {
            val common = Properties().apply { put("bootstrap.servers", redpanda.bootstrapServers) }

            AdminClient.create(common).use {
                it.createTopics(listOf(NewTopic("t1", 1, 1))).all().get()
            }

            val producerProps = Properties().apply {
                putAll(common)
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
                put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            }
            KafkaProducer<String, String>(producerProps).use {
                it.send(ProducerRecord("t1", "k", "v")).get()
            }

            val consumerProps = Properties().apply {
                putAll(common)
                put("group.id", "g1")
                put("auto.offset.reset", "earliest")
                put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
                put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            }
            KafkaConsumer<String, String>(consumerProps).use { consumer ->
                consumer.subscribe(listOf("t1"))
                val records = consumer.poll(Duration.ofSeconds(15))
                assertEquals("v", records.first().value())
            }
        } finally {
            redpanda.stop()
        }
    }
}
```

## Backend notes: the dual-listener trick

Redpanda (and [Kafka](kafka.md)) both advertise a listener address to clients as part
of their own protocol handshake — a producer or consumer doesn't just connect to the
bootstrap address you give it, it also trusts whatever address the broker *advertises
back* for subsequent connections. That advertised address has to be one the *host JVM*
can actually reach — but the real host port isn't known until after rightsize has
allocated it, which happens moments before boot.

This module solves it by overriding `customizeSpec` — a `GenericContainer` extension
point that gets a callback resolving "guest port → its just-allocated host port" the
instant before the container is created — and uses it to launch Redpanda with two
listeners:

- **EXTERNAL** advertises `127.0.0.1:<the real allocated host port>` — this is what
  your host-side `KafkaProducer`/`KafkaConsumer` actually dials.
- **INTERNAL** advertises a fixed alias (`redpanda:9093`) for sibling containers on the
  same [`Network`](../concepts/networking.md) to resolve — native Docker networking on
  the Docker backend, best-effort via the exec-tunnel alias emulation on microsandbox.

You don't need to configure any of this yourself — `RedpandaContainer()` with no
arguments already does it — but if you're writing your own `GenericContainer` subclass
for a broker with the same "advertises its own address" behavior, `customizeSpec` is
the extension point to reach for; see the class's own KDoc
(`RedpandaContainer.customizeSpec`) for the exact command line it constructs.

Registry availability is worth knowing about separately: `docker.redpanda.com` is a
Docker Hub proxy that rate-limits anonymous pulls, and it's been observed to block CI
runs. If you hit persistent pull failures, seed the image into your local `msb` cache
ahead of time (`docker save <img> -o /tmp/img.tar && msb load -i /tmp/img.tar -t <img>`) rather than retrying the pull
indefinitely.
