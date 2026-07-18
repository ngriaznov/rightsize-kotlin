package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for spec 10's checkpoint-archive digest handling (see docs/checkpoints.md's "Moving
 * checkpoints between machines" section): the pure output-parsing helpers first, then
 * [MsbCliBackend.importCheckpoint]'s end-to-end effective-ref resolution against a fake `msb`
 * binary — output-path parsing, already-exists-as-success, and a non-already-exists failure
 * surfacing stderr — the same fake-binary style [MsbCheckpointTest] uses for `createCheckpoint`.
 * The effective ref is the digest-DIR name (`sha256-<16hex>`), never the full `sha256:<64hex>`
 * digest: msb does not resolve the latter as a snapshot ref at all.
 */
class MsbCheckpointArchiveTest {

    // --- parseImportedDigestDir: pure, no binary needed ---

    @Test fun `parseImportedDigestDir reads the last line's trailing path and returns its basename`() {
        val output = "unpacking archive...\nimported snapshot at /home/x/.microsandbox/snapshots/sha256-b9c0448ee9d54e33"
        assertEquals("sha256-b9c0448ee9d54e33", parseImportedDigestDir(output))
    }

    @Test fun `parseImportedDigestDir works against a single-line output`() {
        assertEquals("sha256-abc123", parseImportedDigestDir("/root/.microsandbox/snapshots/sha256-abc123"))
    }

    @Test fun `parseImportedDigestDir ignores trailing blank lines`() {
        val output = "/root/.microsandbox/snapshots/sha256-abc123\n\n\n"
        assertEquals("sha256-abc123", parseImportedDigestDir(output))
    }

    @Test fun `parseImportedDigestDir returns null for blank output`() {
        assertNull(parseImportedDigestDir(""))
        assertNull(parseImportedDigestDir("\n\n"))
    }

    // --- isSnapshotAlreadyExists: pure ---

    @Test fun `isSnapshotAlreadyExists matches msb's own already-exists wording`() {
        assertTrue(isSnapshotAlreadyExists(
            "error: snapshot already exists: /home/x/.microsandbox/snapshots/sha256-b9c0448ee9d54e33"))
    }

    @Test fun `isSnapshotAlreadyExists is false for an unrelated failure`() {
        assertFalse(isSnapshotAlreadyExists("error: database error: state database is corrupt"))
    }

    // --- MsbSnapshotListJson.contains ---

    @Test fun `contains matches on the name field`() {
        val json = """[{"name":"sha256-b9c0448ee9d54e33","artifact_path":"/x/snapshots/sha256-b9c0448ee9d54e33"}]"""
        assertTrue(MsbSnapshotListJson.contains(json, "sha256-b9c0448ee9d54e33"))
    }

    @Test fun `contains matches on the artifact_path basename when name differs`() {
        val json = """[{"name":"something-else","artifact_path":"/x/snapshots/sha256-b9c0448ee9d54e33"}]"""
        assertTrue(MsbSnapshotListJson.contains(json, "sha256-b9c0448ee9d54e33"))
    }

    @Test fun `contains is false when nothing matches`() {
        val json = """[{"name":"unrelated","artifact_path":"/x/snapshots/unrelated"}]"""
        assertFalse(MsbSnapshotListJson.contains(json, "sha256-b9c0448ee9d54e33"))
    }

    @Test fun `contains is false for garbage input rather than throwing`() {
        assertFalse(MsbSnapshotListJson.contains("not json at all", "sha256-b9c0448ee9d54e33"))
    }

    // --- MsbCliBackend.importCheckpoint end-to-end, against a fake msb binary ---

    /**
     * Fake `msb` covering `snapshot import`/`snapshot list --format json` only — every other
     * subcommand is a no-op success, since [MsbCliBackend.importCheckpoint] never calls them.
     * `snapshot import` exits non-zero with unrelated stderr when [importFailFlag] exists; exits
     * non-zero with msb's own already-exists wording when [alreadyExistsFlag] exists (still
     * printing the artifact path, on stderr this time — matching msb's real already-exists
     * behavior per docs/checkpoints.md's verified contracts); otherwise exits 0 printing a
     * success line ending with the artifact path. `snapshot list` always prints [snapshotListJson].
     */
    private fun fakeMsbImport(
        importFailFlag: Path,
        alreadyExistsFlag: Path,
        digestDir: String,
        snapshotListJson: String,
    ): Path {
        val listFile = Files.createTempFile("rz-fake-msb-snaplist", ".json")
        Files.writeString(listFile, snapshotListJson)
        val script = Files.createTempFile("rz-fake-msb-import", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"; shift
            |if [ "${'$'}cmd" = "snapshot" ]; then
            |  sub="${'$'}1"; shift
            |  case "${'$'}sub" in
            |    import)
            |      if [ -f "$importFailFlag" ]; then
            |        echo "error: something else went wrong" 1>&2
            |        exit 1
            |      fi
            |      if [ -f "$alreadyExistsFlag" ]; then
            |        echo "error: snapshot already exists: /home/x/.microsandbox/snapshots/$digestDir" 1>&2
            |        exit 1
            |      fi
            |      echo "imported snapshot at /home/x/.microsandbox/snapshots/$digestDir"
            |      exit 0
            |      ;;
            |    list) cat "$listFile"; exit 0 ;;
            |    *) exit 0 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `importCheckpoint returns the digest-dir name via output-path parsing plus snapshot list confirmation`() {
        val digestDir = "sha256-b9c0448ee9d54e33"
        val importFailFlag = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "").also { Files.deleteIfExists(it) }
        val listJson = """[{"name":"$digestDir","artifact_path":"/home/x/.microsandbox/snapshots/$digestDir"}]"""
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, digestDir, listJson))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        assertEquals(digestDir, effectiveRef, "the effective ref must be the digest-dir name, never the full sha256: digest")
    }

    @Test fun `importCheckpoint treats msb's already-exists failure as success and still returns the digest-dir name`() {
        val digestDir = "sha256-b9c0448ee9d54e33"
        val importFailFlag = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "")   // present => already-exists
        val listJson = """[{"name":"$digestDir","artifact_path":"/home/x/.microsandbox/snapshots/$digestDir"}]"""
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, digestDir, listJson))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        assertEquals(digestDir, effectiveRef,
            "an already-exists outcome must resolve the SAME digest-dir name, not be treated as a failure")
    }

    @Test fun `importCheckpoint surfaces stderr for a non-already-exists failure`() {
        val digestDir = "sha256-b9c0448ee9d54e33"
        val importFailFlag = Files.createTempFile("rz-importfail-", "")   // present => genuine failure
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, digestDir, "[]"))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("something else went wrong"), "message must carry msb's stderr: ${e.message}")
    }

    @Test fun `importCheckpoint throws a typed error when snapshot list has no entry for the parsed digest-dir`() {
        val digestDir = "sha256-b9c0448ee9d54e33"
        val importFailFlag = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, digestDir, "[]"))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains(digestDir), "message must name the unresolved digest-dir: ${e.message}")
    }
}
