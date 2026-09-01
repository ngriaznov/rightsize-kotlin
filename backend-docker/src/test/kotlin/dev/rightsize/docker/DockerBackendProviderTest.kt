package dev.rightsize.docker

import com.github.dockerjava.core.DefaultDockerClientConfig
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.concurrent.thread

/**
 * [DockerBackendProvider.isSupported] no longer just checks the daemon is reachable
 * (`pingCmd()`) — it requires the daemon to report a Linux OS (`GET /version`'s `Os` field,
 * `Version.operatingSystem` in docker-java). That distinction is invisible to a plain ping: a
 * Windows-containers dockerd — the one GitHub's Windows runners already have up for their own
 * CI needs — answers pings fine but can't run this backend's Linux images.
 *
 * The internal `isSupported(probeOs: () -> String?)` overload is the seam: it takes the same
 * `runCatching`-to-`false` path the real zero-arg [DockerBackendProvider.isSupported] does, but
 * lets these tests substitute the docker-java call with a plain lambda instead of a live daemon.
 *
 * The internal `daemonOs(cfg: DockerClientConfig)` overload is a second seam: it runs the real
 * client-build-and-query path (real timeouts, real docker-java exceptions) against whatever
 * `dockerHost` the given config carries, without touching `DOCKER_HOST`/context resolution.
 */
class DockerBackendProviderTest {

    @Test fun `a daemon reporting linux is supported`() {
        assertTrue(DockerBackendProvider().isSupported { "linux" })
    }

    @Test fun `a daemon reporting windows is not supported`() {
        assertFalse(DockerBackendProvider().isSupported { "windows" })
    }

    @Test fun `a probe failure or timeout is not supported, never an exception`() {
        assertFalse(
            DockerBackendProvider().isSupported { throw SocketTimeoutException("connect timed out") },
        )
    }

    @Test fun `a daemon that accepts the connection but never answers is not supported, within budget`() {
        ServerSocket(0).use { server ->
            // Accept the TCP connection and then hold it open silently -- never write a byte
            // back. This is the class of stall a bare "is the socket reachable" check can't see:
            // connect succeeds, so only a bounded *response* timeout can save isSupported() from
            // hanging on it (the same class of stall the brief calls out: an overloaded engine,
            // a stale-but-reachable endpoint, a firewalled port that silently drops packets).
            val acceptedSocket = thread(isDaemon = true) {
                runCatching { server.accept() }
            }
            val cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://127.0.0.1:${server.localPort}")
                .build()

            // Generous relative to the real (short) probe budget, but far below "hangs forever":
            // if the client-side timeout regresses, this fails loudly instead of wedging the
            // whole test run.
            assertTimeoutPreemptively(Duration.ofSeconds(15)) {
                assertFalse(DockerBackendProvider().isSupported { DockerBackendProvider().daemonOs(cfg) })
            }
            acceptedSocket.join(1_000)
        }
    }
}
