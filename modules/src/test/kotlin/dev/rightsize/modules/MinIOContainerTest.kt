package dev.rightsize.modules

import dev.rightsize.core.IncompatibleImageException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Backend-free coverage for [MinIOContainer]'s image defaulting/compatibility — everything this
 * constructor does runs before any backend call (`image.assertCompatibleWith` in `init`), so
 * these run under the plain `test` task, no sandbox runtime required (unlike
 * [ValkeyMinIOCassandraIT], which needs a live backend to actually boot the container).
 */
class MinIOContainerTest {

    @Test fun `default image is the quay-io mirror and is accepted`() {
        // Docker Hub's minio/minio repository was removed upstream ("repository does not
        // exist"); the floating default moved to MinIO's maintained mirror. A mismatched
        // repository would throw IncompatibleImageException here, so simply constructing this
        // without one proves the default is still accepted by EXPECTED_REPOSITORY.
        assertDoesNotThrow { MinIOContainer() }
    }

    @Test fun `an explicit quay-io override with a pinned tag is accepted`() {
        assertDoesNotThrow {
            MinIOContainer("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
        }
    }

    @Test fun `an old-style Docker Hub override is still accepted for repository compatibility`() {
        // minio/minio is gone from Docker Hub as a *pullable* image, but the compatibility check
        // itself is registry-agnostic (DockerImageName strips the registry host before
        // comparing) — a caller pointing at a cached image or a private Docker Hub proxy must
        // not be rejected just because the registry differs from the new default's.
        assertDoesNotThrow {
            MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")
        }
    }

    @Test fun `a genuinely different repository is still rejected`() {
        assertThrows(IncompatibleImageException::class.java) {
            MinIOContainer("quay.io/minio/mc:latest")
        }
    }
}
