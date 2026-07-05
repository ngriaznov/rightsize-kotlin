# Keycloak

`dev.rightsize.modules.KeycloakContainer` — a single-node Keycloak container in
`start-dev` mode (in-memory H2, no external database — fine for tests, never for
production).

## Defaults

| | |
|---|---|
| Default image | `quay.io/keycloak/keycloak:26.4` |
| Exposed ports | `8080` (HTTP / auth server), `9000` (management — health lives here, see below) |
| Command | `start-dev` |
| Env | `KC_BOOTSTRAP_ADMIN_USERNAME=admin`, `KC_BOOTSTRAP_ADMIN_PASSWORD=admin`, `KC_HEALTH_ENABLED=true` |
| Memory limit | `withMemoryLimit(1024)` — see below |
| Wait strategy | `Wait.forHttp("/health").forPort(9000).withStartupTimeout(Duration.ofSeconds(120))` |

## Helpers

| Member | Returns |
|---|---|
| `authServerUrl: String` | The auth server's base URI (HTTP port) — realm/OIDC endpoints live under this |
| `managementUrl: String` | The management interface's base URI (health/metrics — port 9000, a different port than `authServerUrl`) |
| `adminUsername` / `adminPassword: String` | The configured bootstrap admin credentials (default `admin`/`admin`) |
| `withAdminUsername(username: String): KeycloakContainer` | Overrides `KC_BOOTSTRAP_ADMIN_USERNAME` |
| `withAdminPassword(password: String): KeycloakContainer` | Overrides `KC_BOOTSTRAP_ADMIN_PASSWORD` |

Call the `withX` overrides before `start()`.

## Example

```kotlin
package dev.rightsize.modules

import dev.rightsize.modules.KeycloakContainer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class KeycloakContainerTest {
    private val http = HttpClient.newHttpClient()

    @Test
    fun `OIDC discovery document asserts the issuer`() {
        val keycloak = KeycloakContainer()
        keycloak.start()
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
                "unexpected issuer in discovery document: ${resp.body()}",
            )
        } finally {
            keycloak.stop()
        }
    }
}
```

## Backend notes

**Admin bootstrap env names changed in 26.x — verified against the pinned tag.**
Keycloak 26.x replaced the older `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` pair with
`KC_BOOTSTRAP_ADMIN_USERNAME`/`KC_BOOTSTRAP_ADMIN_PASSWORD`. Verified directly against
`quay.io/keycloak/keycloak:26.4`: booting with only the new names produces
`KC-SERVICES0077: Created temporary admin user with username admin` in the log, and
the legacy names are not read by this image at all. If you're overriding the image to
an older tag, check which pair it actually reads before assuming these overrides
apply.

**Health lives on the management port (9000), not the HTTP port (8080) — and the path
is `/health`, not `/health/ready`.** Captured verbatim from a real boot:

```
Listening on: http://0.0.0.0:8080. Management interface listening on http://0.0.0.0:9000.
```

With `KC_HEALTH_ENABLED=true` set, `GET /health` on port **9000** returns `200 OK`
(literal body `OK`). The same path on 8080 404s, and the commonly assumed
`/health/ready` sub-path also 404s on this tag — this image's SmallRye Health root
aggregate is served bare at `/health`, not under a `/ready` sub-path. This module's
wait strategy is pinned to `/health` on 9000 accordingly.

**Memory needed the same ladder as Spring Cloud Config's Paketo JVM.** Booted under
microsandbox's default ~450 MB microVM RAM, the Quarkus JVM gets OOM-killed partway
through startup (captured: `'java' ... -XX:MaxRAMPercentage=70 ... Killed`) — the same
Paketo/Quarkus-on-microVM story as
[Spring Cloud Config](spring-cloud-config.md#backend-notes). Retried with `-m 1024M`:
boots clean, `/health` reports `200` well within the startup timeout. This module
ships with `withMemoryLimit(1024)` for exactly this reason.
