package dev.rightsize

import dev.rightsize.core.ExecResult
import dev.rightsize.core.NonAbsoluteContainerPathException
import dev.rightsize.core.SandboxHandle
import dev.rightsize.core.wait.WaitStrategy
import dev.rightsize.core.wait.WaitTarget
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** A wait strategy that is immediately ready — the fake backend runs nothing to connect to. */
private object CopyReady : WaitStrategy {
    override fun waitUntilReady(target: WaitTarget) {}
    override fun withStartupTimeout(timeout: java.time.Duration): WaitStrategy = this
}

/** A [FakeBackend] that records every `exec` call (proving the generic layer's `mkdir -p`
 * pre-step ran, and in what order) and every [copyToContainer]/[copyFromContainer] call,
 * instead of throwing the interface's defensive default. */
private open class CopyFakeBackend : FakeBackend() {
    val execCalls = mutableListOf<List<String>>()
    val copiedIn = mutableListOf<Triple<String, Path, String>>()
    val copiedOut = mutableListOf<Triple<String, String, Path>>()

    override fun exec(handle: SandboxHandle, cmd: List<String>): ExecResult {
        execCalls += cmd
        return ExecResult(0, "", "")
    }

    override fun copyToContainer(handle: SandboxHandle, hostPath: Path, containerPath: String) {
        copiedIn += Triple(handle.id, hostPath, containerPath)
    }

    override fun copyFromContainer(handle: SandboxHandle, containerPath: String, hostPath: Path) {
        copiedOut += Triple(handle.id, containerPath, hostPath)
    }
}

class GenericContainerCopyTest {
    private fun container(backend: CopyFakeBackend) =
        GenericContainer("alpine:3.19").withBackend(backend).waitingFor(CopyReady)

    // --- copyFileToContainer ---

    @Test fun `copyFileToContainer on a never-started container throws before any backend call`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        val hostFile = Files.createFile(tmp.resolve("src.txt"))
        assertThrows(IllegalStateException::class.java) { c.copyFileToContainer(hostFile, "/dst.txt") }
        assertTrue(backend.execCalls.isEmpty())
        assertTrue(backend.copiedIn.isEmpty())
    }

    @Test fun `copyFileToContainer with a relative container path throws before any backend call`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            val hostFile = Files.createFile(tmp.resolve("src.txt"))
            val e = assertThrows(NonAbsoluteContainerPathException::class.java) {
                c.copyFileToContainer(hostFile, "relative/dst.txt")
            }
            assertTrue(e.message!!.contains("relative/dst.txt"))
            assertTrue(backend.execCalls.isEmpty())
            assertTrue(backend.copiedIn.isEmpty())
        } finally { c.stop() }
    }

    @Test fun `copyFileToContainer creates the destination's parent directory before copying`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            val hostFile = Files.createFile(tmp.resolve("src.txt"))
            c.copyFileToContainer(hostFile, "/dst/nested/file.txt")
            assertEquals(listOf(listOf("mkdir", "-p", "/dst/nested")), backend.execCalls)
            val (handleId, copiedHostPath, containerPath) = backend.copiedIn.single()
            assertEquals(backend.created.single().name, handleId)
            assertEquals(hostFile, copiedHostPath)
            assertEquals("/dst/nested/file.txt", containerPath)
        } finally { c.stop() }
    }

    @Test fun `copyFileToContainer with a top-level destination mkdir-p's root`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            val hostFile = Files.createFile(tmp.resolve("src.txt"))
            c.copyFileToContainer(hostFile, "/dst.txt")
            assertEquals(listOf(listOf("mkdir", "-p", "/")), backend.execCalls)
        } finally { c.stop() }
    }

    // --- copyContentToContainer ---

    @Test fun `copyContentToContainer writes content to a temp file, delegates, and removes the temp file`() {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            c.copyContentToContainer("payload-bytes".toByteArray(), "/dst/from-memory.txt")
            val (_, tempPath, containerPath) = backend.copiedIn.single()
            assertEquals("/dst/from-memory.txt", containerPath)
            assertFalse(Files.exists(tempPath), "the temp file must be removed once the copy delegate returns")
        } finally { c.stop() }
    }

    @Test fun `copyContentToContainer String overload UTF-8 encodes the content`() {
        val backend = object : CopyFakeBackend() {
            val capturedBytes = mutableListOf<ByteArray>()
            override fun copyToContainer(handle: SandboxHandle, hostPath: Path, containerPath: String) {
                capturedBytes += Files.readAllBytes(hostPath)
                super.copyToContainer(handle, hostPath, containerPath)
            }
        }
        val c = container(backend)
        c.start()
        try {
            c.copyContentToContainer("héllo", "/dst/greeting.txt")
            assertArrayEquals("héllo".toByteArray(Charsets.UTF_8), backend.capturedBytes.single())
        } finally { c.stop() }
    }

    @Test fun `copyContentToContainer on a never-started container throws without touching the backend`() {
        val backend = CopyFakeBackend()
        val c = container(backend)
        assertThrows(IllegalStateException::class.java) { c.copyContentToContainer("payload", "/dst.txt") }
        assertTrue(backend.execCalls.isEmpty())
        assertTrue(backend.copiedIn.isEmpty())
    }

    // --- copyFileFromContainer ---

    @Test fun `copyFileFromContainer on a never-started container throws before any backend call`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        assertThrows(IllegalStateException::class.java) { c.copyFileFromContainer("/src.txt", tmp.resolve("dst.txt")) }
        assertTrue(backend.copiedOut.isEmpty())
    }

    @Test fun `copyFileFromContainer with a relative container path throws before any backend call`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            assertThrows(NonAbsoluteContainerPathException::class.java) {
                c.copyFileFromContainer("relative/src.txt", tmp.resolve("dst.txt"))
            }
            assertTrue(backend.copiedOut.isEmpty())
        } finally { c.stop() }
    }

    @Test fun `copyFileFromContainer creates the host destination's parent directory before copying`(@TempDir tmp: Path) {
        val backend = CopyFakeBackend()
        val c = container(backend)
        c.start()
        try {
            val hostDst = tmp.resolve("nested").resolve("dst.txt")
            assertFalse(Files.exists(hostDst.parent))
            c.copyFileFromContainer("/src.txt", hostDst)
            assertTrue(Files.exists(hostDst.parent), "host parent directory must be created before the copy")
            val (handleId, containerPath, copiedHostPath) = backend.copiedOut.single()
            assertEquals(backend.created.single().name, handleId)
            assertEquals("/src.txt", containerPath)
            assertEquals(hostDst, copiedHostPath)
        } finally { c.stop() }
    }
}
