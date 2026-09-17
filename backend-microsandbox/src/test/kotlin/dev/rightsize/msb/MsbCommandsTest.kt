package dev.rightsize.msb

import dev.rightsize.core.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

class MsbCommandsTest {
    private val spec = ContainerSpec(
        name = "rz-abc-1", image = "redis:8.6-alpine",
        env = mapOf("A" to "1"), command = listOf("redis-server", "--port", "6379"),
        ports = listOf(PortBinding(hostPort = 12345, guestPort = 6379)),
        mounts = listOf(FileMount(Path.of("/tmp/f.conf"), "/etc/f.conf")),
        networkId = "rz-net-1", aliases = listOf("redis"), runId = "abc",
    )

    // ATTACHED mode (no -d): detached mode never starts the image ENTRYPOINT (confirmed
    // empirically against the real msb binary).
    @Test fun `run command carries all spec parts, attached, no -d`() {
        val cmd = MsbCommands.run(spec)
        assertEquals(listOf("run", "--name", "rz-abc-1",
            "-p", "12345:6379", "-e", "A=1",
            "--mount-file", "/tmp/f.conf:/etc/f.conf:rw,nodev",
            "redis:8.6-alpine", "--", "redis-server", "--port", "6379"), cmd)
        assertFalse(cmd.contains("-d"))
    }

    // Both spellings matter. `ro` is what makes FileMount.readOnly mean anything on this
    // backend, and an always-present token is what keeps the spec parseable on Windows,
    // where a bare `host:guest` splits at the drive letter's colon.
    @Test fun `mount-file always carries an explicit access token`() {
        val cmd = MsbCommands.run(spec.copy(mounts = listOf(
            FileMount(Path.of("/tmp/rw.conf"), "/etc/rw.conf"),
            FileMount(Path.of("/tmp/ro.conf"), "/etc/ro.conf", readOnly = true),
        )))
        assertTrue(cmd.contains("/tmp/rw.conf:/etc/rw.conf:rw,nodev"), "argv was $cmd")
        assertTrue(cmd.contains("/tmp/ro.conf:/etc/ro.conf:ro,nodev"), "argv was $cmd")
        assertFalse(cmd.contains("/tmp/rw.conf:/etc/rw.conf"), "a two-segment spec must never be emitted: $cmd")
    }

    @Test fun `image default entrypoint runs when command is null`() {
        val cmd = MsbCommands.run(spec.copy(command = null))
        assertEquals("redis:8.6-alpine", cmd.last())   // no trailing `--`: attached mode runs the image default
    }

    // A checkpointRef-carrying spec is never fed to `run` in practice — MsbCliBackend routes it
    // to MsbCommands.restore instead (msb 0.7 removed `run --from-snapshot` outright) — but `run`
    // itself stays pure and simply ignores checkpointRef, always emitting the ordinary image arg.
    @Test fun `run command ignores checkpointRef and always emits the ordinary image arg`() {
        val cmd = MsbCommands.run(spec.copy(checkpointRef = "rz-ckpt-0123456789ab"))
        assertEquals(listOf("run", "--name", "rz-abc-1",
            "-p", "12345:6379", "-e", "A=1",
            "--mount-file", "/tmp/f.conf:/etc/f.conf:rw,nodev",
            "redis:8.6-alpine", "--", "redis-server", "--port", "6379"), cmd)
        assertFalse(cmd.contains("--from-snapshot"), "msb 0.7 rejects --from-snapshot outright — run must never emit it")
        assertFalse(cmd.contains("-d"))
    }

    // --- MsbCommands.restore: msb 0.7's dedicated restore command ---

    @Test fun `restore command carries the ref, name, --disk-only, memory, and ports - never env, command, or mounts`() {
        val cmd = MsbCommands.restore(spec.copy(
            checkpointRef = "rz-ckpt-0123456789ab", memoryLimitMb = 1024))
        assertEquals(listOf("restore", "rz-ckpt-0123456789ab", "--name", "rz-abc-1",
            "--disk-only", "-m", "1024M", "-p", "12345:6379"), cmd)
        assertFalse(cmd.contains("-e"), "restore has no -e/--env flag at all")
        assertFalse(cmd.contains("A=1"))
        assertFalse(cmd.contains("--"), "restore has no trailing-command flag at all")
        assertFalse(cmd.contains("redis-server"))
        assertFalse(cmd.contains("--mount-file"), "restore has no --mount-file equivalent")
        assertFalse(cmd.contains("redis:8.6-alpine"), "the ordinary image arg must not appear on a restore")
    }

    @Test fun `restore command omits -m when memoryLimitMb is null`() {
        val cmd = MsbCommands.restore(spec.copy(checkpointRef = "rz-ckpt-0123456789ab", memoryLimitMb = null))
        assertEquals(listOf("restore", "rz-ckpt-0123456789ab", "--name", "rz-abc-1", "--disk-only",
            "-p", "12345:6379"), cmd)
    }

    @Test fun `restore command omits -p when there are no ports`() {
        val cmd = MsbCommands.restore(spec.copy(checkpointRef = "rz-ckpt-0123456789ab", ports = emptyList()))
        assertEquals(listOf("restore", "rz-ckpt-0123456789ab", "--name", "rz-abc-1", "--disk-only"), cmd)
    }

    @Test fun `restore command always emits --disk-only`() {
        assertTrue(MsbCommands.restore(spec.copy(checkpointRef = "rz-ckpt-0123456789ab")).contains("--disk-only"))
    }

    @Test fun `restore throws when checkpointRef is not set`() {
        assertThrows(IllegalArgumentException::class.java) {
            MsbCommands.restore(spec.copy(checkpointRef = null))
        }
    }

    @Test fun `run command includes -m when memoryLimitMb is set, absent when null`() {
        val withLimit = MsbCommands.run(spec.copy(memoryLimitMb = 1024))
        val mIndex = withLimit.indexOf("-m")
        assertTrue(mIndex >= 0, "expected -m flag in $withLimit")
        assertEquals("1024M", withLimit[mIndex + 1])

        val withoutLimit = MsbCommands.run(spec)   // memoryLimitMb defaults to null
        assertFalse(withoutLimit.contains("-m"), "no -m flag when memoryLimitMb is null: $withoutLimit")
    }

    @Test fun `run command emits --root-disk with a plain M suffix for diskLimitMb`() {
        val cmd = MsbCommands.run(spec.copy(diskLimitMb = 2048))
        val i = cmd.indexOf("--root-disk")
        assertTrue(i >= 0, "expected --root-disk flag in $cmd")
        assertEquals("2048M", cmd[i + 1])
    }

    @Test fun `run command emits --root-disk with a tmpfs prefix for tmpfsRootMb`() {
        val cmd = MsbCommands.run(spec.copy(tmpfsRootMb = 512))
        val i = cmd.indexOf("--root-disk")
        assertTrue(i >= 0, "expected --root-disk flag in $cmd")
        assertEquals("tmpfs:512M", cmd[i + 1])
    }

    @Test fun `run command omits --root-disk when neither diskLimitMb nor tmpfsRootMb is set`() {
        assertFalse(MsbCommands.run(spec).contains("--root-disk"))
    }

    @Test fun `run command emits --net private when networkDisabled is true, absent when false`() {
        val disabled = MsbCommands.run(spec.copy(networkDisabled = true))
        val i = disabled.indexOf("--net")
        assertTrue(i >= 0, "expected --net flag in $disabled")
        assertEquals("private", disabled[i + 1])

        assertFalse(MsbCommands.run(spec).contains("--net"), "no --net flag when networkDisabled is false")
    }

    // Pinned order: `run --name <n> [-m <mem>M] [--root-disk <SPEC>] [--net private] [-p h:g]...`
    // — the new flags sit between memory and ports, root-disk before net.
    @Test fun `run command orders -m, --root-disk, --net, then -p when all are set`() {
        val cmd = MsbCommands.run(spec.copy(memoryLimitMb = 1024, diskLimitMb = 2048, networkDisabled = true))
        assertEquals(listOf("run", "--name", "rz-abc-1",
            "-m", "1024M", "--root-disk", "2048M", "--net", "private",
            "-p", "12345:6379", "-e", "A=1",
            "--mount-file", "/tmp/f.conf:/etc/f.conf:rw,nodev",
            "redis:8.6-alpine", "--", "redis-server", "--port", "6379"), cmd)
    }

    @Test fun `exec logs stop rm ls`() {
        assertEquals(listOf("exec", "rz-abc-1", "--", "redis-cli", "ping"),
            MsbCommands.exec("rz-abc-1", listOf("redis-cli", "ping")))
        assertEquals(listOf("logs", "rz-abc-1", "--tail", "1000"), MsbCommands.logs("rz-abc-1"))
        assertEquals(listOf("logs", "rz-abc-1", "-f"), MsbCommands.followLogs("rz-abc-1"))
        assertEquals(listOf("logs", "rz-abc-1", "--source", "system", "--tail", "1000"),
            MsbCommands.logsSystem("rz-abc-1"))
        assertEquals(listOf("stop", "rz-abc-1"), MsbCommands.stop("rz-abc-1"))
        assertEquals(listOf("rm", "rz-abc-1"), MsbCommands.rm("rz-abc-1"))
        assertEquals(listOf("ls", "--format", "json"), MsbCommands.ls())   // no `--json` flag on ls
        assertEquals(listOf("image", "remove", "floci/floci-az:0.8.0"),
            MsbCommands.imageRemove("floci/floci-az:0.8.0"))
    }

    @Test fun `snapshot create and snapshot rm`() {
        assertEquals(listOf("snapshot", "create", "--from-sandbox", "rz-abc-1", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab"))
        assertEquals(listOf("snapshot", "rm", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotRemove("rz-ckpt-0123456789ab"))
    }

    @Test fun `snapshot create appends --dest-dir when a destination directory is given`() {
        assertEquals(
            listOf("snapshot", "create", "--from-sandbox", "rz-abc-1", "rz-ckpt-0123456789ab",
                "--dest-dir", "/home/u/.cache/rightsize/checkpoints"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab", Path.of("/home/u/.cache/rightsize/checkpoints")),
        )
    }

    @Test fun `snapshot create without a destination directory is byte-identical to today`() {
        assertEquals(listOf("snapshot", "create", "--from-sandbox", "rz-abc-1", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab", null))
    }

    /** [MsbCommands.snapshotCreate] grew a defaulted [Path]? param; without `@JvmOverloads` that
     * removes the original 2-arg `(String, String)` JVM descriptor from a published artifact,
     * breaking any caller compiled against the old jar. Reflection is the only way to see the JVM
     * descriptors Kotlin actually emits — a Kotlin-source call site always compiles against the
     * new signature regardless of `@JvmOverloads`, so it can't catch a regression here. */
    @Test fun `snapshotCreate keeps the original 2-arg JVM descriptor via @JvmOverloads`() {
        val twoArg = MsbCommands::class.java.getMethod("snapshotCreate", String::class.java, String::class.java)
        assertEquals(listOf("snapshot", "create", "--from-sandbox", "rz-abc-1", "rz-ckpt-0123456789ab"),
            twoArg.invoke(MsbCommands, "rz-abc-1", "rz-ckpt-0123456789ab"))
        // The 3-arg descriptor must still exist too — this is an addition, not a replacement.
        MsbCommands::class.java.getMethod("snapshotCreate", String::class.java, String::class.java, Path::class.java)
    }

    @Test fun `copyTo and copyFrom`() {
        assertEquals(listOf("copy", "-q", "/host/src.txt", "rz-abc-1:/dst.txt"),
            MsbCommands.copyTo("rz-abc-1", Path.of("/host/src.txt"), "/dst.txt"))
        assertEquals(listOf("copy", "-q", "rz-abc-1:/src.txt", "/host/dst.txt"),
            MsbCommands.copyFrom("rz-abc-1", "/src.txt", Path.of("/host/dst.txt")))
    }
}
