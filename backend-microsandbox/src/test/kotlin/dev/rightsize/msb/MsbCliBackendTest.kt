package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/** Pure/no-real-sandbox unit coverage for the two reaper SPI members — the rest of
 * [MsbCliBackend] needs a real msb binary and is exercised by [MsbBackendIT] instead. */
class MsbCliBackendTest {
    private val msbPath = Path.of("/nonexistent/msb")

    @Test fun `watchdogCommands is msbPath stop and msbPath rm, no network step`() {
        val backend = MsbCliBackend(msbPath)
        assertEquals(listOf("/nonexistent/msb", "stop"), backend.watchdogCommands.sandboxStop)
        assertEquals(listOf("/nonexistent/msb", "rm"), backend.watchdogCommands.sandboxRemove)
        assertEquals(emptyList<String>(), backend.watchdogCommands.networkRemove)
    }

    // removeByName shells out (via `silently`, the same best-effort helper the shutdown hook
    // and close() use) to a msb path that doesn't exist — proves it never throws to the
    // caller (a sweep must survive a single reap failure), without needing a real msb binary.
    @Test fun `removeByName never throws even when the msb binary cannot be run`() {
        val backend = MsbCliBackend(msbPath)
        assertDoesNotThrow { backend.removeByName("rz-doesnotexist-1") }
    }

    @Test fun `capabilities is hardware-isolated but does not support checkpoint`() {
        val capabilities = MsbCliBackend(msbPath).capabilities
        assertTrue(capabilities.hardwareIsolated, "each msb sandbox is its own microVM")
        assertFalse(capabilities.checkpoint)
    }

    /** A fake `msb` executable that counts its own `rm` invocations in [counterFile] and, on
     * the first one only, prints msb's real `error: database error:` framing and exits
     * non-zero — the exact transient shape [removeByName] must retry once. POSIX-only (a
     * shebang script); the PowerShell equivalent is the watchdog script's own concern,
     * exercised by the msb-windows CI job's integration test instead. */
    private fun fakeMsbRetryOnceOnDatabaseError(counterFile: Path): Path {
        val script = Files.createTempFile("rz-fake-msb", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "rm" ]; then
            |  n=${'$'}(cat "$counterFile" 2>/dev/null || echo 0)
            |  n=${'$'}((n + 1))
            |  echo "${'$'}n" > "$counterFile"
            |  if [ "${'$'}n" -eq 1 ]; then
            |    echo "error: database error: Execution Error: (code: 1) duplicate column name: kind" 1>&2
            |    exit 1
            |  fi
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `removeByName retries rm once on msb's database-error output, same classifier the boot path uses`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val counter = Files.createTempFile("rz-rm-count", "")
        val backend = MsbCliBackend(fakeMsbRetryOnceOnDatabaseError(counter))
        backend.removeByName("rz-test-1")
        assertEquals("2", Files.readString(counter).trim(), "rm must have run exactly twice: the failing " +
            "attempt plus the one retry")
    }

    @Test fun `removeByName does not retry rm on an unrelated failure`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary")
        val script = Files.createTempFile("rz-fake-msb-fail", "").also {
            Files.writeString(it, "#!/bin/sh\necho \"error: sandbox not found\" 1>&2\nexit 1\n")
            it.toFile().setExecutable(true)
        }
        val backend = MsbCliBackend(script)
        // Must not throw, and (unlike the database-error case) must not be given a special
        // retry — best-effort semantics for every other failure shape are unchanged.
        assertDoesNotThrow { backend.removeByName("rz-test-2") }
    }

    /**
     * A fake `msb` executable that implements just enough of `run`/`ls`/`stop`/`rm` to drive a
     * real [MsbCliBackend.start] end to end without a real msb binary: `run --name X ...`
     * writes `X` to [marker] and blocks (attached-mode simulation) until [marker] is deleted;
     * `ls --format json` reports `X` as `Running` for as long as [marker] exists; `stop`
     * deletes [marker] (waking the blocked `run` invocation); `rm` is a no-op success.
     */
    private fun fakeMsbLifecycle(marker: Path): Path {
        val script = Files.createTempFile("rz-fake-msb-lifecycle", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"; shift
            |case "${'$'}cmd" in
            |  run)
            |    name=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      if [ "${'$'}1" = "--name" ]; then name="${'$'}2"; shift 2; continue; fi
            |      shift
            |    done
            |    echo "${'$'}name" > "$marker"
            |    while [ -f "$marker" ]; do sleep 0.05; done
            |    exit 0
            |    ;;
            |  ls)
            |    if [ -f "$marker" ]; then
            |      n=${'$'}(cat "$marker")
            |      echo "[{\"name\":\"${'$'}n\",\"status\":\"Running\"}]"
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

    @Test fun `start tracks a normal sandbox in startedNames, so close would reap it`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbLifecycle(marker))
        val spec = ContainerSpec(name = "rz-test-normal", image = "irrelevant", runId = "run1")
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            assertTrue("rz-test-normal" in backend.trackedNames(),
                "a normal (non-keepAlive) sandbox must be tracked so the shutdown hook/close() reap it")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
        assertFalse("rz-test-normal" in backend.trackedNames(), "remove() must untrack it")
    }

    @Test fun `start does not track a keepAlive sandbox in startedNames, so close would never reap it`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbLifecycle(marker))
        val spec = ContainerSpec(name = "rz-reuse-abc123", image = "irrelevant", runId = "run1", keepAlive = true)
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            assertFalse("rz-reuse-abc123" in backend.trackedNames(),
                "a keepAlive sandbox must never enter startedNames — it must stay out of every " +
                    "own-run cleanup path (constructor shutdown hook and close())")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `findRunning returns a handle for a name Running in msb ls`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbLifecycle(marker))
        val spec = ContainerSpec(name = "rz-reuse-findme", image = "irrelevant", runId = "run1", keepAlive = true)
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            val found = backend.findRunning("rz-reuse-findme")
            assertNotNull(found, "a Running sandbox must be found")
            assertEquals("rz-reuse-findme", found!!.id)
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `findRunning returns null for a name not Running in msb ls`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbLifecycle(marker))
        assertNull(backend.findRunning("rz-reuse-nobodyhome"))
    }
}
