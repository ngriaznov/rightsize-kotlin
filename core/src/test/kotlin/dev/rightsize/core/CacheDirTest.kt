package dev.rightsize.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Path

/** Pins the cache-dir resolution rules the msb provisioner used to own alone — now shared
 * with the reaper, since it needs the same cache root even in docker-only processes. */
class CacheDirTest {
    @Test fun `default cache dir on posix is user-home dot-cache-rightsize`() {
        val dir = CacheDir.resolve(emptyMap(), isWindows = false)
        assertEquals(Path.of(System.getProperty("user.home"), ".cache", "rightsize"), dir)
    }

    @Test fun `default cache dir on windows uses LOCALAPPDATA`() {
        val dir = CacheDir.resolve(mapOf("LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local"), isWindows = true)
        assertEquals(Path.of("C:\\Users\\me\\AppData\\Local", "rightsize"), dir)
    }

    @Test fun `windows falls back to user-home AppData Local when LOCALAPPDATA unset`() {
        val dir = CacheDir.resolve(emptyMap(), isWindows = true)
        assertEquals(Path.of(System.getProperty("user.home"), "AppData", "Local", "rightsize"), dir)
    }

    @Test fun `RIGHTSIZE_CACHE_DIR overrides on every platform`() {
        val posix = CacheDir.resolve(mapOf("RIGHTSIZE_CACHE_DIR" to "/custom/path"), isWindows = false)
        assertEquals(Path.of("/custom/path"), posix)
        val windows = CacheDir.resolve(mapOf("RIGHTSIZE_CACHE_DIR" to "/custom/path", "LOCALAPPDATA" to "C:\\ignored"), isWindows = true)
        assertEquals(Path.of("/custom/path"), windows)
    }

    @Test fun `no-arg resolve reads the real environment and host OS`() {
        // Just proves it doesn't throw and returns something under "rightsize".
        val dir = CacheDir.resolve()
        assertEquals("rightsize", dir.fileName.toString())
    }
}
