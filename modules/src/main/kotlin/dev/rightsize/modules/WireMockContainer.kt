package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/**
 * A WireMock server container for stubbing HTTP dependencies in integration tests.
 *
 * ### Readiness — verified against a real 3.13.2 boot
 *
 * WireMock 3.x ships a dedicated `/__admin/health` endpoint (unlike some older 2.x builds, where
 * `/__admin/mappings` was the only reliable 200). Verified directly against a real container:
 *
 * ```
 * $ curl http://127.0.0.1:<port>/__admin/health
 * {"status":"healthy","message":"Wiremock is ok","version":"3.13.2","uptimeInSeconds":9,...}
 * ```
 *
 * so this module waits on that endpoint rather than falling back to `/__admin/mappings`.
 *
 * No control characters were found in the image's baked env (checked via `docker inspect`),
 * and no `withMemoryLimit` override was needed — the JVM boots comfortably
 * on msb's default ~450M microVM RAM (observed ~5.5s IT round-trip on msb; a small embedded-Jetty
 * app, not a JVM-heavy cluster like Pinot — no memory-ladder escalation was needed).
 */
class WireMockContainer(image: String = "wiremock/wiremock:3.13.2") :
    GenericContainer<WireMockContainer>(image) {
    init {
        withExposedPorts(PORT)
        waitingFor(Wait.forHttp("/__admin/health").forPort(PORT))
    }

    /** The stub server's base URI (mount stubbed paths under this). */
    val baseUrl: String get() = "http://$host:${getMappedPort(PORT)}"
    /** The `/__admin` management API's base URI (stub CRUD, request journal, health). */
    val adminUrl: String get() = "http://$host:${getMappedPort(PORT)}/__admin"

    private companion object {
        const val PORT = 8080
    }
}
