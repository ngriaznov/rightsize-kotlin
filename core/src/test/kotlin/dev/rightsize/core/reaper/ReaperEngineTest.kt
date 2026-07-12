package dev.rightsize.core.reaper

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.ExecResult
import dev.rightsize.core.SandboxBackend
import dev.rightsize.core.SandboxHandle
import dev.rightsize.core.WatchdogCommands
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

private open class FakeEngineBackend(override val name: String) : SandboxBackend {
    val removedByName = mutableListOf<String>()
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
    override fun removeNetwork(networkId: String) {}
    override val watchdogCommands = WatchdogCommands(sandboxRemove = listOf("true"))
}

class ReaperEngineTest {
    private fun engine(mode: ReaperMode = ReaperMode.ON, cacheDir: Path = Files.createTempDirectory("rz-engine")) =
        ReaperEngine(mode, cacheDir, pid = ProcessHandle.current().pid(), startedIso = Liveness.currentProcessStartedIso())

    private fun spec(name: String, keepAlive: Boolean = false) =
        ContainerSpec(name = name, image = "alpine:3.19", runId = "run", keepAlive = keepAlive)

    @Test fun `a backend with an unrecognized name never touches the ledger`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        val fake = FakeEngineBackend("fake")
        e.beforeCreate(fake, spec("rz-x-1"))
        assertFalse(Files.exists(cacheDir.resolve("runs")), "no ledger directory must be created for an unknown backend")
    }

    @Test fun `docker-named backend writes the ledger and appends the sandbox`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        val docker = FakeEngineBackend("docker")
        e.beforeCreate(docker, spec("rz-x-1"))
        val runsDir = cacheDir.resolve("runs")
        assertTrue(Files.isDirectory(runsDir))
        val jsonFiles = Files.list(runsDir).use { it.filter { p -> p.toString().endsWith(".json") }.toList() }
        assertEquals(1, jsonFiles.size)
    }

    @Test fun `microsandbox-named backend records the msb-canonical backend id in the run record`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        val msb = FakeEngineBackend("microsandbox")
        e.beforeCreate(msb, spec("rz-x-1"))
        val runsDir = cacheDir.resolve("runs")
        val jsonFile = Files.list(runsDir).use { it.filter { p -> p.toString().endsWith(".json") }.findFirst().get() }
        val record = RunRecord.parse(Files.readString(jsonFile))
        assertEquals("msb", record?.backend)
        assertEquals("true", record?.msbPath)   // sandboxRemove.first() from watchdogCommands
    }

    @Test fun `keepAlive specs never reach the ledger`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        e.beforeCreate(FakeEngineBackend("docker"), spec("rz-reuse-1", keepAlive = true))
        assertFalse(Files.exists(cacheDir.resolve("runs")))
    }

    @Test fun `afterRemove on a keepAlive spec is a no-op`() {
        val e = engine()
        // Must not throw even though no ledger exists yet.
        e.afterRemove(spec("rz-reuse-1", keepAlive = true))
    }

    @Test fun `mode OFF disables the ledger entirely`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(mode = ReaperMode.OFF, cacheDir = cacheDir)
        e.beforeCreate(FakeEngineBackend("docker"), spec("rz-x-1"))
        assertFalse(Files.exists(cacheDir.resolve("runs")))
    }

    @Test fun `mode OFF skips the sweep too`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        Files.createDirectories(cacheDir.resolve("runs"))
        Files.writeString(cacheDir.resolve("runs").resolve("dead.json"),
            RunRecord(999_999_999, "1999-01-01T00:00:00Z", "docker").toJson())
        Files.writeString(cacheDir.resolve("runs").resolve("dead.sandboxes"), "rz-dead-1\n")
        val e = engine(mode = ReaperMode.OFF, cacheDir = cacheDir)
        val backend = FakeEngineBackend("docker")
        e.onBackendResolved(backend)
        assertTrue(backend.removedByName.isEmpty())
    }

    @Test fun `sweep runs and reaps a dead run left by an earlier process`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        Files.createDirectories(cacheDir.resolve("runs"))
        Files.writeString(cacheDir.resolve("runs").resolve("dead.json"),
            RunRecord(999_999_999, Instant.now().toString(), "docker").toJson())
        Files.writeString(cacheDir.resolve("runs").resolve("dead.sandboxes"), "rz-dead-1\n")
        val e = engine(cacheDir = cacheDir)
        val backend = FakeEngineBackend("docker")
        e.onBackendResolved(backend)
        assertEquals(listOf("rz-dead-1"), backend.removedByName)
    }

    @Test fun `sweep runs exactly once even if onBackendResolved is called again`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        val backend = FakeEngineBackend("docker")
        e.onBackendResolved(backend)
        Files.createDirectories(cacheDir.resolve("runs"))
        Files.writeString(cacheDir.resolve("runs").resolve("dead2.json"),
            RunRecord(999_999_999, Instant.now().toString(), "docker").toJson())
        Files.writeString(cacheDir.resolve("runs").resolve("dead2.sandboxes"), "rz-dead2-1\n")
        e.onBackendResolved(backend)   // second call: must not sweep again
        assertTrue(backend.removedByName.isEmpty())
    }

    @Test fun `afterRemove and afterNetworkRemoved before any create are safe no-ops`() {
        val e = engine()
        e.afterRemove(spec("rz-never-created"))
        e.afterNetworkRemoved("rz-net-never-created")
    }

    @Test fun `onProcessExit deletes the ledger files even with entries still tracked`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        e.beforeCreate(FakeEngineBackend("docker"), spec("rz-x-1"))
        val runsDir = cacheDir.resolve("runs")
        assertTrue(Files.list(runsDir).use { it.count() } > 0)
        e.onProcessExit()
        assertEquals(0L, Files.list(runsDir).use { it.count() })
    }

    @Test fun `onProcessExit before any ledger exists does not throw`() {
        engine().onProcessExit()
    }

    @Test fun `network create and remove round-trip through the ledger`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(cacheDir = cacheDir)
        val docker = FakeEngineBackend("docker")
        e.beforeNetworkCreate(docker, "rz-net-1")
        val runsDir = cacheDir.resolve("runs")
        assertTrue(Files.isDirectory(runsDir))
        e.afterNetworkRemoved("rz-net-1")
        assertEquals(0L, Files.list(runsDir).use { it.count() })
    }

    @Test fun `mode SWEEP still spawns no watchdog but still writes the ledger`() {
        val cacheDir = Files.createTempDirectory("rz-engine")
        val e = engine(mode = ReaperMode.SWEEP, cacheDir = cacheDir)
        e.beforeCreate(FakeEngineBackend("docker"), spec("rz-x-1"))
        assertTrue(Files.isDirectory(cacheDir.resolve("runs")))
        // No direct observable for "no watchdog spawned" other than absence of a reaper/ dir
        // being *required*; Watchdog.spawn always creates cacheDir/reaper if called, so its
        // absence here is the proof SWEEP mode never called it.
        assertFalse(Files.exists(cacheDir.resolve("reaper")))
    }
}
