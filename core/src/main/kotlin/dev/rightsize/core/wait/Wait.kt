package dev.rightsize.core.wait

import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.time.Duration
import java.time.Instant

/** Thrown when a [WaitStrategy] never observes readiness within its timeout. */
class ContainerLaunchException(message: String) : RuntimeException(message)

/** The minimal view of a running container a [WaitStrategy] needs — backend-agnostic by design. */
interface WaitTarget {
    /** The host address the container's ports are reachable on. */
    val host: String
    /** The host port the given guest port is published on. */
    fun mappedPort(guestPort: Int): Int
    /** Every guest port the container declared with `withExposedPorts`. */
    val exposedGuestPorts: List<Int>
    /** The container's logs so far, for readiness probes and timeout diagnostics. */
    fun currentLogs(): String
    /** A short human-readable identifier, used in timeout/error messages. */
    fun describe(): String
}

/** A pluggable readiness check run after a container starts, before `GenericContainer.start()` returns. */
interface WaitStrategy {
    /** Blocks until [target] is ready, or throws [ContainerLaunchException] on timeout. */
    fun waitUntilReady(target: WaitTarget)
    /** Returns a copy of this strategy with a different timeout; the receiver is left unchanged. */
    fun withStartupTimeout(timeout: Duration): WaitStrategy
}

/**
 * Base implementation of the poll-until-ready idiom shared by every built-in [WaitStrategy] and
 * available for third-party/module wait strategies to reuse (e.g. a strategy that speaks a
 * protocol handshake instead of just checking a port). Subclasses supply [isReady] — a single
 * readiness probe — and [what] — a short human description used in the timeout message; this
 * class owns the deadline/poll-interval/log-tail plumbing so subclasses never hand-roll it.
 */
public abstract class AbstractWaitStrategy protected constructor(
    protected var timeout: Duration = Duration.ofSeconds(60),
) : WaitStrategy {
    protected abstract fun isReady(target: WaitTarget): Boolean
    protected abstract fun what(): String

    override fun withStartupTimeout(timeout: Duration): WaitStrategy { this.timeout = timeout; return this }

    override fun waitUntilReady(target: WaitTarget) {
        val deadline = Instant.now().plus(timeout)
        // do-first-probe-then-check-deadline: a `while (before(deadline))` guard can exit having
        // performed zero polls when the timeout is shorter than the time it takes to evaluate the
        // condition once (tiny/1ms timeouts), under-honoring the caller's intent that a wait
        // strategy always gets at least one real chance to observe readiness.
        do {
            try { if (isReady(target)) return } catch (_: Exception) {}
            if (Instant.now().isBefore(deadline)) Thread.sleep(POLL_INTERVAL_MS)
        } while (Instant.now().isBefore(deadline))
        val tail = target.currentLogs().lines().takeLast(LOG_TAIL_LINES).joinToString("\n")
        throw ContainerLaunchException(
            "Timed out after ${timeout.seconds}s waiting for ${what()} on ${target.describe()}.\nLast log lines:\n$tail")
    }

    protected companion object {
        const val POLL_INTERVAL_MS = 250L
        const val LOG_TAIL_LINES = 50
    }
}

abstract class PollingWait internal constructor(timeout: Duration = Duration.ofSeconds(60)) : AbstractWaitStrategy(timeout)

/** Polls an HTTP endpoint until it returns the expected status code. Build via [Wait.forHttp]. */
class HttpWaitStrategy(private val path: String) : PollingWait() {
    private var guestPort: Int? = null
    private var status = 200
    /** The guest port to probe; defaults to the container's first exposed port if never called. */
    fun forPort(guestPort: Int): HttpWaitStrategy { this.guestPort = guestPort; return this }
    /** The response status code that counts as ready; defaults to 200. */
    fun forStatusCode(code: Int): HttpWaitStrategy { this.status = code; return this }
    override fun what() = "HTTP $status on $path"
    override fun isReady(target: WaitTarget): Boolean {
        val port = target.mappedPort(guestPort ?: target.exposedGuestPorts.first())
        val conn = URI("http://${target.host}:$port$path").toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 1000; conn.readTimeout = 1000
        return try { conn.responseCode == status } finally { conn.disconnect() }
    }
}

/** Factory for the built-in [WaitStrategy] implementations; pass the result to `GenericContainer.waitingFor`. */
object Wait {
    // Best-effort probe used after a bare connect succeeds: a userland proxy / port-forwarder
    // (docker-proxy, msb's loopback forwarder) can accept a TCP connection before the in-guest
    // server itself is listening. A zero-byte read with a short SO_TIMEOUT distinguishes an
    // immediate EOF/RST (proxy accepted, nobody home yet) from either data or a timeout (both of
    // which mean a real peer is on the other end). Deliberately conservative: a server that
    // speaks first is "ready" (data arrived), and a server that waits for the client to speak
    // first is also "ready" (the read merely times out) — only a closed connection fails the probe.
    private const val READ_PROBE_TIMEOUT_MS = 200

    private fun connectAndProbe(host: String, port: Int): Boolean =
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 1000)
            socket.soTimeout = READ_PROBE_TIMEOUT_MS
            try {
                // A negative return means the peer closed the stream (EOF) right after accepting —
                // the hallmark of a proxy/forwarder with nothing listening behind it yet.
                socket.getInputStream().read() != -1
            } catch (_: java.net.SocketTimeoutException) {
                true // no data within the window, but the connection is still open: real peer.
            }
        }

    /**
     * Ready when every exposed guest port accepts a TCP connection (with a best-effort read
     * probe to see past a userland proxy/forwarder accepting before the guest server is
     * listening — see the [READ_PROBE_TIMEOUT_MS] note above). The default strategy for
     * `GenericContainer` when [dev.rightsize.GenericContainer.waitingFor] is never called.
     */
    fun forListeningPort(): WaitStrategy = object : PollingWait() {
        override fun what() = "a listening TCP port"
        override fun isReady(target: WaitTarget) = target.exposedGuestPorts.all { gp ->
            connectAndProbe(target.host, target.mappedPort(gp))
        }
    }
    /** Ready when an HTTP GET to [path] returns the expected status; see [HttpWaitStrategy]. */
    fun forHttp(path: String) = HttpWaitStrategy(path)
    /** Ready when a log line matching [regex] has appeared at least [times] times. */
    fun forLogMessage(regex: String, times: Int = 1): WaitStrategy = object : PollingWait() {
        private val re = Regex(regex)
        override fun what() = "log line matching '$regex' x$times"
        // containsMatchIn already subsumes matches() for counting purposes (a whole-line match is
        // necessarily also a substring match), so checking both would have been pure redundancy.
        override fun isReady(target: WaitTarget) =
            target.currentLogs().lineSequence().count { re.containsMatchIn(it) } >= times
    }
}
