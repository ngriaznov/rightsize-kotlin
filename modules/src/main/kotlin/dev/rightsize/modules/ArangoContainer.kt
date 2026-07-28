package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A single-node ArangoDB container. Auth is disabled by default; see [withRootPassword] to enable it.
 *
 * ### Defaults to `arangodb:latest` — this image's floating reference
 *
 * With no image given, this module tracks upstream's `latest` tag rather than a version this
 * library pins, so the version moves with ArangoDB's own releases instead of this library's
 * release cycle. This module previously pinned `arangodb:3.11`; pass that image explicitly to
 * pin it: `ArangoContainer("arangodb:3.11")`.
 */
class ArangoContainer(image: DockerImageName) : GenericContainer<ArangoContainer>(image.toString()) {
    /** Defaults to `arangodb:latest` — this image's floating reference (see the class doc). */
    constructor(image: String = "arangodb:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(8529)
        withEnv("ARANGO_NO_AUTH", "1")
        waitingFor(Wait.forHttp("/_api/version").forPort(8529).forStatusCode(200))
    }
    /** Enables auth with the given root password, instead of the default no-auth setup. */
    fun withRootPassword(pw: String): ArangoContainer {
        removeEnv("ARANGO_NO_AUTH")
        return withEnv("ARANGO_ROOT_PASSWORD", pw)
    }
    /** The HTTP API endpoint for the running container. */
    val endpoint: String get() = "http://$host:${getMappedPort(8529)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "arangodb"
    }
}
