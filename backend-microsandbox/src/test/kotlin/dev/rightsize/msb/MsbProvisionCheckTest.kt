package dev.rightsize.msb

import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * A live provisioning check: [MsbProvisioner.ensureInstalled] downloads the pinned msb release
 * for this host, verifies every asset against the release's `checksums.sha256` and installs it,
 * and the installed binary then has to run and report the pinned version. It needs network access
 * but no VM, which is why the macOS workflow (`.github/workflows/macos.yml`) runs it: GitHub's
 * hosted Apple Silicon runners cannot boot microVMs. Skipped unless
 * `RIGHTSIZE_MSB_PROVISION_CHECK=1`; runs on any host msb supports:
 *
 * ```sh
 * RIGHTSIZE_MSB_PROVISION_CHECK=1 ./gradlew :backend-microsandbox:test --tests dev.rightsize.msb.MsbProvisionCheckTest
 * ```
 *
 * The `test` task points `RIGHTSIZE_CACHE_DIR` at `build/test-cache/rightsize`, so after a clean
 * build this is always a real download.
 */
@EnabledIfEnvironmentVariable(named = "RIGHTSIZE_MSB_PROVISION_CHECK", matches = "1")
class MsbProvisionCheckTest {

    @Test fun `provisions the pinned msb and it runs`() {
        val msb = MsbProvisioner.ensureInstalled()
        val process = ProcessBuilder(msb.toString(), "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "$msb --version did not exit within 60s")
        assertEquals(0, process.exitValue(), "$msb --version failed: $output")
        assertTrue(MsbProvisioner.MSB_VERSION in output,
            "expected msb ${MsbProvisioner.MSB_VERSION}, got: $output")
    }
}
