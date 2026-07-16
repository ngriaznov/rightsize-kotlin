package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.PortBinding
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fake-`msb`-binary unit coverage for [MsbCliBackend.createCheckpoint] (see docs/checkpoints.md)
 * — the stop -> snapshot create -> rm -> re-boot-from-snapshot cycle, and its two typed-failure
 * paths, driven entirely against a local stub script rather than a real msb binary or sandbox
 * (same pattern as [MsbCliBackendTest]'s `fakeMsbLifecycle`). POSIX-only; the msb-windows CI
 * lane's own `sandbox-it` integration test is what exercises this against the real binary on
 * Windows.
 */
class MsbCheckpointTest {

    /**
     * Extends the lifecycle fake with `snapshot create`/`rm`/a `--snapshot`-flagged `run` (the
     * re-boot): `run`/`ls`/`stop` behave exactly as [MsbCliBackendTest]'s own fake; `rm` is
     * always a no-op success (its own failure is not one of [MsbCliBackend.createCheckpoint]'s
     * two typed-failure shapes); `snapshot create` exits non-zero when [snapshotFailFlag] exists;
     * a `run` invocation carrying `--snapshot` (the re-boot, as opposed to the initial ordinary
     * boot) exits non-zero when [rebootFailFlag] exists, otherwise it recreates [marker] (so the
     * sandbox reports Running again) exactly like an ordinary boot. Every invocation's full argv
     * is appended to [callLog], one line per call, so the tests below can assert on the order and
     * shape of the commands [createCheckpoint] actually drives instead of re-implementing msb's
     * own CLI.
     */
    private fun fakeMsbCheckpointLifecycle(
        marker: Path,
        callLog: Path,
        snapshotFailFlag: Path,
        rebootFailFlag: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-checkpoint", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |echo "${'$'}*" >> "$callLog"
            |shift
            |case "${'$'}cmd" in
            |  run)
            |    name=""; snapshot=""
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --name) name="${'$'}2"; shift 2 ;;
            |        --snapshot) snapshot="${'$'}2"; shift 2 ;;
            |        *) shift ;;
            |      esac
            |    done
            |    if [ -n "${'$'}snapshot" ] && [ -f "$rebootFailFlag" ]; then
            |      echo "reboot from snapshot failed" 1>&2
            |      exit 1
            |    fi
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
            |  snapshot)
            |    if [ "${'$'}1" = "create" ]; then
            |      if [ -f "$snapshotFailFlag" ]; then echo "snapshot create failed" 1>&2; exit 1; fi
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

    /** [callLog]'s lines minus `msb ls` polling calls — those interleave with every boot's
     * readiness wait and aren't part of the stop/snapshot-create/rm/run sequence these tests
     * pin down. */
    private fun nonPollingCalls(callLog: Path): List<String> =
        Files.readAllLines(callLog).filterNot { it == "ls --format json" }

    @Test fun `createCheckpoint drives exactly stop, snapshot create, rm, then a --snapshot re-boot under the same name-ports-env`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = Files.createTempFile("rz-snapfail-", "").also { Files.deleteIfExists(it) }
        val rebootFailFlag = Files.createTempFile("rz-rebootfail-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag))
        val spec = ContainerSpec(
            name = "rz-ckpt-test", image = "irrelevant", runId = "run1",
            env = mapOf("FOO" to "bar"),
            ports = listOf(PortBinding(hostPort = 23456, guestPort = 80)),
        )
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            assertTrue("rz-ckpt-test" in backend.runningSandboxNames())
            Files.writeString(callLog, "")   // only createCheckpoint's own calls matter below

            backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")

            assertTrue("rz-ckpt-test" in backend.runningSandboxNames(),
                "the sandbox must be Running again once createCheckpoint returns")

            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-test",
                "snapshot create --from rz-ckpt-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-test",
            ), calls.take(3))
            assertEquals(4, calls.size, "no extra commands beyond stop/snapshot-create/rm/run: $calls")
            val reboot = calls[3]
            assertTrue(reboot.startsWith("run --name rz-ckpt-test"), "unexpected re-boot argv: $reboot")
            assertTrue("--snapshot rz-ckpt-0123456789ab" in reboot, "re-boot must run from the new checkpoint: $reboot")
            assertTrue("-p 23456:80" in reboot, "re-boot must keep the original port mapping: $reboot")
            assertTrue("-e FOO=bar" in reboot, "re-boot must keep the original env: $reboot")
            assertFalse("irrelevant" in reboot, "the ordinary image arg must not appear alongside --snapshot: $reboot")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint leaves the sandbox stopped and issues no rm or reboot when snapshot create fails`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = Files.createTempFile("rz-snapfail-", "")   // present => snapshot create fails
        val rebootFailFlag = Files.createTempFile("rz-rebootfail-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag))
        val spec = ContainerSpec(name = "rz-ckpt-fail-test", image = "irrelevant", runId = "run1")
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            assertTrue(e.message!!.contains("snapshot create"), "message: ${e.message}")
            assertTrue(e.message!!.contains("left") && e.message!!.contains("stopped"),
                "message must say the sandbox is left stopped: ${e.message}")
            assertTrue(e.message!!.contains("msb start rz-ckpt-fail-test"),
                "message must name the by-hand remedy: ${e.message}")

            assertFalse("rz-ckpt-fail-test" in backend.runningSandboxNames(),
                "a failed snapshot step must leave the sandbox stopped, never best-effort resumed")
            assertEquals(listOf(
                "stop rz-ckpt-fail-test",
                "snapshot create --from rz-ckpt-fail-test rz-ckpt-0123456789ab",
            ), nonPollingCalls(callLog), "a failed snapshot create must issue no rm and no re-boot")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint throws naming the ref and fromCheckpoint recovery when the re-boot from a successful snapshot fails`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-calllog-", "")
        val snapshotFailFlag = Files.createTempFile("rz-snapfail-", "").also { Files.deleteIfExists(it) }   // snapshot succeeds
        val rebootFailFlag = Files.createTempFile("rz-rebootfail-", "")   // present => the --snapshot re-boot fails
        val backend = MsbCliBackend(fakeMsbCheckpointLifecycle(marker, callLog, snapshotFailFlag, rebootFailFlag))
        val spec = ContainerSpec(name = "rz-ckpt-reboot-fail-test", image = "irrelevant", runId = "run1")
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-0123456789ab")
            }
            assertTrue(e.message!!.contains("rz-ckpt-0123456789ab"), "message must name the ref: ${e.message}")
            assertTrue(e.message!!.contains("fromCheckpoint"), "message must name the recovery path: ${e.message}")
            assertTrue(e.message!!.contains("reboot from snapshot failed"),
                "message must carry msb's stderr: ${e.message}")

            assertFalse("rz-ckpt-reboot-fail-test" in backend.runningSandboxNames(),
                "a failed re-boot must not be misreported as the sandbox running again")
            val calls = nonPollingCalls(callLog)
            assertEquals(listOf(
                "stop rz-ckpt-reboot-fail-test",
                "snapshot create --from rz-ckpt-reboot-fail-test rz-ckpt-0123456789ab",
                "rm rz-ckpt-reboot-fail-test",
            ), calls.take(3), "the re-boot attempt must follow a successful snapshot and rm: $calls")
            assertTrue(calls[3].startsWith("run --name rz-ckpt-reboot-fail-test"),
                "unexpected re-boot argv: ${calls[3]}")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `removeCheckpoint never throws even when the msb binary cannot be run`() {
        val backend = MsbCliBackend(Path.of("/nonexistent/msb"))
        assertDoesNotThrow { backend.removeCheckpoint("rz-ckpt-0123456789ab") }
    }

    /**
     * Fake `msb snapshot inspect <ref>`: exits 0 for `rz-ckpt-exists`; exits 1 with msb's own
     * miss wording on stderr for `rz-ckpt-missing`; exits 1 with unrelated stderr (a stand-in
     * for a corrupted state database, a permission failure, or any other probe breakage) for
     * anything else — proving [MsbCliBackend.hasCheckpoint] tells "genuinely gone" apart from
     * "probe failed" without a real snapshot store.
     */
    private fun fakeMsbInspect(): Path {
        val script = Files.createTempFile("rz-fake-msb-inspect", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"; shift
            |if [ "${'$'}cmd" = "snapshot" ] && [ "${'$'}1" = "inspect" ]; then
            |  case "${'$'}2" in
            |    rz-ckpt-exists) exit 0 ;;
            |    rz-ckpt-missing) echo "error: snapshot not found: ${'$'}2 at /path/to/.microsandbox/snapshots/${'$'}2" 1>&2; exit 1 ;;
            |    *) echo "error: database error: state database is corrupt" 1>&2; exit 1 ;;
            |  esac
            |fi
            |exit 0
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `hasCheckpoint returns true when msb snapshot inspect exits 0`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        assertTrue(backend.hasCheckpoint("rz-ckpt-exists"))
    }

    @Test fun `hasCheckpoint returns false only when stderr carries msb's snapshot-not-found wording`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        assertFalse(backend.hasCheckpoint("rz-ckpt-missing"))
    }

    @Test fun `hasCheckpoint throws carrying stderr when inspect fails without the miss wording`() {
        val backend = MsbCliBackend(fakeMsbInspect())
        val e = assertThrows(IllegalStateException::class.java) {
            backend.hasCheckpoint("rz-ckpt-other")
        }
        assertTrue(e.message!!.contains("state database is corrupt"), "message must carry msb's stderr: ${e.message}")
        assertTrue(e.message!!.contains("rz-ckpt-other"), "message must name the ref: ${e.message}")
    }
}
