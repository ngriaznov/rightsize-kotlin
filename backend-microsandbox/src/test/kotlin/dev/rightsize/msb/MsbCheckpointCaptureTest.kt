package dev.rightsize.msb

import dev.rightsize.core.CheckpointMissingWorkloadCommandException
import dev.rightsize.core.ContainerSpec
import dev.rightsize.core.checkpoint.CheckpointRegistry
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Red-proofs [MsbCliBackend.createCheckpoint]'s pre-stop guest-cmdline capture (requirement 2/(b)
 * of the workload-revival accommodation — see docs/checkpoints.md): only attempted when the
 * container's spec carries no explicit command, never fails the checkpoint on a miss, and
 * persists a hit into [CheckpointRegistry]'s ref-keyed captured-command store so a LATER, wholly
 * separate restore of the same ref can find it too — not just [createCheckpoint]'s own immediate
 * re-boot (that in-memory path is [MsbCheckpointTest]'s own territory; every spec there carries an
 * explicit command precisely to stay out of this one's way). Driven against a local stub `msb`
 * script, same pattern as [MsbCheckpointTest]/[MsbRestoreSupervisionTest]. POSIX-only.
 */
class MsbCheckpointCaptureTest {

    /**
     * Extends [MsbCheckpointTest.fakeMsbCheckpointLifecycle]'s shape with a capture-aware `exec`:
     * an `exec <name> -- sh -c <script>` invocation (no `-e` flags — [captureWorkloadCmdline]
     * never passes any) is the GUEST CMDLINE CAPTURE probe, answered synchronously — printing
     * [captureOutput]'s NUL-separated bytes and exiting 0, or exiting 1 with no output when
     * [captureMissFlag] exists (msb's own "no such process" shape, e.g. nothing but kernel
     * threads under PID 1) — while every OTHER `exec` invocation (the workload-revival session,
     * carrying a real cmd and possibly `-e` pairs) is the LONG-LIVED child: it blocks on [marker]
     * exactly like `run`/`restore`'s own boot, so `stop`'s `rm -f "$marker"` wakes it too.
     */
    private fun fakeMsbCaptureLifecycle(
        marker: Path,
        callLog: Path,
        captureOutput: String,
        captureMissFlag: Path,
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-capture", "")
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
            |    shift
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
            |  exec)
            |    while [ "${'$'}#" -gt 0 ]; do
            |      case "${'$'}1" in
            |        --) shift; break ;;
            |        *) shift ;;
            |      esac
            |    done
            |    if [ "${'$'}1" = "sh" ] && [ "${'$'}2" = "-c" ]; then
            |      if [ -f "$captureMissFlag" ]; then exit 1; fi
            |      printf '%b' "$captureOutput"
            |      exit 0
            |    fi
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
            |      shift 2
            |      sandbox="${'$'}1"; shift
            |      shift
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

    /** Raw lines of [callLog], one per invocation ordinarily — EXCEPT the guest-capture probe's
     * own `exec ... -- sh -c <script>` call, whose last argv element is the multi-line capture
     * script itself: [Files.readAllLines]/a plain `echo "$*"` both split on every embedded
     * newline inside it, so that ONE invocation surfaces as many separate lines here. Tests that
     * care about the capture call's presence/position use [callLogText]'s raw substring
     * ordering/counting instead of indexing into this list. */
    private fun nonPollingCalls(callLog: Path): List<String> =
        Files.readAllLines(callLog).filterNot { it == "ls --format json" }

    private fun callLogText(callLog: Path): String = Files.readString(callLog)

    @Test fun `createCheckpoint captures the guest cmdline before stop when the spec has no command, and persists it against the effective ref`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-cap-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-cap-calllog-", "")
        val missFlag = Files.createTempFile("rz-cap-missflag-", "").also { Files.deleteIfExists(it) }
        val backend = MsbCliBackend.forHost(
            fakeMsbCaptureLifecycle(marker, callLog, "nginx\\0-g\\0daemon off;\\0", missFlag),
            windowsHost = false, checkpointRegistryDir = tmp,
        )
        val spec = ContainerSpec(name = "rz-cap-test", image = "irrelevant", runId = "run1")   // no command
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            val effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-cap")

            // The capture probe runs FIRST, strictly before stop.
            val text = callLogText(callLog)
            val captureAt = text.indexOf("exec rz-cap-test -- sh -c")
            val stopAt = text.indexOf("stop rz-cap-test")
            assertTrue(captureAt >= 0, "the capture probe must have run: $text")
            assertTrue(stopAt in (captureAt + 1)..Int.MAX_VALUE, "capture must run before stop")

            assertEquals(
                listOf("nginx", "-g", "daemon off;"),
                CheckpointRegistry(tmp).readCapturedCommand(effectiveRef),
                "the captured cmdline must be persisted against the EFFECTIVE ref",
            )
            assertTrue("rz-cap-test" in backend.runningSandboxNames(),
                "the checkpoint's own re-boot must have revived the workload with the just-captured argv")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `createCheckpoint never fails when the guest capture finds nothing, but the re-boot then surfaces the typed missing-workload error`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-capmiss-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-capmiss-calllog-", "")
        val missFlag = Files.createTempFile("rz-capmiss-missflag-", "")   // present => capture script exits 1
        val backend = MsbCliBackend.forHost(
            fakeMsbCaptureLifecycle(marker, callLog, "", missFlag),
            windowsHost = false, checkpointRegistryDir = tmp,
        )
        val spec = ContainerSpec(name = "rz-capmiss-test", image = "irrelevant", runId = "run1")
        val handle = backend.create(spec)
        try {
            backend.start(handle)
            Files.writeString(callLog, "")

            // createCheckpoint itself must not throw from the capture miss — it fails later, at
            // its own re-boot, wrapped exactly like any other re-boot failure (see
            // MsbCheckpointTest's own "re-boot fails" test for that wrapping's shape). The
            // sandbox DOES come back up Running via restore (upstream boots it idle regardless —
            // that is exactly the bug this whole feature fixes) — it is specifically the
            // WORKLOAD-REVIVAL exec that never runs, because resolveWorkloadArgv already knows
            // there is nothing to run it with.
            val e = assertThrows(IllegalStateException::class.java) {
                backend.createCheckpoint(handle, "rz-ckpt-capmiss")
            }
            assertTrue(e.message!!.contains("has no workload command to restore"),
                "the re-boot must surface CheckpointMissingWorkloadCommandException's own message: ${e.message}")
            assertTrue(e.cause is CheckpointMissingWorkloadCommandException || e.message!!.contains("idle"),
                "the underlying cause must be the typed missing-workload error: ${e.message}")

            val calls = nonPollingCalls(callLog)
            assertTrue(calls.any { it.startsWith("stop ") }, "the snapshot step itself must still have run: $calls")
            assertTrue(calls.any { it.startsWith("restore ") },
                "restore itself must still have brought the sandbox up (upstream boots it idle either " +
                    "way) — it is the revival exec specifically that must never be attempted: $calls")
            assertEquals(1, calls.count { it.startsWith("exec rz-capmiss-test -- sh -c") },
                "exactly one exec call (the capture probe) may happen — no revival exec, since there is " +
                    "no argv to revive with: $calls")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }

    @Test fun `a captured command survives to a wholly separate later restore of the same ref, not just createCheckpoint's own immediate re-boot`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val marker = Files.createTempFile("rz-capreuse-marker-", "").also { Files.deleteIfExists(it) }
        val callLog = Files.createTempFile("rz-capreuse-calllog-", "")
        val missFlag = Files.createTempFile("rz-capreuse-missflag-", "").also { Files.deleteIfExists(it) }
        val script = fakeMsbCaptureLifecycle(marker, callLog, "redis-server\\0--daemonize\\0no\\0", missFlag)
        val backend = MsbCliBackend.forHost(script, windowsHost = false, checkpointRegistryDir = tmp)
        val spec = ContainerSpec(name = "rz-capreuse-test", image = "irrelevant", runId = "run1")
        val handle = backend.create(spec)
        val effectiveRef: String
        try {
            backend.start(handle)
            effectiveRef = backend.createCheckpoint(handle, "rz-ckpt-capreuse")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }

        // A brand-new backend instance and a brand-new handle — nothing in-memory carries over —
        // restoring the SAME ref, sharing only the on-disk registry directory.
        val secondBackend = MsbCliBackend.forHost(script, windowsHost = false, checkpointRegistryDir = tmp)
        val restoreSpec = ContainerSpec(
            name = "rz-capreuse-restored", image = "irrelevant", runId = "run2", checkpointRef = effectiveRef,
        )
        val restoredHandle = secondBackend.create(restoreSpec)
        try {
            secondBackend.start(restoredHandle)

            val execCall = Files.readAllLines(callLog).last { it.startsWith("exec rz-capreuse-restored ") }
            assertEquals("exec rz-capreuse-restored -- redis-server --daemonize no", execCall,
                "the fresh restore must revive the workload with the captured argv found via the ref, " +
                    "with no in-memory state carried over from the original checkpoint() call: $execCall")
        } finally {
            secondBackend.stop(restoredHandle)
            secondBackend.remove(restoredHandle)
        }
    }
}
