package dev.rightsize.docker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** [DockerHost.resolve] takes both the environment and the OS name as parameters precisely so
 * this class can prove the Windows branch without a Windows host — no real environment lookup,
 * no daemon, no socket/pipe I/O. */
class DockerHostTest {
    @Test fun `resolve defaults to the unix socket on a non-Windows OS with no DOCKER_HOST`() {
        assertEquals(URI.create("unix:///var/run/docker.sock"), DockerHost.resolve(env = emptyMap(), osName = "Linux"))
        assertEquals(URI.create("unix:///var/run/docker.sock"), DockerHost.resolve(env = emptyMap(), osName = "Mac OS X"))
    }

    @Test fun `resolve defaults to the named pipe on Windows with no DOCKER_HOST`() {
        assertEquals(
            URI.create("npipe:////./pipe/docker_engine"),
            DockerHost.resolve(env = emptyMap(), osName = "Windows 11"),
        )
    }

    @Test fun `resolve recognizes every Windows os_name shape, not just one build`() {
        for (osName in listOf("Windows 10", "Windows Server 2019", "windows 11", "WINDOWS")) {
            assertEquals(
                URI.create("npipe:////./pipe/docker_engine"), DockerHost.resolve(env = emptyMap(), osName = osName),
                "osName=$osName",
            )
        }
    }

    @Test fun `resolve honors DOCKER_HOST over the platform default, on every OS`() {
        val custom = mapOf("DOCKER_HOST" to "tcp://127.0.0.1:2375")
        assertEquals(URI.create("tcp://127.0.0.1:2375"), DockerHost.resolve(env = custom, osName = "Linux"))
        assertEquals(URI.create("tcp://127.0.0.1:2375"), DockerHost.resolve(env = custom, osName = "Windows 11"))
    }

    @Test fun `resolve falls back to the platform default when DOCKER_HOST is blank`() {
        val blank = mapOf("DOCKER_HOST" to "")
        assertEquals(URI.create("unix:///var/run/docker.sock"), DockerHost.resolve(env = blank, osName = "Linux"))
        assertEquals(URI.create("npipe:////./pipe/docker_engine"), DockerHost.resolve(env = blank, osName = "Windows 11"))
    }

    @Test fun `isWindows matches Windows os_name values case-insensitively`() {
        assertTrue(DockerHost.isWindows("Windows 10"))
        assertTrue(DockerHost.isWindows("windows 11"))
        assertTrue(DockerHost.isWindows("Windows Server 2022"))
    }

    @Test fun `isWindows rejects non-Windows os_name values`() {
        assertFalse(DockerHost.isWindows("Linux"))
        assertFalse(DockerHost.isWindows("Mac OS X"))
        assertFalse(DockerHost.isWindows(""))
    }
}
