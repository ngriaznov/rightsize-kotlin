package dev.rightsize.msb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class MsbProvisionerTest {
    private lateinit var http: HttpServer
    private lateinit var baseUrl: String
    private val fakeMsb = "#!/bin/sh\necho fake-msb".toByteArray()
    private val fakeKrun = ByteArray(16) { it.toByte() }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
        .joinToString("") { "%02x".format(it) }

    /** Serves every platform row's assets under its own name, so a single fake server backs
     * both the host's real [Platform.current] tests and the injected-platform Windows tests
     * (Windows can't be [Platform.current] on the macOS/Linux machines CI actually runs this
     * suite on, so those rows are exercised via the platform-injected overload instead). */
    @BeforeEach fun serve() {
        http = HttpServer.create(InetSocketAddress(0), 0)
        val sums = StringBuilder()
        Platform.entries.forEach { p ->
            http.createContext("/${p.msbAsset}") { it.sendResponseHeaders(200, fakeMsb.size.toLong()); it.responseBody.write(fakeMsb); it.close() }
            http.createContext("/${p.krunAsset}") { it.sendResponseHeaders(200, fakeKrun.size.toLong()); it.responseBody.write(fakeKrun); it.close() }
            sums.append("${sha256(fakeMsb)}  ${p.msbAsset}\n${sha256(fakeKrun)}  ${p.krunAsset}\n")
        }
        val sumBytes = sums.toString().toByteArray()
        http.createContext("/checksums.sha256") { it.sendResponseHeaders(200, sumBytes.size.toLong()); it.responseBody.write(sumBytes); it.close() }
        http.start(); baseUrl = "http://127.0.0.1:${http.address.port}"
    }
    @AfterEach fun stop() = http.stop(0)

    @Test fun `downloads verifies and installs bin msb and lib krunfw`() {
        val cache = Files.createTempDirectory("rz-prov")
        val msb = MsbProvisioner.ensureInstalled(baseUrl, cache, emptyMap())
        assertTrue(Files.isExecutable(msb))
        assertEquals("bin", msb.parent.fileName.toString())
        assertTrue(Files.exists(msb.parent.parent.resolve("lib").resolve(Platform.current()!!.krunInstallName)))
        // second call: no re-download (server can be stopped)
        http.stop(0)
        assertEquals(msb, MsbProvisioner.ensureInstalled(baseUrl, cache, emptyMap()))
    }

    @Test fun `corrupted download fails checksum with actionable message`() {
        val p = Platform.current()!!
        http.removeContext("/${p.msbAsset}")
        val evil = "evil".toByteArray()
        http.createContext("/${p.msbAsset}") { it.sendResponseHeaders(200, evil.size.toLong()); it.responseBody.write(evil); it.close() }
        val ex = assertThrows(IllegalStateException::class.java) {
            MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("rz-prov2"), emptyMap())
        }
        assertTrue(ex.message!!.contains("SHA-256")); assertTrue(ex.message!!.contains(p.msbAsset))
    }

    @Test fun `incomplete install missing krun asset is repaired`() {
        val p = Platform.current()!!
        val cache = Files.createTempDirectory("rz-prov-crash")
        // Simulate a crash after bin/msb was moved into place but before lib/<krunAsset>:
        // an executable msb exists, the krun asset does not.
        val installDir = cache.resolve("msb").resolve(MsbProvisioner.MSB_VERSION)
        val binDir = Files.createDirectories(installDir.resolve("bin"))
        val staleMsb = binDir.resolve("msb")
        Files.write(staleMsb, "#!/bin/sh\necho stale".toByteArray())
        staleMsb.toFile().setExecutable(true)
        val krun = installDir.resolve("lib").resolve(p.krunInstallName)
        assertTrue(Files.isExecutable(staleMsb))
        assertFalse(Files.exists(krun))

        val msb = MsbProvisioner.ensureInstalled(baseUrl, cache, emptyMap())

        // The incomplete install must be repaired, not accepted as-is: the krun asset is now present.
        assertTrue(Files.isExecutable(msb))
        assertTrue(Files.exists(krun), "krun asset should have been downloaded to repair the install")
        assertArrayEquals(fakeKrun, Files.readAllBytes(krun))
    }

    @Test fun `MSB_PATH short-circuits everything`() {
        val fake = Files.createTempFile("msb", "").also { it.toFile().setExecutable(true) }
        val got = MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("x"),
            mapOf("MSB_PATH" to fake.toString()))
        assertEquals(fake, got)
    }

    @Test fun `skip-download without cached binary fails with escape hatch hint`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("y"),
                mapOf("RIGHTSIZE_MSB_SKIP_DOWNLOAD" to "true"))
        }
        assertTrue(ex.message!!.contains("MSB_PATH"))
    }

    // --- Windows-specific: exercised via the platform-injected overload, since none of these
    // rows can be Platform.current() on the macOS/Linux machines that actually run this suite.

    @ParameterizedTest
    @EnumSource(Platform::class, names = ["WINDOWS_X64", "WINDOWS_ARM64"])
    fun `windows install lands msb exe and canonical libkrunfw dll`(platform: Platform) {
        val cache = Files.createTempDirectory("rz-prov-win")
        val msb = MsbProvisioner.ensureInstalled(baseUrl, cache, emptyMap(), platform)
        assertEquals("msb.exe", msb.fileName.toString())
        assertEquals("bin", msb.parent.fileName.toString())
        assertTrue(Files.isRegularFile(msb))
        val krun = msb.parent.parent.resolve("lib").resolve("libkrunfw.dll")
        assertTrue(Files.exists(krun), "expected canonical libkrunfw.dll, not the release-asset name")
        assertArrayEquals(fakeKrun, Files.readAllBytes(krun))
        // Idempotent second call, no re-download needed.
        http.stop(0)
        assertEquals(msb, MsbProvisioner.ensureInstalled(baseUrl, cache, emptyMap(), platform))
    }

    @Test fun `windows MSB_PATH accepts a non-executable regular file (no execute bit on Windows)`() {
        val fake = Files.createTempFile("msb", ".exe")   // deliberately NOT setExecutable
        val got = MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("win-msbpath"),
            mapOf("MSB_PATH" to fake.toString()), Platform.WINDOWS_X64)
        assertEquals(fake, got)
    }

    @Test fun `windows MSB_PATH still rejects a missing file`() {
        val missing = Files.createTempDirectory("win-msbpath-missing").resolve("nope.exe")
        val ex = assertThrows(IllegalStateException::class.java) {
            MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("win-msbpath-missing2"),
                mapOf("MSB_PATH" to missing.toString()), Platform.WINDOWS_X64)
        }
        assertTrue(ex.message!!.contains("MSB_PATH"))
    }

    @Test fun `windows MSB_PATH rejects a directory`() {
        val dir = Files.createTempDirectory("win-msbpath-dir")
        val ex = assertThrows(IllegalStateException::class.java) {
            MsbProvisioner.ensureInstalled(baseUrl, Files.createTempDirectory("win-msbpath-dir2"),
                mapOf("MSB_PATH" to dir.toString()), Platform.WINDOWS_X64)
        }
        assertTrue(ex.message!!.contains("MSB_PATH"))
    }

    @Test fun `default cache dir uses LOCALAPPDATA on windows`() {
        val dir = MsbProvisioner.defaultCacheDir(Platform.WINDOWS_X64, mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"))
        assertEquals(java.nio.file.Path.of("C:\\Users\\me\\AppData\\Local", "rightsize"), dir)
    }

    @Test fun `default cache dir falls back to user-home AppData Local when LOCALAPPDATA unset`() {
        val dir = MsbProvisioner.defaultCacheDir(Platform.WINDOWS_X64, emptyMap())
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), "AppData", "Local", "rightsize"), dir)
    }

    @Test fun `default cache dir on non-windows is unchanged`() {
        val dir = MsbProvisioner.defaultCacheDir(Platform.LINUX_X64, emptyMap())
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), ".cache", "rightsize"), dir)
    }

    @Test fun `RIGHTSIZE_CACHE_DIR overrides the windows default`() {
        val dir = MsbProvisioner.defaultCacheDir(Platform.WINDOWS_X64,
            mapOf("RIGHTSIZE_CACHE_DIR" to "/custom/path", "LOCALAPPDATA" to "C:\\ignored"))
        assertEquals(java.nio.file.Path.of("/custom/path"), dir)
    }
}
