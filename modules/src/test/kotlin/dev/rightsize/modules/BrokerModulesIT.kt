package dev.rightsize.modules

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.Properties

@Tag("sandbox-it")
class BrokerModulesIT {
    private fun roundTrip(bootstrap: String) {
        val common = Properties().apply { put("bootstrap.servers", bootstrap) }
        AdminClient.create(common).use { it.createTopics(listOf(org.apache.kafka.clients.admin.NewTopic("t1", 1, 1))).all().get() }
        KafkaProducer<String, String>(common + mapOf(
            "key.serializer" to "org.apache.kafka.common.serialization.StringSerializer",
            "value.serializer" to "org.apache.kafka.common.serialization.StringSerializer")).use {
            it.send(ProducerRecord("t1", "k", "v")).get()
        }
        KafkaConsumer<String, String>(common + mapOf(
            "group.id" to "g1", "auto.offset.reset" to "earliest",
            "key.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer",
            "value.deserializer" to "org.apache.kafka.common.serialization.StringDeserializer")).use { c ->
            c.subscribe(listOf("t1"))
            val records = c.poll(Duration.ofSeconds(15))
            assertEquals("v", records.first().value())
        }
    }
    private operator fun Properties.plus(m: Map<String, String>) = Properties().also { it.putAll(this); it.putAll(m) }

    @Test fun redpanda() {
        val rp = RedpandaContainer(); rp.start()
        try { roundTrip(rp.bootstrapServers) } finally { rp.stop() }
    }
    @Test fun kafka() {
        val k = KafkaContainer(); k.start()
        try { roundTrip(k.bootstrapServers) } finally { k.stop() }
    }
}
