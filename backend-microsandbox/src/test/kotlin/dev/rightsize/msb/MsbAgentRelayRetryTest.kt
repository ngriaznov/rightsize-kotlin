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
 * crash exit with the same sentence is never retried. The first tests go through the public
 * constructor, so they really wait the retry's 2s delay; the diagnostics tests at the end shrink
 * it, read back the notes the retry writes to stderr, and check what the fake saw of
 * `msb logs --source system`.
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
     * `logs` is numbered like boots: capture N prints `syslog-N.txt` (nothing when absent), exits
     * with `logs-exit-N` after an error line, or hangs when `logs-hang-N` exists; `rm -f` exits
     * with `rmf-exit` when that exists (after clearing the row, as msb's failures leave it).
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
        private val logCaptures = dir.resolve("logs-count")

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
                |    force=0
                |    for a in "§@"; do
                |      case "§a" in -f) force=1 ;; -*) ;; *) rm -f "$dir/row-§a" ;; esac
                |    done
                |    if [ "§force" = 1 ] && [ -f "$dir/rmf-exit" ]; then
                |      echo "error: rm failed" 1>&2
                |      exit §(cat "$dir/rmf-exit")
                |    fi
                |    exit 0
                |    ;;
                |  logs)
                |    k=§(cat "$logCaptures" 2>/dev/null || echo 0)
                |    k=§((k + 1))
                |    echo "§k" > "$logCaptures"
                |    if [ -f "$dir/logs-hang-§k" ]; then exec sleep 30; fi
                |    if [ -f "$dir/logs-exit-§k" ]; then
                |      echo "error: sandbox not found: §1" 1>&2
                |      exit §(cat "$dir/logs-exit-§k")
                |    fi
                |    if [ -f "$dir/syslog-§k.txt" ]; then cat "$dir/syslog-§k.txt"; fi
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

        /** The system log capture number [n] (1-based) prints. */
        fun systemLog(n: Int, text: String) {
            Files.writeString(dir.resolve("syslog-$n.txt"), text + "\n")
        }

        /** Capture number [n] fails with exit code [code]. */
        fun failLogs(n: Int, code: Int) {
            Files.writeString(dir.resolve("logs-exit-$n"), "$code\n")
        }

        /** Capture number [n] never finishes. */
        fun hangLogs(n: Int) {
            Files.writeString(dir.resolve("logs-hang-$n"), "")
        }

        /** `msb rm -f` exits with [code] (and still clears the row). */
        fun failRmForce(code: Int) {
            Files.writeString(dir.resolve("rmf-exit"), "$code\n")
        }

        /** How many `msb logs` captures the fake served. */
        fun logCaptureCount(): Int = runCatching { Files.readString(logCaptures).trim().toInt() }.getOrDefault(0)

        /** Lets a test stub record its own calls in the same ordered log. */
        fun log(line: String) {
            Files.writeString(callLog, line + "\n", StandardOpenOption.APPEND)
        }

        fun bootCount(): Int = runCatching { Files.readString(boots).trim().toInt() }.getOrDefault(0)

        /** The calls that matter for ordering, as `run NAME`, `restore NAME`, `rm NAME`,
         * `rm -f NAME` or a stub's own `broker NAME`; `ls`/`exec`/`stop`/... are left out, and
         * `logs NAME` only when [includeLogs] is set. */
        fun events(includeLogs: Boolean = false): List<String> = Files.readAllLines(callLog).mapNotNull { line ->
            val parts = line.trim().split(" ")
            when (parts.firstOrNull()) {
                "run", "restore" -> "${parts[0]} ${parts[parts.indexOf("--name") + 1]}"
                "rm", "broker" -> line.trim()
                "logs" -> if (includeLogs) "logs ${parts[1]}" else null
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

    /** Runs [block] with `System.err` swapped for a buffer, and returns its result with what was written. */
    private fun <T> captureStderr(block: () -> T): Pair<T, String> {
        val real = System.err
        val buffer = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buffer, true, "UTF-8"))
        try {
            val result = block()
            System.err.flush()
            return result to buffer.toString("UTF-8")
        } finally {
            System.setErr(real)
        }
    }

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
            // The only test that reads the production stderr writer (the public constructor's
            // default): every other test injects its own.
            val (e, stderr) = captureStderr {
                assertThrows(IllegalStateException::class.java) { backend.start(handle) }
            }

            assertTrue("${note}msb run for sandbox $name exited before its agent relay came up (attempt 1 of 2); " in stderr,
                "the real stderr must carry the attempt-1 line: $stderr")
            assertTrue("${note}msb run for sandbox $name exited before its agent relay came up again " +
                "(attempt 2 of 2); giving up" in stderr, "the real stderr must carry the giving-up line: $stderr")
            val message = e.message!!
            assertTrue(name in message, "must name the sandbox: $message")
            assertTrue("agent relay came up" in message && "both attempts" in message,
                "must say the sandbox process exited before its agent relay on both attempts: $message")
            assertTrue("first attempt:\n${AgentRelayCaptures.attachedRun}\nafter retry:\n${AgentRelayCaptures.unixCleanExit}"
                in message, "must carry both attempts' output, first then retry: $message")
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

    // The notes the retry writes to stderr (see MsbCliBackend.bootOnce): the failed attempt's
    // `msb logs --source system` and the retry's outcome. Every test below injects the writer and
    // a shrunk delay, so nothing waits the real 2s or reaches the real stderr.

    private class Notes {
        val writes = mutableListOf<String>()
        val writer: (String) -> Unit = { writes += it }
    }

    private fun diagnosticBackend(
        fake: FakeMsb, notes: Notes, windowsHost: Boolean = false, delayMs: Long = 50, logTimeoutSec: Long = 10,
    ) = MsbCliBackend.forHost(
        fake.msb, windowsHost = windowsHost, agentRelayRetryDelayMs = delayMs,
        agentRelayLogTimeoutSec = logTimeoutSec, stderrWriter = notes.writer)

    private val note = "rightsize: agent-relay retry: "

    /** The attempt-1 line and the log block that follows it, as one write. */
    private fun attempt1Note(command: String, name: String, rm: String, delay: String, logBlock: String) =
        "${note}msb $command for sandbox $name exited before its agent relay came up (attempt 1 of 2); " +
            "'msb rm -f $name' exited $rm; retrying in $delay\n" +
            "${note}'msb logs $name --source system' after attempt 1 $logBlock"

    @Test fun `a retry that works writes the failed attempt's system log, read before rm -f, then the outcome`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.systemLog(1, "kernel: booting\nruntime: spawned the sandbox process\nruntime: sandbox process exited")
        val name = "rz-relay-diag-run"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "logs $name", "rm -f $name", "run $name"), fake.events(includeLogs = true),
                "the system log is read once, before the rm -f that deletes it")
            assertEquals(1, fake.logCaptureCount())
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms",
                        "(last 3 of 3 lines):\n" +
                            "  kernel: booting\n" +
                            "  runtime: spawned the sandbox process\n" +
                            "  runtime: sandbox process exited\n"),
                    "${note}msb run for sandbox $name booted on attempt 2\n",
                ),
                notes.writes,
                "one write for the attempt-1 line with its log block, one for the outcome, nothing else")
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a restore retry names restore in its notes`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.powerShellDecorated)
        fake.systemLog(1, "runtime: restore child exited")
        val name = "rz-relay-diag-restore"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(restoreSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("restore $name", "logs $name", "rm -f $name", "restore $name"),
                fake.events(includeLogs = true))
            assertEquals(
                listOf(
                    attempt1Note("restore", name, "0", "50 ms", "(last 1 of 1 lines):\n  runtime: restore child exited\n"),
                    "${note}msb restore for sandbox $name booted on attempt 2\n",
                ),
                notes.writes)
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `two relay failures write both system logs, and the final error carries both attempts' output but no log text`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, AgentRelayCaptures.unixCleanExit)
        // Words GenericContainer's classifiers match on: if they reached an exception message, a
        // guest log line would start its port-rename loop.
        fake.systemLog(1, "guest: first boot\nguest: listen tcp :8080: bind: address already in use")
        fake.systemLog(2, "guest: second boot\nguest: port 5432 already allocated\nguest: sandbox already exists")
        val name = "rz-relay-diag-twice"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            assertEquals(listOf("run $name", "logs $name", "rm -f $name", "run $name", "logs $name"),
                fake.events(includeLogs = true), "one capture before rm -f, one after attempt 2, no third boot")
            assertEquals(2, fake.logCaptureCount())
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms",
                        "(last 2 of 2 lines):\n  guest: first boot\n  guest: listen tcp :8080: bind: address already in use\n"),
                    "${note}msb run for sandbox $name exited before its agent relay came up again " +
                        "(attempt 2 of 2); giving up\n" +
                        "${note}'msb logs $name --source system' after attempt 2 (last 3 of 3 lines):\n" +
                        "  guest: second boot\n  guest: port 5432 already allocated\n  guest: sandbox already exists\n",
                ),
                notes.writes)
            assertEquals(
                "msb run for sandbox $name: msb's sandbox process exited before its agent relay came up on " +
                    "both attempts (the retry ran after `msb rm -f` of the failed sandbox and a short pause). " +
                    "msb's system log for each attempt was written to stderr.\n" +
                    "first attempt:\n${AgentRelayCaptures.attachedRun}\n" +
                    "after retry:\n${AgentRelayCaptures.unixCleanExit}",
                e.message)
            generateSequence<Throwable>(e) { it.cause }.forEach { t ->
                for (phrase in listOf("address already in use", "already allocated", "already exists", "guest:")) {
                    assertFalse(phrase in t.message.orEmpty(), "system log text leaked into ${t.javaClass}: ${t.message}")
                }
            }
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a capture that exits non-zero is reported as unreadable and the retry still happens`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failLogs(1, 1)
        val name = "rz-relay-diag-logs-fail"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "logs $name", "rm -f $name", "run $name"), fake.events(includeLogs = true))
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms",
                        "could not be read (exit code 1: error: sandbox not found: $name)\n"),
                    "${note}msb run for sandbox $name booted on attempt 2\n",
                ),
                notes.writes)
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a capture that times out is reported as unreadable and the retry still happens`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.hangLogs(1)
        val name = "rz-relay-diag-logs-hang"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes, logTimeoutSec = 1)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "logs $name", "rm -f $name", "run $name"), fake.events(includeLogs = true))
            assertEquals(2, notes.writes.size)
            val header = "${note}'msb logs $name --source system' after attempt 1 could not be read (" +
                "msb logs $name --source system --tail 1000 timed out after 1s and was force-killed"
            assertTrue(header in notes.writes[0], "the timeout is named: ${notes.writes[0]}")
            assertEquals("${note}msb run for sandbox $name booted on attempt 2\n", notes.writes[1])
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a capture with no output is a one-line header and the retry still happens`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        val name = "rz-relay-diag-logs-empty"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "logs $name", "rm -f $name", "run $name"), fake.events(includeLogs = true))
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms", "produced no output\n"),
                    "${note}msb run for sandbox $name booted on attempt 2\n",
                ),
                notes.writes)
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a failed capture after attempt 2 still ends in the final error`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, AgentRelayCaptures.attachedRun)
        fake.failLogs(2, 7)
        val name = "rz-relay-diag-twice-logs-fail"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            assertTrue("first attempt:\n" in e.message!! && "after retry:\n" in e.message!!, e.message)
            assertEquals(
                "${note}msb run for sandbox $name exited before its agent relay came up again " +
                    "(attempt 2 of 2); giving up\n" +
                    "${note}'msb logs $name --source system' after attempt 2 " +
                    "could not be read (exit code 7: error: sandbox not found: $name)\n",
                notes.writes[1])
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a long system log is cut to its last 200 lines`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.systemLog(1, (1..250).joinToString("\n") { "log line $it" })
        val name = "rz-relay-diag-tail"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            val lines = notes.writes[0].lines().dropLastWhile { it.isEmpty() }
            assertEquals("${note}'msb logs $name --source system' after attempt 1 (last 200 of 250 lines):", lines[1])
            assertEquals(2 + 200, lines.size, "the attempt-1 line, the header and 200 log lines")
            assertEquals("  log line 51", lines[2], "the first shown line is the 51st")
            assertEquals("  log line 250", lines.last())
            assertFalse("log line 50\n" in notes.writes[0], "line 50 is cut")
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a non-zero exit of rm -f is named in the attempt-1 line and does not stop the retry`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failRmForce(3)
        val name = "rz-relay-diag-rm"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(
                listOf(
                    attempt1Note("run", name, "3", "50 ms", "produced no output\n"),
                    "${note}msb run for sandbox $name booted on attempt 2\n",
                ),
                notes.writes)
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a different error on attempt 2 is noted and propagates unchanged`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, "error: something else broke")
        fake.systemLog(1, "runtime: child exited")
        val name = "rz-relay-diag-other"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

            assertEquals(
                "msb run for sandbox $name exited (code 1) before reaching Running — check the image " +
                    "entrypoint and `msb run` output below:\nerror: something else broke",
                e.message, "the error is the one a plain failed boot produces")
            assertSame(IllegalStateException::class.java, e.javaClass, "not wrapped in, or replaced by, another type")
            assertNull(e.cause, "no cause added")
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms", "(last 1 of 1 lines):\n  runtime: child exited\n"),
                    "${note}msb run for sandbox $name failed on attempt 2 with a different error\n",
                ),
                notes.writes)
            assertEquals(1, fake.logCaptureCount(), "no capture after a different error")
        } finally {
            cleanUp(backend, handle)
        }
    }

    // A typed failure on attempt 2 must reach spawnAndAwaitRunning as the same type: its catches
    // are by class, and each one heals or retries with one more boot. If bootOnce re-threw a
    // generic error instead, start() would fail here with only two boots.

    private val stateDbFailure = "error: database error: no such table: sandbox"
    private val imageCacheFailure =
        "error: image error: cache error at /cache/layers/sha256_ab.tar.gz: No such file or directory (os error 2)"

    @Test fun `a state-database error on attempt 2 reaches the state-database retry as the same type`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, stateDbFailure)
        fake.systemLog(1, "runtime: child exited")
        val name = "rz-relay-diag-statedb"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "rm -f $name", "run $name", "run $name"), fake.events(),
                "the state-database retry boots a third time under the same name")
            assertEquals(3, fake.bootCount())
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms", "(last 1 of 1 lines):\n  runtime: child exited\n"),
                    "${note}msb run for sandbox $name failed on attempt 2 with a different error\n",
                ),
                notes.writes, "the relay retry's own notes end at attempt 2; the third boot is not its business")
            assertEquals(1, fake.logCaptureCount(), "no capture after a different error")
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `an image-cache error on attempt 2 reaches the image-cache heal as the same type`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        fake.failBoot(2, imageCacheFailure)
        fake.systemLog(1, "runtime: child exited")
        val name = "rz-relay-diag-imgcache"
        val notes = Notes()
        val backend = diagnosticBackend(fake, notes)
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "rm -f $name", "run $name", "run $name"), fake.events(),
                "the image-cache heal boots a third time under the same name")
            assertEquals(3, fake.bootCount())
            assertEquals(
                listOf(
                    attempt1Note("run", name, "0", "50 ms", "(last 1 of 1 lines):\n  runtime: child exited\n"),
                    "${note}msb run for sandbox $name failed on attempt 2 with a different error\n",
                ),
                notes.writes)
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }

    @Test fun `a first attempt that succeeds or fails another way writes nothing and never reads the system log`() {
        posixOnly()
        val shapes = mapOf(
            "succeeds" to null,
            "crash exit" to AgentRelayCaptures.withStatus("exit code: 0xc0000409"),
            "other error" to "error: something else broke",
            "port conflict" to "error: failed to bind host port: address already in use",
        )
        for ((label, failure) in shapes) {
            val fake = FakeMsb()
            if (failure != null) fake.failBoot(1, failure)
            val name = "rz-relay-diag-quiet"
            val notes = Notes()
            val backend = diagnosticBackend(fake, notes)
            val handle = backend.create(runSpec(name))
            try {
                if (failure == null) backend.start(handle) else assertThrows(Exception::class.java) { backend.start(handle) }

                assertEquals(emptyList<String>(), notes.writes, "[$label] no stderr output")
                assertEquals(listOf("run $name"), fake.events(includeLogs = true), "[$label] one boot, no logs, no rm -f")
                assertEquals(0, fake.logCaptureCount(), "[$label]")
            } finally {
                cleanUp(backend, handle)
            }
        }
    }

    @Test fun `a writer that throws does not change the retry`() {
        posixOnly()
        val fake = FakeMsb()
        fake.failBoot(1, AgentRelayCaptures.attachedRun)
        val name = "rz-relay-diag-bad-writer"
        val backend = MsbCliBackend.forHost(
            fake.msb, windowsHost = false, agentRelayRetryDelayMs = 50,
            stderrWriter = { throw java.io.IOException("stderr closed") })
        val handle = backend.create(runSpec(name))
        try {
            backend.start(handle)

            assertEquals(listOf("run $name", "rm -f $name", "run $name"), fake.events())
            assertTrue(name in backend.runningSandboxNames())
        } finally {
            cleanUp(backend, handle)
        }
    }
}
