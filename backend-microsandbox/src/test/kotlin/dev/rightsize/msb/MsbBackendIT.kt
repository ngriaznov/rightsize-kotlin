package dev.rightsize.msb

import dev.rightsize.contract.BackendContractTest
import org.junit.jupiter.api.BeforeAll

class MsbBackendIT : BackendContractTest() {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            org.junit.jupiter.api.Assumptions.assumeTrue(MsbBackendProvider().isSupported())
            // Force this backend regardless of host default:
            org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }
}
