package dev.rightsize.msb

import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.*
import java.security.MessageDigest

object MsbProvisioner {
    const val MSB_VERSION = "0.6.3"
    private const val DEFAULT_BASE =
        "https://github.com/superradcompany/microsandbox/releases/download/v$MSB_VERSION"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 300_000

    fun ensureInstalled(): Path = ensureInstalled(
        DEFAULT_BASE,
        System.getenv("RIGHTSIZE_CACHE_DIR")?.let { Path.of(it) }
            ?: Path.of(System.getProperty("user.home"), ".cache", "rightsize"),
        System.getenv(),
    )

    internal fun ensureInstalled(baseUrl: String, cacheDir: Path, env: Map<String, String>): Path {
        env["MSB_PATH"]?.let { override ->
            val p = Path.of(override)
            check(Files.isExecutable(p)) { "MSB_PATH='$override' is not an executable file" }
            return p
        }
        val platform = Platform.current()
            ?: error("microsandbox has no build for ${System.getProperty("os.name")}/${System.getProperty("os.arch")} " +
                "— use the docker backend (RIGHTSIZE_BACKEND=docker) or set MSB_PATH")
        val installDir = cacheDir.resolve("msb").resolve(MSB_VERSION)
        val msb = installDir.resolve("bin").resolve("msb")
        // Installed under the canonical name msb resolves (`../lib/` next to its binary),
        // not the release-asset name it is downloaded as — msb never probes the asset name.
        val krun = installDir.resolve("lib").resolve(platform.krunInstallName)
        // An install is complete only when BOTH the msb binary and the krun asset are present.
        // The msb binary is written last (see below), so its presence alone is not sufficient.
        if (isInstalled(msb, krun)) return msb
        check(env["RIGHTSIZE_MSB_SKIP_DOWNLOAD"] != "true") {
            "msb $MSB_VERSION not found at $msb and RIGHTSIZE_MSB_SKIP_DOWNLOAD=true " +
                "— pre-install it there or point MSB_PATH at an msb binary"
        }
        Files.createDirectories(installDir)
        FileChannel.open(installDir.resolve(".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            ch.lock().use {
                if (isInstalled(msb, krun)) return msb   // another process won the race
                downloadAndInstall(baseUrl, platform, installDir, msb, krun)
            }
        }
        return msb
    }

    /**
     * Downloads and verifies BOTH assets before moving either into place, then moves the krun
     * asset FIRST and the msb binary LAST. That way the msb binary's existence is the commit
     * marker for a complete install: a crash between moves cannot leave a present-msb /
     * missing-krun state that future callers would wrongly accept. Called with the install-dir
     * lock already held.
     */
    private fun downloadAndInstall(baseUrl: String, platform: Platform, installDir: Path, msb: Path, krun: Path) {
        val sums = fetchChecksums(baseUrl)
        val msbTmp = downloadVerified(baseUrl, platform.msbAsset, installDir.resolve("bin"), sums, executable = true)
        val krunTmp = downloadVerified(baseUrl, platform.krunAsset, installDir.resolve("lib"), sums, executable = false)
        try {
            Files.move(krunTmp, krun, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Files.move(msbTmp, msb, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(msbTmp)
            Files.deleteIfExists(krunTmp)
        }
    }

    private fun isInstalled(msb: Path, krun: Path): Boolean =
        Files.isExecutable(msb) && Files.exists(krun)

    private fun fetchChecksums(baseUrl: String): Map<String, String> =
        openConnection("$baseUrl/checksums.sha256").use { conn ->
            conn.inputStream.bufferedReader().readText()
        }.lines()
            .filter { it.isNotBlank() }
            .associate { rawLine ->
                val line = rawLine.trim()
                val parts = line.split(Regex("\\s+"), limit = 2)
                check(parts.size == 2) { "malformed line in checksums.sha256: '$line'" }
                parts[1].trim() to parts[0]
            }

    // Downloads and verifies [asset] into a temp file in [dir], returning that temp file.
    // The caller is responsible for moving it into place (and deleting it on failure).
    private fun downloadVerified(baseUrl: String, asset: String, dir: Path,
                                 sums: Map<String, String>, executable: Boolean): Path {
        Files.createDirectories(dir)
        val expected = sums[asset] ?: error("No SHA-256 for '$asset' in $baseUrl/checksums.sha256")
        val tmp = Files.createTempFile(dir, ".dl-", ".part")
        var ok = false
        try {
            openConnection("$baseUrl/$asset").use { conn ->
                conn.inputStream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp))
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) {
                "SHA-256 mismatch for $asset from $baseUrl (expected $expected, got $actual) " +
                    "— delete $dir and retry, or set MSB_PATH to a trusted msb binary"
            }
            if (executable) tmp.toFile().setExecutable(true, false)
            ok = true
            return tmp
        } finally {
            if (!ok) Files.deleteIfExists(tmp)
        }
    }

    private fun openConnection(url: String): HttpConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true   // default; explicit for clarity (30x are followed)
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        val code = conn.responseCode
        check(code == 200) {
            conn.disconnect()
            "HTTP $code fetching $url"
        }
        return HttpConnection(conn)
    }

    /** Thin AutoCloseable wrapper so callers can use `.use { }` and always disconnect. */
    private class HttpConnection(private val conn: HttpURLConnection) : AutoCloseable {
        val inputStream get() = conn.inputStream
        override fun close() = conn.disconnect()
    }
}
