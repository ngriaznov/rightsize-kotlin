package dev.rightsize.msb

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coverage for the Windows-only salvage of an `msb snapshot save` that wrote its archive and then
 * failed the fsync that follows it (see [isSnapshotSaveAccessDenied], [salvageStagedArchive] and
 * [MsbCliBackend.exportCheckpoint]). Both halves are plain functions rather than Windows-gated
 * code, so the classifier and the file move are exercised on every platform; the call-site tests
 * drive a [MsbCliBackend] constructed with `windowsHost = true` against a fake `msb` binary, the
 * same fake-binary style [MsbCheckpointArchiveTest] uses for `importCheckpoint`.
 */
class SnapshotSaveAccessDeniedTest {

    // Captured verbatim from a windows-2025 hosted runner exporting a checkpoint archive.
    private val capturedStderr =
        "error: io error: Access is denied. (os error 5)"

    // --- isSnapshotSaveAccessDenied: pure ---

    @Test fun `matches the captured windows access-denied failure`() {
        assertTrue(isSnapshotSaveAccessDenied(capturedStderr))
    }

    @Test fun `matches the same errno on a host whose message text is not English`() {
        // The message half comes from Windows' FormatMessage and is localized; only the
        // Rust-appended errno suffix is stable, which is what the classifier reads.
        assertTrue(isSnapshotSaveAccessDenied("error: io error: Zugriff verweigert (os error 5)"))
    }

    @Test fun `does not match other msb save failures`() {
        assertFalse(isSnapshotSaveAccessDenied(
            "error: snapshot not found: rz-ckpt-fccd7568 at /home/x/.microsandbox/snapshots/rz-ckpt-fccd7568"))
        assertFalse(isSnapshotSaveAccessDenied("error: io error: No such file or directory (os error 2)"))
        assertFalse(isSnapshotSaveAccessDenied(""))
    }

    // --- salvageStagedArchive: pure filesystem work, no msb binary ---

    /** Writes msb's own staging-file name — `.<destination file name>.tmp.<pid>.<unix nanos>` —
     * beside [dest], carrying [content]. */
    private fun stage(dest: Path, content: String, pid: Int = 8980, nanos: Long = 1785507050813959600L): Path =
        Files.writeString(dest.parent.resolve(".${dest.fileName}.tmp.$pid.$nanos"), content)

    @Test fun `salvage moves the single staging file onto the destination, bytes intact`() {
        val dir = Files.createTempDirectory("rz-export-salvage")
        val dest = dir.resolve("artifact")
        val staged = stage(dest, "zstd archive payload")

        assertTrue(salvageStagedArchive(dest))
        assertEquals("zstd archive payload", Files.readString(dest),
            "the destination must carry the staging file's exact bytes")
        assertFalse(Files.exists(staged), "the staging file must be gone, not copied")
    }

    @Test fun `salvage reports failure and creates nothing when there is no staging file`() {
        val dir = Files.createTempDirectory("rz-export-salvage-none")
        val dest = dir.resolve("artifact")

        assertFalse(salvageStagedArchive(dest))
        assertFalse(Files.exists(dest), "no destination may be conjured out of nothing")
        assertEquals(emptyList<Path>(), Files.list(dir).use { it.toList() },
            "the directory must be left exactly as it was")
    }

    @Test fun `salvage ignores a directory carrying the staging name`() {
        // msb writes a regular file there. A directory under that name would otherwise be the
        // single candidate and get moved onto the destination, leaving the archive step to
        // bundle a directory as the artifact member.
        val dir = Files.createTempDirectory("rz-export-salvage-dir")
        val dest = dir.resolve("artifact")
        val decoy = Files.createDirectory(dir.resolve(".${dest.fileName}.tmp.8980.1785507050813959600"))

        assertFalse(salvageStagedArchive(dest))
        assertFalse(Files.exists(dest), "a directory is not a salvageable archive")
        assertTrue(Files.isDirectory(decoy), "the decoy must be left exactly as it was")
    }

    @Test fun `salvage reports failure and touches nothing when two staging files are present`() {
        // Two candidates means this is not the failure being worked around, and picking one
        // would be a guess — both must survive untouched.
        val dir = Files.createTempDirectory("rz-export-salvage-two")
        val dest = dir.resolve("artifact")
        val first = stage(dest, "first", pid = 8980)
        val second = stage(dest, "second", pid = 9111)

        assertFalse(salvageStagedArchive(dest))
        assertFalse(Files.exists(dest))
        assertEquals("first", Files.readString(first))
        assertEquals("second", Files.readString(second))
    }

    // --- MsbCliBackend.exportCheckpoint, against a fake msb binary ---

    /**
     * Fake `msb` whose `snapshot save` always fails with [stderrLine], first writing [stagedBytes]
     * to msb's own staging-file name beside the destination when non-null — the shape a save that
     * wrote its archive and then failed the fsync leaves behind. Every other subcommand is a no-op
     * success; [MsbCliBackend.exportCheckpoint] calls none of them.
     */
    private fun fakeMsbSaveFailure(stderrLine: String, stagedBytes: String?): Path {
        val d = '$'
        val stageStep = if (stagedBytes == null) "" else
            "|  printf '%s' '$stagedBytes' > \"${d}dir/.${d}base.tmp.8980.1785507050813959600\"\n"
        val script = Files.createTempFile("rz-fake-msb-save", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${d}1" = "snapshot" ] && [ "${d}2" = "save" ]; then
            |  dest="${d}4"
            |  dir=${d}(dirname "${d}dest")
            |  base=${d}(basename "${d}dest")
            $stageStep|  echo "$stderrLine" 1>&2
            |  exit 1
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `exportCheckpoint surfaces an unrelated save failure unchanged and salvages nothing`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val dir = Files.createTempDirectory("rz-export-unrelated")
        val dest = dir.resolve("artifact")
        // A staging file IS present, so the only thing keeping the salvage from running is the
        // classifier rejecting this stderr.
        val staged = stage(dest, "not this failure's business")
        val backend = MsbCliBackend.forHost(
            fakeMsbSaveFailure("error: snapshot not found: rz-ckpt-fccd7568", stagedBytes = null),
            windowsHost = true,
        )

        val e = assertThrows(IllegalStateException::class.java) { backend.exportCheckpoint("rz-ckpt-fccd7568", dest) }

        assertTrue(e.message!!.contains("error: snapshot not found: rz-ckpt-fccd7568"),
            "the original msb error must surface intact: ${e.message}")
        assertFalse(Files.exists(dest), "no salvage may be attempted for a failure that isn't the fsync one")
        assertEquals("not this failure's business", Files.readString(staged))
    }

    @Test fun `exportCheckpoint completes the rename on windows when the save failed its fsync`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val dir = Files.createTempDirectory("rz-export-fsync")
        val dest = dir.resolve("artifact")
        val backend = MsbCliBackend.forHost(
            fakeMsbSaveFailure(capturedStderr, stagedBytes = "zstd archive payload"),
            windowsHost = true,
        )

        backend.exportCheckpoint("rz-ckpt-fccd7568", dest)

        assertEquals("zstd archive payload", Files.readString(dest),
            "the archive msb had already finished writing must end up at the destination")
    }

    @Test fun `exportCheckpoint does not salvage off windows, where the fsync failure cannot arise`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val dir = Files.createTempDirectory("rz-export-posix")
        val dest = dir.resolve("artifact")
        val backend = MsbCliBackend.forHost(
            fakeMsbSaveFailure(capturedStderr, stagedBytes = "zstd archive payload"),
            windowsHost = false,
        )

        val e = assertThrows(IllegalStateException::class.java) { backend.exportCheckpoint("rz-ckpt-fccd7568", dest) }

        assertTrue(e.message!!.contains("(os error 5)"), "the original msb error must surface: ${e.message}")
        assertFalse(Files.exists(dest), "an errno 5 on a POSIX host is a different bug and must not be masked")
    }
}
