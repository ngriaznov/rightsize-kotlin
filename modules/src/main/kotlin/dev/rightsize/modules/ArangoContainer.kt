package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/** A single-node ArangoDB container. Auth is disabled by default; see [withRootPassword] to enable it. */
class ArangoContainer(image: String = "arangodb:3.11") : GenericContainer<ArangoContainer>(image) {
    init {
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
}
