package dev.rightsize.docker

import dev.rightsize.contract.BackendContractTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll

class DockerBackendIT : BackendContractTest() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireDocker() {
            assumeTrue(DockerBackendProvider().isSupported(), "docker socket not reachable")
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("microsandbox", true) != true)
        }
    }
}
