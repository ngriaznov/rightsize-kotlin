package dev.rightsize.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

private fun provider(name: String, prio: Int, supported: Boolean) = object : BackendProvider {
    override val name = name
    override val priority = prio
    override fun isSupported() = supported
    override fun unsupportedReason() = "$name not supported on this host"
    override fun create(): SandboxBackend = error("not needed: $name")
}

class BackendsTest {
    @Test fun `picks highest priority supported provider`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, true), provider("microsandbox", 20, true)), null)
        }
        assertTrue(ex.message!!.contains("not needed: microsandbox"))
    }
    @Test fun `env override wins even at lower priority`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, true), provider("microsandbox", 20, true)), "docker")
        }
        assertTrue(ex.message!!.contains("not needed: docker"))
    }
    @Test fun `no supported provider gives every reason`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("microsandbox", 20, false), provider("docker", 10, false)), null)
        }
        assertTrue(ex.message!!.contains("microsandbox not supported"))
        assertTrue(ex.message!!.contains("docker not supported"))
    }
    @Test fun `unknown requested backend lists known names`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, true)), "podman")
        }
        assertTrue(ex.message!!.contains("podman")); assertTrue(ex.message!!.contains("docker"))
    }

    @Test fun `no providers on the classpath names both artifacts, with or without a requested backend`() {
        val noneRequested = assertThrows(IllegalArgumentException::class.java) { Backends.resolve(emptyList(), null) }
        assertTrue(noneRequested.message!!.contains("rightsize-backend-microsandbox"))
        assertTrue(noneRequested.message!!.contains("rightsize-backend-docker"))
        val requested = assertThrows(IllegalArgumentException::class.java) { Backends.resolve(emptyList(), "docker") }
        assertTrue(requested.message!!.contains("rightsize-backend-microsandbox"))
        assertTrue(requested.message!!.contains("rightsize-backend-docker"))
    }

    @Test fun `requested backend that is unsupported throws naming its unsupportedReason`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, false)), "docker")
        }
        assertTrue(ex.message!!.contains("unavailable:"))
        assertTrue(ex.message!!.contains("docker not supported on this host"))   // provider().unsupportedReason()
    }

    @Test fun `requested backend name match is case-insensitive`() {
        val exUpper = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, true)), "DOCKER")
        }
        assertTrue(exUpper.message!!.contains("not needed: docker"))   // matched and reached create()
        val exMixed = assertThrows(IllegalStateException::class.java) {
            Backends.resolve(listOf(provider("docker", 10, true)), "Docker")
        }
        assertTrue(exMixed.message!!.contains("not needed: docker"))
    }
}
