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

    @Test fun `RootDiskConflictException carries the fixed conflict message`() {
        val e = RootDiskConflictException()
        assertEquals(
            "withDiskLimit() cannot be combined with withTmpfsRoot() — the root disk is either " +
                "size-capped or RAM-backed, not both. Drop one.",
            e.message)
    }

    @Test fun `TmpfsRootExceedsMemoryException reports both values`() {
        val e = TmpfsRootExceedsMemoryException(1024, 512)
        assertEquals(
            "withTmpfsRoot(1024) exceeds withMemoryLimit(512) — a tmpfs root lives in guest " +
                "memory and must fit inside it.",
            e.message)
    }

    @Test fun `NetworkDisabledConflictException carries the fixed conflict message`() {
        val e = NetworkDisabledConflictException()
        assertEquals(
            "withNetworkDisabled() cannot be combined with withNetwork() — a network-disabled " +
                "container cannot join a network. Drop one.",
            e.message)
    }

    @Test fun `TmpfsRootCheckpointException carries the fixed refusal message`() {
        val e = TmpfsRootCheckpointException()
        assertEquals(
            "this container uses a tmpfs root (withTmpfsRoot), which is ephemeral and cannot be " +
                "checkpointed — use withDiskLimit or the default root disk for checkpointable containers.",
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
