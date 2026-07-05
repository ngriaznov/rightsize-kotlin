package dev.rightsize.msb

import dev.rightsize.contract.BackendContractTest
import org.junit.jupiter.api.BeforeAll

class MsbBackendIT : BackendContractTest() {
    // Pinned current behavior: msb 0.6.2's `--mount-file ...:ro` marks the guest mount
    // read-only in `mount(8)` output but does not reject in-guest writes. See the base class
    // doc on `readOnlyMountEnforced`. Flag before touching msb mount plumbing in
    // MsbCliBackend/MsbCommands.
    override val readOnlyMountEnforced = false

    companion object {
        @JvmStatic @BeforeAll fun requireMsb() {
            org.junit.jupiter.api.Assumptions.assumeTrue(MsbBackendProvider().isSupported())
            // Force this backend regardless of host default:
            org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv("RIGHTSIZE_BACKEND")?.equals("docker", true) != true)
        }
    }
}
