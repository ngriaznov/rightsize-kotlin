package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Red-proofs [MsbCliBackend.createCheckpoint]'s post-`rm` name-release wait
 * ([MsbCliBackend.awaitNameReleased]) and its already-exists reboot retry
 * ([MsbCliBackend.rebootRetryingNameCollision]) — both driven entirely against a local fake `msb`
 * script, same pattern as [MsbCheckpointTest]/[MsbRestoreSupervisionTest]. POSIX-only; the
 * msb-windows CI lane's own integration test is what exercises this against the real binary on
 * Windows, where the lag this covers actually occurs.
 */
class MsbCheckpointNameReleaseTest {

    /**
     * Extends [MsbCheckpointTest]'s own `fakeMsbCheckpointLifecycle` shape with two more knobs, both
     * consulted only in the gap between `stop` clearing [marker] and `restore` rewriting it — the
     * exact window [MsbCliBackend.awaitNameReleased] polls:
     *
     * - [rmLingerCounter], when present, holds a decimal count of how many MORE `ls` calls should
     *   still report [sandboxName] lingering (status `Stopped`, msb's own post-teardown status)
     *   before finally reporting it absent — decremented on every such call. Absent (or already at
     *   0) means the name is free on the very first poll, the unix-always shape.
     * - [restoreFailCounter], when present, holds a decimal count of how many MORE `restore`
     *   invocations should fail with msb's own already-exists wording — decremented on every such
     *   call — before finally succeeding. Absent (or already at 0) means `restore` succeeds on the
     *   very first invocation, same as [MsbCheckpointTest]'s own fake.
     * - [lsGlitchCounter], when present, holds a decimal count of how many MORE `ls` calls should
     *   fail outright — nonzero exit, unparseable stderr chatter instead of JSON on stdout, the
     *   shape of a transient daemon hiccup while teardown is still in flight — before falling
     *   through to the ordinary marker/[rmLingerCounter] handling above. Consulted first, so a
     *   glitch can be layered on top of either a lingering or an already-free name.
     *
     * `run`/`stop`/`exec`/`snapshot create` behave exactly as [MsbCheckpointTest]'s own fake.
     */
    private fun fakeMsbNameReleaseLifecycle(
        marker: Path,
        callLog: Path,
        sandboxName: String,
        rmLingerCounter: Path,
        restoreFailCounter: Path,
        lsGlitchCounter: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-namerelease", "")
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
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    echo "${'$'}name" > "$marker"
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  restore)
            |    snapshot="${'$'}1"; shift
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    if [ -f "$restoreFailCounter" ]; then
            |      rcnt=${'$'}(cat "$restoreFailCounter")
            |      if [ "${'$'}rcnt" -gt 0 ]; then
            |        rcnt=${'$'}((rcnt - 1))
            |        echo "${'$'}rcnt" > "$restoreFailCounter"
            |        echo "error: sandbox already exists: sandbox '${'$'}name' already exists; remove it, start the stopped sandbox, or recreate with .replace()" 1>&2
            |        exit 1
            |      fi
            |    fi
            |    echo "${'$'}name" > "$marker"
            |    exit 0
            |    ;;
            |  exec)
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --) shift; break ;;
            |        *) shift ;;
            |      esac
            |    done
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  ls)
            |    if [ -f "$lsGlitchCounter" ]; then
            |      gcnt=${'$'}(cat "$lsGlitchCounter")
            |      if [ "${'$'}gcnt" -gt 0 ]; then
            |        gcnt=${'$'}((gcnt - 1))
            |        echo "${'$'}gcnt" > "$lsGlitchCounter"
            |        echo "msb: daemon hiccup, try again" 1>&2
            |        exit 1
            |      fi
            |    fi
            |    if [ -f "$marker" ]; then
            |      n=${'$'}(cat "$marker")
            |      echo "[{\"name\":\"${'$'}n\",\"status\":\"Running\"}]"
            |    elif [ -f "$rmLingerCounter" ]; then
            |      cnt=${'$'}(cat "$rmLingerCounter")
            |      if [ "${'$'}cnt" -gt 0 ]; then
            |        cnt=${'$'}((cnt - 1))
            |        echo "${'$'}cnt" > "$rmLingerCounter"
            |        echo "[{\"name\":\"$sandboxName\",\"status\":\"Stopped\"}]"
            |      else
            |        echo "[]"
            |      fi
            |    else
            |      echo "[]"
            |    fi
            |    ;;
            |  stop) rm -f "$marker"; exit 0 ;;
            |  rm) exit 0 ;;
            |  snapshot)
            |    if [ "${'$'}1" = "create" ]; then
            |      shift 2   # drop "create" "--from-sandbox"
            |      sandbox="${'$'}1"; shift
            |      shift     # drop <name>
            |      destdir="/fake-msb-store"
            |      while [ "${'$'}#" -gt 0 ]; do
            |        case "${'$'}1" in
            |          --dest-dir) destdir="${'$'}2"; shift 2 ;;
            |          *) shift ;;
            |        esac
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

    private fun unsetFlag(prefix: String): Path = Files.createTempFile(prefix, "").also { Files.deleteIfExists(it) }

    /** The call-log lines strictly between the (single) `rm <name>` line and the (single) `restore
     * ...` line — i.e. exactly the calls [MsbCliBackend.awaitNameReleased]'s own poll loop issued. */
    private fun callsBetweenRmAndRestore(callLog: Path, name: String): List<String> {
        val lines = Files.readAllLines(callLog)
        val rmIndex = lines.indexOf("rm $name")
        val restoreIndex = lines.indexOfFirst { it.startsWith("restore ") }
        assertTrue(rmIndex >= 0, "no 'rm $name' call found in log: $lines")
        assertTrue(restoreIndex > rmIndex, "no 'restore ...' call found after rm in log: $lines")
        return lines.subList(rmIndex + 1, restoreIndex)
    }

    @Test fun `createCheckpoint proceeds to restore once msb ls reports the name absent, after polling through it lingering twice`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-linger-test"
        // Left UNSET (nonexistent, not merely empty — `-f` in the fake's `ls` case only checks
        // existence) until after start() below: start()'s own readiness poll races the `run`
        // child's marker write, and a counter file already present then could otherwise absorb one
        // of its decrements before the checkpoint cycle this test is actually pinning down ever
        // begins — see unsetFlag's own convention, used the same way elsewhere in this suite.
        val rmLingerCounter = unsetFlag("rz-linger-")
        val restoreFailCounter = unsetFlag("rz-restorefail-")
        val lsGlitchCounter = unsetFlag("rz-lsglitch-")
        val backend = MsbCliBackend(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter))
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")
            Files.writeString(rmLingerCounter, "2")   // armed only now — see the field's own comment above

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id   // rewritten in place by the reboot — never sandboxName

            assertTrue(freshName in backend.runningSandboxNames(),
                "the sandbox must be Running again, under the fresh name, once createCheckpoint returns")
            assertTrue(effectiveRef.endsWith("snap_0123456789abcdef0123456789abcdef"))

            val between = callsBetweenRmAndRestore(callLog, sandboxName)
            val lsCalls = between.count { it == "ls --format json" }
            assertEquals(3, lsCalls,
                "the wait must poll `ls` exactly 3 times: lingering twice (still listed), then once " +
                    "more confirming absence, before ever calling restore: $between")
            assertEquals(lsCalls, between.size, "no command besides the `ls` poll may run in this gap: $between")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint fails with a clear typed error naming the stuck sandbox, never calling restore, when the name never frees`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-stuck-test"
        // Left UNSET until after start() below — see the identically-named field's comment in the
        // lingering-twice test above for why (start()'s own readiness poll races the marker write).
        val rmLingerCounter = unsetFlag("rz-linger-stuck-")
        val restoreFailCounter = unsetFlag("rz-restorefail-")
        val lsGlitchCounter = unsetFlag("rz-lsglitch-")
        val backend = MsbCliBackend(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter))
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")
            // Armed only now: effectively never frees within the budget — far more decrements than
            // the budget could ever poll through.
            Files.writeString(rmLingerCounter, "999999")

            val started = System.nanoTime()
            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertTrue(e.message!!.contains(sandboxName), "message must name the stuck sandbox: ${e.message}")
            assertTrue(e.message!!.contains("msb ls"), "message must explain the signal it's acting on: ${e.message}")
            assertFalse(e.message!!.contains("re-booting sandbox"),
                "the name-release failure must surface directly, never wrapped behind the reboot-failure " +
                    "message (which would mean the restore's own already-exists error, not this wait, " +
                    "became the surfacing message): ${e.message}")
            assertTrue(elapsedMs < 10_000, "must fail within the bounded budget, not hang: took ${elapsedMs}ms")

            assertFalse(Files.readAllLines(callLog).any { it.startsWith("restore ") },
                "restore must never be invoked when the name never frees: ${Files.readAllLines(callLog)}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint retries the reboot through 5 already-exists failures and succeeds on the 6th attempt`() {
        // N=5 is deliberately more than the pre-fix budget (3 attempts total, ~900ms of retrying)
        // could ever survive — this red-proofs that the reboot retry now runs on a wall-clock
        // BUDGET (production default ~30s at CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS
        // intervals) rather than the old fixed attempt count, so it outlives a collision that
        // clears only after several retries. No budget/delay override needed here: the default
        // 30s budget comfortably covers the ~10s these 5 retries take at the production 2s
        // interval.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-alreadyexists-test"
        val rmLingerCounter = unsetFlag("rz-linger-none-")   // name frees immediately — isolates this retry
        val restoreFailCounter = Files.createTempFile("rz-restorefail-", "")
            .also { Files.writeString(it, "5") }   // next 5 restore invocations fail, the 6th succeeds
        val lsGlitchCounter = unsetFlag("rz-lsglitch-")
        val backend = MsbCliBackend(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter))
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id   // rewritten in place by the reboot — never sandboxName

            assertTrue(freshName in backend.runningSandboxNames(),
                "the sandbox must be Running again, under the fresh name, once createCheckpoint returns")
            assertTrue(effectiveRef.endsWith("snap_0123456789abcdef0123456789abcdef"))

            val restoreCalls = Files.readAllLines(callLog).filter { it.startsWith("restore ") }
            assertEquals(6, restoreCalls.size,
                "restore must have run exactly 6 times: 5 already-exists failures plus the succeeding retry")
            assertTrue(restoreCalls.all { "--name $freshName" in it },
                "every retry must keep targeting the SAME fresh name: $restoreCalls")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint retries once on a single already-exists refusal against the fresh name, then succeeds`() {
        // Red-proof (c): the already-exists retry machinery still fires against the FRESH name
        // (not the original) — a single forced refusal, the minimal case, distinct from the
        // 5-failures test above which exists to pin the wall-clock-budget shape itself.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-alreadyexists-once-test"
        val rmLingerCounter = unsetFlag("rz-linger-once-")   // name frees immediately — isolates this retry
        val restoreFailCounter = Files.createTempFile("rz-restorefail-once-", "")
            .also { Files.writeString(it, "1") }   // the next restore invocation fails, the 2nd succeeds
        val lsGlitchCounter = unsetFlag("rz-lsglitch-once-")
        val backend = MsbCliBackend(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter))
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id

            assertNotEquals(sandboxName, freshName)
            assertTrue(freshName in backend.runningSandboxNames(),
                "the sandbox must be Running again, under the fresh name, once createCheckpoint returns")
            val restoreCalls = Files.readAllLines(callLog).filter { it.startsWith("restore ") }
            assertEquals(2, restoreCalls.size,
                "restore must have run exactly twice: the fresh name's own already-exists refusal, " +
                    "plus the succeeding retry")
            assertTrue(restoreCalls.all { "--name $freshName" in it },
                "every retry must keep targeting the SAME fresh name, never falling back to the original: $restoreCalls")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint fails with a clear typed error naming the sandbox and preserved checkpoint once the already-exists retry budget is exhausted`() {
        // The budget is shrunk via MsbCliBackend.forHost's own test-only seam (same mechanism
        // MsbRestoreSupervisionTest's restoreReadinessBudgetMs override uses) so this resolves in
        // seconds instead of the real ~30s production budget. There is no seam for
        // CHECKPOINT_REBOOT_ALREADY_EXISTS_RETRY_DELAY_MS itself, so the retry loop still sleeps
        // the real 2s between attempts; exactly one retry (2 restore invocations) is what that
        // guarantees, PROVIDED the shrunk budget clears the deadline check right after the first
        // attempt's own failure. That check races the first attempt's actual subprocess round
        // trip (JVM ProcessBuilder fork/exec of the fake script, itself forking a nested `cat`
        // subshell, plus disk I/O) — a budget merely "small" isn't enough margin for that to hold
        // reliably under CI load, so this picks 500ms: comfortably above one such round trip (a
        // few hundred ms is the sibling convention's order of magnitude — see
        // MsbRestoreSupervisionTest's own restoreReadinessBudgetMs=900ms vs its 300ms poll
        // interval, a similar 3x-ish margin) while still comfortably under the 2s retry delay, so
        // the second attempt (after that mandatory 2s sleep) is guaranteed to land past the
        // deadline and stop the loop at exactly one retry either way.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-alwaysexists-test"
        val rmLingerCounter = unsetFlag("rz-linger-none-")   // name frees immediately — isolates this retry
        // Armed only after start() below (see the identically-named field's comment in the
        // lingering-twice test): effectively never clears within the shrunk budget.
        val restoreFailCounter = unsetFlag("rz-restorefail-always-")
        val lsGlitchCounter = unsetFlag("rz-lsglitch-")
        val backend = MsbCliBackend.forHost(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter),
            windowsHost = false,
            checkpointRebootAlreadyExistsBudgetMs = 500L)
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")
            Files.writeString(restoreFailCounter, "999999")

            val started = System.nanoTime()
            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            // handle.spec/id is rewritten to the fresh name BEFORE the reboot is attempted (see
            // createCheckpoint's own doc), so this still reflects the name actually retried against,
            // never sandboxName (the original, never passed to restore at all).
            val freshName = handle.id

            assertTrue(e.message!!.contains(freshName), "message must name the sandbox actually retried against: ${e.message}")
            assertTrue(e.message!!.contains("already exists"), "message must carry msb's own refusal: ${e.message}")
            assertTrue(e.message!!.contains("restorable via GenericContainer.fromCheckpoint"),
                "message must point at the preserved checkpoint: ${e.message}")
            assertTrue(elapsedMs < 10_000, "must fail within the shrunk budget, not hang: took ${elapsedMs}ms")

            val restoreCalls = Files.readAllLines(callLog).count { it.startsWith("restore ") }
            assertEquals(2, restoreCalls,
                "restore must have run exactly twice against the shrunk budget: the initial attempt plus one retry")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint polls through a failed msb ls probe instead of reading it as the name being absent`() {
        // Red-proof for the review finding: invoke() never throws on a nonzero exit code, and
        // MsbLsJson.statusOf's runCatching turns any unparseable stdout into null — indistinguishable,
        // pre-fix, from a genuinely empty listing. A transient `msb ls` glitch on the very first poll
        // (nonzero exit, garbage stderr instead of JSON) must NOT be read as "name confirmed absent":
        // the wait must keep polling through it and only proceed once `ls` itself succeeds and
        // parses cleanly.
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val sandboxName = "rz-ckpt-lsglitch-test"
        // The name is otherwise free immediately (no lingering) — isolates the glitch itself as
        // the only reason a naive fix would proceed early.
        val rmLingerCounter = unsetFlag("rz-linger-lsglitch-")
        val restoreFailCounter = unsetFlag("rz-restorefail-lsglitch-")
        val lsGlitchCounter = unsetFlag("rz-lsglitch-")
        val backend = MsbCliBackend(
            fakeMsbNameReleaseLifecycle(
                marker, callLog, sandboxName, rmLingerCounter, restoreFailCounter, lsGlitchCounter))
        val spec = ContainerSpec(name = sandboxName, image = "irrelevant", runId = "run1", command = listOf("serve"))
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")
            Files.writeString(lsGlitchCounter, "2")   // armed only now — the first 2 `ls` calls fail outright

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            val freshName = handle.id   // rewritten in place by the reboot — never sandboxName

            assertTrue(freshName in backend.runningSandboxNames(),
                "the sandbox must be Running again, under the fresh name, once createCheckpoint returns")
            assertTrue(effectiveRef.endsWith("snap_0123456789abcdef0123456789abcdef"))

            val between = callsBetweenRmAndRestore(callLog, sandboxName)
            val lsCalls = between.count { it == "ls --format json" }
            assertEquals(3, lsCalls,
                "the wait must poll `ls` exactly 3 times: 2 failed probes it must not read as absent, " +
                    "then one clean success confirming absence, before ever calling restore: $between")
            assertEquals(lsCalls, between.size, "no command besides the `ls` poll may run in this gap: $between")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }
}
