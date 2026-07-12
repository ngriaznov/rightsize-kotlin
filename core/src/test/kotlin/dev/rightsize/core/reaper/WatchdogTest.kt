package dev.rightsize.core.reaper

import dev.rightsize.core.WatchdogCommands
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * POSIX-only: exercises the real `sh` script end to end with a stub recorder command standing
 * in for the backend CLI. Windows script correctness is exercised by the msb-windows CI job's
 * SIGKILL integration test instead (see docs/reaping.md), per the spec's own gating guidance.
 */
class WatchdogTest {
    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    @Test fun `on stdin EOF, kills each sandbox, removes each network, deletes the record files`() {
        assumeFalse(isWindows(), "POSIX-only watchdog script; Windows path is covered by msb-windows CI")

        val cacheDir = Files.createTempDirectory("rz-watchdog")
        val runsDir = Files.createDirectories(cacheDir.resolve("runs"))
        val sandboxesFile = runsDir.resolve("run1.sandboxes")
        val networksFile = runsDir.resolve("run1.networks")
        val recordFile = runsDir.resolve("run1.json")
        Files.writeString(sandboxesFile, "rz-run1-1\nrz-run1-2\n")
        Files.writeString(networksFile, "rz-net-1\n")
        Files.writeString(recordFile, "{}")

        val stopLog = Files.createTempFile("rz-stoplog-", ".txt")
        val rmLog = Files.createTempFile("rz-rmlog-", ".txt")
        val netLog = Files.createTempFile("rz-netlog-", ".txt")
        val stopCmd = stubRecorderTo(stopLog)
        val rmCmd = stubRecorderTo(rmLog)
        val netCmd = stubRecorderTo(netLog)

        val commands = WatchdogCommands(
            sandboxStop = listOf(stopCmd.toString()),
            sandboxRemove = listOf(rmCmd.toString()),
            networkRemove = listOf(netCmd.toString()),
        )
        val pipe = Watchdog.spawn(cacheDir, sandboxesFile, networksFile, recordFile, commands, isWindows = false)

        // Close the write end: this is the process-death signal the watchdog blocks on.
        pipe.close()

        // Poll for the record files to disappear rather than a fixed sleep — the script runs
        // fast, but CI runners can be slow to schedule the freshly spawned process.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && Files.exists(recordFile)) Thread.sleep(50)

        assertFalse(Files.exists(recordFile), "record file must be deleted once the watchdog fires")
        assertFalse(Files.exists(sandboxesFile))
        assertFalse(Files.exists(networksFile))

        val stopped = Files.readAllLines(stopLog).filter { it.isNotBlank() }
        val removed = Files.readAllLines(rmLog).filter { it.isNotBlank() }
        val netRemoved = Files.readAllLines(netLog).filter { it.isNotBlank() }
        assertEquals(listOf("rz-run1-1", "rz-run1-2"), stopped)
        assertEquals(listOf("rz-run1-1", "rz-run1-2"), removed)
        assertEquals(listOf("rz-net-1"), netRemoved)
    }

    @Test fun `empty sandboxes and networks files - just deletes the record files (clean shutdown)`() {
        assumeFalse(isWindows(), "POSIX-only watchdog script; Windows path is covered by msb-windows CI")

        val cacheDir = Files.createTempDirectory("rz-watchdog-empty")
        val runsDir = Files.createDirectories(cacheDir.resolve("runs"))
        val sandboxesFile = runsDir.resolve("run2.sandboxes")
        val networksFile = runsDir.resolve("run2.networks")
        val recordFile = runsDir.resolve("run2.json")
        Files.writeString(recordFile, "{}")   // sandboxes/networks files never even created

        val commands = WatchdogCommands(sandboxRemove = listOf("true"))
        val pipe = Watchdog.spawn(cacheDir, sandboxesFile, networksFile, recordFile, commands, isWindows = false)
        pipe.close()

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && Files.exists(recordFile)) Thread.sleep(50)
        assertFalse(Files.exists(recordFile))
    }

    @Test fun `a missing sandboxStop step is skipped entirely`() {
        assumeFalse(isWindows(), "POSIX-only watchdog script; Windows path is covered by msb-windows CI")

        val cacheDir = Files.createTempDirectory("rz-watchdog-nostop")
        val runsDir = Files.createDirectories(cacheDir.resolve("runs"))
        val sandboxesFile = runsDir.resolve("run3.sandboxes")
        val networksFile = runsDir.resolve("run3.networks")
        val recordFile = runsDir.resolve("run3.json")
        Files.writeString(sandboxesFile, "rz-run3-1\n")
        Files.writeString(recordFile, "{}")

        val rmLog = Files.createTempFile("rz-rmlog2-", ".txt")
        val rmCmd = stubRecorderTo(rmLog)
        // docker-shaped: no stop step, force-remove only.
        val commands = WatchdogCommands(sandboxRemove = listOf(rmCmd.toString(), "-f"))
        val pipe = Watchdog.spawn(cacheDir, sandboxesFile, networksFile, recordFile, commands, isWindows = false)
        pipe.close()

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && Files.exists(recordFile)) Thread.sleep(50)
        assertEquals(listOf("-f rz-run3-1"), Files.readAllLines(rmLog).filter { it.isNotBlank() })
    }

    @Test fun `script name derives from content so differing scripts can never collide`() {
        val a = Watchdog.scriptName("#!/bin/sh\necho a\n", "sh")
        val b = Watchdog.scriptName("#!/bin/sh\necho b\n", "sh")
        assertTrue(a.matches(Regex("watchdog-[0-9a-f]{12}\\.sh")), a)
        assertTrue(a != b, "distinct content must yield distinct filenames")
        assertEquals(a, Watchdog.scriptName("#!/bin/sh\necho a\n", "sh"))
    }

    private fun stubRecorderTo(log: Path): Path {
        val script = Files.createTempFile("rz-stub-", ".sh")
        Files.writeString(script, "#!/bin/sh\necho \"\$@\" >> \"$log\"\n")
        script.toFile().setExecutable(true)
        return script
    }
}
