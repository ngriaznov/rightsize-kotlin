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
            "--mount-file", "/tmp/f.conf:/etc/f.conf",
            "redis:8.6-alpine", "--", "redis-server", "--port", "6379"), cmd)
        assertFalse(cmd.contains("-d"))
    }

    @Test fun `image default entrypoint runs when command is null`() {
        val cmd = MsbCommands.run(spec.copy(command = null))
        assertEquals("redis:8.6-alpine", cmd.last())   // no trailing `--`: attached mode runs the image default
    }

    // --snapshot replaces the image arg entirely when checkpointRef is set (mutually exclusive
    // per `msb run --help`) — still no -d, same as every other boot this backend does.
    @Test fun `run command uses --snapshot instead of the image arg when checkpointRef is set`() {
        val cmd = MsbCommands.run(spec.copy(checkpointRef = "rz-ckpt-0123456789ab"))
        assertEquals(listOf("run", "--name", "rz-abc-1",
            "-p", "12345:6379", "-e", "A=1",
            "--mount-file", "/tmp/f.conf:/etc/f.conf",
            "--snapshot", "rz-ckpt-0123456789ab", "--", "redis-server", "--port", "6379"), cmd)
        assertFalse(cmd.contains("redis:8.6-alpine"), "the ordinary image arg must not appear alongside --snapshot")
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

    @Test fun `copyTo and copyFrom`() {
        assertEquals(listOf("copy", "-q", "/host/src.txt", "rz-abc-1:/dst.txt"),
            MsbCommands.copyTo("rz-abc-1", Path.of("/host/src.txt"), "/dst.txt"))
        assertEquals(listOf("copy", "-q", "rz-abc-1:/src.txt", "/host/dst.txt"),
            MsbCommands.copyFrom("rz-abc-1", "/src.txt", Path.of("/host/dst.txt")))
    }
}
