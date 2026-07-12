package dev.rightsize.core.reuse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

class ReuseRegistryTest {
    @Test fun `read returns null when no file exists yet`(@TempDir tmp: Path) {
        assertNull(ReuseRegistry(tmp).read("deadbeef0000"))
    }

    @Test fun `write then read round-trips the record`(@TempDir tmp: Path) {
        val registry = ReuseRegistry(tmp)
        val record = ReuseRecord("rz-reuse-deadbeef0000", "redis:7-alpine", mapOf(6379 to 32768),
            "2026-07-11T12:00:00Z", "docker")
        registry.write("deadbeef0000", record)
        assertEquals(record, registry.read("deadbeef0000"))
    }

    @Test fun `write is atomic - the file never appears half-written`(@TempDir tmp: Path) {
        val registry = ReuseRegistry(tmp)
        val record = ReuseRecord("rz-reuse-deadbeef0000", "redis:7-alpine", mapOf(6379 to 32768),
            "2026-07-11T12:00:00Z", "docker")
        registry.write("deadbeef0000", record)
        // No stray .tmp files left behind in the reuse directory after a successful write.
        val leftovers = Files.list(tmp.resolve("reuse")).use { it.toList() }
            .filter { it.fileName.toString().endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "atomic write left a temp file behind: $leftovers")
    }

    @Test fun `read returns null for a corrupted registry file`(@TempDir tmp: Path) {
        val registry = ReuseRegistry(tmp)
        Files.createDirectories(tmp.resolve("reuse"))
        Files.writeString(registry.file("deadbeef0000"), "{ not valid json")
        assertNull(registry.read("deadbeef0000"))
    }

    @Test fun `delete removes the file and is a no-op when already gone`(@TempDir tmp: Path) {
        val registry = ReuseRegistry(tmp)
        val record = ReuseRecord("rz-reuse-deadbeef0000", "redis:7-alpine", mapOf(6379 to 32768),
            "2026-07-11T12:00:00Z", "docker")
        registry.write("deadbeef0000", record)
        registry.delete("deadbeef0000")
        assertNull(registry.read("deadbeef0000"))
        assertDoesNotThrow { registry.delete("deadbeef0000") }
    }
}
