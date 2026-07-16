package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointSpec
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Minimal fake for [CheckpointRegistry.find]/[remove]'s backend-facing calls: [existingRefs]
 * drives [hasCheckpoint] (true iff the probed ref is in the set — the "definitely present" vs.
 * "definitely absent" the SPI contract requires, never a caught failure), and every
 * [removeCheckpoint] call is recorded rather than being a silent no-op. */
private class ProbeFakeBackend(override val name: String = "fake") : SandboxBackend {
    val existingRefs = mutableSetOf<String>()
    val removedRefs = mutableListOf<String>()
    override val supportsNativeNetworks = false
    override fun create(spec: ContainerSpec): SandboxHandle = error("not needed")
    override fun start(handle: SandboxHandle) {}
    override fun stop(handle: SandboxHandle) {}
    override fun remove(handle: SandboxHandle) {}
    override fun exec(handle: SandboxHandle, cmd: List<String>) = ExecResult(0, "", "")
    override fun logs(handle: SandboxHandle) = ""
    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit) = AutoCloseable {}
    override fun ensureNetwork(networkId: String) {}
    override fun removeNetwork(networkId: String) {}
    override fun hasCheckpoint(ref: String): Boolean = ref in existingRefs
    override fun removeCheckpoint(ref: String) { removedRefs += ref; existingRefs -= ref }
}

private fun record(name: String, ref: String, backend: String) = CheckpointRecord(
    name = name, ref = ref, backend = backend, createdIso = "2026-07-11T12:00:00Z",
    spec = CheckpointSpec(
        env = mapOf("A" to "1"), command = listOf("sleep", "120"), exposedPorts = listOf(8080), memoryLimitMb = 256),
)

class CheckpointRegistryTest {

    // --- write/read/delete: the file discipline itself, mirrors ReuseRegistryTest ---

    @Test fun `read returns null when no file exists yet`(@TempDir tmp: Path) {
        assertNull(CheckpointRegistry(tmp).read("seeded-db"))
    }

    @Test fun `write then read round-trips the record`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker")
        registry.write("seeded-db", r)
        assertEquals(r, registry.read("seeded-db"))
    }

    @Test fun `write is atomic - the file never appears half-written`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("seeded-db", record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker"))
        val leftovers = Files.list(tmp.resolve("checkpoints")).use { it.toList() }
            .filter { it.fileName.toString().endsWith(".tmp") }
        assertTrue(leftovers.isEmpty(), "atomic write left a temp file behind: $leftovers")
    }

    @Test fun `read returns null for a corrupted registry file`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        Files.createDirectories(tmp.resolve("checkpoints"))
        Files.writeString(registry.file("seeded-db"), "{ not valid json")
        assertNull(registry.read("seeded-db"))
    }

    @Test fun `delete removes the file and is a no-op when already gone`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("seeded-db", record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker"))
        registry.delete("seeded-db")
        assertNull(registry.read("seeded-db"))
        assertDoesNotThrow { registry.delete("seeded-db") }
    }

    @Test fun `write then read round-trips a null command, empty env, and a null memoryLimitMb`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "bare", ref = "rightsize/checkpoint:deadbeef0000", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(env = emptyMap(), command = null, exposedPorts = emptyList(), memoryLimitMb = null),
        )
        registry.write("bare", r)
        assertEquals(r, registry.read("bare"))
    }

    @Test fun `write then read round-trips env values and command args containing quotes and commas`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "tricky", ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(
                env = mapOf("MSG" to "say \"hi, there\""),
                command = listOf("sh", "-c", "echo \"a, b\""),
                exposedPorts = listOf(80, 443),
                memoryLimitMb = 512,
            ),
        )
        registry.write("tricky", r)
        assertEquals(r, registry.read("tricky"))
    }

    // --- write/read round-trips of literal braces, backslashes, and newlines (C1: extractObject's
    // brace-depth scanner must be string-aware, or a literal '}'/'{' inside a value truncates or
    // over-extends the extracted "spec" substring and parse() returns null for a record write()
    // just wrote fine) ---

    @Test fun `the exact reviewer repro - a lone closing brace in an env value must not corrupt the record`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker").copy(
            spec = CheckpointSpec(
                env = mapOf("MSG" to "a } b"), command = null, exposedPorts = listOf(5432), memoryLimitMb = null),
        )
        registry.write("seeded-db", r)
        assertEquals(r, registry.read("seeded-db"),
            "a literal '}' inside an env value must not make parse() return null for a record write() just wrote")
    }

    @Test fun `write then read round-trips env values and command args containing literal braces`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "brace-case", ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(
                env = mapOf("MSG" to "a } b", "OPEN" to "x { y", "BOTH" to "{unbalanced"),
                command = listOf("sh", "-c", "echo '{\"a\":1}'"),
                exposedPorts = listOf(80),
                memoryLimitMb = 128,
            ),
        )
        registry.write("brace-case", r)
        assertEquals(r, registry.read("brace-case"))
    }

    @Test fun `write then read round-trips env values and command args containing backslashes and newlines`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "escape-case", ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(
                env = mapOf("PATH_LIKE" to "C:\\a\\b}c", "MULTILINE" to "line1\nline2"),
                command = listOf("sh", "-c", "printf 'a\\\\b\\n'"),
                exposedPorts = emptyList(),
                memoryLimitMb = null,
            ),
        )
        registry.write("escape-case", r)
        assertEquals(r, registry.read("escape-case"))
    }

    @Test fun `write then read round-trips a command arg with a quote immediately followed by a brace`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "quote-brace", ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(
                env = mapOf("Q" to "\"}"),
                command = listOf("echo", "\"}{\""),
                exposedPorts = listOf(1),
                memoryLimitMb = 1,
            ),
        )
        registry.write("quote-brace", r)
        assertEquals(r, registry.read("quote-brace"))
    }

    @Test fun `write then read round-trips a command arg containing literal square brackets`(@TempDir tmp: Path) {
        // Same bug class as the brace-in-env-value case, one level down: extractCommand's array
        // scan must not stop at a literal ']' inside a quoted command argument either.
        val registry = CheckpointRegistry(tmp)
        val r = CheckpointRecord(
            name = "bracket-case", ref = "rightsize/checkpoint:0123456789ab", backend = "docker",
            createdIso = "2026-07-11T12:00:00Z",
            spec = CheckpointSpec(
                env = mapOf("ARR" to "[a]"),
                command = listOf("echo", "a]b", "[c", "d]"),
                exposedPorts = listOf(2),
                memoryLimitMb = 2,
            ),
        )
        registry.write("bracket-case", r)
        assertEquals(r, registry.read("bracket-case"))
    }

    // --- CheckpointRegistry.file's own boundary validation (C2: name validation must guard every
    // path that turns a name into a registry file path, not just GenericContainer.checkpoint(name)'s
    // write path — defense in depth so find/remove/read/write/delete all reject a path-traversal
    // name shape regardless of whether a caller already validated it) ---

    @Test fun `file rejects a path-traversal name shape`(@TempDir tmp: Path) {
        assertThrows(InvalidCheckpointNameException::class.java) { CheckpointRegistry(tmp).file("../secret") }
    }

    @Test fun `file rejects a name containing a path separator`(@TempDir tmp: Path) {
        assertThrows(InvalidCheckpointNameException::class.java) { CheckpointRegistry(tmp).file("a/b") }
    }

    @Test fun `write validates the name before touching the filesystem at all`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        assertThrows(InvalidCheckpointNameException::class.java) {
            registry.write("../secret", record("../secret", "rightsize/checkpoint:0123456789ab", "docker"))
        }
        assertFalse(Files.exists(tmp.resolve("checkpoints")),
            "an invalid name must not even create the checkpoints directory, let alone a stray temp file")
    }

    @Test fun `read throws for a path-traversal name rather than silently resolving outside checkpoints`(@TempDir tmp: Path) {
        assertThrows(InvalidCheckpointNameException::class.java) { CheckpointRegistry(tmp).read("../secret") }
    }

    @Test fun `find throws for a path-traversal name and touches no file`(@TempDir tmp: Path) {
        assertThrows(InvalidCheckpointNameException::class.java) {
            CheckpointRegistry(tmp).find(ProbeFakeBackend(), "../secret")
        }
        assertFalse(Files.exists(tmp.resolve("checkpoints")))
    }

    @Test fun `remove throws for a path-traversal name and touches no file`(@TempDir tmp: Path) {
        assertThrows(InvalidCheckpointNameException::class.java) {
            CheckpointRegistry(tmp).remove(ProbeFakeBackend(), "../secret")
        }
        assertFalse(Files.exists(tmp.resolve("checkpoints")))
    }

    // --- find (see docs/checkpoints.md's "Reusing checkpoints across runs" section) ---

    @Test fun `find returns null when no entry exists`(@TempDir tmp: Path) {
        assertNull(CheckpointRegistry(tmp).find(ProbeFakeBackend(), "seeded-db"))
    }

    @Test fun `find returns the checkpoint when the entry's backend matches and the artifact still exists`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val ref = "rightsize/checkpoint:0123456789ab"
        val backend = ProbeFakeBackend("docker").apply { existingRefs += ref }
        registry.write("seeded-db", record("seeded-db", ref, "docker"))

        val found = registry.find(backend, "seeded-db")

        assertEquals(
            Checkpoint(ref, "docker", CheckpointSpec(mapOf("A" to "1"), listOf("sleep", "120"), listOf(8080), 256)),
            found,
        )
        assertNotNull(registry.read("seeded-db"), "a present-and-valid entry must not be deleted by find")
    }

    @Test fun `find deletes a stale entry and returns null when the backend matches but the artifact is gone`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val backend = ProbeFakeBackend("docker")   // existingRefs left empty: the artifact is gone
        registry.write("seeded-db", record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker"))

        assertNull(registry.find(backend, "seeded-db"))
        assertNull(registry.read("seeded-db"), "a stale entry must be cleaned up, not left behind")
    }

    @Test fun `find returns a different-backend entry unprobed, without deleting it`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val backend = ProbeFakeBackend("docker")   // active backend is docker
        registry.write("seeded-db", record("seeded-db", "rz-ckpt-0123456789ab", "microsandbox"))

        val found = registry.find(backend, "seeded-db")

        assertNotNull(found, "a different-backend entry must be returned, not treated as absent")
        assertEquals("microsandbox", found!!.backend)
        assertTrue(backend.removedRefs.isEmpty(), "a different-backend entry must never be probed or removed")
        assertNotNull(registry.read("seeded-db"), "a different-backend entry must not be deleted by find")
    }

    @Test fun `find treats a corrupt entry as absent and best-effort removes the bad file`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        Files.createDirectories(tmp.resolve("checkpoints"))
        Files.writeString(registry.file("seeded-db"), "{ not valid json")

        assertNull(registry.find(ProbeFakeBackend(), "seeded-db"))
        assertFalse(Files.exists(registry.file("seeded-db")), "a corrupt entry's file must be removed")
    }

    // --- list: registry contents only, never any artifact probing ---

    @Test fun `list returns every entry with no artifact probing`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("a", record("a", "rightsize/checkpoint:aaaaaaaaaaaa", "docker"))
        registry.write("b", record("b", "rz-ckpt-bbbbbbbbbbbb", "microsandbox"))

        assertEquals(
            setOf("rightsize/checkpoint:aaaaaaaaaaaa", "rz-ckpt-bbbbbbbbbbbb"),
            registry.list().map { it.ref }.toSet(),
        )
    }

    @Test fun `list skips a corrupt entry`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("a", record("a", "rightsize/checkpoint:aaaaaaaaaaaa", "docker"))
        Files.createDirectories(tmp.resolve("checkpoints"))
        Files.writeString(registry.file("bad"), "{ not valid json")

        assertEquals(listOf("rightsize/checkpoint:aaaaaaaaaaaa"), registry.list().map { it.ref })
    }

    @Test fun `list is empty when the registry directory doesn't exist yet`(@TempDir tmp: Path) {
        assertTrue(CheckpointRegistry(tmp).list().isEmpty())
    }

    // --- remove: idempotent, best-effort backend cleanup plus the registry file ---

    @Test fun `remove is a no-op success (false) when nothing exists`(@TempDir tmp: Path) {
        val backend = ProbeFakeBackend()
        assertFalse(CheckpointRegistry(tmp).remove(backend, "seeded-db"))
        assertTrue(backend.removedRefs.isEmpty())
    }

    @Test fun `remove deletes the backend artifact and the registry file, and reports true`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val ref = "rightsize/checkpoint:0123456789ab"
        val backend = ProbeFakeBackend("docker").apply { existingRefs += ref }
        registry.write("seeded-db", record("seeded-db", ref, "docker"))

        assertTrue(registry.remove(backend, "seeded-db"))
        assertEquals(listOf(ref), backend.removedRefs)
        assertNull(registry.read("seeded-db"))
    }

    @Test fun `remove is idempotent - calling it again on an already-removed name returns false`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val backend = ProbeFakeBackend("docker")
        registry.write("seeded-db", record("seeded-db", "rightsize/checkpoint:0123456789ab", "docker"))

        assertTrue(registry.remove(backend, "seeded-db"))
        assertFalse(registry.remove(backend, "seeded-db"))
    }

    @Test fun `remove skips the backend call for a different-backend entry but still deletes the registry entry and returns true`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val ref = "rz-ckpt-0123456789ab"
        val backend = ProbeFakeBackend("docker").apply { existingRefs += ref }   // active backend is docker
        registry.write("seeded-db", record("seeded-db", ref, "microsandbox"))   // entry recorded under msb

        assertTrue(registry.remove(backend, "seeded-db"))
        assertTrue(backend.removedRefs.isEmpty(), "a different-backend entry must never reach removeCheckpoint")
        assertNull(registry.read("seeded-db"), "the registry entry must still be deleted regardless of the backend mismatch")
    }
}
