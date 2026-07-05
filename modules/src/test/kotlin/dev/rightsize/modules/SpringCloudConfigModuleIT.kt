package dev.rightsize.modules

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.net.HttpURLConnection
import java.net.URI
import java.time.Duration

@Tag("sandbox-it")
class SpringCloudConfigModuleIT {
    @Test fun `config server serves actuator health`() {
        val server = SpringCloudConfigContainer()
            // "native" profile serves config from the classpath so no external git repo is required;
            // without it the default git EnvironmentRepository refuses to start ("configure a uri...").
            .withEnv("SPRING_PROFILES_ACTIVE", "native")
            // JVM Spring Boot image is a slow boot on first pull; be generous.
            .apply { waitingFor(dev.rightsize.core.wait.Wait.forHttp("/actuator/health").forPort(8888)
                .withStartupTimeout(Duration.ofSeconds(180))) }
        server.start()
        try {
            val conn = URI("${server.uri}/actuator/health").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 2000; conn.readTimeout = 2000
            assertEquals(200, conn.responseCode)
            assertTrue(conn.inputStream.bufferedReader().readText().contains("UP"))
            conn.disconnect()
        } finally { server.stop() }
    }
}
