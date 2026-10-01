package dev.rightsize.msb

import dev.rightsize.core.NetworkLink
import java.net.Socket
import java.nio.file.Path

/**
 * One alias:guestPort route into a consumer sandbox, bridged over `msb exec --stream`.
 * Single connection at a time: the in-guest listener is one `nc -l -p <port>`, which serves one
 * connection, and the worker thread serves one exchange at a time, then respawns the listener.
 * Client-speaks-first protocols only (HTTP). Confirmed empirically against the real msb
 * binary: host-side reads MUST be raw unbuffered streams, never buffered readers, or the
 * pump hangs.
 */
internal class ExecTunnel(
    private val msb: Path,
    private val sandboxName: String,
    private val link: NetworkLink,
) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var current: Process? = null

    private companion object {
        // Respawning the in-guest `nc -l` listener is itself an `msb exec` process spawn; without
        // a pause between attempts, a listener that keeps exiting immediately (no traffic, or a
        // broken pump) busy-spins `msb exec` in a tight loop. Same backoff on both respawn paths.
        const val RESPAWN_BACKOFF_MS = 200L

        // How the target->guest pump learns an exchange is over. From msb 0.7.5 on, a published
        // TCP port passes the guest's close to this host-side socket, so a target that closes
        // after its response (`Connection: close`, HTTP/1.0, a server that answers and hangs up)
        // ends the read with EOF at once. Before 0.7.5 that close never arrived (confirmed against
        // the real binary). A keep-alive target (any persistent HTTP/1.1 server) never closes even
        // on the pinned msb, and without an end-of-exchange signal serveOneConnection would block
        // forever after the first exchange, so the in-guest listener would never be respawned.
        // So the target socket gets a read timeout and a timeout counts as end-of-exchange,
        // scoped in two phases so a slow-but-real response is never truncated:
        //  - before any target byte arrives, tolerate silence up to FIRST_BYTE_DEADLINE_MS (a
        //    cold response taking this long must still come through whole);
        //  - once the first byte has arrived, tighten to IDLE_WINDOW_MS - a gap this short right
        //    after data has started flowing really does mean "no more is coming", per this
        //    tunnel's own single-exchange, client-speaks-first contract.
        const val FIRST_BYTE_DEADLINE_MS = 10_000
        const val IDLE_WINDOW_MS = 500
    }

    private val worker = Thread({
        while (!closed) {
            val servedAConnection = runCatching { serveOneConnection() }.getOrDefault(false)
            if (!closed && !servedAConnection) Thread.sleep(RESPAWN_BACKOFF_MS)
        }
    }, "rz-tunnel-$sandboxName-${link.alias}:${link.guestPort}").apply { isDaemon = true; start() }

    /** Returns true if a connection was actually relayed, so the caller only backs off on churn. */
    private fun serveOneConnection(): Boolean {
        val p = ProcessBuilder(
            listOf(msb.toString()) +
                MsbCommands.execStream(sandboxName, listOf("nc", "-l", "-p", link.guestPort.toString()))
        ).start()
        current = p
        try {
            val first = p.inputStream.read()          // block until the guest client sends its first byte
            if (first < 0) return false                // listener exited without traffic; back off and respawn
            Socket("127.0.0.1", link.targetHostPort).use { sock ->
                sock.tcpNoDelay = true
                sock.soTimeout = FIRST_BYTE_DEADLINE_MS
                // guest -> target: relay what the guest client sent (starting with the byte
                // already read above) over to the real service.
                val guestToTargetPump = Thread {
                    val out = sock.getOutputStream()
                    out.write(first); out.flush()
                    pump(p.inputStream, out)
                }.apply { isDaemon = true; start() }
                // target -> guest: relay the response back. Ends on the target's EOF (msb 0.7.5+,
                // when the target closes) or on the idle timeout, which is the only end-of-exchange
                // signal for a keep-alive target (see companion object comment above).
                pumpWithIdleTimeout(sock, p.outputStream)
                guestToTargetPump.join(2000)
            }
            return true
        } finally {
            p.destroy()
            current = null
        }
    }

    /** Raw unbuffered pump; flush after every read (buffering hangs the relay). */
    private fun pump(src: java.io.InputStream, dst: java.io.OutputStream) {
        val buf = ByteArray(8 * 1024)
        try {
            while (true) {
                val n = src.read(buf); if (n < 0) break
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: Exception) { /* connection closed */ }
        runCatching { dst.close() }
    }

    /**
     * Same raw unbuffered relay as [pump], except [sock] carries a read timeout and a
     * [java.net.SocketTimeoutException] is treated exactly like a clean EOF - "no more data is
     * coming, this exchange is over" - rather than as a failure. Used specifically for the
     * target-to-guest direction, where a keep-alive target never closes its side, so the timeout
     * is the only end-of-exchange signal. A target that does close ends the loop earlier, on
     * EOF (msb 0.7.5+; see companion object comment).
     *
     * Scoped in two phases: [sock] arrives with its timeout already set to
     * [FIRST_BYTE_DEADLINE_MS] by the caller, tolerating a slow target that hasn't sent anything
     * yet. The instant the first byte arrives, this function tightens the timeout down to
     * [IDLE_WINDOW_MS] - from then on, a gap that short really does mean the exchange is over.
     */
    private fun pumpWithIdleTimeout(sock: Socket, dst: java.io.OutputStream) {
        val src = sock.getInputStream()
        val buf = ByteArray(8 * 1024)
        var sawData = false
        try {
            while (true) {
                val n = try {
                    src.read(buf)
                } catch (_: java.net.SocketTimeoutException) {
                    break // idle timeout (first-byte or post-data): end-of-exchange, not a failure.
                }
                if (n < 0) break
                if (!sawData) {
                    sawData = true
                    // Real data has started flowing; tighten the window. A failure to set it is
                    // not fatal - the read loop just keeps the more generous first-byte deadline.
                    runCatching { sock.soTimeout = IDLE_WINDOW_MS }
                }
                dst.write(buf, 0, n); dst.flush()
            }
        } catch (_: Exception) { /* connection closed */ }
        runCatching { dst.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        current?.destroy()
        worker.join(2000)
    }
}
