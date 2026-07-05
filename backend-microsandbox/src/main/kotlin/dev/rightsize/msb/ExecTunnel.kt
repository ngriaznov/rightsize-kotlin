package dev.rightsize.msb

import dev.rightsize.core.NetworkLink
import java.net.Socket
import java.nio.file.Path

/**
 * One alias:guestPort route into a consumer sandbox, bridged over `msb exec --stream`.
 * Single connection at a time; the in-guest listener is respawned after each connection.
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

        // msb 0.6.2's host-port-publish proxy never propagates the target's own TCP close back
        // to this host-side socket (empirically confirmed against the real binary) - a plain
        // `read() == 0` on the target->guest direction therefore never arrives,
        // and serveOneConnection blocks forever after the first exchange, wedging the tunnel to
        // exactly one connection for its whole lifetime. Fix: give the target socket a read
        // timeout and treat a timeout as end-of-exchange, scoped in two phases so a slow-but-real
        // response is never truncated:
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
                // target -> guest: relay the response back, ending on idle timeout since msb's
                // proxy never delivers a real EOF here (see companion object comment above).
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
     * target-to-guest direction, where msb's port-publish proxy never delivers a real close
     * (see companion object comment).
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
