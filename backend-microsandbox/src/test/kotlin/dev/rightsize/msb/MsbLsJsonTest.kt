package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit coverage for the tolerant `msb ls --format json` parse. The live-CLI shape is pinned
 * separately by MsbRunningSandboxNamesIT — these tests exercise robustness the IT can't
 * (key order, extra/missing keys, non-Running statuses, malformed neighbors) without a real msb.
 */
class MsbLsJsonTest {
    @Test fun `parses the documented flat shape, keys in spec order`() {
        val json = """
            [
              {"created_at":"2024-01-01T00:00:00Z","image":"alpine:3.19","name":"rz-abc-1","status":"Running"},
              {"created_at":"2024-01-01T00:00:01Z","image":"alpine:3.19","name":"rz-abc-2","status":"Stopped"}
            ]
        """.trimIndent()
        assertEquals(setOf("rz-abc-1"), MsbLsJson.runningNames(json))
    }

    @Test fun `key order within an object does not matter`() {
        val json = """[{"status":"Running","name":"rz-xyz-1","image":"x","created_at":"t"}]"""
        assertEquals(setOf("rz-xyz-1"), MsbLsJson.runningNames(json))
    }

    @Test fun `extra unknown fields are ignored`() {
        val json = """[{"name":"rz-1","status":"Running","cpu_percent":12.5,"labels":{"a":"b"}}]"""
        assertEquals(setOf("rz-1"), MsbLsJson.runningNames(json))
    }

    @Test fun `object missing status or name is skipped, not thrown`() {
        val json = """
            [
              {"name":"rz-no-status"},
              {"status":"Running"},
              {"name":"rz-both","status":"Running"}
            ]
        """.trimIndent()
        assertEquals(setOf("rz-both"), MsbLsJson.runningNames(json))
    }

    @Test fun `non-Running statuses are excluded`() {
        val json = """[{"name":"a","status":"Stopped"},{"name":"b","status":"running"},{"name":"c","status":"Running"}]"""
        assertEquals(setOf("c"), MsbLsJson.runningNames(json))
    }

    @Test fun `empty array yields empty set`() {
        assertEquals(emptySet<String>(), MsbLsJson.runningNames("[]"))
    }

    @Test fun `braces and colons inside string values are parsed as plain string content`() {
        val json = """[{"name":"rz-brace","status":"Running","image":"repo/{tag}:v1"},{"name":"rz-2","status":"Running"}]"""
        assertEquals(setOf("rz-brace", "rz-2"), MsbLsJson.runningNames(json))
    }

    @Test fun `escaped quote inside a string value is parsed correctly`() {
        val json = """[{"name":"rz-esc","status":"Running","image":"a\"b"},{"name":"rz-after","status":"Running"}]"""
        assertEquals(setOf("rz-esc", "rz-after"), MsbLsJson.runningNames(json))
    }

    @Test fun `nested object values do not throw off name status extraction`() {
        val json = """[{"name":"rz-nested","status":"Running","meta":{"nested":"{not a name}"}}]"""
        assertEquals(setOf("rz-nested"), MsbLsJson.runningNames(json))
    }
}
