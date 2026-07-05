package dev.rightsize.docker

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression for the httpclient5-5.4 unix-socket misrouting bug.
 *
 * docker-java's `docker-java-transport-httpclient5` transport, when combined with
 * httpclient5 >= 5.4 (as managed by e.g. the Spring Boot BOM on a consumer classpath),
 * silently dials TCP `localhost:2375` instead of the `unix://` socket, so
 * `DockerBackendProvider.isSupported()` reports the daemon unreachable. We switched the
 * backend to the `docker-java-transport-zerodep` transport, which bundles its own
 * JNA-based unix-socket client and pulls in no httpclient5.
 *
 * This test locks in that choice: the httpclient5 transport must NOT be on the classpath
 * (so it can never be re-selected implicitly), and the zerodep transport must be.
 */
class DockerTransportRegressionTest {

    @Test
    fun `httpclient5 transport is absent from the docker backend classpath`() {
        val loaded = runCatching {
            Class.forName("com.github.dockerjava.httpclient5.ApacheDockerHttpClient")
        }.isSuccess
        assertFalse(
            loaded,
            "docker-java-transport-httpclient5 is back on the classpath; it misroutes " +
                "unix:// sockets to TCP localhost:2375 under httpclient5 5.4. Keep the zerodep transport.",
        )
    }

    @Test
    fun `zerodep transport is present and used by the docker client`() {
        val zerodep = runCatching {
            Class.forName("com.github.dockerjava.zerodep.ZerodepDockerHttpClient")
        }.isSuccess
        assertTrue(zerodep, "docker-java-transport-zerodep must be on the classpath")
    }
}
