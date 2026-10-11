package dev.rightsize.core.reuse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReuseRecordTest {
    private val record = ReuseRecord(
        name = "rz-reuse-799aad5a3338",
        image = "redis:7-alpine",
        ports = mapOf(6379 to 32768, 6380 to 32769),
        createdIso = "2026-07-11T12:00:00Z",
        backend = "docker",
    )

    @Test fun `round-trips through toJson and parse`() {
        val parsed = ReuseRecord.parse(record.toJson())
        assertEquals(record, parsed)
    }

    @Test fun `parse rejects garbage text`() {
        assertNull(ReuseRecord.parse("not json at all"))
    }

    @Test fun `parse rejects a record missing a required field`() {
        val missingBackend = "{\"name\":\"rz-reuse-abc\",\"image\":\"redis\",\"ports\":{}," +
            "\"createdIso\":\"2026-07-11T12:00:00Z\"}"
        assertNull(ReuseRecord.parse(missingBackend))
    }

    @Test fun `parse tolerates an empty ports object`() {
        val noPorts = record.copy(ports = emptyMap())
        assertEquals(noPorts, ReuseRecord.parse(noPorts.toJson()))
    }

    @Test fun `parse rejects a record with no ports key at all`() {
        val noPortsKey = "{\"name\":\"rz-reuse-abc\",\"image\":\"redis\"," +
            "\"createdIso\":\"2026-07-11T12:00:00Z\",\"backend\":\"docker\"}"
        assertNull(ReuseRecord.parse(noPortsKey))
    }

    @Test fun `parse skips a port entry whose value overflows Int rather than failing the whole record`() {
        val text = "{\"name\":\"rz-reuse-abc\",\"image\":\"redis\"," +
            "\"ports\":{\"6379\":32768,\"6380\":99999999999999}," +
            "\"createdIso\":\"2026-07-11T12:00:00Z\",\"backend\":\"docker\"}"
        val parsed = ReuseRecord.parse(text)
        assertNotNull(parsed)
        assertEquals(mapOf(6379 to 32768), parsed!!.ports)
    }

    @Test fun `udpPorts round-trips through toJson and parse`() {
        val withUdp = record.copy(udpPorts = mapOf(53 to 40000, 5353 to 40001))
        assertEquals(withUdp, ReuseRecord.parse(withUdp.toJson()))
    }

    // The registry is shared with the Node and Rust libraries, which write the same optional
    // `udpPorts` object after `backend` and leave it out when there are no UDP ports.
    @Test fun `udpPorts is written last under the key the other libraries use, and omitted when empty`() {
        assertEquals(
            "{\"name\":\"rz-reuse-799aad5a3338\",\"image\":\"redis:7-alpine\"," +
                "\"ports\":{\"6379\":32768,\"6380\":32769},\"createdIso\":\"2026-07-11T12:00:00Z\"," +
                "\"backend\":\"docker\",\"udpPorts\":{\"53\":40000}}",
            record.copy(udpPorts = mapOf(53 to 40000)).toJson())
        assertFalse("udpPorts" in record.toJson(), "a record with no UDP ports must not write the key")
    }

    @Test fun `a record with no udpPorts key parses with no UDP ports`() {
        val tcpOnly = "{\"name\":\"rz-reuse-abc\",\"image\":\"redis\",\"ports\":{\"6379\":32768}," +
            "\"createdIso\":\"2026-07-11T12:00:00Z\",\"backend\":\"docker\"}"
        assertEquals(emptyMap<Int, Int>(), ReuseRecord.parse(tcpOnly)!!.udpPorts)
    }

    @Test fun `parse reads udpPorts from a record the Node library wrote`() {
        // JSON.stringify of Node's ReuseRegistryEntry, key order as Node emits it.
        val fromNode = "{\"name\":\"rz-reuse-abc\",\"image\":\"coredns/coredns:1.11.1\",\"ports\":{}," +
            "\"createdIso\":\"2026-07-11T12:00:00.000Z\",\"backend\":\"microsandbox\",\"udpPorts\":{\"53\":40002}}"
        val parsed = ReuseRecord.parse(fromNode)
        assertNotNull(parsed)
        assertEquals(emptyMap<Int, Int>(), parsed!!.ports)
        assertEquals(mapOf(53 to 40002), parsed.udpPorts)
    }

    @Test fun `parse rejects a record whose udpPorts is not an object`() {
        val malformed = "{\"name\":\"rz-reuse-abc\",\"image\":\"redis\",\"ports\":{}," +
            "\"createdIso\":\"2026-07-11T12:00:00Z\",\"backend\":\"docker\",\"udpPorts\":[53]}"
        assertNull(ReuseRecord.parse(malformed))
    }

    @Test fun `name and image containing quotes, backslashes, and newlines round-trip through toJson and parse`() {
        val tricky = record.copy(
            name = "rz-reuse-799aad5a3338",
            image = "weird\"image\\name\nwith\ttabs\rreturns",
        )
        assertEquals(tricky, ReuseRecord.parse(tricky.toJson()))
    }
}
