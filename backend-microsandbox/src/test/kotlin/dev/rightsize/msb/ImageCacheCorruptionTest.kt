package dev.rightsize.msb

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ImageCacheCorruptionTest {

    @Test fun `matches the captured msb error verbatim`() {
        // Captured verbatim from a real msb 0.6.3 binary, reproduced by racing concurrent
        // `msb run` of images sharing a base layer against one fresh cache (see the
        // classifier's doc for the full repro).
        val output = "   ✗ Pulling      floci/floci-gcp:0.4.0\n" +
            "error: image error: cache error at /home/runner/.microsandbox/cache/layers/" +
            "sha256_2a9a84f53fe64d76a54296ab37a4664aacef9f848d4aa6ad7efd84b135a351c6.tar.gz: " +
            "No such file or directory (os error 2)\n"
        assertTrue(isImageCacheCorruption(output))
    }

    @Test fun `matches regardless of which image, digest, or host path`() {
        // Path, digest, and image name all vary per host/run — the classifier must match
        // on the stable parts of msb's wording only.
        assertTrue(isImageCacheCorruption(
            "error: image error: cache error at /tmp/msb-repro/cache/layers/" +
                "sha256_c01d7b7a3f78972c12a4244ffb10257694b9d989c40172ab6184de42b967ab85.tar.gz: " +
                "No such file or directory (os error 2)"))
        assertTrue(isImageCacheCorruption(
            "error: cache error at C:\\Users\\runner\\.microsandbox\\cache\\layers\\" +
                "sha256_deadbeef.tar.gz: No such file or directory (os error 2)"))
    }

    @Test fun `negative cases do not match`() {
        assertFalse(isImageCacheCorruption("panic: index out of bounds"))
        assertFalse(isImageCacheCorruption(""))
        assertFalse(isImageCacheCorruption("error: image not found: floci/floci-az:0.8.0"))
        // A generic "No such file" with no cache-error framing must not false-positive
        // (e.g. a workload's own stderr complaining about a missing file it expected).
        assertFalse(isImageCacheCorruption("sh: /app/config.yaml: No such file or directory"))
        // A cache error about something other than a missing file (e.g. a permissions
        // problem) must not be classified as this specific corruption signature.
        assertFalse(isImageCacheCorruption(
            "error: cache error at /tmp/x/layers/sha256_abc.tar.gz: Permission denied (os error 13)"))
    }
}
