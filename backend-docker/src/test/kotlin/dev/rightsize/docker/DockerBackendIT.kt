package dev.rightsize.docker

import dev.rightsize.contract.BackendContractTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotEquals

class DockerBackendIT : BackendContractTest() {
    companion object {
        @JvmStatic
        @BeforeAll
        fun requireDocker() {
            assumeTrue(DockerBackendProvider().isSupported(), "docker socket not reachable")
            assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("microsandbox", true) != true)
        }
    }

    /**
     * Regression for the init-time sweep's network-cleanup step: [DockerBackend.removeNetwork]
     * must resolve a network by NAME against the daemon, not only its own [DockerBackend]
     * instance's in-memory cache — the sweep calls it against a *different*, dead process's
     * ledger entry, so there is never a cache hit to rely on. Uses three separate
     * [DockerBackend] instances (each with its own empty [DockerBackend.ensureNetworkGetId]
     * cache) to simulate three different processes sharing one daemon.
     */
    @Test fun `removeNetwork removes a network created by a different DockerBackend instance`() {
        val creator = DockerBackend()
        val networkId = "rz-net-crossproc-${System.nanoTime().toString().takeLast(8)}"
        val originalId = creator.ensureNetworkGetId(networkId)
        try {
            // Simulates the reaper's sweep: a fresh instance, standing in for the sweeping
            // process, removes a network it never created and has no cache entry for.
            DockerBackend().removeNetwork(networkId)

            // Proof it is actually gone at the daemon, not merely uncached here: a third,
            // equally fresh instance re-resolving the same name must create a brand-new
            // network (a different id) rather than finding the old one still registered.
            val afterId = DockerBackend().ensureNetworkGetId(networkId)
            assertNotEquals(originalId, afterId,
                "network must be removed from the daemon, not merely dropped from one instance's cache")
        } finally {
            runCatching { DockerBackend().removeNetwork(networkId) }
        }
    }
}
