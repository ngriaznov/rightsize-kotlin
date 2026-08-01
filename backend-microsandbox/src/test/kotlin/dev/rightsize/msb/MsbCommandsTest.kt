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

    // --from-snapshot replaces the image arg entirely when checkpointRef is set (mutually exclusive
    // per `msb run --help`) — still no -d, same as every other boot this backend does.
    @Test fun `run command uses --from-snapshot instead of the image arg when checkpointRef is set`() {
        val cmd = MsbCommands.run(spec.copy(checkpointRef = "rz-ckpt-0123456789ab"))
        assertEquals(listOf("run", "--name", "rz-abc-1",
            "-p", "12345:6379", "-e", "A=1",
            "--mount-file", "/tmp/f.conf:/etc/f.conf:rw,nodev",
            "--from-snapshot", "rz-ckpt-0123456789ab", "--", "redis-server", "--port", "6379"), cmd)
        assertFalse(cmd.contains("redis:8.6-alpine"), "the ordinary image arg must not appear alongside --from-snapshot")
        assertFalse(cmd.contains("-d"))
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
        assertEquals(listOf("stop", "rz-abc-1"), MsbCommands.stop("rz-abc-1"))
        assertEquals(listOf("rm", "rz-abc-1"), MsbCommands.rm("rz-abc-1"))
        assertEquals(listOf("ls", "--format", "json"), MsbCommands.ls())   // no `--json` flag on ls
        assertEquals(listOf("image", "remove", "floci/floci-az:0.8.0"),
            MsbCommands.imageRemove("floci/floci-az:0.8.0"))
    }

    @Test fun `snapshot create and snapshot rm`() {
        assertEquals(listOf("snapshot", "create", "--from", "rz-abc-1", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab"))
        assertEquals(listOf("snapshot", "rm", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotRemove("rz-ckpt-0123456789ab"))
    }

    @Test fun `snapshot create appends --dest-dir when a destination directory is given`() {
        assertEquals(
            listOf("snapshot", "create", "--from", "rz-abc-1", "rz-ckpt-0123456789ab",
                "--dest-dir", "/home/u/.cache/rightsize/checkpoints"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab", Path.of("/home/u/.cache/rightsize/checkpoints")),
        )
    }

    @Test fun `snapshot create without a destination directory is byte-identical to today`() {
        assertEquals(listOf("snapshot", "create", "--from", "rz-abc-1", "rz-ckpt-0123456789ab"),
            MsbCommands.snapshotCreate("rz-abc-1", "rz-ckpt-0123456789ab", null))
    }

    /** [MsbCommands.snapshotCreate] grew a defaulted [Path]? param; without `@JvmOverloads` that
     * removes the original 2-arg `(String, String)` JVM descriptor from a published artifact,
     * breaking any caller compiled against the old jar. Reflection is the only way to see the JVM
     * descriptors Kotlin actually emits — a Kotlin-source call site always compiles against the
     * new signature regardless of `@JvmOverloads`, so it can't catch a regression here. */
    @Test fun `snapshotCreate keeps the original 2-arg JVM descriptor via @JvmOverloads`() {
        val twoArg = MsbCommands::class.java.getMethod("snapshotCreate", String::class.java, String::class.java)
        assertEquals(listOf("snapshot", "create", "--from", "rz-abc-1", "rz-ckpt-0123456789ab"),
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
