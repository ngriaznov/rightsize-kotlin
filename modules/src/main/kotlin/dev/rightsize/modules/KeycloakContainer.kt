package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait
import java.time.Duration

/**
 * A single-node Keycloak container in `start-dev` mode (in-memory H2, no external database —
 * fine for tests, never for production).
 *
 * ### Admin bootstrap env — 26.x renamed these, verified against the pinned tag
 *
 * Keycloak 26.x replaced the old `KEYCLOAK_ADMIN`/`KEYCLOAK_ADMIN_PASSWORD` pair with
 * `KC_BOOTSTRAP_ADMIN_USERNAME`/`KC_BOOTSTRAP_ADMIN_PASSWORD`. Verified directly against
 * `quay.io/keycloak/keycloak:26.4` — booting with only the new names produces
 * `KC-SERVICES0077: Created temporary admin user with username admin` in the log; the legacy names
 * are not read by this image. This module sets only the new names.
 *
 * ### Health lives on the MANAGEMENT port (9000), not 8080 — verified, and the path is `/health`
 *
 * Captured verbatim from a real boot: `Listening on: http://0.0.0.0:8080. Management interface
 * listening on http://0.0.0.0:9000.` With `KC_HEALTH_ENABLED=true` set, `GET /health` on port
 * **9000** returns `200 OK` (body: literal `OK`); the same path on 8080 404s, and the commonly
 * assumed `/health/ready` sub-path 404s on this tag too (this image's SmallRye Health root
 * aggregate is served bare at `/health`, not `/health/ready`) — pinned to `/health` on 9000
 * accordingly, not `/health/ready` on 8080.
 *
 * ### Memory — Quarkus JVM, needed the ladder
 *
 * Booted under msb's default ~450M microVM RAM, the JVM is `Killed` (OOM) partway through startup
 * (captured: `'java' ... -XX:MaxRAMPercentage=70 ... Killed`) — same Paketo/Quarkus-on-microVM
 * story as [SpringCloudConfigContainer]. Retried with `-m 1024M`: boots clean, `/health` reports
 * `200` well within the startup timeout. `withMemoryLimit(1024)` is this module's default.
 *
 * No control characters were found in the image's baked env (checked via `docker
 * inspect`), so no env override is needed here.
 */
class KeycloakContainer(image: String = "quay.io/keycloak/keycloak:26.4") :
    GenericContainer<KeycloakContainer>(image) {
    private var adminUsernameState = "admin"
    private var adminPasswordState = "admin"

    init {
        withExposedPorts(HTTP_PORT, MANAGEMENT_PORT)
        withCommand("start-dev")
        withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", adminUsernameState)
        withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", adminPasswordState)
        withEnv("KC_HEALTH_ENABLED", "true")
        // Quarkus JVM — see the class doc for the measured OOM-at-default / clean-at-1024 story.
        withMemoryLimit(1024)
        // Health is on the MANAGEMENT port, not HTTP — see the class doc for the captured boot line.
        waitingFor(Wait.forHttp("/health").forPort(MANAGEMENT_PORT).withStartupTimeout(Duration.ofSeconds(180)))
    }

    /** Overrides `KC_BOOTSTRAP_ADMIN_USERNAME` (default `admin`). */
    fun withAdminUsername(username: String): KeycloakContainer {
        adminUsernameState = username
        return withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", username)
    }

    /** Overrides `KC_BOOTSTRAP_ADMIN_PASSWORD` (default `admin`). */
    fun withAdminPassword(password: String): KeycloakContainer {
        adminPasswordState = password
        return withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", password)
    }

    /** The configured bootstrap admin username (default `admin`). */
    val adminUsername: String get() = adminUsernameState
    /** The configured bootstrap admin password (default `admin`). */
    val adminPassword: String get() = adminPasswordState

    /** The auth server's base URI (the HTTP port — realm/OIDC endpoints live under this). */
    val authServerUrl: String get() = "http://$host:${getMappedPort(HTTP_PORT)}"
    /** The management interface's base URI (health/metrics — port 9000, not [authServerUrl]'s port). */
    val managementUrl: String get() = "http://$host:${getMappedPort(MANAGEMENT_PORT)}"

    private companion object {
        const val HTTP_PORT = 8080
        const val MANAGEMENT_PORT = 9000
    }
}
