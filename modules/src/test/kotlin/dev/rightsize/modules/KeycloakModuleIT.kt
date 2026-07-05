package dev.rightsize.modules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Real OIDC-discovery round-trip for [KeycloakContainer]. */
@Tag("sandbox-it")
class KeycloakModuleIT {
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test fun `OIDC discovery document asserts the issuer`() {
        val keycloak = KeycloakContainer(); keycloak.start()
        try {
            val resp = http.send(
                HttpRequest.newBuilder(
                    URI("${keycloak.authServerUrl}/realms/master/.well-known/openid-configuration"),
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode(), "discovery document fetch failed: ${resp.body()}")
            assertTrue(
                resp.body().contains("\"issuer\":\"${keycloak.authServerUrl}/realms/master\""),
                "discovery document did not carry the expected issuer: ${resp.body()}",
            )
        } finally { keycloak.stop() }
    }
}
