package dev.rightsize.msb

import dev.rightsize.GenericContainer
import dev.rightsize.core.Backends
import dev.rightsize.core.CacheDir
import dev.rightsize.core.reuse.ReuseIdentity
import dev.rightsize.core.reuse.ReuseIdentitySpec
import dev.rightsize.core.reuse.ReuseRegistry
import dev.rightsize.core.wait.Wait
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.security.SecureRandom
import java.time.Duration

/**
 * Same-process adoption against a real msb sandbox (see docs/reuse.md): a second, independently
 * constructed [GenericContainer] with an equivalent configuration adopts the first's sandbox
 * instead of creating a new one —
 * proven at the backend level via [MsbCliBackend.runningSandboxNames] never showing more than
 * one entry for this test's reuse name. Requires `RIGHTSIZE_REUSE=true` (set on the
 * `integrationTest` Gradle task — see root build.gradle.kts); `withReuse()` alone is only half
 * of the double opt-in.
 *
 * The reuse identity is keyed off a fresh random hex nonce every run (`RZ_TEST_NONCE`) so a
 * sandbox left running by an earlier, aborted run of this same test can never collide with this
 * run's. `name`/`hash` are derived from that nonce before either container starts, so the outer
 * `finally` below can always find and remove the sandbox/registry entry — including when an
 * assertion fails partway through — and CI never leaks the sandbox.
 */
@Tag("sandbox-it")
class MsbReuseIT {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            Assumptions.assumeTrue(MsbBackendProvider().isSupported())
            Assumptions.assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }

        private val random = SecureRandom()

        /** 12 lowercase hex chars from 6 cryptographically random bytes. */
        private fun randomHex(): String {
            val bytes = ByteArray(6)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    @Test fun `a second equivalent container adopts the first's real msb sandbox`() {
        val backend = Backends.active() as MsbCliBackend
        val nonce = randomHex()
        fun reuseContainer() = GenericContainer("alpine:3.19")
            .withEnv("RZ_TEST_NONCE", nonce)
            .withCommand("sh", "-c", "sleep 120")
            .waitingFor(Wait.forLogMessage(".*", 0).withStartupTimeout(Duration.ofSeconds(30)))
            .withReuse()

        val identity = ReuseIdentitySpec(
            image = "alpine:3.19", env = mapOf("RZ_TEST_NONCE" to nonce), command = listOf("sh", "-c", "sleep 120"),
            exposedPorts = emptyList(), memoryLimitMb = null, copies = emptyList(),
        )
        val hash = ReuseIdentity.hash(identity)
        val name = ReuseIdentity.name(hash)

        val first = reuseContainer()
        try {
            first.start()
            assertTrue(name in backend.runningSandboxNames(),
                "the reuse sandbox must be Running under its minted name '$name'")

            val second = reuseContainer()
            try {
                second.start()
                assertTrue(second.isRunning, "the second instance must adopt, not fail to start")
                assertEquals(1, backend.runningSandboxNames().count { it == name },
                    "adoption must not create a second sandbox under a different name")
            } finally { second.stop() }

            // stop() on a reuse container must leave the real sandbox running.
            assertTrue(name in backend.runningSandboxNames(), "stop() must not remove the reuse sandbox")
        } finally {
            first.stop()
            runCatching { backend.removeByName(name) }
            runCatching { ReuseRegistry(CacheDir.resolve()).delete(hash) }
        }
    }
}
