package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end coverage, through a fake `msb` binary, for [MsbCliBackend]'s fast-exit
 * post-mortem classification: msb 0.6.16 reworked the sandbox lifecycle so Running is no
 * longer guaranteed observable for a workload that completes quickly, so a `msb run` child
 * that exits 0 before this backend's poll ever samples Running is no longer automatically a
 * failed boot (see [MsbCliBackend.isCleanFastExit]'s doc). These four tests red-proof that
 * classification: the success case, each of its two negative gates, and — separately — that
 * the marker is read from the *system* log and not the workload's own output.
 */
class MsbFastExitClassificationTest {

    /**
     * A fake `msb` executable for the fast-exit classification: `run` exits 0 immediately —
     * before this backend's poll loop can ever see the sandbox Running — `ls --format json`
     * reports [lsJson] verbatim. `logs` dispatches on whether `--source system` is present in
     * argv: with it, prints [systemLog]; without it (a plain `logs <name>` call, i.e. the
     * workload's own output), prints [workloadLog] instead. The two are deliberately made to
     * diverge so a regression that reads the workload log instead of the system log (e.g.
     * [MsbCommands.logs] swapped in for [MsbCommands.logsSystem]) is red-proofed by
     * `start still fails when the marker appears only in workload output, not the system
     * log` below, not silently passed by a fake that can't tell the two calls apart.
     * `stop`/`rm`/anything else are no-op successes, matching
     * [MsbCliBackendTest.fakeMsbLifecycle]'s sibling shape for the other command groups.
     */
    private fun fakeMsbFastExit(
        lsJson: String,
        systemLog: String,
        workloadLog: String = "ordinary workload output, nothing marker-like here",
    ): Path {
        val script = Files.createTempFile("rz-fake-msb-fastexit", "")
        Files.writeString(
            script,
            """
            |#!/bin/sh
            |cmd="${'$'}1"
            |case "${'$'}cmd" in
            |  run) exit 0 ;;
            |  ls) printf '%s' '$lsJson' ;;
            |  logs)
            |    case " ${'$'}* " in
            |      *" --source system "*)
            |        cat <<'RZFASTEXITEOF'
            |$systemLog
            |RZFASTEXITEOF
            |        ;;
            |      *)
            |        cat <<'RZFASTEXITEOF'
            |$workloadLog
            |RZFASTEXITEOF
            |        ;;
            |    esac
            |    ;;
            |  *) exit 0 ;;
            |esac
            |""".trimMargin(),
        )
        script.toFile().setExecutable(true)
        return script
    }

    @Test fun `start succeeds when a fast-exit child leaves the sandbox Stopped with the started marker`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val name = "rz-test-fastexit-ok"
        val backend = MsbCliBackend(fakeMsbFastExit(
            lsJson = """[{"name":"$name","status":"Stopped"}]""",
            systemLog = "--- sandbox started ---",
        ))
        val handle = backend.create(ContainerSpec(name = name, image = "irrelevant", runId = "run1"))
        backend.start(handle)   // must not throw: a clean fast exit is a success, not a failed boot
        assertTrue(name in backend.trackedNames(),
            "a fast-exit success is still an own-run start, tracked for cleanup like any other")
        assertFalse(name in backend.runningSandboxNames(),
            "the workload already finished — state/inspection surfaces must report it as not running")
        assertDoesNotThrow({ backend.stop(handle) }, "stop() must remain safe on an already-Stopped sandbox")
    }

    @Test fun `start still fails today's error when the started marker is absent`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val name = "rz-test-fastexit-nomarker"
        val backend = MsbCliBackend(fakeMsbFastExit(
            lsJson = """[{"name":"$name","status":"Stopped"}]""",
            systemLog = "guest kernel booting\nagentd: listening\n",   // no completion marker
        ))
        val handle = backend.create(ContainerSpec(name = name, image = "irrelevant", runId = "run1"))
        val ex = assertThrows(IllegalStateException::class.java) { backend.start(handle) }
        assertTrue(ex.message!!.contains("before reaching Running"), "unexpected message: ${ex.message}")
    }

    @Test fun `start still fails when the marker appears only in workload output, not the system log`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val name = "rz-test-fastexit-workloadmarker"
        val backend = MsbCliBackend(fakeMsbFastExit(
            lsJson = """[{"name":"$name","status":"Stopped"}]""",
            systemLog = "guest kernel booting\nagentd: listening\n",   // no completion marker
            // The workload's own stdout happens to contain something marker-like -- this must
            // not be mistaken for the guest agent's real completion marker in the system log.
            workloadLog = "--- sandbox started ---",
        ))
        val handle = backend.create(ContainerSpec(name = name, image = "irrelevant", runId = "run1"))
        val ex = assertThrows(IllegalStateException::class.java) { backend.start(handle) }
        assertTrue(ex.message!!.contains("before reaching Running"), "unexpected message: ${ex.message}")
    }

    @Test fun `start still fails today's error when the sandbox state is not Stopped`() {
        assumeFalse(Platform.current()?.isWindows == true, "POSIX-only fake binary; see doc comment")
        val name = "rz-test-fastexit-notstopped"
        val backend = MsbCliBackend(fakeMsbFastExit(
            lsJson = "[]",   // no entry at all for this sandbox -- status is neither Stopped nor anything else
            systemLog = "--- sandbox started ---",
        ))
        val handle = backend.create(ContainerSpec(name = name, image = "irrelevant", runId = "run1"))
        val ex = assertThrows(IllegalStateException::class.java) { backend.start(handle) }
        assertTrue(ex.message!!.contains("before reaching Running"), "unexpected message: ${ex.message}")
    }
}
