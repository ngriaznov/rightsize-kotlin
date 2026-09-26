package dev.rightsize.modules

import dev.rightsize.core.IncompatibleImageException
import dev.rightsize.core.image.DockerImageName
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Backend-free coverage for [MinIOContainer]'s image defaulting/compatibility — everything this
 * constructor does runs before any backend call (`assertCompatibleWith` in `init`), so these run
 * under the plain `test` task, no sandbox runtime required (unlike [ValkeyMinIOCassandraIT], which
 * needs a live backend to actually boot the container).
 */
class MinIOContainerTest {

    @Test fun `default image is pgsty-minio and is accepted`() {
        // Neither Docker Hub's minio/minio nor quay.io/minio/minio still serve anonymous pulls;
        // the floating default moved to pgsty/minio, Pigsty's community build (the constructor's
        // own default parameter, "pgsty/minio:latest", is what's passed here). A mismatched
        // repository would throw IncompatibleImageException, so simply constructing this without
        // one proves the default is accepted by the module's pgsty/minio special case.
        assertDoesNotThrow { MinIOContainer() }
        assertDoesNotThrow { MinIOContainer("pgsty/minio:latest") }
    }

    @Test fun `an explicit pgsty-minio override with a pinned tag is accepted with no substitute declaration`() {
        assertDoesNotThrow {
            MinIOContainer("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
        }
    }

    @Test fun `an explicit quay-io override with a pinned tag is still accepted`() {
        assertDoesNotThrow {
            MinIOContainer("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
        }
    }

    @Test fun `an old-style Docker Hub override is still accepted for repository compatibility`() {
        // minio/minio is gone from Docker Hub as a *pullable* image, but the compatibility check
        // itself is registry-agnostic (DockerImageName strips the registry host before
        // comparing) — a caller pointing at a cached image or a private Docker Hub proxy must
        // not be rejected just because the registry differs from the current default's.
        assertDoesNotThrow {
            MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
        }
    }

    @Test fun `a genuinely different repository is still rejected naming minio-minio`() {
        val exception = assertThrows(IncompatibleImageException::class.java) {
            MinIOContainer("quay.io/minio/mc:latest")
        }
        assertTrue(exception.message?.contains("minio/minio") == true)
    }

    @Test fun `an explicit asCompatibleSubstituteFor override still works`() {
        assertDoesNotThrow {
            MinIOContainer(
                DockerImageName.parse("mycorp/minio-hardened:latest").asCompatibleSubstituteFor("minio/minio"),
            )
        }
    }
}
