package dev.rightsize.core.reaper

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private open class FakeReapBackend(override val name: String = "docker") : SandboxBackend {
    val removedByName = mutableListOf<String>()
    val removedNetworks = mutableListOf<String>()
    override val supportsNativeNetworks = true
    override fun create(spec: ContainerSpec) = object : SandboxHandle {
        override val id = spec.name; override val spec = spec
    }
    override fun start(handle: SandboxHandle) {}
    override fun stop(handle: SandboxHandle) {}
    override fun remove(handle: SandboxHandle) {}
    override fun removeByName(name: String) { removedByName += name }
    override fun exec(handle: SandboxHandle, cmd: List<String>) = ExecResult(0, "", "")
    override fun logs(handle: SandboxHandle) = ""
    override fun followLogs(handle: SandboxHandle, consumer: (String) -> Unit) = AutoCloseable {}
    override fun ensureNetwork(networkId: String) {}
    override fun removeNetwork(networkId: String) { removedNetworks += networkId }
}

class SweeperTest {
    private fun deadPid(): Long {
        val javaBin = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java").toString()
        val proc = ProcessBuilder(javaBin, "-version").redirectErrorStream(true).start()
        proc.waitFor()
        return proc.pid()
    }

    private fun writeRecord(runsDir: Path, runId: String, record: RunRecord?, rawJson: String? = null) {
        Files.createDirectories(runsDir)
        Files.writeString(runsDir.resolve("$runId.json"), rawJson ?: record!!.toJson())
    }

    private fun writeLines(runsDir: Path, file: String, lines: List<String>) {
        Files.createDirectories(runsDir)
        Files.writeString(runsDir.resolve(file), lines.joinToString("") { "$it\n" })
    }

    @Test fun `dead run is reaped - sandboxes and networks removed, files deleted`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val dead = deadPid()
        writeRecord(runsDir, "dead1", RunRecord(dead, Instant.now().toString(), "docker"))
        writeLines(runsDir, "dead1.sandboxes", listOf("rz-dead1-1", "rz-dead1-2"))
        writeLines(runsDir, "dead1.networks", listOf("rz-net-1"))

        val backend = FakeReapBackend()
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)

        assertEquals(listOf("rz-dead1-1", "rz-dead1-2"), backend.removedByName)
        assertEquals(listOf("rz-net-1"), backend.removedNetworks)
        assertFalse(Files.exists(runsDir.resolve("dead1.json")))
        assertFalse(Files.exists(runsDir.resolve("dead1.sandboxes")))
        assertFalse(Files.exists(runsDir.resolve("dead1.networks")))
    }

    @Test fun `alive run is left completely untouched`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val self = ProcessHandle.current()
        val startedIso = self.info().startInstant().orElseThrow().toString()
        writeRecord(runsDir, "alive1", RunRecord(self.pid(), startedIso, "docker"))
        writeLines(runsDir, "alive1.sandboxes", listOf("rz-alive1-1"))

        val backend = FakeReapBackend()
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)

        assertTrue(backend.removedByName.isEmpty())
        assertTrue(Files.exists(runsDir.resolve("alive1.json")))
        assertTrue(Files.exists(runsDir.resolve("alive1.sandboxes")))
    }

    @Test fun `own run is never swept even if its own liveness check would otherwise pass`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val self = ProcessHandle.current()
        val startedIso = self.info().startInstant().orElseThrow().toString()
        writeRecord(runsDir, "own", RunRecord(self.pid(), startedIso, "docker"))
        writeLines(runsDir, "own.sandboxes", listOf("rz-own-1"))

        val backend = FakeReapBackend()
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)

        assertTrue(backend.removedByName.isEmpty())
        assertTrue(Files.exists(runsDir.resolve("own.json")))
    }

    @Test fun `dead run on a different backend name is left for that backend to sweep`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val dead = deadPid()
        writeRecord(runsDir, "dead-msb", RunRecord(dead, Instant.now().toString(), "msb"))
        writeLines(runsDir, "dead-msb.sandboxes", listOf("rz-deadmsb-1"))

        val dockerBackend = FakeReapBackend(name = "docker")
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = dockerBackend)

        assertTrue(dockerBackend.removedByName.isEmpty(), "a docker process must never remove msb sandboxes")
        assertTrue(Files.exists(runsDir.resolve("dead-msb.json")), "left for an msb process to sweep")
    }

    @Test fun `removeByName matches on canonical backend id, not the raw SandboxBackend name`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val dead = deadPid()
        writeRecord(runsDir, "dead-msb", RunRecord(dead, Instant.now().toString(), "msb"))
        writeLines(runsDir, "dead-msb.sandboxes", listOf("rz-deadmsb-1"))

        // SandboxBackend.name for the msb backend is "microsandbox", not "msb".
        val msbBackend = FakeReapBackend(name = "microsandbox")
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = msbBackend)

        assertEquals(listOf("rz-deadmsb-1"), msbBackend.removedByName)
    }

    @Test fun `not-found removeByName errors are ignored - sweep still deletes the record`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        val dead = deadPid()
        writeRecord(runsDir, "dead1", RunRecord(dead, Instant.now().toString(), "docker"))
        writeLines(runsDir, "dead1.sandboxes", listOf("rz-dead1-1"))

        val throwing = object : FakeReapBackend() {
            override fun removeByName(name: String) { throw RuntimeException("not found") }
        }
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = throwing)

        assertFalse(Files.exists(runsDir.resolve("dead1.json")))
        assertFalse(Files.exists(runsDir.resolve("dead1.sandboxes")))
    }

    @Test fun `unparseable fresh json is skipped, unparseable stale json is cleaned up`() {
        val cacheDir = Files.createTempDirectory("rz-sweep")
        val runsDir = cacheDir.resolve("runs")
        writeRecord(runsDir, "garbage-fresh", null, rawJson = "{not valid json")
        val backend = FakeReapBackend()

        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)
        assertTrue(Files.exists(runsDir.resolve("garbage-fresh.json")), "a fresh unparseable record must be left alone")

        // Backdate the file's mtime past the grace window to simulate a stale abandoned record.
        Files.setLastModifiedTime(runsDir.resolve("garbage-fresh.json"),
            java.nio.file.attribute.FileTime.from(Instant.now().minus(java.time.Duration.ofHours(2))))
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)
        assertFalse(Files.exists(runsDir.resolve("garbage-fresh.json")), "a stale unparseable record must be cleaned up")
    }

    @Test fun `sweeping an empty or missing runs directory does nothing`() {
        val cacheDir = Files.createTempDirectory("rz-sweep-empty")
        val backend = FakeReapBackend()
        Sweeper.sweep(cacheDir, ownRunId = "own", backend = backend)   // runs/ doesn't even exist
        assertTrue(backend.removedByName.isEmpty())
    }
}
