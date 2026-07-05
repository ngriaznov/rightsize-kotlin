package dev.rightsize.msb

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
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

    @BeforeEach fun serve() {
        val p = Platform.current()!!
        http = HttpServer.create(InetSocketAddress(0), 0)
        http.createContext("/${p.msbAsset}") { it.sendResponseHeaders(200, fakeMsb.size.toLong()); it.responseBody.write(fakeMsb); it.close() }
        http.createContext("/${p.krunAsset}") { it.sendResponseHeaders(200, fakeKrun.size.toLong()); it.responseBody.write(fakeKrun); it.close() }
        val sums = "${sha256(fakeMsb)}  ${p.msbAsset}\n${sha256(fakeKrun)}  ${p.krunAsset}\n".toByteArray()
        http.createContext("/checksums.sha256") { it.sendResponseHeaders(200, sums.size.toLong()); it.responseBody.write(sums); it.close() }
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
}
