package dev.rightsize.docker

import com.github.dockerjava.core.DefaultDockerClientConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Regression for a real bug caught in code review, reproduced here against a throwaway
 * `~/.docker`-shaped fixture — no live daemon, no real `~/.docker`, nothing booted.
 *
 * Decompiling the pinned docker-java-core 3.4.0 confirms `DefaultDockerClientConfig.Builder
 * .build()` only resolves the active docker CLI context into `dockerHost` `if (this.dockerHost
 * == null)`. [DockerBackend] and [DockerBackendProvider] used to call
 * `.withDockerHost(DockerHost.resolve().toString())` before `.build()` unconditionally — which
 * pre-sets `dockerHost` even when there is no override to apply, silently skipping context
 * resolution on every OS, not just where `DOCKER_HOST` is actually set. That regresses any
 * daemon reached only via context — Colima, OrbStack, Podman, a remote context, or a Docker
 * Desktop install without the `/var/run/docker.sock` compatibility symlink (see
 * `docs/backends.md`'s `DOCKER_HOST` row) — from a working connection to a broken one, even
 * though the literal default happens to coincide with the real socket on a plain "default"
 * context, which is why neither CI nor a bare dev box caught it.
 *
 * This builds a minimal `config.json` + `contexts/meta/<sha256(name)>/meta.json` pair — the
 * exact layout `DockerContextMetaFile` reads — and proves both directions: `build()` resolves
 * the context's real socket when left alone, and forcing `dockerHost` first reproduces the
 * regression, so nobody can silently reintroduce that call into [DockerBackend]/
 * [DockerBackendProvider] without this test catching it.
 */
class DockerContextResolutionTest {
    private val contextSocket = "unix:///tmp/rightsize-context-resolution-test.sock"

    private fun writeContextFixture(dir: File, contextName: String, host: String) {
        dir.resolve("config.json").writeText("""{"currentContext":"$contextName"}""")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(contextName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val metaDir = dir.resolve("contexts/meta/$hash").apply { mkdirs() }
        metaDir.resolve("meta.json").writeText(
            """{"Name":"$contextName","Endpoints":{"docker":{"Host":"$host","SkipTLSVerify":false}}}""",
        )
    }

    @Test
    fun `build resolves the active docker context's real socket when dockerHost is left untouched`(
        @TempDir dir: File,
    ) {
        writeContextFixture(dir, "testctx", contextSocket)
        val cfg = DefaultDockerClientConfig.Builder()
            .withDockerConfig(dir.absolutePath)
            .withDockerContext("testctx")
            .build()
        assertEquals(URI.create(contextSocket), cfg.dockerHost)
    }

    @Test
    fun `forcing dockerHost before build defeats context resolution -- the regression this fix removes`(
        @TempDir dir: File,
    ) {
        writeContextFixture(dir, "testctx", contextSocket)
        val cfg = DefaultDockerClientConfig.Builder()
            .withDockerConfig(dir.absolutePath)
            .withDockerContext("testctx")
            .withDockerHost(DockerHost.UNIX_DEFAULT) // the exact call this PR used to make
            .build()
        // Proves the bug the old code had, not the fix: dockerHost falls back to the hardcoded
        // literal default instead of the context's real socket.
        assertNotEquals(URI.create(contextSocket), cfg.dockerHost)
        assertEquals(URI.create(DockerHost.UNIX_DEFAULT), cfg.dockerHost)
    }
}
