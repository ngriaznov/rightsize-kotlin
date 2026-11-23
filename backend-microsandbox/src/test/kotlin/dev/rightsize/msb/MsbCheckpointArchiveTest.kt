package dev.rightsize.msb

import dev.rightsize.core.CacheDir
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for the checkpoint-archive handling (see docs/checkpoints.md's "Moving checkpoints
 * between machines" section) on msb 0.8.1's snapshot-group CLI: the pure `Added:`/`Reused:`
 * parser first, then [MsbCliBackend.exportCheckpoint]'s argv and [MsbCliBackend.importCheckpoint]'s
 * end-to-end ref resolution against a fake `msb` binary — the fresh group name, `--file` and
 * `--dest` argv, `Added:` and `Reused:` outcomes, a failure surfacing stderr, and a missing
 * report line surfacing a clear error — the same fake-binary style [MsbCheckpointTest] uses for
 * `createCheckpoint`. The effective ref is `<checkpoints dir>/<group>/<id>`, never the archive's
 * originally recorded ref.
 */
class MsbCheckpointArchiveTest {

    private val id = "snap_0123456789abcdef0123456789abcdef"

    // --- parseSnapshotLoadMemberId: pure ---

    @Test fun `parseSnapshotLoadMemberId reads an Added line`() {
        assertEquals(id, parseSnapshotLoadMemberId("Added: $id\n"))
    }

    @Test fun `parseSnapshotLoadMemberId reads a Reused line`() {
        assertEquals(id, parseSnapshotLoadMemberId("Reused: $id\n"))
    }

    @Test fun `parseSnapshotLoadMemberId searches every line, not just the last`() {
        assertEquals(id, parseSnapshotLoadMemberId("warning: something\nAdded: $id\ntrailing note\n"))
    }

    @Test fun `parseSnapshotLoadMemberId returns null when there is no Added or Reused line`() {
        assertNull(parseSnapshotLoadMemberId(""))
        assertNull(parseSnapshotLoadMemberId("group head updated: $id\n"))
        assertNull(parseSnapshotLoadMemberId("Added:\n"))
    }

    @Test fun `parseSnapshotLoadMemberId rejects an id that would leave the group directory`() {
        assertNull(parseSnapshotLoadMemberId("Added: ../escape"))
        assertNull(parseSnapshotLoadMemberId("Added: a/b"))
        assertNull(parseSnapshotLoadMemberId("Added: .."))
    }

    // --- MsbCliBackend.exportCheckpoint argv ---

    @Test fun `exportCheckpoint splits the member path into group dir and --checkpoint member`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-export-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-export", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"\$*\" >> \"$callLog\"\nexit 0\n")
            it.toFile().setExecutable(true)
        }
        val groupDir = Files.createTempDirectory("rz-export-checkpoints").resolve("rz-ckpt-mysnapshot")
        val dest = Files.createTempDirectory("rz-export-dest").resolve("artifact")

        MsbCliBackend(script).exportCheckpoint(groupDir.resolve(id).toString(), dest)

        assertEquals(listOf("snapshot save $groupDir --checkpoint $id $dest"),
            Files.readAllLines(callLog).filter { it.isNotBlank() })
    }

    // --- MsbCliBackend.importCheckpoint end-to-end, against a fake msb binary ---

    /**
     * Fake `msb` covering `snapshot load` only; every other subcommand is a no-op success. Reads
     * `<group> --file <archive> --dest <dir>` from argv. On success it creates
     * `<dir>/<group>/<id>/snapshot.json` (unless [writeMember] is false) and prints `Added: <id>`
     * (`Reused: <id>` when [verb] says so, nothing when [verb] is empty) on stdout; the
     * head-update line goes to stderr, as on msb 0.8.1. Exits non-zero with unrelated stderr when
     * [failFlag] exists. Every invocation's argv is appended to [callLog].
     */
    private fun fakeMsbImport(failFlag: Path, callLog: Path, verb: String = "Added", writeMember: Boolean = true): Path {
        val script = Files.createTempFile("rz-fake-msb-import", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |echo "${'$'}*" >> "$callLog"
            |if [ "${'$'}1" = "snapshot" ] && [ "${'$'}2" = "load" ]; then
            |  group="${'$'}3"; shift 3
            |  dest=""
            |  while [ "${'$'}#" -gt 0 ]; do
            |    case "${'$'}1" in
            |      --dest) dest="${'$'}2"; shift 2 ;;
            |      *) shift ;;
            |    esac
            |  done
            |  if [ -f "$failFlag" ]; then
            |    echo "error: something else went wrong" 1>&2
            |    exit 1
            |  fi
            |  if [ "$writeMember" = "true" ]; then
            |    mkdir -p "${'$'}dest/${'$'}group/$id"
            |    echo '{}' > "${'$'}dest/${'$'}group/$id/snapshot.json"
            |  fi
            |  echo "head: ${'$'}group -> $id" 1>&2
            |  if [ -n "$verb" ]; then echo "$verb: $id"; fi
            |  exit 0
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    private fun noFlag(): Path = Files.createTempFile("rz-importfail-", "").also { Files.deleteIfExists(it) }

    /** Splits the recorded `snapshot load <group> --file <src> --dest <dir>` line; returns the group. */
    private fun loadedGroup(callLog: Path, src: Path, checkpointsDir: Path): String {
        val line = Files.readAllLines(callLog).single { it.isNotBlank() }
        val m = Regex("^snapshot load (rz-ckpt-import-[0-9a-f]{12}) --file ${Regex.escape(src.toString())} " +
            "--dest ${Regex.escape(checkpointsDir.toString())}$").find(line)
        assertNotNull(m, "unexpected snapshot load argv: $line")
        return m!!.groupValues[1]
    }

    @Test fun `importCheckpoint loads into a fresh rz-ckpt-import group and returns group-slash-id under the checkpoints dir`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(noFlag(), callLog))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        val checkpointsDir = CacheDir.resolve().resolve("checkpoints")
        val group = loadedGroup(callLog, src, checkpointsDir)
        assertEquals(checkpointsDir.resolve(group).resolve(id).toString(), effectiveRef,
            "the ref must be <checkpoints>/<group>/<id>, never the archive's original ref")
    }

    @Test fun `importCheckpoint accepts a Reused line, which msb prints with exit 0 for content already present`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(noFlag(), callLog, verb = "Reused"))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val effectiveRef = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        val checkpointsDir = CacheDir.resolve().resolve("checkpoints")
        assertEquals(checkpointsDir.resolve(loadedGroup(callLog, src, checkpointsDir)).resolve(id).toString(), effectiveRef)
    }

    @Test fun `importCheckpoint picks a different group for every import`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-import-calllog-", "")
        val backend = MsbCliBackend(fakeMsbImport(noFlag(), callLog))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val first = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")
        val second = backend.importCheckpoint(src, "rz-ckpt-mysnapshot")

        assertNotEquals(first, second)
    }

    @Test fun `importCheckpoint surfaces stderr for a failed load`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val failFlag = Files.createTempFile("rz-importfail-", "")   // present => failure
        val backend = MsbCliBackend(fakeMsbImport(failFlag, Files.createTempFile("rz-import-calllog-", "")))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("something else went wrong"), "message must carry msb's stderr: ${e.message}")
    }

    @Test fun `importCheckpoint throws a clear error carrying msb's output when no Added or Reused line is printed`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val backend = MsbCliBackend(fakeMsbImport(noFlag(), Files.createTempFile("rz-import-calllog-", ""), verb = ""))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("no 'Added:' or 'Reused:' line"), "message: ${e.message}")
        assertTrue(e.message!!.contains("head: rz-ckpt-import-"), "message must quote msb's stderr: ${e.message}")
    }

    @Test fun `importCheckpoint throws when the reported member has no snapshot json on disk`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val backend = MsbCliBackend(fakeMsbImport(noFlag(), Files.createTempFile("rz-import-calllog-", ""), writeMember = false))
        val src = Files.createTempFile("rz-archive-", ".tar.zst")

        val e = assertThrows(IllegalStateException::class.java) { backend.importCheckpoint(src, "rz-ckpt-mysnapshot") }

        assertTrue(e.message!!.contains("snapshot.json does not exist"), "message: ${e.message}")
    }
}
