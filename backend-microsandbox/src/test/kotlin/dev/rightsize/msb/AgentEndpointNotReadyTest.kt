package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AgentEndpointNotReadyTest {

    @Test fun `matches the captured windows named-pipe failure verbatim`() {
        // Captured verbatim from a windows-2025 hosted runner: an exec issued against a
        // sandbox restored from a checkpoint archive, before the guest agent had created its
        // named pipe.
        assertTrue(isAgentEndpointNotReady(
            "error: agent client error: connect \\\\.\\pipe\\msb-agent-e7779577a75cc1f89f66c534458bf8fd: " +
                "The system cannot find the file specified. (os error 2)"))
    }

    @Test fun `matches the same failure shaped as a unix socket`() {
        // Same msb framing, unix socket rather than a named pipe — the endpoint is
        // platform-specific but the "couldn't connect at all" classification is not.
        assertTrue(isAgentEndpointNotReady(
            "error: agent client error: connect /run/user/1001/msb-agent-abc123.sock: " +
                "No such file or directory (os error 2)"))
    }

    @Test fun `does not match a guest command's own failure`() {
        // A command that ran and failed inside the guest must return on the first attempt —
        // retrying it would multiply its runtime for no reason.
        assertFalse(isAgentEndpointNotReady("cat: /srv/missing.txt: No such file or directory"))
        assertFalse(isAgentEndpointNotReady(""))
    }

    @Test fun `does not match an agent error raised after the connection is established`() {
        // Reached the agent, then the agent itself reported a problem — a real error to
        // surface, not a not-yet-listening endpoint to wait out.
        assertFalse(isAgentEndpointNotReady(
            "error: agent client error: request failed: guest process exited before responding"))
    }
}
