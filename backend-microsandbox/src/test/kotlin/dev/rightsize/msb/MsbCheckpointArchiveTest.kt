package dev.rightsize.msb

import dev.rightsize.core.CacheDir
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for spec 10's checkpoint-archive handling (see docs/checkpoints.md's "Moving
 * checkpoints between machines" section): the pure output-parsing helper first, then
 * [MsbCliBackend.importCheckpoint]'s end-to-end effective-ref resolution against a fake `msb`
 * binary — output-path parsing, the always-pass `--dest` flag, already-exists-as-success, a
 * non-already-exists failure surfacing stderr, and an unparseable-stdout failure surfacing a
 * clear error — the same fake-binary style [MsbCheckpointTest] uses for `createCheckpoint`. The
 * effective ref is msb 0.7.1's own LOADED ARTIFACT PATH (an absolute path nested under this
 * library's own checkpoints directory), never the archive's originally recorded ref, and never
 * a bare digest-dir name looked up separately via `snapshot list` (msb 0.7.1 dropped that
 * shape — see [parseSnapshotLoadArtifactPath]'s doc).
 */
class MsbCheckpointArchiveTest {

    // --- isSnapshotAlreadyExists: pure ---

    @Test fun `isSnapshotAlreadyExists matches msb's own already-exists wording`() {
        assertTrue(isSnapshotAlreadyExists(
            "error: snapshot already exists: /home/x/.microsandbox/snapshots/sha256-b9c0448ee9d54e33"))
    }

    @Test fun `isSnapshotAlreadyExists is false for an unrelated failure`() {
        assertFalse(isSnapshotAlreadyExists("error: database error: state database is corrupt"))
    }

    // --- parseSnapshotLoadArtifactPath: pure, no binary needed ---
    //
    // Absolute-path fixtures are built via toAbsolutePath() rather than hand-typed Unix strings,
    // the same reasoning MsbCheckpointTest's parseSnapshotCreateArtifactPath fixtures use (a
    // leading-slash string isn't absolute on Windows' Path implementation).

    @Test fun `parseSnapshotLoadArtifactPath reads the last non-blank line when it is an absolute path`() {
        val absolute = Path.of("msb-1a2b3c4d", "snap_0123456789abcdef0123456789abcdef").toAbsolutePath().normalize()
        val output = "group msb-1a2b3c4d: head snap_0123456789abcdef0123456789abcdef (Initialized)\n" +
            "digest: sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcd\n$absolute"
        assertEquals(absolute.toString(), parseSnapshotLoadArtifactPath(output))
    }

    @Test fun `parseSnapshotLoadArtifactPath ignores trailing blank lines`() {
        val absolute = Path.of("msb-1a2b3c4d", "snap_0123456789abcdef0123456789abcdef").toAbsolutePath().normalize()
        assertEquals(absolute.toString(), parseSnapshotLoadArtifactPath("$absolute\n\n\n"))
    }

    @Test fun `parseSnapshotLoadArtifactPath returns null for blank output`() {
        assertNull(parseSnapshotLoadArtifactPath(""))
        assertNull(parseSnapshotLoadArtifactPath("\n\n"))
    }

    // Red-proofs the real CI failure the dossier for this migration flagged: the group summary
    // line's own trailing token, "(Initialized)", is not an absolute path and must never be
    // mistaken for the artifact ref.
    @Test fun `parseSnapshotLoadArtifactPath returns null when the last line is not an absolute path`() {
        assertNull(parseSnapshotLoadArtifactPath(
            "group msb-1a2b3c4d: head snap_0123456789abcdef0123456789abcdef (Initialized)"))
        assertNull(parseSnapshotLoadArtifactPath("not a path at all, just words"))
    }

    // --- MsbCliBackend.importCheckpoint end-to-end, against a fake msb binary ---

    /**
     * Fake `msb` covering `snapshot load` only — every other subcommand is a no-op success, since
     * [MsbCliBackend.importCheckpoint] never calls them. Mirrors msb 0.7.1's own `snapshot load`
     * shape (verified empirically against the real binary): on an ordinary success it prints a
     * `group <group>: head snap_<digest> (Initialized)` line, a digest line, and the LOADED
     * ARTIFACT's absolute path — nested under whatever `--dest` the invocation carried — as the
     * LAST line. When [alreadyExistsFlag] exists, it prints that same stdout summary (the
     * artifact is already there, so msb can still report where) but ALSO writes msb's own
     * already-exists sentence to stderr and exits non-zero — [isSnapshotAlreadyExists] is what
     * tells this apart from a genuine failure. `snapshot load` exits non-zero with unrelated
     * stderr and no artifact-path line at all when [importFailFlag] exists. Every invocation's
     * full argv is appended to [callLog], so tests can assert `--dest` was actually passed.
     */
    private fun fakeMsbImport(
        importFailFlag: Path,
        alreadyExistsFlag: Path,
        group: String,
        digest: String,
        callLog: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-import", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |echo "${'$'}*" >> "$callLog"
            |cmd="${'$'}1"; shift
            |if [ "${'$'}cmd" = "snapshot" ]; then
            |  sub="${'$'}1"; shift
            |  case "${'$'}sub" in
            |    load)
            |      shift   # drop the archive path positional
            |      dest=""
            |      while [ "${'$'}#" -gt 0 ]; do
            |        case "${'$'}1" in
            |          --dest) dest="${'$'}2"; shift 2 ;;
            |          *) shift ;;
            |        esac
            |      done
            |      if [ -f "$importFailFlag" ]; then
            |        echo "error: something else went wrong" 1>&2
            |        exit 1
            |      fi
            |      artifact="${'$'}dest/$group/snap_$digest"
            |      echo "group $group: head snap_$digest (Initialized)"
            |      echo "digest: sha256:$digest"
            |      echo "${'$'}artifact"
            |      if [ -f "$alreadyExistsFlag" ]; then
            |        echo "error: snapshot already exists: ${'$'}artifact" 1>&2
            |        exit 1
            |      fi
            |      exit 0
            |      ;;
            |    *) exit 0 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    /** Fake `msb snapshot load` whose stdout ends with a non-absolute-path line — the shape the
     * migration's own dossier flagged as the real CI failure ("it extracts the literal
     * '(Initialized)'"). Exits 0: the failure this drives is a parse failure, not an msb one. */
    private fun fakeMsbImportGarbageStdout(): Path {
        val script = Files.createTempFile("rz-fake-msb-import-garbage", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "snapshot" ] && [ "${'$'}2" = "load" ]; then
            |  echo "group msb-1a2b3c4d: head snap_0123456789abcdef0123456789abcdef (Initialized)"
            |  exit 0
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `importCheckpoint returns the loaded artifact's absolute path and always passes --dest`() {
        val group = "msb-1a2b3c4d"
        val digest = "0123456789abcdef0123456789abcdef"
        val importFailFlag = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, group, digest, callLog))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        val checkpointsDir = CacheDir.resolve().resolve("checkpoints")
        val expectedRef = checkpointsDir.resolve(group).resolve("snap_$digest").toString()
        assertEquals(expectedRef, effectiveRef,
            "the effective ref must be the loaded artifact's absolute path, never the archive's original ref")
        assertEquals(listOf("snapshot load $src --dest $checkpointsDir"), Files.readAllLines(callLog).filter { it.isNotBlank() },
            "snapshot load must always carry --dest pointing at this library's own checkpoints dir")
    }

    @Test fun `importCheckpoint treats msb's already-exists failure as success and still returns the loaded artifact path`() {
        val group = "msb-1a2b3c4d"
        val digest = "0123456789abcdef0123456789abcdef"
        val importFailFlag = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "")   // present => already-exists
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, group, digest, callLog))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        val expectedRef = CacheDir.resolve().resolve("checkpoints").resolve(group).resolve("snap_$digest").toString()
        assertEquals(expectedRef, effectiveRef,
            "an already-exists outcome must resolve the SAME loaded-artifact path, not be treated as a failure")
    }

    @Test fun `importCheckpoint surfaces stderr for a non-already-exists failure`() {
        val group = "msb-1a2b3c4d"
        val digest = "0123456789abcdef0123456789abcdef"
        val importFailFlag = Files.createTempFile("rz-importfail-", "")   // present => genuine failure
        val alreadyExistsFlag = Files.createTempFile("rz-alreadyexists-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(importFailFlag, alreadyExistsFlag, group, digest, callLog))

        val src = Files.createTempFile("rz-archive-", ".tar.zst")
        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("something else went wrong"), "message must carry msb's stderr: ${e.message}")
    }

    @Test fun `importCheckpoint throws a clear error when snapshot load's stdout does not end with an absolute path`() {
        val backend = MsbCliBackend(fakeMsbImportGarbageStdout())
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("(Initialized)"), "message must quote the unparsed output: ${e.message}")
        assertTrue(e.message!!.contains("did not end with an absolute artifact path"),
            "message must say what's wrong, not just fail silently or misresolve: ${e.message}")
    }
}
