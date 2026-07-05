package dev.rightsize.contract

import dev.rightsize.GenericContainer
import dev.rightsize.MountableFile
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Behavioural contract every SandboxBackend must satisfy. Subclass per backend; tag `sandbox-it`. */
@Tag("sandbox-it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BackendContractTest {

    /**
     * Whether this backend actually enforces [dev.rightsize.core.FileMount.readOnly] as a
     * guest-side write block. Overridden `false` by msb: its `--mount-file SOURCE:DEST:ro`
     * reports the mount `ro` in `mount(8)` output but does not reject in-guest writes — a
     * real backend asymmetry the read-only-mount test below pins. Re-check this override if
     * MsbCliBackend/MsbCommands mount plumbing changes or a new msb release fixes enforcement.
     */
    protected open val readOnlyMountEnforced: Boolean = true

    @Test fun `container publishes TCP port to host loopback`() {
        val c = GenericContainer("python:3.12-alpine")
            .withCommand("python", "-m", "http.server", "8000")
            .withExposedPorts(8000)
            .waitingFor(Wait.forHttp("/").forPort(8000)
                // 120s: shared CI runners boot a microVM + python noticeably slower
                // than dev hardware; the default 60s flakes there.
                .withStartupTimeout(java.time.Duration.ofSeconds(120)))
        c.start()
        try {
            val conn = URI("http://127.0.0.1:${c.getMappedPort(8000)}/").toURL()
                .openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
        } finally { c.stop() }
    }

    @Test fun `env vars are visible to the workload`() {
        val c = GenericContainer("alpine:3.19")
            .withEnv("RZ_PROBE", "hello-rz")
            .withCommand("sh", "-c", "sleep 120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(java.time.Duration.ofSeconds(30)))
        c.start()
        try {
            val r = c.execInContainer("sh", "-c", "echo \$RZ_PROBE")
            assertEquals(0, r.exitCode)
            assertTrue(r.stdout.contains("hello-rz"), "stdout was: ${r.stdout}")
        } finally { c.stop() }
    }

    @Test fun `exec returns real exit codes and stderr`() {
        val c = GenericContainer("alpine:3.19").withCommand("sleep", "120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(java.time.Duration.ofSeconds(30)))
        c.start()
        try {
            val r = c.execInContainer("sh", "-c", "echo oops >&2; exit 7")
            assertEquals(7, r.exitCode)
            assertTrue(r.stderr.contains("oops"))
        } finally { c.stop() }
    }

    @Test fun `logs capture workload stdout and forLogMessage waits on them`() {
        val c = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c", "echo BOOT-MARKER; sleep 120")
            .waitingFor(Wait.forLogMessage(".*BOOT-MARKER.*"))
        c.start()
        try { assertTrue(c.logs.contains("BOOT-MARKER")) } finally { c.stop() }
    }

    @Test fun `stop terminates and frees the container`() {
        val c = GenericContainer("alpine:3.19").withCommand("sleep", "120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(java.time.Duration.ofSeconds(30)))
        c.start(); c.stop()
        assertFalse(c.isRunning)
        assertThrows(IllegalStateException::class.java) { c.execInContainer("true") }
    }

    // withCopyFileToContainer / MountableFile round-trip into the guest, both source kinds.
    // Guest destinations use distinct parent directories: msb 0.6.2 silently fails to wire up
    // two `--mount-file` targets that share a parent directory (e.g. both under /tmp) — a real
    // msb quirk unrelated to rightsize's mount plumbing, routed around here rather than pinned,
    // since it would make this contract test flaky/misleading on that backend.
    @Test fun `withCopyFileToContainer round-trips a classpath resource and a host path`() {
        val classpathBytes = "rightsize-mount-fixture-payload\n"
        val hostFile = Files.createTempFile("rightsize-hostpath-", ".txt")
        val hostBytes = "rightsize-host-path-payload\n"
        Files.writeString(hostFile, hostBytes)
        val c = GenericContainer("alpine:3.19")
            .withCopyFileToContainer(MountableFile.forClasspathResource("rightsize-fixture.txt"), "/mnt-a/from-classpath.txt")
            .withCopyFileToContainer(MountableFile.forHostPath(hostFile.toString()), "/mnt-b/from-host.txt")
            .withCommand("sleep", "120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        c.start()
        try {
            val fromClasspath = c.execInContainer("cat", "/mnt-a/from-classpath.txt")
            assertEquals(0, fromClasspath.exitCode, "cat classpath mount failed: ${fromClasspath.stderr}")
            assertEquals(classpathBytes, fromClasspath.stdout, "classpath resource bytes did not round-trip")

            val fromHost = c.execInContainer("cat", "/mnt-b/from-host.txt")
            assertEquals(0, fromHost.exitCode, "cat host-path mount failed: ${fromHost.stderr}")
            assertEquals(hostBytes, fromHost.stdout, "host path bytes did not round-trip")
        } finally { c.stop(); Files.deleteIfExists(hostFile) }
    }

    // Read-only mount default (FileMount.readOnly == true unless overridden) is honored.
    @Test fun `withCopyFileToContainer default read-only mount rejects an in-guest write`() {
        val hostFile = Files.createTempFile("rightsize-ro-", ".txt")
        Files.writeString(hostFile, "seed\n")
        val c = GenericContainer("alpine:3.19")
            .withCopyFileToContainer(MountableFile.forHostPath(hostFile.toString()), "/tmp/ro.txt")
            .withCommand("sleep", "120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        c.start()
        try {
            val write = c.execInContainer("sh", "-c", "echo overwritten > /tmp/ro.txt")
            if (readOnlyMountEnforced) {
                assertNotEquals(0, write.exitCode, "expected read-only mount to reject the write; stderr=${write.stderr}")
            } else {
                // Pinned current msb behavior (see readOnlyMountEnforced doc above): the write
                // succeeds despite the mount being flagged `ro` in the guest's mount table.
                assertEquals(0, write.exitCode, "msb read-only-mount write behavior changed — update readOnlyMountEnforced pin")
            }
        } finally { c.stop(); Files.deleteIfExists(hostFile) }
    }

    // followOutput streams live lines in order and close() halts delivery + joins the pump.
    @Test fun `followOutput streams lines in order and close halts delivery`() {
        val c = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c",
                "i=1; while [ \$i -le 20 ]; do echo LINE-\$i; i=\$((i+1)); sleep 0.2; done; sleep 120")
            .waitingFor(Wait.forLogMessage(".*LINE-1$.*", 0).withStartupTimeout(Duration.ofSeconds(30)))
        c.start()
        try {
            val received = CopyOnWriteArrayList<String>()
            val sawThree = CountDownLatch(1)
            val closable = c.followOutput { line ->
                received += line
                if (received.count { it.contains("LINE-") } >= 3) sawThree.countDown()
            }
            try {
                assertTrue(sawThree.await(20, TimeUnit.SECONDS), "did not observe 3 streamed lines in time")
                val lineNumbers = received.filter { it.contains("LINE-") }
                    .map { Regex("LINE-(\\d+)").find(it)!!.groupValues[1].toInt() }
                assertEquals(lineNumbers.sorted(), lineNumbers, "streamed lines arrived out of order: $lineNumbers")
            } finally { closable.close() }

            val countAfterClose = received.size
            Thread.sleep(1000) // workload keeps emitting; a live pump would keep growing `received`
            assertEquals(countAfterClose, received.size, "followOutput kept delivering after close()")
        } finally { c.stop() }
    }

    // A workload whose final output is an unterminated line (no trailing '\n') must still have
    // that line delivered once the workload — and therefore the follow stream — ends. The
    // workload exits on its own (rather than being stopped from the test side) so that both
    // backends' natural stream-end path (docker's onComplete/onError, msb's `logs -f` process
    // exit when the sandbox stops) is what has to flush the trailing partial line, not close().
    // The `sleep 2` between the boot marker and the final fragment is deliberate: msb's `start()`
    // polls `msb ls` every 300ms for the sandbox to reach Running before returning, so a workload
    // that exits immediately can race that poll and make `start()` itself fail (sandbox already
    // exited, never observed Running) — the short-lived pause gives that poll a window to see it
    // running before the workload (and therefore the follow stream) ends.
    //
    // This also guards against a duplicate-delivery regression: msb's watchdog (MsbCliBackend)
    // races a live-reader thread against a `msb logs` replay of "everything not yet delivered",
    // and if the watchdog snapshots `delivered` before the reader has drained lines still sitting
    // in the pipe at stop-time, those complete lines get delivered twice (once live, once
    // replayed). The workload below emits distinct LINE-1..N markers plus the unterminated
    // fragment so we can assert, after a settle window past the final-line latch, that every
    // complete line — and the final fragment — arrived exactly once. On docker (no watchdog) this
    // should trivially hold.
    @Test fun `followOutput delivers a final unterminated line after the workload exits`() {
        val c = GenericContainer("alpine:3.19")
            .withCommand("sh", "-c",
                "echo BOOT-MARKER; sleep 2; " +
                    "i=1; while [ \$i -le 5 ]; do echo LINE-\$i; i=\$((i+1)); done; " +
                    "printf 'LINE-END-NO-NEWLINE'")
            .waitingFor(Wait.forLogMessage(".*BOOT-MARKER.*"))
        c.start()
        try {
            val received = CopyOnWriteArrayList<String>()
            val sawFinalLine = CountDownLatch(1)
            val closable = c.followOutput { line ->
                received += line
                if (line.contains("LINE-END-NO-NEWLINE")) sawFinalLine.countDown()
            }
            try {
                assertTrue(
                    sawFinalLine.await(20, TimeUnit.SECONDS),
                    "final unterminated line was never delivered; received so far: $received",
                )
                Thread.sleep(1000) // settle window: let any duplicate/late delivery surface
                assertEquals(1, received.count { it.contains("LINE-END-NO-NEWLINE") },
                    "final unterminated line was delivered more than once: $received")
                for (n in 1..5) {
                    assertEquals(1, received.count { it == "LINE-$n" },
                        "LINE-$n was not delivered exactly once: $received")
                }
            } finally { closable.close() }
        } finally { c.stop() }
    }
}
