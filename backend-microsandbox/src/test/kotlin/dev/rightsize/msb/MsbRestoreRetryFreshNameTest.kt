package dev.rightsize.msb

import dev.rightsize.core.CheckpointMissingCopiedFilesException
import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Red-proofs [MsbCliBackend.restoreRetryingFreshName]'s SECOND caller: an ordinary
 * `GenericContainer.fromCheckpoint(cp).start()` restore, reached from [MsbCliBackend.start]
 * directly (never through [MsbCliBackend.createCheckpoint]'s own reboot — that half is already
 * covered by [MsbCheckpointRebootBrokerTest]/[MsbCheckpointNameReleaseTest]). Before this policy
 * extended to the ordinary path, `start()` on a `checkpointRef` spec retried a Windows
 * access-denied restore failure under the SAME sandbox name — see
 * [MsbRestoreSupervisionTest]'s own now-updated advancement red-proof for that half — which is
 * exactly the five-test CI failure this round's dossier describes (`SandboxNameCollisionException`
 * at `MsbCliBackend.classifyRestoreExit`). These tests drive `backend.start(handle)` directly
 * against a handle whose spec ALREADY carries `checkpointRef` (simulating
 * `GenericContainer.fromCheckpoint(cp).start()`), entirely against a local fake `msb` script plus
 * a stubbed [RestoreBroker] — same pattern as [MsbCheckpointRebootBrokerTest].
 */
class MsbRestoreRetryFreshNameTest {

    /**
     * A fake `msb` whose `restore` case fails with [failureSignature] for the first
     * [restoreFailCounter] invocations (decrementing a counter file so it can be read from
     * outside too), then succeeds on every invocation after that — marking [marker] with the
     * restored name. `exec`/`ls`/`stop`/`rm` behave the same as every other fake in this package.
     */
    private fun fakeMsbRestoreFailsThenSucceeds(
        marker: Path, callLog: Path, restoreFailCounter: Path, failureSignature: String,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-restore-retry", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |echo "${'$'}*" >> "$callLog"
            |shift
            |case "${'$'}cmd" in
            |  restore)
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in --name) name="${'$'}2"; shift 2 ;; *) shift ;; esac
            |    done
            |    n=${'$'}(cat "$restoreFailCounter" 2>/dev/null || echo 0)
            |    if [ "${'$'}n" -gt 0 ]; then
            |      n=${'$'}((n - 1))
            |      echo "${'$'}n" > "$restoreFailCounter"
            |      echo "$failureSignature" 1>&2
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
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    private val accessDenied = "error: io error: Access is denied. (os error 5)"
    private val alreadyExists = "error: sandbox already exists: sandbox 'x' already exists; remove it, " +
        "start the stopped sandbox, or recreate with .replace()"

    // msb 0.7.8's refusal of a restore that lacks a destination for a copied file, verbatim
    // (three lines: the error, then two hints).
    private val missingBindings = "error: invalid config: restore requires destination bindings for: " +
        "mount /etc/app/b.conf; provide a destination mount for each path or explicitly allow missing resources\n" +
        "  \u2192 map each path with -v SOURCE:GUEST or --mount-disk SOURCE:GUEST\n" +
        "  \u2192 or pass --allow-missing-resources to start without them"

    private fun restoreSpec(name: String) = ContainerSpec(
        name = name, image = "irrelevant", runId = "run1", command = listOf("serve"),
        checkpointRef = "/fake-store/$name/snap_0123456789abcdef0123456789abcdef",
    )

    private fun restoreNames(callLog: Path): List<String> =
        Files.readAllLines(callLog).filter { it.startsWith("restore ") }.map { line ->
            val parts = line.trim().split(" ")
            parts[parts.indexOf("--name") + 1]
        }

    private fun rmNames(callLog: Path): List<String> =
        Files.readAllLines(callLog).filter { it.startsWith("rm ") }.map { it.trim().removePrefix("rm ").trim() }

    /** One [RestoreBroker] invocation, captured for assertions — same shape as
     * [MsbCheckpointRebootBrokerTest]'s own stub. */
    private data class BrokerCall(val argv: List<String>, val name: String)

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

    @Test fun `the first ordinary restore attempt is always a direct spawn under the original name, even on a Windows host`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "0") }
        val originalName = "rz-ordinary-firstattempt-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            backend.start(handle)

            assertTrue(stub.calls.isEmpty(), "the broker must never be touched when the direct restore succeeds first try")
            assertEquals(originalName, handle.id, "a first-try success must never rename the handle")
            assertEquals(listOf(originalName), restoreNames(callLog))
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `escalation trigger - on Windows, the first access-denied classification switches every remaining ordinary-restore attempt to the broker`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-ordinary-escalate-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            backend.start(handle)
            val winningName = handle.id

            assertNotEquals(originalName, winningName, "must never retry under the name that just hit access-denied")
            assertTrue(winningName in backend.runningSandboxNames())
            assertEquals(1, stub.calls.size, "exactly one brokered attempt, the second overall")
            assertEquals(winningName, stub.calls.single().name)
            assertEquals(listOf(originalName), restoreNames(callLog),
                "only the FIRST (denied) attempt may be a direct restore call — the winning attempt must " +
                    "never ALSO show up as a direct restore invocation")
            assertTrue(originalName in rmNames(callLog), "expected a best-effort rm of the failed original name")
            assertTrue(handle.id in backend.trackedNames(),
                "the winning name must be re-keyed into this backend's own own-run cleanup set")
            assertFalse(originalName in backend.trackedNames(),
                "the failed original name must never remain tracked once advanced past")
            assertNotNull((handle as MsbCliBackend.Handle).attached,
                "the winning attempt must still revive the workload, same as a first-try success")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `an already-exists refusal on a healthy host advances to a fresh name directly, never touching the broker even on Windows`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-ordinary-alreadyexists-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, alreadyExists)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            backend.start(handle)
            val winningName = handle.id

            assertNotEquals(originalName, winningName)
            assertTrue(stub.calls.isEmpty(),
                "an already-exists refusal alone (never access-denied) must never engage the broker: ${stub.calls}")
            assertEquals(listOf(originalName, winningName), restoreNames(callLog),
                "both attempts must be direct restore calls, under two distinct names")
            assertTrue(winningName in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `non-Windows never brokers an ordinary restore, even after the identical access-denied classification, and exhausts its budget retrying directly`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Always denied — isolates "does this ever call the broker" from "does it eventually
        // succeed via direct retry", a separate, already-covered concern.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "999999") }
        val originalName = "rz-ordinary-nonwindows-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        // A larger budget than the reboot path's own equivalent test uses (500ms there): THAT
        // test's retry loop starts from an already-warm JVM/process-spawn state (a prior ordinary
        // start() + createCheckpoint() already ran first). This test's very first call is the
        // budgeted retry loop itself, so it needs enough slack to absorb one cold process-spawn
        // round trip and still leave room for the fixed inter-attempt delay
        // (CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS, not itself injectable) to elapse at
        // least once before the budget is checked again.
        val backend = MsbCliBackend.forHost(
            fakeMsb, windowsHost = false, restoreBroker = stub.fn, checkpointRebootAlreadyExistsBudgetMs = 3_000L)
        val handle = backend.create(restoreSpec(originalName))
        try {
            val e = assertThrows(Exception::class.java) { backend.start(handle) }

            assertTrue(stub.calls.isEmpty(), "windowsHost=false must never invoke the broker, no matter what " +
                "the direct restore's own output classifies as: ${stub.calls}")
            assertTrue(e.message!!.contains("Access is denied") || e.message!!.contains("io error"),
                "the surfaced failure must still be the access-denied one: ${e.message}")
            val names = restoreNames(callLog)
            assertTrue(names.size >= 2, "must have kept retrying directly, never switching to the broker: $names")
            assertEquals(names.size, names.toSet().size, "no two attempts may share a name: $names")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `broker-infrastructure-failure falls back to a direct spawn for that same ordinary-restore attempt, without minting an extra name`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Attempt 1 (direct, pre-escalation) is denied; attempt 2's own DIRECT FALLBACK (triggered
        // by the broker's BrokerFailure below) must succeed, so the counter only needs to absorb
        // ONE denial.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-ordinary-infra-failure-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val stub = StubBroker(marker, listOf(BrokerOutcome.BrokerFailure("simulated: powershell.exe not found")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            backend.start(handle)
            val winningName = handle.id

            assertNotEquals(originalName, winningName)
            assertEquals(1, stub.calls.size, "the broker is consulted once for attempt 2, reports infra failure")
            assertEquals(winningName, stub.calls.single().name,
                "the broker was asked to launch under the SAME name the direct fallback then won under — a " +
                    "BrokerFailure must fall back for that one attempt, never mint an extra name of its own")
            assertEquals(listOf(originalName, winningName), restoreNames(callLog),
                "attempt 1's own direct call, plus attempt 2's direct FALLBACK after the broker infra failure")
            assertTrue(winningName in backend.runningSandboxNames())
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `on success under a fresh name, the winning name is what stop and remove target, and it alone is tracked`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-ordinary-rekey-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        backend.start(handle)
        val winningName = handle.id
        assertNotEquals(originalName, winningName)
        // Every container-layer consumer of this handle's own identity — exec/logs/stop/rm — reads
        // handle.id fresh at call time (Handle.id is computed straight off the mutated Handle.spec),
        // so stop()/remove() below transparently target the winner with no separate re-key step.
        assertEquals(winningName, (handle as MsbCliBackend.Handle).spec.name)
        assertTrue(winningName in backend.trackedNames())
        assertFalse(originalName in backend.trackedNames())

        backend.stop(handle)
        backend.remove(handle)

        assertFalse(winningName in backend.trackedNames(), "remove() must untrack the WINNING name")
        assertFalse(winningName in backend.runningSandboxNames())
    }

    // The retry walk tracks each fresh name it tries, and a keepAlive (reuse) sandbox must stay out
    // of own-run cleanup on that path too, the same as on start()'s own first attempt.
    @Test fun `a keepAlive restore that wins under a fresh name is not tracked for own-run cleanup`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-reuse-keepaliveretry"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, alreadyExists)
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = false)
        val handle = backend.create(restoreSpec(originalName).copy(keepAlive = true))
        try {
            backend.start(handle)

            assertNotEquals(originalName, handle.id, "the already-exists refusal must advance to a fresh name")
            assertTrue(handle.id in backend.runningSandboxNames())
            assertTrue(backend.trackedNames().isEmpty(),
                "neither the failed nor the winning name may be tracked for a keepAlive sandbox: ${backend.trackedNames()}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // A refused restore is a definite answer about this snapshot: no fresh-name walk, no broker, no
    // agent-relay or state-db retry, no readiness poll. One restore call, then the typed error.
    @Test fun `msb's refusal of missing destination bindings ends in the typed error after exactly one restore call`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        // Would keep failing the same way forever: any retry would show up as a second restore call.
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "999999") }
        val originalName = "rz-ordinary-missing-bindings-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, missingBindings)
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(0, "")))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            val e = assertThrows(CheckpointMissingCopiedFilesException::class.java) { backend.start(handle) }

            assertEquals(originalName, e.sandbox)
            assertEquals(restoreSpec(originalName).checkpointRef, e.checkpointRef)
            val msg = e.message!!
            assertTrue(originalName in msg, "message must name the sandbox: $msg")
            assertTrue(e.checkpointRef in msg, "message must name the checkpoint ref: $msg")
            assertTrue("withCopyFileToContainer" in msg, "message must name the remedy: $msg")
            assertTrue("not part of a checkpoint" in msg, "message must say why: $msg")
            assertTrue("/etc/app/b.conf" in msg, "message must carry msb's output, which lists the guest path: $msg")
            assertFalse("was removed" in msg, "a plain restore never removed anything: $msg")
            assertEquals(listOf(originalName), restoreNames(callLog), "exactly one restore call, no retry")
            assertTrue(stub.calls.isEmpty(), "the broker must never be consulted: ${stub.calls}")
            assertEquals(emptyList<String>(), rmNames(callLog), "no fresh-name walk, so no rm of a failed name")
            assertFalse(Files.readAllLines(callLog).any { it.startsWith("ls ") }, "no readiness poll after a refusal")
            assertNull((handle as MsbCliBackend.Handle).attached)
            assertEquals(originalName, handle.id, "the handle must not be renamed")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `the refusal is not retried on a non-Windows host either`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "999999") }
        val originalName = "rz-ordinary-missing-bindings-posix-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, missingBindings)
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = false)
        val handle = backend.create(restoreSpec(originalName))
        try {
            assertThrows(CheckpointMissingCopiedFilesException::class.java) { backend.start(handle) }
            assertEquals(listOf(originalName), restoreNames(callLog))
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // The brokered site: attempt 1 is access-denied (which latches the broker on, on Windows), and
    // the broker's own captured output for attempt 2 is the refusal, PowerShell-decorated.
    @Test fun `a brokered restore's refusal is classified the same way, decorated by PowerShell`() {
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val restoreFailCounter = Files.createTempFile("rz-failcnt-", "").also { Files.writeString(it, "1") }
        val originalName = "rz-ordinary-missing-bindings-broker-test"
        val fakeMsb = fakeMsbRestoreFailsThenSucceeds(marker, callLog, restoreFailCounter, accessDenied)
        val decorated = "msb.exe : error: invalid config: restore requires destination bindings for: " +
            "mount /etc/app/b.conf; provide a destination mount for each path or explicitly allow missing resources\n" +
            "At line:1 char:1\n+ & 'C:\\msb.exe' restore --name rz-x\n" +
            "+ CategoryInfo          : NotSpecified: (error: invalid ...:String) [], RemoteException\n" +
            "+ FullyQualifiedErrorId : NativeCommandError"
        val stub = StubBroker(marker, listOf(BrokerOutcome.Completed(1, decorated)))
        val backend = MsbCliBackend.forHost(fakeMsb, windowsHost = true, restoreBroker = stub.fn)
        val handle = backend.create(restoreSpec(originalName))
        try {
            val e = assertThrows(CheckpointMissingCopiedFilesException::class.java) { backend.start(handle) }

            assertEquals(1, stub.calls.size, "the refusal must not be retried through the broker: ${stub.calls}")
            assertEquals(stub.calls.single().name, e.sandbox)
            assertTrue("/etc/app/b.conf" in e.message!!, "message must carry the decorated output: ${e.message}")
            assertEquals(listOf(originalName), restoreNames(callLog),
                "only the first, access-denied attempt was a direct restore call")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }
}
