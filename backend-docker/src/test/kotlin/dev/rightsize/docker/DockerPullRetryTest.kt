package dev.rightsize.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.DockerClientException
import com.github.dockerjava.api.exception.DockerException
import com.github.dockerjava.api.exception.InternalServerErrorException
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [DockerBackend.pullWithRetry] against a stub daemon: a loopback HTTP server answering
 * `POST /images/create` from a script, driven through the real docker-java client. That pins which
 * exception docker-java 3.4.0 raises for each failure shape (error object in the progress stream,
 * 5xx, 4xx) instead of assuming it, and needs no real Docker daemon. The pause is a recording
 * lambda, so nothing here sleeps.
 */
class DockerPullRetryTest {
    private val image = "quay.io/keycloak/keycloak:latest"

    private val progress = listOf(
        """{"status":"Pulling from keycloak/keycloak","id":"latest"}""",
        """{"status":"Pulling fs layer","progressDetail":{},"id":"4f4fb700ef54"}""",
        """{"status":"Downloading","progressDetail":{"current":512,"total":2048},"progress":"[==>   ]","id":"4f4fb700ef54"}""",
        """{"status":"Download complete","progressDetail":{},"id":"4f4fb700ef54"}""",
        """{"status":"Digest: sha256:0123456789abcdef"}""",
        """{"status":"Status: Downloaded newer image for quay.io/keycloak/keycloak:latest"}""",
    )

    private val failedPull = listOf(
        """{"status":"Pulling from keycloak/keycloak","id":"latest"}""",
        """{"errorDetail":{"message":"received unexpected HTTP status: 502 Bad Gateway"},"error":"received unexpected HTTP status: 502 Bad Gateway"}""",
    )

    private class Reply(val status: Int, val body: String)

    /** Answers each request with the next scripted [Reply] (the last one repeats) and records the
     * request line of every request it served. */
    private class StubDaemon(private val script: List<Reply>) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        private val served = AtomicInteger()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readAllBytes()
                requests += "${ex.requestMethod} ${URLDecoder.decode(ex.requestURI.toString(), Charsets.UTF_8)}"
                val reply = script[minOf(served.getAndIncrement(), script.lastIndex)]
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(reply.status, 0)
                ex.responseBody.use { it.write(reply.body.toByteArray()) }
            }
            start()
        }

        val port get() = server.address.port

        override fun close() = server.stop(0)
    }

    @TempDir lateinit var dockerConfigDir: Path

    private val pauses = mutableListOf<Duration>()
    private val stubs = mutableListOf<StubDaemon>()

    @AfterEach fun stopStubs() {
        stubs.forEach { it.close() }
    }

    private fun daemonScripted(vararg replies: Reply): StubDaemon =
        StubDaemon(replies.toList()).also { stubs += it }

    private fun clientFor(stub: StubDaemon): DockerClient {
        // An empty docker config dir keeps the client from reading the developer's own
        // ~/.docker/config.json (credential helpers, contexts) when it looks up registry auth.
        val cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
            .withDockerConfig(dockerConfigDir.toString())
            .withDockerHost("tcp://127.0.0.1:${stub.port}")
            .withDockerTlsVerify(false)
            .build()
        return DockerClientImpl.getInstance(
            cfg,
            ZerodepDockerHttpClient.Builder()
                .dockerHost(cfg.dockerHost).sslConfig(cfg.sslConfig)
                .connectionTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(10)).build(),
        )
    }

    private fun ok(lines: List<String>) = Reply(200, lines.joinToString("\n", postfix = "\n"))

    private fun errorReply(status: Int, message: String) = Reply(status, """{"message":"$message"}""")

    /** Runs the pull against [stub]; the pauses it asks for land in [pauses]. */
    private fun pull(stub: StubDaemon) = DockerBackend().pullWithRetry(clientFor(stub), image) { pauses += it }

    private fun assertPullRequests(stub: StubDaemon, count: Int) {
        assertEquals(count, stub.requests.size, "requests: ${stub.requests}")
        stub.requests.forEach {
            assertTrue(it.startsWith("POST ") && "/images/create?" in it && "fromImage=$image" in it, it)
        }
    }

    @Test fun `a normal progress stream is one pull request and no pause`() {
        val stub = daemonScripted(ok(progress))
        pull(stub)
        assertPullRequests(stub, 1)
        assertEquals(emptyList<Duration>(), pauses)
    }

    @Test fun `an error object in the stream once, then success, retries after two seconds and succeeds`() {
        val stub = daemonScripted(ok(failedPull), ok(progress))
        pull(stub)
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
    }

    @Test fun `an error object in the stream twice is exactly two pull requests, then fails naming the image and Docker's message`() {
        val stub = daemonScripted(ok(failedPull))
        val e = assertThrows(IllegalStateException::class.java) { pull(stub) }
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
        val m = e.message!!
        assertTrue(m.startsWith("docker could not pull image '$image'"), m)
        assertTrue("retried once" in m, m)
        assertTrue("received unexpected HTTP status: 502 Bad Gateway" in m, m)
        assertInstanceOf(DockerClientException::class.java, e.cause)
    }

    @Test fun `an errorDetail with no error string twice fails with Docker's message, not null`() {
        val stub = daemonScripted(
            ok(listOf(failedPull.first(), """{"errorDetail":{"message":"toomanyrequests: rate limit"}}""")),
        )
        val e = assertThrows(IllegalStateException::class.java) { pull(stub) }
        assertPullRequests(stub, 2)
        val m = e.message!!
        assertTrue(m.startsWith("docker could not pull image '$image' (retried once)"), m)
        assertTrue("toomanyrequests: rate limit" in m, m)
        assertTrue("null" !in m, m)
    }

    @Test fun `an errorDetail with no error string still fails the pull and is retried`() {
        val stub = daemonScripted(
            ok(listOf(failedPull.first(), """{"errorDetail":{"message":"toomanyrequests: rate limit"}}""")),
            ok(progress),
        )
        pull(stub)
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
    }

    @Test fun `HTTP 500 once, then success, is retried and succeeds`() {
        val stub = daemonScripted(errorReply(500, "registry hiccup"), ok(progress))
        pull(stub)
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
    }

    @Test fun `HTTP 502 once, then success, is retried too`() {
        val stub = daemonScripted(errorReply(502, "bad gateway"), ok(progress))
        pull(stub)
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
    }

    @Test fun `HTTP 500 twice fails after exactly two requests with Docker's message`() {
        val stub = daemonScripted(errorReply(500, "registry hiccup"))
        val e = assertThrows(IllegalStateException::class.java) { pull(stub) }
        assertPullRequests(stub, 2)
        assertEquals(listOf(Duration.ofSeconds(2)), pauses)
        val m = e.message!!
        assertTrue(m.startsWith("docker could not pull image '$image'"), m)
        assertTrue("retried once" in m, m)
        assertTrue("registry hiccup" in m, m)
        assertInstanceOf(InternalServerErrorException::class.java, e.cause)
    }

    @Test fun `HTTP 404 on pull is one request and fails at once, no pause`() {
        val stub = daemonScripted(errorReply(404, "pull access denied, repository does not exist"))
        val e = assertThrows(IllegalStateException::class.java) { pull(stub) }
        assertPullRequests(stub, 1)
        assertEquals(emptyList<Duration>(), pauses)
        val m = e.message!!
        assertTrue(m.startsWith("docker could not pull image '$image'"), m)
        assertTrue("retried once" !in m, m)
        assertTrue("repository does not exist" in m, m)
        assertInstanceOf(NotFoundException::class.java, e.cause)
    }

    @Test fun `other 4xx statuses are not retried either`() {
        for (status in listOf(400, 401, 403, 409)) {
            val stub = daemonScripted(errorReply(status, "denied $status"))
            val e = assertThrows(IllegalStateException::class.java) { pull(stub) }
            assertPullRequests(stub, 1)
            assertTrue("denied $status" in e.message!!, e.message)
            assertEquals(status, (e.cause as DockerException).httpStatus)
        }
        assertEquals(emptyList<Duration>(), pauses)
    }
}
