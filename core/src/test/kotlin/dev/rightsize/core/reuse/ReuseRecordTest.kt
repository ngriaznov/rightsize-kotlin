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

    @Test fun `name and image containing quotes, backslashes, and newlines round-trip through toJson and parse`() {
        val tricky = record.copy(
            name = "rz-reuse-799aad5a3338",
            image = "weird\"image\\name\nwith\ttabs\rreturns",
        )
        assertEquals(tricky, ReuseRecord.parse(tricky.toJson()))
    }
}
