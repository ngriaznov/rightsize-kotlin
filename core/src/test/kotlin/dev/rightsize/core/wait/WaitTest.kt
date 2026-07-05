package dev.rightsize.core.wait

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration

private class FakeTarget(
    val ports: Map<Int, Int> = emptyMap(),
    @Volatile var logs: String = "",
) : WaitTarget {
    override val host = "127.0.0.1"
    override fun mappedPort(guestPort: Int) = ports.getValue(guestPort)
    override val exposedGuestPorts = ports.keys.toList()
    override fun currentLogs() = logs
    override fun describe() = "fake-target"
}

class WaitTest {
    @Test fun `forListeningPort succeeds when port open and times out when closed`() {
        ServerSocket(0).use { srv ->
            Wait.forListeningPort().waitUntilReady(FakeTarget(mapOf(80 to srv.localPort)))
        }
        val closed = ServerSocket(0).let { s -> s.close(); s.localPort }
        val ex = assertThrows(ContainerLaunchException::class.java) {
            Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(2))
                .waitUntilReady(FakeTarget(mapOf(80 to closed), logs = "boot log line"))
        }
        assertTrue(ex.message!!.contains("fake-target")); assertTrue(ex.message!!.contains("boot log line"))
    }

    // A bare TCP accept immediately followed by a close (EOF) mimics a userland proxy /
    // port-forwarder accepting on the host side before the real server is listening in the
    // guest. The read-probe must treat that as not-ready and keep polling until a real server —
    // one that either sends data or simply holds the connection open — takes over the port.
    @Test fun `forListeningPort treats an immediate accept-then-close as not ready`() {
        val behaveLikeRealServer = java.util.concurrent.atomic.AtomicBoolean(false)
        val srv = ServerSocket(0)
        val acceptor = Thread {
            while (!srv.isClosed) {
                val accepted = try { srv.accept() } catch (_: Exception) { break }
                if (behaveLikeRealServer.get()) {
                    // Real server: just hold the connection open (no data required).
                } else {
                    accepted.close() // proxy-with-nobody-home: accept then immediately EOF.
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val port = srv.localPort
            val ex = assertThrows(ContainerLaunchException::class.java) {
                Wait.forListeningPort().withStartupTimeout(Duration.ofMillis(400))
                    .waitUntilReady(FakeTarget(mapOf(80 to port)))
            }
            assertTrue(ex.message!!.contains("a listening TCP port"))
            behaveLikeRealServer.set(true)
            Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(2))
                .waitUntilReady(FakeTarget(mapOf(80 to port)))
        } finally { srv.close(); acceptor.join(2000) }
    }

    @Test fun `forHttp matches path and status`() {
        val http = HttpServer.create(InetSocketAddress(0), 0)
        http.createContext("/health") { it.sendResponseHeaders(200, -1); it.close() }
        http.start()
        try {
            Wait.forHttp("/health").forPort(8080).forStatusCode(200)
                .waitUntilReady(FakeTarget(mapOf(8080 to http.address.port)))
        } finally { http.stop(0) }
    }

    @Test fun `forLogMessage polls until pattern seen n times`() {
        val target = FakeTarget()
        Thread { Thread.sleep(300); target.logs = "a\nready\nb\nready\n" }.start()
        Wait.forLogMessage(".*ready.*", times = 2).waitUntilReady(target)
    }

    @Test fun `forLogMessage with times zero succeeds immediately on empty log`() {
        val target = FakeTarget()
        Wait.forLogMessage(".*", times = 0).waitUntilReady(target)
    }

    // Pins current behavior: `all{}` over an empty guest-port list is vacuously true, so a
    // target that exposes no ports is "ready" on the very first poll.
    @Test fun `forListeningPort with no exposed ports is vacuously ready immediately`() {
        val target = FakeTarget(ports = emptyMap())
        assertEquals(emptyList<Int>(), target.exposedGuestPorts)
        Wait.forListeningPort().waitUntilReady(target)   // must not block/timeout
    }

    @Test fun `waitUntilReady on a never-ready target times out within a bounded window and probes at least once`() {
        var probes = 0
        val target = object : WaitTarget {
            override val host = "127.0.0.1"
            override fun mappedPort(guestPort: Int): Int { probes++; return guestPort } // never a real listener
            override val exposedGuestPorts = listOf(1)
            override fun currentLogs() = ""
            override fun describe() = "never-ready-target"
        }
        val start = System.nanoTime()
        assertThrows(ContainerLaunchException::class.java) {
            // Sub-poll-interval timeout: shorter than the strategy's own poll interval, so the
            // "at least one probe before deadline" assertion below is the actual point of this
            // test — a zero-probe implementation (e.g. checking the deadline before ever calling
            // isReady) would fail this, whereas a timeout of several poll intervals would not
            // discriminate between the two.
            Wait.forListeningPort().withStartupTimeout(Duration.ofMillis(1)).waitUntilReady(target)
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - start)
        assertTrue(elapsed < Duration.ofSeconds(2), "should time out promptly, took $elapsed")
        assertTrue(probes >= 1, "must perform at least one isReady probe even at a short timeout")
    }

    @Test fun `forHttp falls back to the target's first exposed port when forPort is never called`() {
        val http = HttpServer.create(InetSocketAddress(0), 0)
        http.createContext("/health") { it.sendResponseHeaders(200, -1); it.close() }
        http.start()
        try {
            // forPort() deliberately not called: must probe exposedGuestPorts.first().
            Wait.forHttp("/health").waitUntilReady(FakeTarget(mapOf(8080 to http.address.port)))
        } finally { http.stop(0) }
    }

    @Test fun `forHttp timeout message includes the log tail and the what() description`() {
        val target = FakeTarget(mapOf(8080 to ServerSocket(0).let { s -> s.close(); s.localPort }), logs = "http boot log")
        val ex = assertThrows(ContainerLaunchException::class.java) {
            Wait.forHttp("/health").forPort(8080).forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(1)).waitUntilReady(target)
        }
        assertTrue(ex.message!!.contains("http boot log"), "must include the tail: ${ex.message}")
        assertTrue(ex.message!!.contains("HTTP 200 on /health"), "must include what(): ${ex.message}")
    }

    @Test fun `forLogMessage timeout message includes the log tail and the what() description`() {
        val target = FakeTarget(logs = "line one\nline two")
        val ex = assertThrows(ContainerLaunchException::class.java) {
            Wait.forLogMessage(".*ready.*", times = 3).withStartupTimeout(Duration.ofSeconds(1)).waitUntilReady(target)
        }
        assertTrue(ex.message!!.contains("line one"), "must include the tail: ${ex.message}")
        assertTrue(ex.message!!.contains("line two"), "must include the tail: ${ex.message}")
        assertTrue(ex.message!!.contains("log line matching '.*ready.*' x3"), "must include what(): ${ex.message}")
    }

    // Pins current behavior of the forLogMessage match-counting logic (a line satisfying both
    // matches() and containsMatchIn() must still count once, not twice).
    @Test fun `forLogMessage counts a line matching both matches() and containsMatchIn() only once`() {
        // ".*ready.*" both `matches` the whole line "ready" AND `containsMatchIn` it — must not
        // double-count. Two matching lines with times=2 must succeed; times=3 must still time out.
        val target = FakeTarget(logs = "ready\nnoise\nready\n")
        Wait.forLogMessage(".*ready.*", times = 2).waitUntilReady(target)   // exactly 2, not miscounted as 4
        val ex = assertThrows(ContainerLaunchException::class.java) {
            Wait.forLogMessage(".*ready.*", times = 3).withStartupTimeout(Duration.ofSeconds(1)).waitUntilReady(target)
        }
        assertTrue(ex.message!!.contains("x3"))
    }
}
