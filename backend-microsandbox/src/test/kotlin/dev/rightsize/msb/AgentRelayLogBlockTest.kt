package dev.rightsize.msb

import dev.rightsize.core.ExecResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The text pieces of the agent-relay retry's stderr notes; [MsbAgentRelayRetryTest] covers the
 * retry that writes them. */
class AgentRelayLogBlockTest {

    private val header = "rightsize: agent-relay retry: 'msb logs rz-a --source system' after attempt 1"

    private fun block(text: String) = agentRelayLogBlock("rz-a", 1, SystemLogCapture.Output(text))

    @Test fun `the delay is whole seconds, or milliseconds when it is not a whole number of them`() {
        assertEquals("2 s", formatRetryDelay(2_000))
        assertEquals("1 s", formatRetryDelay(1_000))
        assertEquals("150 ms", formatRetryDelay(150))
        assertEquals("1500 ms", formatRetryDelay(1_500))
        assertEquals("999 ms", formatRetryDelay(999))
        assertEquals("0 ms", formatRetryDelay(0), "a zero delay is milliseconds, not `0 s`")
    }

    @Test fun `lines are indented two spaces under a header counting what is shown`() {
        assertEquals("$header (last 2 of 2 lines):\n  one\n  two\n", block("one\ntwo\n"))
    }

    @Test fun `a Windows capture with CRLF line ends gives the same block`() {
        assertEquals(block("one\ntwo\n"), block("one\r\ntwo\r\n"))
    }

    @Test fun `trailing blank lines are not counted but inner ones are kept`() {
        assertEquals("$header (last 3 of 3 lines):\n  one\n  \n  two\n", block("one\n\ntwo\n\n\n"))
    }

    @Test fun `exactly 200 lines are all shown, 201 cut the first`() {
        val all = (1..200).joinToString("\n") { "l$it" }
        assertTrue(block(all).startsWith("$header (last 200 of 200 lines):\n  l1\n"))
        val cut = block("l0\n$all")
        assertTrue(cut.startsWith("$header (last 200 of 201 lines):\n  l1\n"), cut)
        assertFalse("l0" in cut)
    }

    @Test fun `no output, or only blank lines, is the header alone`() {
        assertEquals("$header produced no output\n", block(""))
        assertEquals("$header produced no output\n", block("\n  \n"))
    }

    @Test fun `an unreadable capture is the header with the reason`() {
        assertEquals("$header could not be read (exit code 1: boom)\n",
            agentRelayLogBlock("rz-a", 1, SystemLogCapture.Unreadable("exit code 1: boom")))
    }

    @Test fun `the attempt number comes from the caller`() {
        assertTrue(agentRelayLogBlock("rz-a", 2, SystemLogCapture.Output("x")).contains("after attempt 2 (last 1 of 1 lines)"))
    }

    @Test fun `rm -f is reported as its exit code, or as never finishing with the first line of why`() {
        assertEquals("0", describeRmForce(Result.success(ExecResult(0, "", ""))))
        assertEquals("3", describeRmForce(Result.success(ExecResult(3, "", "error: rm failed"))))
        assertEquals("without finishing (msb rm -f x timed out after 60s)",
            describeRmForce(Result.failure(IllegalStateException("msb rm -f x timed out after 60s\nsecond line"))))
        assertEquals("without finishing (no message)", describeRmForce(Result.failure(RuntimeException())))
    }
}
