# Kafka

`dev.rightsize.modules.KafkaContainer` — a single-node Kafka broker in KRaft mode (no
ZooKeeper).

## Defaults

| | |
|---|---|
| Default image | `apache/kafka:4.0.0` |
| Exposed port | `9092` |
| Env | KRaft single-node config (`KAFKA_PROCESS_ROLES=broker,controller`, `KAFKA_NODE_ID=1`, etc.); `KAFKA_HEAP_OPTS=-Xmx256M -Xms256M` (see below) |
| Wait strategy | `Wait.forLogMessage(".*Kafka Server started.*")` |

## Helpers

| Member | Returns |
|---|---|
| `bootstrapServers: String` | The `PLAINTEXT://` bootstrap-servers address for the running broker |

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.KafkaContainer
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties

class KafkaContainerTest {
    @Test
    fun `produce then consume a record`() {
        val kafka = KafkaContainer()
        kafka.start()
        try {
            val common = Properties().apply { put("bootstrap.servers", kafka.bootstrapServers) }

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
            kafka.stop()
        }
    }
}
```

## Backend notes

**The advertised-listener trick.** Like [Redpanda](redpanda.md), Kafka advertises its
own address back to clients as part of the protocol, so this module overrides
`customizeSpec` to rewrite `KAFKA_ADVERTISED_LISTENERS` with the real, just-allocated
host port the instant before the container is created — see Redpanda's page for the
full explanation of why this is necessary and how the extension point works.

**The heap override is required, not cosmetic.** The `apache/kafka` image defaults
`KAFKA_HEAP_OPTS` to `-Xmx1G`, which exceeds microsandbox's default microVM RAM (~450
MB — see [Files & Memory](../concepts/files-and-memory.md#when-you-actually-need-this))
and aborts the JVM with an insufficient-memory error on that backend. A single-node
KRaft dev broker runs comfortably in a 256 MB heap, so this module sets
`KAFKA_HEAP_OPTS=-Xmx256M -Xms256M` unconditionally — harmless on the Docker backend,
which isn't memory-constrained the same way, and necessary on microsandbox. If you
override the image and hit an OOM/insufficient-memory abort on microsandbox, check
whether the image bakes in a large default heap the way this one did.
