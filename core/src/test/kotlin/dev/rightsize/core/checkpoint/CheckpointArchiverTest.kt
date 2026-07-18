package dev.rightsize.core.checkpoint

import dev.rightsize.core.Checkpoint
import dev.rightsize.core.CheckpointBackendMismatchException
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

/**
 * A fake backend whose [exportCheckpoint] writes a recognizable payload file (recorded in
 * [exportedRefs] even when [failExport] makes it throw first, so a staging-cleanup test can still
 * find the directory) and whose [importCheckpoint] records the source path it was handed and
 * returns [importEffectiveRef] — the fake described by spec 10's own unit-test list, driving
 * [CheckpointArchiver] without any real tar/msb/docker underneath the SPI boundary.
 */
private class ArchiveFakeBackend(override val name: String = "fake") : SandboxBackend {
    val existingRefs = mutableSetOf<String>()
    val removedRefs = mutableListOf<String>()
    val exportedRefs = mutableListOf<Pair<String, Path>>()
    val importedSrcPaths = mutableListOf<Path>()
    // The staging artifact file is gone (staging is removed in importArchive's own finally)
    // by the time importArchive returns, so its content must be captured here, during the call.
    val importedSrcContents = mutableListOf<String>()
    var exportPayload = "recognizable-payload-bytes"
    var failExport = false
    var importEffectiveRef = "effective-ref"
    var failImport = false

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
    override fun removeCheckpoint(ref: String) { removedRefs += ref }
    override fun exportCheckpoint(ref: String, dest: Path) {
        exportedRefs += ref to dest
        if (failExport) error("simulated export failure")
        Files.writeString(dest, exportPayload)
    }
    override fun importCheckpoint(src: Path, ref: String): String {
        // .add(), not += — java.nio.file.Path is itself Iterable<Path> (its name elements),
        // which makes MutableList<Path> += aPath an ambiguous plusAssign/plus overload rather
        // than a plain single-element add.
        importedSrcPaths.add(src)
        importedSrcContents += Files.readString(src)
        if (failImport) error("simulated import failure")
        return importEffectiveRef
    }
}

private fun manifestJson(
    rightsizeArchive: Int = 1,
    name: String? = null,
    ref: String = "src-ref",
    backend: String = "fake",
    createdIso: String = "2026-07-11T12:00:00Z",
    spec: CheckpointSpec = CheckpointSpec(),
) = CheckpointArchiveRecord(rightsizeArchive, name, ref, backend, createdIso, spec).toJson()

/** Builds a tar archive at [dest] from [members] under [dir], via the same host `tar` CLI
 * [CheckpointArchiver] itself shells out to — used here only to construct fixture archives
 * directly (bypassing [CheckpointArchiver.exportArchive]) for the malformed-archive tests. */
private fun tarArchive(dir: Path, dest: Path, vararg members: String) {
    val proc = ProcessBuilder(listOf("tar", "-cf", dest.toString(), "-C", dir.toString()) + members).start()
    check(proc.waitFor() == 0) { "test fixture tar failed" }
}

class CheckpointArchiverTest {

    // --- export -> import round trip ---

    @Test fun `export then import round-trips payload bytes and propagates the effective ref`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val archiver = CheckpointArchiver(registry)
        val backend = ArchiveFakeBackend().apply {
            existingRefs += "src-ref"
            importEffectiveRef = "digest-abcdef"
        }
        val cp = Checkpoint(
            ref = "src-ref", backend = "fake",
            spec = CheckpointSpec(env = mapOf("A" to "1"), command = listOf("sleep", "120"), exposedPorts = listOf(8080), memoryLimitMb = 256),
        )
        val archiveFile = tmp.resolve("out").resolve("cp.tar")

        archiver.exportArchive(cp, backend, archiveFile)
        assertTrue(Files.exists(archiveFile), "archive must exist after a successful export")

        val imported = archiver.importArchive(backend, archiveFile)

        assertEquals("digest-abcdef", imported.ref, "the returned checkpoint must carry the EFFECTIVE ref")
        assertEquals("fake", imported.backend)
        assertEquals(cp.spec, imported.spec, "metadata fields must round-trip identically")
        assertEquals(
            backend.exportPayload, backend.importedSrcContents.single(),
            "the artifact bytes handed to importCheckpoint must be byte-for-byte what exportCheckpoint wrote",
        )
    }

    @Test fun `export then import round-trips a named checkpoint into the registry under the effective ref`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val archiver = CheckpointArchiver(registry)
        val backend = ArchiveFakeBackend().apply {
            existingRefs += "src-ref"
            importEffectiveRef = "digest-123456"
        }
        val spec = CheckpointSpec(env = mapOf("SEEDED" to "true"), command = null, exposedPorts = listOf(5432), memoryLimitMb = null)
        registry.write("seeded-db", CheckpointRecord("seeded-db", "src-ref", "fake", "2026-07-11T12:00:00Z", spec))
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = spec)
        val archiveFile = tmp.resolve("named.tar")

        archiver.exportArchive(cp, backend, archiveFile)
        val imported = archiver.importArchive(backend, archiveFile)

        assertEquals("digest-123456", imported.ref)
        val record = registry.read("seeded-db")
        assertEquals("digest-123456", record?.ref, "the registry entry must be rewritten to the EFFECTIVE ref")
        assertEquals("fake", record?.backend)
        assertEquals(spec, record?.spec)
    }

    @Test fun `export of an unnamed checkpoint round-trips as a nameless archive with no registry write on import`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        val archiver = CheckpointArchiver(registry)
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref"; importEffectiveRef = "eph-ref" }
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = CheckpointSpec())
        val archiveFile = tmp.resolve("nameless.tar")

        archiver.exportArchive(cp, backend, archiveFile)
        val imported = archiver.importArchive(backend, archiveFile)

        assertEquals("eph-ref", imported.ref)
        assertFalse(Files.isDirectory(tmp.resolve("checkpoints")),
            "a nameless archive must never create the registry directory, on export or import")
    }

    @Test fun `export creates the destination's parent directories and overwrites an existing file`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref" }
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = CheckpointSpec())
        val dest = tmp.resolve("nested").resolve("dir").resolve("cp.tar")
        Files.createDirectories(dest.parent)
        Files.write(dest, byteArrayOf(1, 2, 3))

        archiver.exportArchive(cp, backend, dest)

        assertTrue(Files.exists(dest))
        assertFalse(Files.readAllBytes(dest).contentEquals(byteArrayOf(1, 2, 3)), "the stale destination file must be overwritten")
    }

    // --- export preconditions ---

    @Test fun `export throws a backend-mismatch typed error before any backend or filesystem work`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend(name = "docker")
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "some-ref", backend = "microsandbox", spec = CheckpointSpec())
        val dest = tmp.resolve("out.tar")

        val e = assertThrows(CheckpointBackendMismatchException::class.java) { archiver.exportArchive(cp, backend, dest) }

        assertTrue(backend.exportedRefs.isEmpty(), "no exportCheckpoint call on a backend mismatch")
        assertFalse(Files.exists(dest))
        assertTrue(e.message!!.contains("microsandbox") && e.message!!.contains("docker"))
    }

    @Test fun `export throws a typed error for a stale artifact and touches no filesystem`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend()   // existingRefs left empty: hasCheckpoint reports false
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "gone-ref", backend = "fake", spec = CheckpointSpec())
        val dest = tmp.resolve("out.tar")

        val e = assertThrows(StaleCheckpointException::class.java) { archiver.exportArchive(cp, backend, dest) }

        assertTrue(backend.exportedRefs.isEmpty(), "no exportCheckpoint call for a stale artifact")
        assertFalse(Files.exists(dest))
        assertTrue(e.message!!.contains("gone-ref"))
    }

    @Test fun `export cleans up its staging directory on success`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref" }
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = CheckpointSpec())

        archiver.exportArchive(cp, backend, tmp.resolve("out.tar"))

        val stagingDir = backend.exportedRefs.single().second.parent
        assertFalse(Files.exists(stagingDir), "the staging directory must be removed after a successful export")
    }

    @Test fun `export cleans up its staging directory even when the backend export call fails`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref"; failExport = true }
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = CheckpointSpec())

        assertThrows(IllegalStateException::class.java) { archiver.exportArchive(cp, backend, tmp.resolve("out.tar")) }

        val stagingDir = backend.exportedRefs.single().second.parent
        assertFalse(Files.exists(stagingDir), "the staging directory must be removed even when the backend call throws")
    }

    // --- import validation, all before any backend call ---

    @Test fun `import throws a typed error when the file does not exist`(@TempDir tmp: Path) {
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val backend = ArchiveFakeBackend()
        val missing = tmp.resolve("nope.tar")

        assertThrows(MalformedCheckpointArchiveException::class.java) { archiver.importArchive(backend, missing) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertFalse(Files.isDirectory(tmp.resolve("checkpoints")), "no registry write for a missing archive file")
    }

    @Test fun `import throws a typed error when the archive is missing checkpoint-json`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("artifact"), "payload")
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "artifact")
        val registryDir = tmp.resolve("registry")
        val archiver = CheckpointArchiver(CheckpointRegistry(registryDir))
        val backend = ArchiveFakeBackend()

        val e = assertThrows(MalformedCheckpointArchiveException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertTrue(e.message!!.contains("checkpoint.json"))
        assertFalse(Files.isDirectory(registryDir.resolve("checkpoints")), "no registry write for an archive missing checkpoint.json")
    }

    @Test fun `import throws a typed error for malformed json`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), "{ not valid json")
        Files.writeString(stage.resolve("artifact"), "payload")
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")
        val registryDir = tmp.resolve("registry")
        val archiver = CheckpointArchiver(CheckpointRegistry(registryDir))
        val backend = ArchiveFakeBackend()

        assertThrows(MalformedCheckpointArchiveException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertFalse(Files.isDirectory(registryDir.resolve("checkpoints")), "no registry write for malformed json")
    }

    @Test fun `import throws a typed error when the archive is missing the artifact member`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson())
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "checkpoint.json")
        val registryDir = tmp.resolve("registry")
        val archiver = CheckpointArchiver(CheckpointRegistry(registryDir))
        val backend = ArchiveFakeBackend()

        val e = assertThrows(MalformedCheckpointArchiveException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty(), "no importCheckpoint call for an archive missing the artifact member")
        assertTrue(e.message!!.contains("artifact"))
        assertFalse(Files.isDirectory(registryDir.resolve("checkpoints")), "no registry write for an archive missing the artifact member")
    }

    @Test fun `import throws a typed error naming an unsupported rightsizeArchive version`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(rightsizeArchive = 2))
        Files.writeString(stage.resolve("artifact"), "payload")
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")
        val registryDir = tmp.resolve("registry")
        val archiver = CheckpointArchiver(CheckpointRegistry(registryDir))
        val backend = ArchiveFakeBackend()

        val e = assertThrows(MalformedCheckpointArchiveException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertTrue(e.message!!.contains("2"), "message should name the offending version: ${e.message}")
        assertFalse(Files.isDirectory(registryDir.resolve("checkpoints")), "no registry write for an unsupported archive version")
    }

    @Test fun `import throws InvalidCheckpointNameException for a bad name, before any backend call`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(name = "Bad_Name!"))
        Files.writeString(stage.resolve("artifact"), "payload")
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp.resolve("registry")))
        val backend = ArchiveFakeBackend()

        assertThrows(InvalidCheckpointNameException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertFalse(Files.isDirectory(tmp.resolve("registry").resolve("checkpoints")))
    }

    @Test fun `import throws a backend-mismatch typed error before any backend call`(@TempDir tmp: Path) {
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(backend = "docker"))
        Files.writeString(stage.resolve("artifact"), "payload")
        val archive = tmp.resolve("bad.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp.resolve("registry")))
        val backend = ArchiveFakeBackend(name = "fake")

        val e = assertThrows(CheckpointBackendMismatchException::class.java) { archiver.importArchive(backend, archive) }
        assertTrue(backend.importedSrcPaths.isEmpty())
        assertTrue(e.message!!.contains("docker") && e.message!!.contains("fake"))
        assertFalse(Files.isDirectory(tmp.resolve("registry").resolve("checkpoints")))
    }

    @Test fun `import cleans up its staging directory on success and on failure`(@TempDir tmp: Path) {
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref" }
        val archiver = CheckpointArchiver(CheckpointRegistry(tmp))
        val cp = Checkpoint(ref = "src-ref", backend = "fake", spec = CheckpointSpec())
        val archiveFile = tmp.resolve("out.tar")
        archiver.exportArchive(cp, backend, archiveFile)

        archiver.importArchive(backend, archiveFile)
        val stagingAfterSuccess = backend.importedSrcPaths.single().parent
        assertFalse(Files.exists(stagingAfterSuccess), "staging must be removed after a successful import")

        backend.failImport = true
        assertThrows(IllegalStateException::class.java) { archiver.importArchive(backend, archiveFile) }
        val stagingAfterFailure = backend.importedSrcPaths.last().parent
        assertFalse(Files.exists(stagingAfterFailure), "staging must be removed even when the backend import call fails")
    }

    // --- import replace semantics ---

    @Test fun `import replace semantics - old same-backend entry's artifact is removed and the entry rewritten`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("seeded-db", CheckpointRecord("seeded-db", "old-ref", "fake", "2026-07-11T12:00:00Z", CheckpointSpec()))
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref"; importEffectiveRef = "new-ref" }
        val archiver = CheckpointArchiver(registry)
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(name = "seeded-db"))
        Files.writeString(stage.resolve("artifact"), "bytes")
        val archive = tmp.resolve("named.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")

        val imported = archiver.importArchive(backend, archive)

        assertEquals("new-ref", imported.ref)
        assertEquals(listOf("old-ref"), backend.removedRefs, "the OLD ref must be best-effort removed before the entry is rewritten")
        val record = registry.read("seeded-db")
        assertEquals("new-ref", record?.ref, "the registry entry must be rewritten to the new effective ref")
    }

    @Test fun `import replace semantics - a cross-backend old entry is left untouched except the rewrite`(@TempDir tmp: Path) {
        val registry = CheckpointRegistry(tmp)
        registry.write("seeded-db", CheckpointRecord("seeded-db", "old-ref-msb", "microsandbox", "2026-07-11T12:00:00Z", CheckpointSpec()))
        val backend = ArchiveFakeBackend(name = "docker").apply { existingRefs += "src-ref"; importEffectiveRef = "new-ref" }
        val archiver = CheckpointArchiver(registry)
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(name = "seeded-db", backend = "docker"))
        Files.writeString(stage.resolve("artifact"), "bytes")
        val archive = tmp.resolve("named.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")

        val imported = archiver.importArchive(backend, archive)

        assertEquals("new-ref", imported.ref)
        assertTrue(backend.removedRefs.isEmpty(), "a cross-backend old entry's ref must never reach removeCheckpoint")
        val record = registry.read("seeded-db")
        assertEquals("new-ref", record?.ref, "the entry must still be rewritten to the new effective ref")
        assertEquals("docker", record?.backend)
    }

    @Test fun `import replace semantics - a same ref old entry is rewritten without a removal call`(@TempDir tmp: Path) {
        // The already-exists-as-success msb outcome resolves to the SAME digest that was already
        // registered: nothing to best-effort remove, but the entry (createdIso in particular) is
        // still rewritten, same as any other successful import.
        val registry = CheckpointRegistry(tmp)
        registry.write("seeded-db", CheckpointRecord("seeded-db", "same-ref", "fake", "2020-01-01T00:00:00Z", CheckpointSpec()))
        val backend = ArchiveFakeBackend().apply { existingRefs += "src-ref"; importEffectiveRef = "same-ref" }
        val archiver = CheckpointArchiver(registry)
        val stage = Files.createDirectory(tmp.resolve("stage"))
        Files.writeString(stage.resolve("checkpoint.json"), manifestJson(name = "seeded-db"))
        Files.writeString(stage.resolve("artifact"), "bytes")
        val archive = tmp.resolve("named.tar")
        tarArchive(stage, archive, "checkpoint.json", "artifact")

        archiver.importArchive(backend, archive)

        assertTrue(backend.removedRefs.isEmpty(), "the ref did not change, so there is nothing to best-effort remove")
        assertEquals("same-ref", registry.read("seeded-db")?.ref)
    }
}
