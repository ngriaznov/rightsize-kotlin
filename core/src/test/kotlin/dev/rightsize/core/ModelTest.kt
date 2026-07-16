package dev.rightsize.core

import dev.rightsize.core.checkpoint.InvalidCheckpointNameException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ModelTest {
    @Test fun `UnsupportedByBackendException without a remedy renders the base sentence only`() {
        val e = UnsupportedByBackendException("network alias 'bad'", "microsandbox")
        assertEquals("Feature 'network alias 'bad'' is not supported by the 'microsandbox' backend", e.message)
    }

    @Test fun `UnsupportedByBackendException with a remedy appends it after an em-dash`() {
        val e = UnsupportedByBackendException(
            "network links (no nc/busybox in consumer image 'X')", "microsandbox",
            remedy = "run this test with RIGHTSIZE_BACKEND=docker instead")
        assertEquals(
            "Feature 'network links (no nc/busybox in consumer image 'X')' is not supported by " +
                "the 'microsandbox' backend — run this test with RIGHTSIZE_BACKEND=docker instead",
            e.message)
    }

    // --- Checkpoint.find/remove must validate name before turning it into a registry file path
    // (C2) — before either Backends.active() or CacheDir.resolve() runs, so an invalid name never
    // resolves an active backend or touches any file. core's test classpath has no BackendProvider
    // (see BackendsTest), so if validation didn't run first, Backends.active() would throw a
    // DIFFERENT exception (ServiceLoader finds no providers) instead of the typed name error —
    // these tests would fail with the wrong exception type if the ordering regressed. ---

    @Test fun `find validates the checkpoint name before touching Backends or CacheDir`() {
        val e = assertThrows(InvalidCheckpointNameException::class.java) { Checkpoint.find("../secret") }
        assertTrue(e.message!!.contains("../secret"))
    }

    @Test fun `remove validates the checkpoint name before touching Backends or CacheDir`() {
        val e = assertThrows(InvalidCheckpointNameException::class.java) { Checkpoint.remove("../secret") }
        assertTrue(e.message!!.contains("../secret"))
    }
}
