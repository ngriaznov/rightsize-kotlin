package dev.rightsize.docker

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/** Pure/no-daemon-required unit coverage — [DockerBackend]'s `client` is lazy, so a plain
 * `val` like `watchdogCommands` never touches it. Everything that needs a real daemon is
 * exercised by [DockerBackendIT]/the contract suite instead. */
class DockerBackendTest {
    @Test fun `watchdogCommands is docker rm -f, no separate stop step, docker network rm`() {
        val commands = DockerBackend().watchdogCommands
        assertEquals(emptyList<String>(), commands.sandboxStop)
        assertEquals(listOf("docker", "rm", "-f"), commands.sandboxRemove)
        assertEquals(listOf("docker", "network", "rm"), commands.networkRemove)
    }

    @Test fun `labelsFor a normal spec applies the run-id label, not the reuse label`() {
        val spec = ContainerSpec(name = "rz-abcd1234-1", image = "busybox", runId = "abcd1234")
        val labels = DockerBackend().labelsFor(spec)
        assertEquals(mapOf("dev.rightsize.runId" to "abcd1234"), labels)
    }

    @Test fun `labelsFor a keepAlive spec applies the reuse label instead of the run-id label`() {
        val spec = ContainerSpec(
            name = "rz-reuse-0123456789ab", image = "busybox", runId = "abcd1234", keepAlive = true,
        )
        val labels = DockerBackend().labelsFor(spec)
        assertEquals(mapOf("dev.rightsize.reuse" to "0123456789ab"), labels)
    }

    @Test fun `labelsFor rejects a keepAlive spec whose name doesn't follow the reuse naming shape`() {
        val spec = ContainerSpec(name = "rz-abcd1234-1", image = "busybox", runId = "abcd1234", keepAlive = true)
        assertThrows(IllegalArgumentException::class.java) { DockerBackend().labelsFor(spec) }
    }

    @Test fun `capabilities is not hardware-isolated but does support checkpoint`() {
        val capabilities = DockerBackend().capabilities
        assertFalse(capabilities.hardwareIsolated, "docker containers share the host kernel")
        assertTrue(capabilities.checkpoint)
    }

    @Test fun `repoAndTag splits a rightsize checkpoint imageRef into repository and tag`() {
        val (repo, tag) = DockerBackend().repoAndTag("rightsize/checkpoint:0123456789ab")
        assertEquals("rightsize/checkpoint", repo)
        assertEquals("0123456789ab", tag)
    }

    @Test fun `repoAndTag rejects an imageRef with no colon`() {
        assertThrows(IllegalArgumentException::class.java) { DockerBackend().repoAndTag("rightsize/checkpoint") }
    }
}
