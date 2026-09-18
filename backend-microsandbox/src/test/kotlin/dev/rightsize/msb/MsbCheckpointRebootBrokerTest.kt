package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Red-proofs POLICY v2 — [MsbCliBackend.restoreRetryingFreshName]'s escalation to
 * [MsbCliBackend.restoreBroker] once it has hit a Windows [RestoreAccessDeniedException] — driven
 * entirely against a local fake `msb` script plus a STUBBED [RestoreBroker] (never real
 * `powershell`/WMI, which [MsbCliBackend.windowsHost] being pinned `true` via [MsbCliBackend.forHost]
 * is exactly the seam that lets this run on a POSIX CI host at all — see that seam's own doc).
 *
 * [MsbRestoreBrokerScriptTest] in this same file covers the OTHER half — the pure, no-binary-needed
 * script/escaping shape [MsbCliBackend.buildBrokerScript]/[powerShellSingleQuoted] produce.
 */
class MsbCheckpointRebootBrokerTest {

    /**
     * A fake `msb` whose `restore` case fails with msb's own Windows access-denied signature for
     * the first [failCount] invocations (decrementing a counter file so it can be read from
     * outside too), then succeeds on every invocation after that — marking [marker] with the
     * restored name exactly like [MsbCheckpointNameReleaseTest]'s own fakes. `run`/`exec`/`ls`/
     * `stop`/`rm`/`snapshot create` behave the same as every other fake in this package.
     */
    private fun fakeMsbAccessDeniedThenSucceeds(
        marker: Path, callLog: Path, restoreFailCounter: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-broker", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |echo "${'$'}*" >> "$callLog"
            |shift
            |case "${'$'}cmd" in
            |  run)
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --name) name="${'$'}2"; shift 2 ;; *) shift ;; esac
            |    done
            |    echo "${'$'}name" > "$marker"
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  restore)
            |    snapshot="${'$'}1"; shift
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --name) name="${'$'}2"; shift 2 ;; *) shift ;; esac
            |    done
            |    n=${'$'}(cat "$restoreFailCounter" 2>/dev/null || echo 0)
            |    if [ "${'$'}n" -gt 0 ]; then
            |      n=${'$'}((n - 1))
            |      echo "${'$'}n" > "$restoreFailCounter"
            |      echo "error: io error: Access is denied. (os error 5)" 1>&2
            |      exit 1
            |    fi
            |    echo "${'$'}name" > "$marker"
            |    exit 0
            |    ;;
            |  exec)
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --) shift; break ;; *) shift ;; esac
            |    done
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  ls)
            |    if [ -f "$marker" ]; then
            |      n2=${'$'}(cat "$marker")
            |      echo "[{\"name\":\"${'$'}n2\",\"status\":\"Running\"}]"
            |    else
            |      echo "[]"
            |    fi
            |    ;;
            |  stop) rm -f "$marker"; exit 0 ;;
            |  rm) exit 0 ;;
            |  snapshot)
            |    if [ "${'$'}1" = "create" ]; then
            |      shift 2
            |      sandbox="${'$'}1"; shift
            |      shift
            |      destdir="/fake-msb-store"
            |      while [ "${'$'}#" -gt 0 ]; do
            |        case "${'$'}1" in --dest-dir) destdir="${'$'}2"; shift 2 ;; *) shift ;; esac
            |      done
            |      echo "Snapshot ID: fake0000-0000-0000-0000-000000000000"
            |      echo "${'$'}destdir/${'$'}sandbox/snap_0123456789abcdef0123456789abcdef"
            |      exit 0
            |    fi
            |    exit 0
            |    ;;
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    /** One [RestoreBroker] invocation, captured for assertions. */
    private data class BrokerCall(val argv: List<String>, val name: String)

    /** A programmable [RestoreBroker] stub: [outcomes] supplies one [BrokerOutcome] per call, in
     * order (the last entry repeats if exhausted); every call is recorded in [calls]. When an
     * outcome implies the restore itself succeeded ([BrokerOutcome.Completed] with exit 0, or
     * [BrokerOutcome.LaunchedUnconfirmed]), this ALSO writes [marker] with the attempt's own name
     * — simulating what a genuinely brokered `msb restore` would itself eventually do — so the
     * caller's own subsequent `msb ls` poll (against the fake msb script above) finds it Running.
     */
    private class StubBroker(private val marker: Path, private val outcomes: List<BrokerOutcome>) {
        val calls = CopyOnWriteArrayList<BrokerCall>()
        val fn: RestoreBroker = { _, argv, name ->
            calls += BrokerCall(argv, name)
            val outcome = outcomes.getOrElse(calls.size - 1) { outcomes.last() }
            val impliesRunning = outcome is BrokerOutcome.LaunchedUnconfirmed ||
                (outcome is BrokerOutcome.Completed && outcome.exitCode == 0)
            if (impliesRunning) Files.writeString(marker, name)
            outcome
        }
    }

    private fun restoreTarget(argv: List<String>): String {
        val i = argv.indexOf("--name")
        return argv[i + 1]
    }

    @Test fun `the first reboot attempt is always a direct spawn, even on a Windows host, never touching the broker`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "0") }
        val sandboxName = "rz-broker-firstattempt-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")

            assertTrue(stub.calls.isEmpty(), "the broker must never be touched when the direct restore succeeds first try")
            val restoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(1, restoreCalls, "exactly one direct restore attempt, no retry needed")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `escalation trigger - on Windows, the first access-denied classification switches every remaining attempt to the broker`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Only the FIRST (direct, pre-escalation) restore call fails; a same-name or later direct
        // call would also succeed, so this test only proves anything if the broker path really is
        // what the second attempt takes.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-escalate-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertNotEquals(sandboxName, winningName)
            assertTrue(winningName in backend.runningSandboxNames())
            assertEquals(1, stub.calls.size, "exactly one brokered attempt, the second overall")
            assertEquals(winningName, stub.calls.single().name)
            val directRestoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(1, directRestoreCalls,
                "only the FIRST (denied) attempt may be a direct restore call — the winning attempt " +
                    "must never ALSO show up as a direct `restore` invocation")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `non-Windows never brokers, even after the identical access-denied classification`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Always denied — isolates "does this ever call the broker" from "does it eventually
        // succeed via direct retry", which is a separate, already-covered concern.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "999999") }
        val sandboxName = "rz-broker-nonwindows-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(
            fakeMsb, windowsHost = false, restoreBroker = stub.fn, checkpointRebootAlreadyExistsBudgetMs = 500L)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }

            assertTrue(stub.calls.isEmpty(), "windowsHost=false must never invoke the broker, no matter what " +
                "the direct restore's own output classifies as: ${stub.calls}")
            assertTrue(e.message!!.contains("Access is denied") || e.message!!.contains("access-denied") ||
                e.message!!.contains("io error"), "the surfaced failure must still be the access-denied one: ${e.message}")
            val directRestoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertTrue(directRestoreCalls >= 2, "must have kept retrying directly, never switching to the broker")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `brokered-output classification - an already-exists refusal from the broker mints another fresh name and retries brokered again`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-alreadyexists-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(
            marker,
            listOf(
                BrokerOutcome.Completed(1, "error: sandbox already exists: sandbox 'x' already exists; " +
                    "remove it, start the stopped sandbox, or recreate with .replace()"),
                BrokerOutcome.Completed(0, ""),
            ),
        )
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertEquals(2, stub.calls.size, "the refused brokered attempt plus the succeeding brokered retry")
            val brokeredNames = stub.calls.map { it.name }
            assertEquals(2, brokeredNames.toSet().size, "no two brokered attempts may share a name: $brokeredNames")
            assertEquals(winningName, brokeredNames[1])
            val directRestoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(1, directRestoreCalls, "still exactly one direct call — the original pre-escalation one")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `brokered-output classification - an access-denied-again result from the broker mints another fresh name and stays brokered`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-accessdenied-again-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(
            marker,
            listOf(
                BrokerOutcome.Completed(1, "error: io error: Access is denied. (os error 5)"),
                BrokerOutcome.Completed(0, ""),
            ),
        )
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertEquals(2, stub.calls.size, "the access-denied-again brokered attempt plus the succeeding retry")
            val brokeredNames = stub.calls.map { it.name }
            assertEquals(2, brokeredNames.toSet().size, "no two brokered attempts may share a name: $brokeredNames")
            assertEquals(winningName, brokeredNames[1])
            assertTrue(winningName in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `brokered success classification - exit 0 with output reaches Running via the ls poll`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-success-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "activation ok")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")

            assertTrue(effectiveRef.endsWith("snap_0123456789abcdef0123456789abcdef"))
            assertEquals(1, stub.calls.size)
            assertTrue(handle.id in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `missing-ecFile-but-ls-shows-name - LaunchedUnconfirmed skips straight to the ls poll and still succeeds`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-unconfirmed-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.LaunchedUnconfirmed))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertEquals(1, stub.calls.size)
            assertEquals(winningName, stub.calls.single().name)
            assertTrue(winningName in backend.runningSandboxNames(),
                "LaunchedUnconfirmed must still reach Running via the ordinary ls poll, never treated as a failure")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `broker-infrastructure-failure falls back to a direct spawn for that same attempt, without minting an extra name`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Attempt 1 (direct, pre-escalation) is denied; attempt 2's own DIRECT FALLBACK (triggered
        // by the broker's BrokerFailure below) is call #2 to this same fake and must succeed, so
        // the counter only ever needs to absorb ONE denial.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val sandboxName = "rz-broker-infra-failure-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(marker, listOf(BrokerOutcome.BrokerFailure("simulated: powershell.exe not found")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertNotEquals(sandboxName, winningName)
            assertEquals(1, stub.calls.size, "the broker is consulted once for attempt 2, reports infra failure")
            assertEquals(winningName, stub.calls.single().name,
                "the broker was asked to launch under the SAME name the direct fallback then won under — " +
                    "a BrokerFailure must fall back for that one attempt, never mint an extra name of its own")
            val directRestoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(2, directRestoreCalls,
                "attempt 1's own direct call, plus attempt 2's direct FALLBACK after the broker infra failure")
            assertTrue(winningName in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `a broker-infrastructure-failure does not permanently un-escalate - a later attempt tries the broker again`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Attempt 1 direct denied; attempt 2's direct FALLBACK (after BrokerFailure) is ALSO
        // denied (2 total direct denials needed), forcing a third attempt — which must still be
        // brokered, proving `brokered` itself was never reset by the fallback.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "2") }
        val sandboxName = "rz-broker-infra-failure-stays-escalated-test"
        val fakeMsb = fakeMsbAccessDeniedThenSucceeds(marker, callLog, restoreFailCounter)
        val stub = StubBroker(
            marker,
            listOf(BrokerOutcome.BrokerFailure("simulated: powershell.exe not found"), BrokerOutcome.Completed(0, "")),
        )
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val winningName = handle.id

            assertEquals(2, stub.calls.size,
                "attempt 2 consults the broker (infra failure -> direct fallback, itself denied), then " +
                    "attempt 3 consults it again and succeeds — the broker must never be skipped once escalated")
            assertEquals(winningName, stub.calls[1].name)
            val directRestoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(2, directRestoreCalls, "attempt 1's direct call, plus attempt 2's denied direct fallback")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }
}

/**
 * Pure, no-binary-needed coverage for [powerShellSingleQuoted]/[buildBrokerScript] — the escaping
 * and script-shape half of POLICY v2 (see [MsbCheckpointRebootBrokerTest]'s own doc for the
 * end-to-end half). Runs on every platform: neither function spawns a process or touches a file.
 */
class MsbRestoreBrokerScriptTest {

    @Test fun `powerShellSingleQuoted doubles an embedded single quote and wraps the whole token`() {
        assertEquals("'plain'", powerShellSingleQuoted("plain"))
        assertEquals("'it''s a test'", powerShellSingleQuoted("it's a test"))
        assertEquals("''''", powerShellSingleQuoted("'"))
        assertEquals("''", powerShellSingleQuoted(""))
    }

    @Test fun `buildBrokerScript embeds the CIM WMI process-creation call naming Win32_Process Create`() {
        val script = buildBrokerScript(
            Path.of("C:\\tools\\msb.exe"),
            listOf("restore", "C:\\snaps\\ref\\snap_0123", "--name", "rz-run1-3"),
            Path.of("C:\\temp\\rz-broker-1.out.txt"),
            Path.of("C:\\temp\\rz-broker-1.ec.txt"),
        )
        assertTrue("Invoke-CimMethod" in script)
        assertTrue("Win32_Process" in script)
        assertTrue("-MethodName Create" in script)
        assertTrue("LASTEXITCODE" in script, "the inner command must report the restore's own exit code back")
        assertTrue("CIM_RETURN=" in script, "the ReturnValue must be surfaced for the caller's infra-failure check")
        assertTrue("CIM_PID=" in script)
        assertTrue("EC_FILE_START" in script && "EC_FILE_END" in script)
        assertTrue("OUT_FILE_START" in script && "OUT_FILE_END" in script)
    }

    @Test fun `buildBrokerScript single-quote-escapes every interpolated path and argv token`() {
        val msb = Path.of("/usr/local/bin/msb")
        val argv = listOf("restore", "/snaps/it's-a-ref/snap_0123", "--name", "rz-run1-7")
        val outFile = Path.of("/tmp/rz-broker-7.out.txt")
        val ecFile = Path.of("/tmp/rz-broker-7.ec.txt")
        val script = buildBrokerScript(msb, argv, outFile, ecFile)

        assertTrue(powerShellSingleQuoted(msb.toString()) in script, "the msb path must be single-quoted verbatim")
        argv.forEach { token ->
            assertTrue(powerShellSingleQuoted(token) in script,
                "argv token '$token' must appear single-quoted, with any embedded quote doubled: $script")
        }
        assertTrue(powerShellSingleQuoted(outFile.toString()) in script)
        assertTrue(powerShellSingleQuoted(ecFile.toString()) in script)
        // The dangerous case: a raw, un-doubled single quote from the ref token must never appear
        // immediately adjacent to content that would let it prematurely close a quoted literal.
        assertFalse("'/snaps/it's-a-ref" in script,
            "an embedded single quote must never survive un-doubled: $script")
    }

    @Test fun `buildBrokerScript handles an empty argv without producing malformed PowerShell spacing`() {
        val script = buildBrokerScript(Path.of("/usr/local/bin/msb"), emptyList(), Path.of("/tmp/o"), Path.of("/tmp/e"))
        assertTrue("Invoke-CimMethod" in script)
        assertTrue(powerShellSingleQuoted("/usr/local/bin/msb") in script)
    }
}
