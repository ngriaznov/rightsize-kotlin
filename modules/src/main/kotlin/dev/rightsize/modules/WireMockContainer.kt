package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A WireMock server container for stubbing HTTP dependencies in integration tests.
 *
 * ### Defaults to `wiremock/wiremock:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with WireMock's own releases instead of this library's
 * release cycle. The facts below were verified against `wiremock/wiremock:3.13.2` specifically —
 * pass that image explicitly to pin it: `WireMockContainer("wiremock/wiremock:3.13.2")`.
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
class WireMockContainer(image: DockerImageName) : GenericContainer<WireMockContainer>(image.toString()) {
    /** Defaults to `wiremock/wiremock:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "wiremock/wiremock:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(PORT)
        waitingFor(Wait.forHttp("/__admin/health").forPort(PORT))
    }

    /** The stub server's base URI (mount stubbed paths under this). */
    val baseUrl: String get() = "http://$host:${getMappedPort(PORT)}"
    /** The `/__admin` management API's base URI (stub CRUD, request journal, health). */
    val adminUrl: String get() = "http://$host:${getMappedPort(PORT)}/__admin"

    private companion object {
        const val PORT = 8080
        const val EXPECTED_REPOSITORY = "wiremock/wiremock"
    }
}
