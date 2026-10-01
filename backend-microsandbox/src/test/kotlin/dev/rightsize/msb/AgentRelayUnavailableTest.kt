package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** Real and variant captures of msb's "agent relay" boot failure, shared with
 * [MsbAgentRelayRetryTest]. */
internal object AgentRelayCaptures {
    /** What `msb run` / `msb restore` print, as msb renders it (v0.7.6, Windows `exit code`
     * wording). */
    val attachedRun = """
        |error: failed to start "rz-731fdcdc-8"
        |  → other: sandbox process exited (exit code: 0) before agent relay became available
        |  → run `msb logs --source system rz-731fdcdc-8` for full diagnostics
        """.trimMargin()

    /** The same failure as rightsize received it from a Windows CI brokered restore: PowerShell
     * decorated the native-command error and mangled the arrows. */
    val powerShellDecorated = """
        |msb.exe : error: failed to start "rz-731fdcdc-8"
        |At line:1 char:1
        |+ & 'C:\Users\runneradmin\AppData\Local\rightsize\msb\0.7.6\bin\msb.exe ...
        |+ ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        |    + CategoryInfo          : NotSpecified: (error: failed to start "rz-731fdcdc-8":String) [], RemoteExcept
        |   ion
        |    + FullyQualifiedErrorId : NativeCommandError
        |
        |  ΓåÆ other: sandbox process exited (exit code: 0) before agent relay became available
        |  ΓåÆ run `msb logs --source system rz-731fdcdc-8` for full diagnostics
        """.trimMargin()

    /** The Unix rendering of a clean exit: `exit status: 0` instead of Windows' `exit code: 0`. */
    val unixCleanExit = attachedRun.replace("(exit code: 0)", "(exit status: 0)")

    /** The same sentence with a crash status in place of the clean exit. */
    fun withStatus(status: String) = attachedRun.replace("(exit code: 0)", "($status)")
}

class AgentRelayUnavailableTest {

    @Test fun `matches the attached-run capture verbatim`() {
        assertTrue(isAgentRelayUnavailable(AgentRelayCaptures.attachedRun))
    }

    @Test fun `matches the PowerShell-decorated capture from the Windows brokered restore verbatim`() {
        // The arrows arrive as mojibake and the error is wrapped in PowerShell's own report; the
        // classifier must not lean on the arrow, the `other:` prefix or the line structure.
        assertTrue(isAgentRelayUnavailable(AgentRelayCaptures.powerShellDecorated))
    }

    @Test fun `matches the Unix exit status rendering of the same failure`() {
        assertTrue(isAgentRelayUnavailable(AgentRelayCaptures.unixCleanExit))
    }

    @Test fun `matches without the arrow, the other prefix, the error prefix or line structure`() {
        assertTrue(isAgentRelayUnavailable(
            "sandbox process exited (exit code: 0) before agent relay became available"))
        // msb's one-line form, used when its own rollback could not finish (it has no `other:`
        // prefix and no hint line).
        assertTrue(isAgentRelayUnavailable(
            "error: runtime error: failed to start \"rz-abc-1\": sandbox process exited (exit status: 0) " +
                "before agent relay became available; startup cleanup pending"))
    }

    @Test fun `does not match a crash exit with the same message`() {
        // The old tab-in-env SIGABRT printed this sentence with a signal; a crash is deterministic
        // and must not be retried. Parentheses keep a hex or multi-digit code from matching 0.
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("exit code: 0xc0000409")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("exit code: 1")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("exit code: 10")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("exit code: 0x0")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("exit status: 1")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("signal: 6 (SIGABRT)")))
        assertFalse(isAgentRelayUnavailable(AgentRelayCaptures.withStatus("signal: 9 (SIGKILL)")))
    }

    @Test fun `does not match output without the relay sentence`() {
        assertFalse(isAgentRelayUnavailable(""))
        assertFalse(isAgentRelayUnavailable("error: failed to start \"rz-abc-1\"\n  \u2192 other: sandbox process exited (exit code: 0)"))
        // Other msb start-up failures that also say "failed to start".
        assertFalse(isAgentRelayUnavailable(
            "error: failed to start \"rz-abc-1\"\n  \u2192 other: startup timed out after 180 seconds before agent readiness"))
        assertFalse(isAgentRelayUnavailable("sandbox exited during preparation: exit code: 0"))
        assertFalse(isAgentRelayUnavailable("error: agent client error: connect /run/msb-agent.sock: No such file"))
    }

    @Test fun `does not match the relay sentence alone, with no exit marker`() {
        assertFalse(isAgentRelayUnavailable("before agent relay became available"))
        assertFalse(isAgentRelayUnavailable(
            "error: failed to start \"rz-abc-1\"\n  \u2192 other: sandbox process exited before agent relay became available"))
    }
}
