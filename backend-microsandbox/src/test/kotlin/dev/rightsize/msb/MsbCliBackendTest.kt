package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.NetworkLink
import dev.rightsize.core.PortProtocol
import dev.rightsize.core.UnsupportedByBackendException
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

    @Test fun `capabilities is hardware-isolated and supports checkpoint via disk snapshot, which restarts the workload`() {
        val capabilities = MsbCliBackend(msbPath).capabilities
        assertTrue(capabilities.hardwareIsolated, "each msb sandbox is its own microVM")
        assertTrue(capabilities.checkpoint, "msb supports checkpoint via disk snapshot")
        assertTrue(capabilities.checkpointRestartsWorkload, "the stop/snapshot/start cycle restarts the workload")
    }

    // --- UDP network links: installNetworkLinks' pure pre-flight checks (duplicate guest ports,
    // now checked per (protocol, guestPort) rather than guestPort alone). Runs entirely against
    // the nonexistent msbPath: these checks are pure and throw before any process is spawned. ---

    @Test fun `installNetworkLinks accepts a tcp and a udp link sharing one guest port, only rejects two of the same protocol`() {
        val backend = MsbCliBackend(msbPath)
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-dns-1", image = "alpine:3.19", runId = "abc"))
        val dnsBoth = listOf(NetworkLink("sibling", 53, 40000), NetworkLink("sibling", 53, 40001, PortProtocol.UDP))
        // TCP 53 + UDP 53: accepted by the duplicate-port guard itself — it must fall through to
        // the next real step (an nc/timeout probe against the nonexistent msbPath), never the
        // "two siblings exposing the same guest port" error.
        val e = assertThrows(Exception::class.java) { backend.installNetworkLinks(handle, dnsBoth) }
        assertFalse(e.message?.contains("two siblings exposing") == true,
            "a tcp+udp pair on one guest port must never trip the duplicate-guest-port guard: ${e.message}")

        val udpTwice = listOf(NetworkLink("a", 53, 40000, PortProtocol.UDP), NetworkLink("b", 53, 40001, PortProtocol.UDP))
        val dup = assertThrows(UnsupportedByBackendException::class.java) {
            backend.installNetworkLinks(handle, udpTwice)
        }
        assertTrue(dup.message!!.contains("53"), "should name the duplicated guest port: ${dup.message}")
    }

    @Test fun `installNetworkLinks throws UnsupportedByBackendException on a udp capability probe failure, never launching the forwarder`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-udp-probe-fail-", ".log")
        val backend = MsbCliBackend(fakeMsbUdpProbeFails(marker))
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-udp-4", image = "alpine:3.19", runId = "abc"))
        val links = listOf(NetworkLink("udp-server", 9153, 41234, PortProtocol.UDP))
        val e = assertThrows(UnsupportedByBackendException::class.java) { backend.installNetworkLinks(handle, links) }
        assertTrue(e.message!!.contains("UDP network links"), "should name the unsupported feature: ${e.message}")
        assertTrue(e.message!!.contains("alpine:3.19"), "should name the consumer image: ${e.message}")
        assertTrue(e.message!!.contains("docker"), "the remedy should point at the docker backend: ${e.message}")
        val log = Files.readString(marker)
        assertFalse(log.contains("rz-udp-link"),
            "a failed capability probe must short-circuit before any script-write/launch exec: $log")
    }

    @Test fun `installUdpForwarder writes the exact forwarder script, launches it detached, then polls readiness`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-udp-install-", ".log")
        val backend = MsbCliBackend(fakeMsbUdpInstall(marker))
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-udp-2", image = "alpine:3.19", runId = "abc"))
        val links = listOf(NetworkLink("udp-server", 9153, 41234, PortProtocol.UDP))
        backend.installNetworkLinks(handle, links)
        val log = Files.readString(marker)
        assertTrue(log.contains(UDP_FORWARDER_SCRIPT), "the exact forwarder script must reach the guest: $log")
        assertTrue(log.contains("nohup sh /tmp/rz-udp-link-9153.sh 9153 41234 >/tmp/rz-udp-link-9153.log 2>&1 &"),
            "the launch command must name the guest/target ports and log path: $log")
    }

    // A `"` anywhere in an exec argument reaches msb.exe mangled on Windows (the JDK's default
    // ProcessBuilder command-line building quotes an argument without escaping embedded quotes) —
    // see UDP_FORWARDER_SCRIPT's own doc comment. Covers the constant itself plus every exec
    // payload the UDP link install path produces on the happy path: capability probe, hosts
    // alias, script write (the heredoc carries the script verbatim), launch, readiness probe. The
    // readiness-timeout test below covers the remaining one, the log tail.
    @Test fun `UDP_FORWARDER_SCRIPT and every exec argument the udp install path produces are free of double quotes`() {
        assertFalse(UDP_FORWARDER_SCRIPT.contains('"'),
            "the forwarder script is exec'd verbatim: $UDP_FORWARDER_SCRIPT")
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-udp-quote-audit-", ".log")
        val backend = MsbCliBackend(fakeMsbUdpInstall(marker))
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-udp-quote", image = "alpine:3.19", runId = "abc"))
        val links = listOf(NetworkLink("udp-server", 9153, 41234, PortProtocol.UDP))
        backend.installNetworkLinks(handle, links)
        val payloads = Files.readAllLines(marker)
        assertTrue(payloads.size >= 4, "expected at least probe/alias/write/launch/readiness payloads: $payloads")
        payloads.forEachIndexed { i, payload ->
            assertFalse(payload.contains('"'), "exec payload #$i must contain no double quote: $payload")
        }
    }

    // Real UDP_FORWARDER_READINESS_BUDGET_MS (5s) elapses here — there is no test seam for it
    // (unlike restoreReadinessBudgetMs/checkpointRebootAlreadyExistsBudgetMs), so this one test
    // pays the real budget rather than growing the constructor for a single caller.
    @Test fun `installUdpForwarder throws a descriptive error, tailing the forwarder log, on a readiness timeout`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-udp-timeout-", ".log")
        val backend = MsbCliBackend(fakeMsbUdpNeverReady(marker))
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-udp-3", image = "alpine:3.19", runId = "abc"))
        val links = listOf(NetworkLink("udp-server", 9153, 41234, PortProtocol.UDP))
        val e = assertThrows(Exception::class.java) { backend.installNetworkLinks(handle, links) }
        assertTrue(e.message!!.contains("9153"), "should name the guest port that never bound: ${e.message}")
        assertTrue(e.message!!.contains("boom"), "should tail the forwarder's own log: ${e.message}")
        val payloads = Files.readAllLines(marker)
        assertTrue(payloads.any { it.startsWith("tail") }, "must have exec'd the log-tail payload: $payloads")
        payloads.forEachIndexed { i, payload ->
            assertFalse(payload.contains('"'),
                "exec payload #$i, including the timeout's own log tail, must contain no double quote: $payload")
        }
    }

    @Test fun `installNetworkLinks probes udp capability only when a udp link is present, tcp-only is unaffected`() {
        val backend = MsbCliBackend(msbPath)
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-tcp-1", image = "alpine:3.19", runId = "abc"))
        val links = listOf(NetworkLink("sibling", 8080, 41000))   // protocol defaults to TCP
        // Reaches requireNcAvailable next, which shells out to the nonexistent msbPath and fails
        // there instead — proving no UDP-specific probe/message fires for an all-TCP link list.
        val e = assertThrows(Exception::class.java) { backend.installNetworkLinks(handle, links) }
        assertFalse(e.message?.contains("UDP", ignoreCase = true) == true,
            "a tcp-only link list must never trip the UDP capability probe: ${e.message}")
    }

    @Test fun `a mixed tcp+udp link list gets an ExecTunnel for the tcp link and a forwarder install for the udp one`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-udp-mixed-", ".log")
        val backend = MsbCliBackend(fakeMsbUdpInstall(marker))
        val handle = MsbCliBackend.Handle(ContainerSpec(name = "rz-mixed-1", image = "alpine:3.19", runId = "abc"))
        val links = listOf(
            NetworkLink("svc", 8080, 41000),
            NetworkLink("svc-udp", 9153, 41234, PortProtocol.UDP),
        )
        try {
            backend.installNetworkLinks(handle, links)
            assertEquals(1, handle.resources.count { it is ExecTunnel },
                "the tcp link must get exactly one ExecTunnel, the existing path untouched")
            val log = Files.readString(marker)
            assertTrue(log.contains("rz-udp-link-9153.sh"), "the udp link must still get its forwarder installed: $log")
        } finally {
            handle.resources.forEach { it.close() }
        }
    }

    /** A fake `msb` that answers `exec <name> -- sh -c <payload>` well enough to drive
     * [MsbCliBackend.installUdpForwarder] end to end: the capability probe and the hosts-alias
     * write both succeed unconditionally; the script write and the detached launch each succeed
     * and append their own exact `-c` payload to [marker] (so the test can assert on exactly what
     * reached the guest); the readiness poll (its payload alone starts with `awk` — every OTHER
     * payload here, including the write's own heredoc, merely CONTAINS "awk" as part of the
     * forwarder script text it carries, so matching is on the payload's PREFIX, never a bare
     * substring) succeeds on its first check. */
    private fun fakeMsbUdpInstall(marker: Path): Path {
        val script = Files.createTempFile("rz-fake-msb-udp", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "exec" ]; then
            |  shift 3   # drop 'exec', the sandbox name, and '--'; ${'$'}3 is now the sh -c payload
            |  # printf, never echo: this /bin/sh's echo interprets backslash escapes (e.g. the
            |  # forwarder script's own literal '\0') by default, corrupting the logged payload.
            |  printf '%s\n' "${'$'}3" >> "$marker"
            |  case "${'$'}3" in
            |    awk*) exit 0 ;;   # readiness probe: bound on the first check
            |    *) exit 0 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    /** A fake `msb` whose UDP capability probe (the payload starting with `command`, unique to
     * [MsbCliBackend.requireUdpForwarderCapable] among this test's exec payloads — the alias/
     * script/launch/readiness payloads start with `echo`/`cat`/`nohup`/`awk`) always fails; every
     * other exec would succeed but is logged to [marker] first, so the test can prove none of
     * them ever ran. */
    private fun fakeMsbUdpProbeFails(marker: Path): Path {
        val script = Files.createTempFile("rz-fake-msb-udp-probe-fail", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "exec" ]; then
            |  shift 3
            |  printf '%s\n' "${'$'}3" >> "$marker"
            |  case "${'$'}3" in
            |    command*) exit 1 ;;
            |    *) exit 0 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    /** Same shape as [fakeMsbUdpInstall], except the readiness probe (its payload starts with
     * `awk`) always fails, and the log-tail payload (starts with `tail`) echoes "boom" — driving
     * [MsbCliBackend.awaitUdpForwarderReady]'s own timeout path. Every payload is logged to
     * [marker] first, same as [fakeMsbUdpInstall], so the timeout test can audit the log-tail
     * exec argument alongside the rest. No test seam shrinks the real readiness budget (see that
     * test's own doc), so this one runs it out for real. */
    private fun fakeMsbUdpNeverReady(marker: Path): Path {
        val script = Files.createTempFile("rz-fake-msb-udp-stuck", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |if [ "${'$'}1" = "exec" ]; then
            |  shift 3
            |  printf '%s\n' "${'$'}3" >> "$marker"
            |  case "${'$'}3" in
            |    awk*) exit 1 ;;
            |    tail*) echo "boom"; exit 0 ;;
            |    *) exit 0 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
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
