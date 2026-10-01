package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.SandboxHandle
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * End-to-end coverage, through a fake `msb` script, for [MsbCliBackend.bootOnce]'s one retry of
 * msb's agent-relay boot failure (see [isAgentRelayUnavailable]): an attached `run`, a direct
 * restore, brokered restores (stubbed [RestoreBroker]) with and without the broker's direct
 * fallback, and [MsbCliBackend.createCheckpoint]'s reboot. Each retry must `msb rm -f` the failed
 * name and boot again under the SAME name; a second failure is an error, not a third try; a
 * crash exit with the same sentence is never retried. The retry's 2s delay is not injectable, so
 * the tests that retry really wait for it.
 */
class MsbAgentRelayRetryTest {

    /**
     * A fake `msb` in a temp directory. Every `run`/`restore` is a numbered boot (counted
     * together); boot N prints `fail-N.txt` to stderr and exits 1 when [failBoot] wrote one, and
     * otherwise succeeds. A failed boot whose output mentions the agent relay leaves a "row" for
     * its name, like msb's stopped sandbox record: any later boot under that name fails with
     * msb's already-exists refusal until an `rm` (plain or `-f`) clears it, so a same-name retry
     * only works if the backend removed the failed sandbox first. `run` writes the marker and
     * blocks while it exists (attached mode), `restore` writes it and exits, `exec` blocks on it,
     * `ls` reports the marked name Running, `stop` clears it. Every call is appended to a log.
     *
     * The script text uses `§` where the shell needs `$`, since a raw string would otherwise
     * read `$x` as a Kotlin template.
     */
    private class FakeMsb {
        val dir: Path = Files.createTempDirectory("rz-fake-msb-relay-")
        val msb: Path = dir.resolve("msb")
        private val marker = dir.resolve("marker")
        private val callLog = dir.resolve("calls.log")
        private val boots = dir.resolve("boots")

        init {
            Files.writeString(callLog, "")
            Files.writeString(
                msb,
                """
                |#!/bin/sh
                |cmd="§1"
                |echo "§*" >> "$callLog"
                |shift
                |case "§cmd" in
                |  run|restore)
                |    name=""
                |    while [ "§#" -gt 0 ]; do
                |      case "§1" in --name) name="§2"; shift 2 ;; *) shift ;; esac
                |    done
                |    n=§(cat "$boots" 2>/dev/null || echo 0)
                |    n=§((n + 1))
                |    echo "§n" > "$boots"
                |    if [ -f "$dir/row-§name" ]; then
                |      echo "error: sandbox already exists: sandbox '§name' already exists; remove it, start the stopped sandbox, or recreate with .replace()" 1>&2
                |      exit 1
                |    fi
                |    if [ -f "$dir/fail-§n.txt" ]; then
                |      case "§(cat "$dir/fail-§n.txt")" in *"agent relay"*) touch "$dir/row-§name" ;; esac
                |      cat "$dir/fail-§n.txt" 1>&2
                |      exit 1
                |    fi
                |    echo "§name" > "$marker"
                |    if [ "§cmd" = run ]; then
                |      while [ -f "$marker" ]; do sleep 0.05; done
                |    fi
                |    exit 0
                |    ;;
                |  exec)
                |    while [ "§#" -gt 0 ]; do
                |      case "§1" in --) shift; break ;; *) shift ;; esac
                |    done
                |    while [ -f "$marker" ]; do sleep 0.05; done
                |    exit 0
                |    ;;
                |  ls)
                |    if [ -f "$marker" ]; then
                |      n2=§(cat "$marker")
                |      echo "[{\"name\":\"§n2\",\"status\":\"Running\"}]"
                |    else
                |      echo "[]"
                |    fi
                |    ;;
                |  stop) rm -f "$marker"; exit 0 ;;
                |  rm)
                |    for a in "§@"; do
                |      case "§a" in -*) ;; *) rm -f "$dir/row-§a" ;; esac
                |    done
                |    exit 0
                |    ;;
                |  snapshot)
                |    if [ "§1" = "create" ]; then
                |      shift 2
                |      sandbox="§1"; shift
                |      shift
                |      echo "Snapshot ID: fake0000-0000-0000-0000-000000000000"
                |      echo "/fake-msb-store/§sandbox/snap_0123456789abcdef0123456789abcdef"
                |      exit 0
                |    fi
                |    exit 0
                |    ;;
                |  *) exit 0 ;;
                |esac
                |""".trimMargin().replace('§', '$'),
            )
            msb.toFile().setExecutable(true)
        }

        /** Boot number [n] (1-based, `run` and `restore` counted together) prints [output] and exits 1. */
        fun failBoot(n: Int, output: String) {
            Files.writeString(dir.resolve("fail-$n.txt"), output + "\n")
        }

        /** Lets a test stub record its own calls in the same ordered log. */
        fun log(line: String) {
            Files.writeString(callLog, line + "\n", StandardOpenOption.APPEND)
        }

        fun bootCount(): Int = runCatching { Files.readString(boots).trim().toInt() }.getOrDefault(0)

        /** The calls that matter for ordering, as `run NAME`, `restore NAME`, `rm NAME`,
         * `rm -f NAME` or a stub's own `broker NAME`; `ls`/`exec`/`stop`/... are left out. */
        fun events(): List<String> = Files.readAllLines(callLog).mapNotNull { line ->
            val parts = line.trim().split(" ")
            when (parts.firstOrNull()) {
                "run", "restore" -> "${parts[0]} ${parts[parts.indexOf("--name") + 1]}"
                "rm", "broker" -> line.trim()
                else -> null
            }
        }
    }

    private fun posixOnly() =
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")

    private fun runSpec(name: String) = ContainerSpec(name = name, image = "irrelevant", runId = "run1")

    private fun restoreSpec(name: String) = ContainerSpec(
        name = name, image = "irrelevant", runId = "run1", command = listOf("serve"),
        checkpointRef = "/fake-store/$name/snap_0123456789abcdef0123456789abcdef",
    )

    private val accessDenied = "error: io error: Access is denied. (os error 5)"

    /** Stops and removes [handle], tolerating whatever state a failed start left behind. */
    private fun cleanUp(backend: MsbCliBackend, handle: SandboxHandle) {
        runCatching { backend.stop(handle) }
        runCatching { backend.remove(handle) }
    }

    @Test fun `an attached run that dies once with the agent-relay failure is removed with rm -f and retried under the same name`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        val name = "rz-relay-run-once"
        val backend = MsbCliBackend(fake.msb)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "rm -f $name", "run $name"), fake.events(),
                "the fake must see rm -f of the failed name between two runs under that same name")
            assertEquals(name, handle.id, "a same-name retry must not rename the handle")
            assertTrue(name in backend.runningSandboxNames())
            assertTrue(name in backend.trackedNames(), "the retried boot is tracked for own-run cleanup like any start")
            assertNotNull((handle as MsbCliBackend.Handle).attached, "the retried run is the supervising child")
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `an attached run that fails twice with the agent-relay failure ends in the new error and never tries a third time`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, AgentRelayCaptures.unixCleanExit)
        val name = "rz-relay-run-twice"
        val backend = MsbCliBackend(fake.msb)
        val handle = backend.create(runSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            val message = e.message!!
            assertTrue(name in message, "must name the sandbox: $message")
            assertTrue("agent relay came up" in message && "both attempts" in message,
                "must say the sandbox process exited before its agent relay on both attempts: $message")
            assertTrue("(exit status: 0) before agent relay became available" in message,
                "must carry the last attempt's output: $message")
            assertEquals(listOf("run $name", "rm -f $name", "run $name"), fake.events(),
                "exactly two boots and one rm -f, no third attempt")
            assertEquals(2, fake.bootCount())
            assertFalse(name in backend.trackedNames(), "a failed start is never tracked")
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a crash exit with the same message is not retried on a run boot`() {
        posixOnly()
        for (status in listOf("exit code: 0xc0000409", "exit code: 1", "exit status: 1", "signal: 6 (SIGABRT)")) {
            val fake = FakeMsb()
            fake.failBoot(1, AgentRelayCaptures.withStatus(status))
            val name = "rz-relay-run-crash"
            val backend = MsbCliBackend(fake.msb)
            val handle = backend.create(runSpec(name))
            try {
                val e = assertThrows(IllegalStateException::class.java, { backend.start(handle) }, status)

                assertTrue("before reaching Running" in e.message!!, "[$status] the generic boot error: ${e.message}")
                assertTrue("($status)" in e.message!!, "[$status] the raw output stays in the error: ${e.message}")
                assertEquals(listOf("run $name"), fake.events(), "[$status] a crash must be a single boot, no rm -f")
            } finally {
                cleanUp(backend, handle)
            }
        }
    }

    @Test fun `a direct restore that dies once with the agent-relay failure is retried under the same name`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.powerShellDecorated)
        val name = "rz-relay-restore-once"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = false)
        val handle = backend.create(restoreSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("restore $name", "rm -f $name", "restore $name"), fake.events())
            assertEquals(name, handle.id, "the relay retry reuses the name; only access-denied/collision walks mint one")
            assertTrue(name in backend.runningSandboxNames())
            assertNotNull((handle as MsbCliBackend.Handle).attached, "the retried restore still revives the workload")
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a restore that fails twice with the agent-relay failure ends in the new error and never tries a third time`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, AgentRelayCaptures.attachedRun)
        val name = "rz-relay-restore-twice"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = false)
        val handle = backend.create(restoreSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            assertTrue(name in e.message!! && "msb restore" in e.message!! && "both attempts" in e.message!!, e.message)
            assertEquals(listOf("restore $name", "rm -f $name", "restore $name"), fake.events())
            assertEquals(2, fake.bootCount())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a crash exit with the same message is not retried on a restore boot`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.withStatus("exit code: 0xc0000409"))
        val name = "rz-relay-restore-crash"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = false)
        val handle = backend.create(restoreSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            assertTrue("msb restore for sandbox $name failed (exit 1)" in e.message!!, e.message)
            assertEquals(listOf("restore $name"), fake.events())
        } finally {
            cleanUp(backend, handle)
        }
    }

    /**
     * A [RestoreBroker] stub returning [outcomes] in order (the last repeats), recording each call
     * in [fake]'s own log so its position against the `rm -f` is visible. An outcome that means
     * the restore succeeded also writes the fake's marker, so `msb ls` then reports it Running.
     */
    private fun stubBroker(fake: FakeMsb, outcomes: List<BrokerOutcome>): RestoreBroker {
        var calls = 0
        return { _, _, name ->
            fake.log("broker $name")
            val outcome = outcomes.getOrElse(calls++) { outcomes.last() }
            val impliesRunning = outcome is BrokerOutcome.LaunchedUnconfirmed ||
                (outcome is BrokerOutcome.Completed && outcome.exitCode == 0)
            if (impliesRunning) Files.writeString(fake.dir.resolve("marker"), name)
            outcome
        }
    }

    @Test fun `a brokered restore that dies once with the agent-relay failure is retried through the broker under the same name`() {
        posixOnly()
        val fake = FakeMsb()
        // Boot 1 is the direct restore that hits access-denied and latches the broker; the retries
        // below happen inside the escalated attempt.
        fake.failBoot(1, accessDenied)
        val broker = stubBroker(fake, listOf(
            BrokerOutcome.Completed(1, AgentRelayCaptures.powerShellDecorated),
            BrokerOutcome.Completed(0, ""),
        ))
        val original = "rz-relay-broker-once"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = true, restoreBroker = broker)
        val handle = backend.create(restoreSpec(original))
        try {
            backend.start(handle)
            val fresh = handle.id

            assertNotEquals(original, fresh, "the access-denied step still mints a fresh name")
            assertEquals(
                listOf("restore $original", "rm $original", "broker $fresh", "rm -f $fresh", "broker $fresh"),
                fake.events(),
                "two brokered launches under one name with rm -f between them, no direct restore for the retry")
            assertTrue(fresh in backend.runningSandboxNames())
            assertNotNull((handle as MsbCliBackend.Handle).attached)
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a brokered restore falls back to a direct spawn on each attempt, and the relay retry covers the fallback`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, accessDenied)                           // direct restore: latches the broker
        fake.failBoot(2, AgentRelayCaptures.powerShellDecorated) // the broker's direct fallback
        val broker = stubBroker(fake, listOf(BrokerOutcome.BrokerFailure("simulated: powershell.exe not found")))
        val original = "rz-relay-fallback"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = true, restoreBroker = broker)
        val handle = backend.create(restoreSpec(original))
        try {
            backend.start(handle)
            val fresh = handle.id

            assertNotEquals(original, fresh)
            assertEquals(
                listOf(
                    "restore $original", "rm $original",
                    "broker $fresh", "restore $fresh", "rm -f $fresh",
                    "broker $fresh", "restore $fresh",
                ),
                fake.events(),
                "the retry keeps the launch mode: broker first, direct fallback second, under the same name")
            assertTrue(fresh in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a checkpoint reboot that dies once with the agent-relay failure is retried under the reboot's fresh name`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(2, AgentRelayCaptures.attachedRun)         // boot 1 is the initial run
        val original = "rz-relay-ckpt"
        val backend = MsbCliBackend.forHost(fake.msb, windowsHost = false)
        val handle = backend.create(
            ContainerSpec(name = original, image = "irrelevant", runId = "run1", command = listOf("serve")))
        try {
            backend.start(handle)
            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val fresh = handle.id

            assertNotEquals(original, fresh, "a checkpoint reboot always boots under a fresh name")
            assertEquals(
                listOf("run $original", "rm $original", "restore $fresh", "rm -f $fresh", "restore $fresh"),
                fake.events())
            assertTrue(fresh in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    // The three other boot transients each retry through bootOnce too: a relay failure on their
    // retried boot is itself retried, then the boot succeeds.

    @Test fun `a relay failure on the state-database retry's boot is retried`() {
        posixOnly()
        assertRelayRetriedAfter("error: database error: Execution Error: error returned from database: " +
            "(code: 1) duplicate column name: kind", "rz-relay-after-statedb")
    }

    @Test fun `a relay failure on the install-lock poll's boot is retried`() {
        posixOnly()
        assertRelayRetriedAfter("error: runtime error: microsandbox install operation in progress until " +
            "2026-07-31 20:55:04.779845600; retry after it completes", "rz-relay-after-installlock")
    }

    @Test fun `a relay failure on the image-cache heal's boot is retried`() {
        posixOnly()
        assertRelayRetriedAfter("error: image error: cache error at /tmp/cache/layers/sha256_dead.tar.gz: " +
            "No such file or directory (os error 2)", "rz-relay-after-imagecache")
    }

    private fun assertRelayRetriedAfter(firstFailure: String, name: String) {
        val fake = FakeMsb()
        fake.failBoot(1, firstFailure)
        fake.failBoot(2, AgentRelayCaptures.attachedRun)
        val backend = MsbCliBackend(fake.msb)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "run $name", "rm -f $name", "run $name"), fake.events())
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }
}
