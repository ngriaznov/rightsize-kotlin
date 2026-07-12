package dev.rightsize.core.reuse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

class ReuseIdentityTest {
    // Pinned cross-language vector (see docs/reuse.md and docs/parity.md): this exact spec MUST
    // hash to this exact value in every one of rightsize's Kotlin, Rust, and Node libraries —
    // computed once and pinned identically in each contract suite.
    private val pinnedSpec = ReuseIdentitySpec(
        image = "redis:7-alpine",
        env = mapOf("A" to "1", "B" to "2"),
        command = emptyList(),
        exposedPorts = listOf(6379),
        memoryLimitMb = null,
        copies = emptyList(),
    )
    private val pinnedHash = "799aad5a3338ce3d36999c7ff2733d4673c0592d417563f334544693ec1907a5"

    @Test fun `pinned cross-language vector hashes to the pinned value`() {
        assertEquals(pinnedHash, ReuseIdentity.hash(pinnedSpec))
    }

    @Test fun `canonical JSON has stable key order and no whitespace`() {
        assertEquals(
            "{\"image\":\"redis:7-alpine\",\"env\":{\"A\":\"1\",\"B\":\"2\"}," +
                "\"command\":[],\"exposedPorts\":[6379],\"memoryLimitMb\":null,\"copies\":[]}",
            ReuseIdentity.canonicalJson(pinnedSpec),
        )
    }

    @Test fun `name is rz-reuse- plus the first 12 hex chars of the hash`() {
        assertEquals("rz-reuse-799aad5a3338", ReuseIdentity.name(pinnedHash))
    }

    @Test fun `env key order never affects the hash`() {
        val reordered = pinnedSpec.copy(env = mapOf("B" to "2", "A" to "1"))
        assertEquals(ReuseIdentity.hash(pinnedSpec), ReuseIdentity.hash(reordered))
    }

    @Test fun `changing the image changes the hash`() {
        val other = pinnedSpec.copy(image = "redis:7.2-alpine")
        assertNotEquals(ReuseIdentity.hash(pinnedSpec), ReuseIdentity.hash(other))
    }

    @Test fun `changing a mounted copy's content changes the hash even if the guestPath is unchanged`() {
        val fileA = Files.createTempFile("rz-reuse-copy-", ".txt")
        val fileB = Files.createTempFile("rz-reuse-copy-", ".txt")
        Files.writeString(fileA, "content-a")
        Files.writeString(fileB, "content-b")
        val specA = pinnedSpec.copy(copies = listOf(
            ReuseIdentitySpec.CopyEntry("/data/seed.sql", ReuseIdentity.sha256OfFile(fileA))))
        val specB = pinnedSpec.copy(copies = listOf(
            ReuseIdentitySpec.CopyEntry("/data/seed.sql", ReuseIdentity.sha256OfFile(fileB))))
        assertNotEquals(ReuseIdentity.hash(specA), ReuseIdentity.hash(specB))
        Files.deleteIfExists(fileA); Files.deleteIfExists(fileB)
    }

    @Test fun `command declaration order is preserved and changing it changes the hash`() {
        val forward = pinnedSpec.copy(command = listOf("redis-server", "--appendonly", "yes"))
        val backward = pinnedSpec.copy(command = listOf("yes", "--appendonly", "redis-server"))
        assertTrue(ReuseIdentity.canonicalJson(forward)
            .contains("\"command\":[\"redis-server\",\"--appendonly\",\"yes\"]"))
        assertNotEquals(ReuseIdentity.hash(forward), ReuseIdentity.hash(backward))
        assertNotEquals(ReuseIdentity.hash(pinnedSpec), ReuseIdentity.hash(forward))
    }

    @Test fun `a non-null memoryLimitMb is part of the hash`() {
        val withLimit = pinnedSpec.copy(memoryLimitMb = 512)
        assertNotEquals(ReuseIdentity.hash(pinnedSpec), ReuseIdentity.hash(withLimit))
        assertTrue(ReuseIdentity.canonicalJson(withLimit).contains("\"memoryLimitMb\":512"))
    }

    @Test fun `canonical JSON escapes quotes, backslashes, whitespace controls, and raw control bytes`() {
        val spec = pinnedSpec.copy(env = mapOf("MSG" to "line1\nline2\ttab\"quote\\back\rcr"))
        val json = ReuseIdentity.canonicalJson(spec)
        assertTrue(json.contains("\\n"), "newline must be escaped: $json")
        assertTrue(json.contains("\\t"), "tab must be escaped: $json")
        assertTrue(json.contains("\\\""), "quote must be escaped: $json")
        assertTrue(json.contains("\\\\"), "backslash must be escaped: $json")
        assertTrue(json.contains("\\r"), "carriage return must be escaped: $json")
        // A raw control byte with no named escape (e.g. 0x01) is \u-escaped, lowercase hex.
        val withControlByte = pinnedSpec.copy(image = "redis${1.toChar()}alpine")
        assertTrue(ReuseIdentity.canonicalJson(withControlByte).contains("\\u0001"))
    }

    @Test fun `copies are order-independent, sorted by guestPath`() {
        val fileA = Files.createTempFile("rz-reuse-copy-", ".txt").also { Files.writeString(it, "a") }
        val fileB = Files.createTempFile("rz-reuse-copy-", ".txt").also { Files.writeString(it, "b") }
        val hashA = ReuseIdentity.sha256OfFile(fileA)
        val hashB = ReuseIdentity.sha256OfFile(fileB)
        val forward = pinnedSpec.copy(copies = listOf(
            ReuseIdentitySpec.CopyEntry("/a.txt", hashA), ReuseIdentitySpec.CopyEntry("/b.txt", hashB)))
        val backward = pinnedSpec.copy(copies = listOf(
            ReuseIdentitySpec.CopyEntry("/b.txt", hashB), ReuseIdentitySpec.CopyEntry("/a.txt", hashA)))
        assertEquals(ReuseIdentity.hash(forward), ReuseIdentity.hash(backward))
        Files.deleteIfExists(fileA); Files.deleteIfExists(fileB)
    }
}
