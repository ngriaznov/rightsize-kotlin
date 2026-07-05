package dev.rightsize.core

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
}
