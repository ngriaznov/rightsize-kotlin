package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.image.DockerImageName
import dev.rightsize.core.wait.Wait

/**
 * A Spring Cloud Config Server container, ready-checked via its actuator health endpoint.
 *
 * Already defaulted to `hyness/spring-cloud-config-server:latest` before this library's other
 * modules adopted floating references, so this default is unchanged — no version was pinned here
 * to move away from.
 */
class SpringCloudConfigContainer(image: DockerImageName) :
    GenericContainer<SpringCloudConfigContainer>(image.toString()) {
    /** Defaults to `hyness/spring-cloud-config-server:latest`, this image's floating reference. */
    constructor(image: String = "hyness/spring-cloud-config-server:latest") : this(DockerImageName.parse(image))

    init {
        image.assertCompatibleWith(EXPECTED_REPOSITORY)
        withExposedPorts(8888)
        waitingFor(Wait.forHttp("/actuator/health").forPort(8888))
        // Paketo's memory calculator sizes this JVM image's fixed regions (~688M) above
        // microsandbox's default microVM RAM (~443M); this is the reason withMemoryLimit exists.
        withMemoryLimit(1024)
    }
    /** The config server's base URI for the running container. */
    val uri: String get() = "http://$host:${getMappedPort(8888)}"

    private companion object {
        const val EXPECTED_REPOSITORY = "hyness/spring-cloud-config-server"
    }
}
