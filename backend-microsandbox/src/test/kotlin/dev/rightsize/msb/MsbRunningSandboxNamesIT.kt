package dev.rightsize.msb

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * `runningSandboxNames()` parses `msb ls --format json`; this is the only guard against the
 * real CLI's output shape short of a live msb instance. Untestable in unit form because the
 * JSON shape is coupled to the installed msb version — pinned here against the real CLI.
 */
@Tag("sandbox-it")
class MsbRunningSandboxNamesIT {
    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            Assumptions.assumeTrue(MsbBackendProvider().isSupported())
            Assumptions.assumeTrue(System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }

    @Test fun `a started sandbox appears in runningSandboxNames`() {
        val backend = MsbCliBackend(MsbProvisioner.ensureInstalled())
        val spec = ContainerSpec(
            name = "rz-runningnames-${System.nanoTime().toString().takeLast(8)}",
            image = "alpine:3.19",
            command = listOf("sleep", "120"),
            runId = "runningnames-it",
        )
        val handle = backend.create(spec)
        backend.start(handle)   // blocks until Running per runningSandboxNames() itself (see MsbCliBackend.start)
        try {
            val names = backend.runningSandboxNames()
            assertTrue(names.isNotEmpty(), "runningSandboxNames() parsed no Running entries from `msb ls --format json`")
            assertTrue(spec.name in names, "expected '${spec.name}' among parsed names: $names")
        } finally {
            backend.stop(handle)
            backend.remove(handle)
        }
    }
}
