package dev.rightsize.core.image

import dev.rightsize.core.IncompatibleImageException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerImageNameTest {
    // --- parse: plain names ---

    @Test fun `parses a plain repository with no registry, tag, or digest`() {
        val n = DockerImageName.parse("postgres")
        assertNull(n.registry)
        assertEquals("postgres", n.repository)
        assertNull(n.tag)
        assertNull(n.digest)
        assertEquals("postgres", n.toString())
    }

    @Test fun `treats a bare localhost first segment as a registry`() {
        // Docker special-cases `localhost`: it is a registry host even though it carries neither a
        // dot nor a port, so this must not parse as the repository `localhost/myimage`.
        val n = DockerImageName.parse("localhost/myimage:16")
        assertEquals("localhost", n.registry)
        assertEquals("myimage", n.repository)
        assertEquals("16", n.tag)
        assertEquals("localhost/myimage:16", n.toString())
    }

    @Test fun `a bare localhost registry does not defeat a compatibility check`() {
        DockerImageName.parse("localhost/postgres:16").assertCompatibleWith("postgres")
    }

    @Test fun `parses a plain repository with a tag`() {
        val n = DockerImageName.parse("postgres:16")
        assertNull(n.registry)
        assertEquals("postgres", n.repository)
        assertEquals("16", n.tag)
        assertEquals("postgres:16", n.toString())
    }

    // --- parse: namespaced names (no dot/colon in the first segment stay whole) ---

    @Test fun `a namespaced repository with no dot or colon in its first segment stays whole`() {
        val n = DockerImageName.parse("floci/floci")
        assertNull(n.registry)
        assertEquals("floci/floci", n.repository)
    }

    @Test fun `a namespaced repository with a tag stays whole and keeps the tag separate`() {
        val n = DockerImageName.parse("qdrant/qdrant:v1.18.3")
        assertNull(n.registry)
        assertEquals("qdrant/qdrant", n.repository)
        assertEquals("v1.18.3", n.tag)
        assertEquals("qdrant/qdrant:v1.18.3", n.toString())
    }

    // --- parse: registry-qualified names ---

    @Test fun `a first segment containing a dot is stripped off as the registry`() {
        val n = DockerImageName.parse("quay.io/keycloak/keycloak")
        assertEquals("quay.io", n.registry)
        assertEquals("keycloak/keycloak", n.repository)
        assertNull(n.tag)
    }

    @Test fun `a registry-qualified name with a tag keeps registry, repository, and tag distinct`() {
        val n = DockerImageName.parse("quay.io/keycloak/keycloak:26.4")
        assertEquals("quay.io", n.registry)
        assertEquals("keycloak/keycloak", n.repository)
        assertEquals("26.4", n.tag)
        assertEquals("quay.io/keycloak/keycloak:26.4", n.toString())
    }

    @Test fun `a first segment containing a colon (registry port) is stripped off as the registry`() {
        val n = DockerImageName.parse("localhost:5000/myrepo:16")
        assertEquals("localhost:5000", n.registry)
        assertEquals("myrepo", n.repository)
        assertEquals("16", n.tag)
    }

    @Test fun `a registry port is not mistaken for a tag when there is no tag`() {
        val n = DockerImageName.parse("localhost:5000/myrepo")
        assertEquals("localhost:5000", n.registry)
        assertEquals("myrepo", n.repository)
        assertNull(n.tag)
    }

    // --- parse: digests ---

    @Test fun `parses a digest with no tag`() {
        val n = DockerImageName.parse("postgres@sha256:abcd1234")
        assertEquals("postgres", n.repository)
        assertNull(n.tag)
        assertEquals("sha256:abcd1234", n.digest)
        assertEquals("postgres@sha256:abcd1234", n.toString())
    }

    @Test fun `parses a tag and a digest together`() {
        val n = DockerImageName.parse("postgres:16@sha256:abcd1234")
        assertEquals("postgres", n.repository)
        assertEquals("16", n.tag)
        assertEquals("sha256:abcd1234", n.digest)
        assertEquals("postgres:16@sha256:abcd1234", n.toString())
    }

    @Test fun `parses registry, repository, tag, and digest all together`() {
        val n = DockerImageName.parse("quay.io/keycloak/keycloak:26.4@sha256:abcd1234")
        assertEquals("quay.io", n.registry)
        assertEquals("keycloak/keycloak", n.repository)
        assertEquals("26.4", n.tag)
        assertEquals("sha256:abcd1234", n.digest)
        assertEquals("quay.io/keycloak/keycloak:26.4@sha256:abcd1234", n.toString())
    }

    @Test fun `blank image name is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DockerImageName.parse("  ") }
    }

    // --- compatibility check ---

    @Test fun `assertCompatibleWith passes when the repository matches exactly`() {
        DockerImageName.parse("postgres:16").assertCompatibleWith("postgres") // no throw
    }

    @Test fun `assertCompatibleWith passes for a namespaced repository match`() {
        DockerImageName.parse("qdrant/qdrant:v1.18.3").assertCompatibleWith("qdrant/qdrant") // no throw
    }

    @Test fun `assertCompatibleWith strips only the registry host, not further path segments`() {
        val n = DockerImageName.parse("quay.io/keycloak/keycloak:26.4")
        n.assertCompatibleWith("keycloak/keycloak") // no throw
        assertThrows(IncompatibleImageException::class.java) { n.assertCompatibleWith("keycloak") }
    }

    @Test fun `assertCompatibleWith fails on a mismatched repository, naming both repositories`() {
        val e = assertThrows(IncompatibleImageException::class.java) {
            DockerImageName.parse("mysql:8").assertCompatibleWith("postgres")
        }
        assertTrue(e.message!!.contains("mysql"))
        assertTrue(e.message!!.contains("postgres"))
    }

    @Test fun `assertCompatibleWith fails on a registry-qualified mismatch`() {
        assertThrows(IncompatibleImageException::class.java) {
            DockerImageName.parse("quay.io/keycloak/keycloak").assertCompatibleWith("qdrant/qdrant")
        }
    }

    // --- asCompatibleSubstituteFor escape hatch ---

    @Test fun `asCompatibleSubstituteFor makes an otherwise-mismatched image pass the check`() {
        val n = DockerImageName.parse("mycorp/pg-hardened:16").asCompatibleSubstituteFor("postgres")
        n.assertCompatibleWith("postgres") // no throw
    }

    @Test fun `asCompatibleSubstituteFor does not change the rendered image reference`() {
        val n = DockerImageName.parse("mycorp/pg-hardened:16").asCompatibleSubstituteFor("postgres")
        assertEquals("mycorp/pg-hardened:16", n.toString())
        assertEquals("mycorp/pg-hardened", n.repository)
    }

    @Test fun `asCompatibleSubstituteFor still fails against a repository other than the declared one`() {
        val n = DockerImageName.parse("mycorp/pg-hardened:16").asCompatibleSubstituteFor("postgres")
        assertThrows(IncompatibleImageException::class.java) { n.assertCompatibleWith("mysql") }
    }
}
