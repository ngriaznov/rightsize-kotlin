package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Red-proofs [MsbCliBackend]'s restore-boot supervision (`start()` on a `checkpointRef` spec —
 * the same boot path [MsbCliBackend.createCheckpoint]'s own re-boot drives; see
 * [MsbCheckpointTest] for the re-boot's own call-sequence coverage): `msb restore` is a
 * short-lived, DETACHED activation launcher, never a supervising attached child the way
 * `msb run` is (see [MsbCliBackend.awaitRestoreRunning]'s doc) — these fakes emulate exactly
 * that contract, never the old (wrong) attached-child model [MsbCliBackendTest.fakeMsbLifecycle]
 * uses for ordinary `run` boots.
 */
class MsbRestoreSupervisionTest {

    /**
     * A fake `msb` executable for the detached-restore success/timeout shapes: `restore <ref>
     * --name X ...` never blocks — it records `X` as the sandbox under restoration in [marker]
     * and exits 0 immediately, exactly like the real detached command. `ls --format json` counts
     * its own calls by grepping [callLog] (every invocation's full argv is appended there first,
     * matching [MsbCheckpointTest.fakeMsbCheckpointLifecycle]'s own convention) and reports the
     * restoring sandbox `Starting` for the first [pollsBeforeRunning] polls, `Running` from the
     * next one on — proving [MsbCliBackend] genuinely polls after a restore's exit rather than
     * assuming success from the exit code alone. Passing a huge [pollsBeforeRunning] simulates a
     * sandbox that never reaches Running (the boot-failure/timeout case). `stop`/`rm` are
     * no-op successes.
     */
    private fun fakeMsbRestorePolling(marker: Path, callLog: Path, pollsBeforeRunning: Int): Path {
        val script = Files.createTempFile("rz-fake-msb-restore-poll", "")
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
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    echo "${'$'}name" > "$marker"
            |    exit 0
            |    ;;
            |  ls)
            |    n=${'$'}(grep -c '^ls ' "$callLog")
            |    target=${'$'}(cat "$marker" 2>/dev/null || echo "")
            |    if [ -z "${'$'}target" ]; then echo "[]"; exit 0; fi
            |    if [ "${'$'}n" -gt $pollsBeforeRunning ]; then
            |      echo "[{\"name\":\"${'$'}target\",\"status\":\"Running\"}]"
            |    else
            |      echo "[{\"name\":\"${'$'}target\",\"status\":\"Starting\"}]"
            |    fi
            |    ;;
            |  stop|rm) exit 0 ;;
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    // --- (a) successful detached restore => start() succeeds, and the handle is childless ---

    @Test fun `start on a checkpointRef spec succeeds once msb ls reports Running after a fast, non-blocking restore exit`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-restore-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-restore-calllog-", "")
        // Running only from the SECOND ls poll on — proves the poll loop actually polls more
        // than once rather than trusting the first check (or the restore exit code alone).
        val backend = MsbCliBackend(fakeMsbRestorePolling(marker, callLog, pollsBeforeRunning = 1))
        val spec = ContainerSpec(
            name = "rz-restore-ok", image = "irrelevant", runId = "run1",
            checkpointRef = "/fake-store/rz-restore-ok/snap_0123456789abcdef0123456789abcdef",
        )
        val handle = backend.create(spec)
        try {
            backend.start(handle)   // must not throw, and must not hang

            assertTrue("rz-restore-ok" in backend.runningSandboxNames(),
                "the restored sandbox must be reported Running once start() returns")
            assertNull((handle as MsbCliBackend.Handle).attached,
                "a restore boot spawns no supervising child — Handle.attached must stay null " +
                    "(requirement 3: childless handle)")
            val lsCalls = Files.readAllLines(callLog).count { it.startsWith("ls ") }
            assertTrue(lsCalls >= 2, "the poll loop must have polled `msb ls` more than once: saw $lsCalls call(s)")

            // stop()/remove() must both be safe no-ops on the child front (they still shell out
            // to the CLI by name, unaffected) even though there was never a child to reap.
            assertDoesNotThrow { backend.stop(handle) }
            assertNull(handle.attached, "attached must still be null after stop()")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    // --- (b) fake restore exits nonzero with output => classified failure ---

    @Test fun `start on a checkpointRef spec fails fast and surfaces msb's own output when the restore process itself exits nonzero`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val callLog = Files.createTempFile("rz-restore-fail-calllog-", "")
        val script = Files.createTempFile("rz-fake-msb-restore-fail", "").also {
            Files.writeString(
                it,
                """
                |#!/bin/sh
                |echo "${'$'}*" >> "$callLog"
                |if [ "${'$'}1" = "restore" ]; then
                |  echo "error: restore failed: destination disk full (fake)" 1>&2
                |  exit 3
                |fi
                |exit 0
                |""".trimMargin(),
            )
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val spec = ContainerSpec(
            name = "rz-restore-badexit", image = "irrelevant", runId = "run1",
            checkpointRef = "/fake-store/rz-restore-badexit/snap_0123456789abcdef0123456789abcdef",
        )
        val handle = backend.create(spec)

        val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }

        assertTrue(e.message!!.contains("destination disk full"), "message must carry msb's own output: ${e.message}")
        assertTrue(e.message!!.contains("rz-restore-badexit"), "message must name the sandbox: ${e.message}")
        assertNull((handle as MsbCliBackend.Handle).attached, "a failed restore must never populate Handle.attached")
        assertFalse(Files.readAllLines(callLog).any { it.startsWith("ls ") },
            "a nonzero restore exit must fail before ever polling `msb ls` — there is nothing to poll for")
    }

    // --- (c) fake restore exits 0 but ls never shows Running within the budget => boot failure, not a hang ---

    @Test fun `start on a checkpointRef spec fails within the shrunk readiness budget, not a hang, when msb ls never reports Running`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-restore-neverup-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-restore-neverup-calllog-", "")
        // Effectively never Running: Int.MAX_VALUE polls would be needed. Paired with a tiny
        // restoreReadinessBudgetMs (the test-only seam — see MsbCliBackend's class doc) so this
        // test resolves in well under a second instead of the real ten-minute production budget.
        val script = fakeMsbRestorePolling(marker, callLog, pollsBeforeRunning = Int.MAX_VALUE)
        val backend = MsbCliBackend.forHost(script, windowsHost = false, restoreReadinessBudgetMs = 900L)
        val spec = ContainerSpec(
            name = "rz-restore-neverup", image = "irrelevant", runId = "run1",
            checkpointRef = "/fake-store/rz-restore-neverup/snap_0123456789abcdef0123456789abcdef",
        )
        val handle = backend.create(spec)

        val started = System.nanoTime()
        val e = assertThrows(IllegalStateException::class.java) { backend.start(handle) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(e.message!!.contains("did not reach Running"), "unexpected message: ${e.message}")
        assertTrue(e.message!!.contains("rz-restore-neverup"), "message must name the sandbox: ${e.message}")
        assertTrue(elapsedMs < 10_000, "must fail within the shrunk budget, not hang: took ${elapsedMs}ms")
        assertNull((handle as MsbCliBackend.Handle).attached,
            "a timed-out restore boot must never populate Handle.attached")
    }

    // --- requirement 4: boot-transient classification (image-cache corruption) applies to a ---
    // --- restore's output the same way it does for run, healed and retried transparently.   ---

    @Test fun `start on a checkpointRef spec heals and retries once on msb's image-cache-corruption signature, same as an ordinary run boot`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-restore-heal-marker-", "").also { Files.deleteIfExists(it) }
        val counter = Files.createTempFile("rz-restore-heal-counter-", "")
        val script = Files.createTempFile("rz-fake-msb-restore-heal", "").also {
            Files.writeString(
                it,
                """
                |#!/bin/sh
                |cmd="${'$'}1"; shift
                |case "${'$'}cmd" in
                |  restore)
                |    n=${'$'}(cat "$counter" 2>/dev/null || echo 0)
                |    n=${'$'}((n + 1))
                |    echo "${'$'}n" > "$counter"
                |    name=""
                |    while [ "${'$'}#" -gt 0 ]; do
                |      case "${'$'}1" in
                |        --name) name="${'$'}2"; shift 2 ;;
                |        *) shift ;;
                |      esac
                |    done
                |    if [ "${'$'}n" -eq 1 ]; then
                |      echo "error: image error: cache error at /fake/.microsandbox/cache/layers/sha256_dead.tar.gz: No such file or directory (os error 2)" 1>&2
                |      exit 1
                |    fi
                |    echo "${'$'}name" > "$marker"
                |    exit 0
                |    ;;
                |  ls)
                |    target=${'$'}(cat "$marker" 2>/dev/null || echo "")
                |    if [ -z "${'$'}target" ]; then echo "[]"; exit 0; fi
                |    echo "[{\"name\":\"${'$'}target\",\"status\":\"Running\"}]"
                |    ;;
                |  image|stop|rm) exit 0 ;;
                |  *) exit 0 ;;
                |esac
                |""".trimMargin(),
            )
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        val spec = ContainerSpec(
            name = "rz-restore-heal", image = "irrelevant", runId = "run1",
            checkpointRef = "/fake-store/rz-restore-heal/snap_0123456789abcdef0123456789abcdef",
        )
        val handle = backend.create(spec)
        try {
            backend.start(handle)   // must not throw: the first failure is healed and retried once

            assertTrue("rz-restore-heal" in backend.runningSandboxNames())
            assertEquals("2", Files.readString(counter).trim(),
                "restore must have run exactly twice: the classified failure plus the one heal-and-retry")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }
}
