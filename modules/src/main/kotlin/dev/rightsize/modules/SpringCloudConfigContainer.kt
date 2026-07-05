package dev.rightsize.modules

import dev.rightsize.GenericContainer
import dev.rightsize.core.wait.Wait

/** A Spring Cloud Config Server container, ready-checked via its actuator health endpoint. */
class SpringCloudConfigContainer(image: String = "hyness/spring-cloud-config-server:latest") :
    GenericContainer<SpringCloudConfigContainer>(image) {
    init {
        withExposedPorts(8888); waitingFor(Wait.forHttp("/actuator/health").forPort(8888))
        // Paketo's memory calculator sizes this JVM image's fixed regions (~688M) above
        // microsandbox's default microVM RAM (~443M); this is the reason withMemoryLimit exists.
        withMemoryLimit(1024)
    }
    /** The config server's base URI for the running container. */
    val uri: String get() = "http://$host:${getMappedPort(8888)}"
}
