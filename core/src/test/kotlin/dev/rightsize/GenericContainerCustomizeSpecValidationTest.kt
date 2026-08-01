package dev.rightsize

import dev.rightsize.core.*
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** A wait strategy that is immediately ready — these tests never expect to reach it: every spec
 * under test is rejected before any backend call, let alone a wait. */
private object CustomizeSpecReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** [customizeSpec] hands back a spec with BOTH root-disk fields set, regardless of what the
 * ordinary `withX` builders were asked for — the shape a subclass's hook could produce that the
 * pre-customizeSpec builder-field check can never see. */
private class DiskConflictInjectingContainer(image: String) : GenericContainer<DiskConflictInjectingContainer>(image) {
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec =
        spec.copy(diskLimitMb = 100, tmpfsRootMb = 50)
}

/** [customizeSpec] hands back a spec with `networkId` set — the builder itself never called
 * `withNetwork()`, only `withNetworkDisabled()`, so the pre-customizeSpec check (which only ever
 * sees the builder's own `network` field) has nothing to reject. */
private class NetworkConflictInjectingContainer(image: String) : GenericContainer<NetworkConflictInjectingContainer>(image) {
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec =
        spec.copy(networkId = "rz-net-injected")
}

/** [customizeSpec] hands back a spec whose `tmpfsRootMb` exceeds the container's own
 * `withMemoryLimit` — the builder-time `tmpfsRootMb` field is null throughout, so the
 * pre-customizeSpec check sees nothing to compare against memory. */
private class TmpfsExceedsMemoryInjectingContainer(image: String) : GenericContainer<TmpfsExceedsMemoryInjectingContainer>(image) {
    override fun customizeSpec(spec: ContainerSpec, mapped: (Int) -> Int): ContainerSpec =
        spec.copy(tmpfsRootMb = 999)
}

/**
 * Proves `GenericContainer.start()` re-validates the FINAL spec — the one [customizeSpec]
 * actually produces — not just the builder-set fields captured before that hook ever runs (see
 * `GenericContainer.validateSpecConflicts`'s doc). Without this, a `customizeSpec`-overriding
 * subclass could reach `backend.create`/`MsbCommands.run` with a spec carrying both root-disk
 * fields, `networkDisabled` alongside a network, or a tmpfs root exceeding memory — exactly the
 * conflicts the ordinary builder-time checks exist to reject.
 */
class GenericContainerCustomizeSpecValidationTest {

    @Test fun `customizeSpec producing both root-disk fields throws RootDiskConflictException before any backend call`() {
        val backend = FakeBackend()
        val c = DiskConflictInjectingContainer("alpine:3.19").withBackend(backend).waitingFor(CustomizeSpecReady)
        assertThrows(RootDiskConflictException::class.java) { c.start() }
        assertTrue(backend.created.isEmpty(), "no create call once the post-customizeSpec validation rejects the spec")
    }

    @Test fun `customizeSpec injecting a networkId onto a networkDisabled spec throws NetworkDisabledConflictException`() {
        val backend = FakeBackend()
        val c = NetworkConflictInjectingContainer("alpine:3.19").withBackend(backend).waitingFor(CustomizeSpecReady)
            .withNetworkDisabled()
        assertThrows(NetworkDisabledConflictException::class.java) { c.start() }
        assertTrue(backend.created.isEmpty(), "no create call once the post-customizeSpec validation rejects the spec")
    }

    @Test fun `customizeSpec injecting a tmpfsRootMb exceeding memoryLimitMb throws TmpfsRootExceedsMemoryException`() {
        val backend = FakeBackend()
        val c = TmpfsExceedsMemoryInjectingContainer("alpine:3.19").withBackend(backend).waitingFor(CustomizeSpecReady)
            .withMemoryLimit(128)
        assertThrows(TmpfsRootExceedsMemoryException::class.java) { c.start() }
        assertTrue(backend.created.isEmpty(), "no create call once the post-customizeSpec validation rejects the spec")
    }

    @Test fun `customizeSpec conflict on the reuse-fresh construction site is also rejected before any backend call`(
        @TempDir tmp: Path,
    ) {
        val backend = FakeBackend()
        val c = DiskConflictInjectingContainer("alpine:3.19").withBackend(backend).waitingFor(CustomizeSpecReady)
            .withReuse().withReuseEnvOverride(mapOf("RIGHTSIZE_REUSE" to "true")).withReuseCacheDir(tmp)
        assertThrows(RootDiskConflictException::class.java) { c.start() }
        assertTrue(backend.created.isEmpty(), "no create call on the reuse-fresh path either, once validation rejects the spec")
    }
}
