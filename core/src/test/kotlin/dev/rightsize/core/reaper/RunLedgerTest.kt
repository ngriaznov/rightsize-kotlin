package dev.rightsize.core.reaper

import dev.rightsize.core.ContainerSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files

class RunLedgerTest {
    private fun ledger(cacheDir: java.nio.file.Path = Files.createTempDirectory("rz-ledger"),
                        runId: String = "abc12345") =
        RunLedger(runId, cacheDir, pid = 4242, startedIso = "2026-07-11T10:00:00Z",
            backend = "docker", msbPath = null)

    private fun spec(name: String, keepAlive: Boolean = false) =
        ContainerSpec(name = name, image = "alpine:3.19", runId = "abc12345", keepAlive = keepAlive)

    @Test fun `run record is written before the first sandbox is appended`() {
        val l = ledger()
        assertFalse(Files.exists(l.recordFile))
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        assertTrue(Files.exists(l.recordFile))
        val record = RunRecord.parse(Files.readString(l.recordFile))
        assertEquals(RunRecord(4242, "2026-07-11T10:00:00Z", "docker"), record)
        assertEquals(listOf("rz-abc12345-1"), Files.readAllLines(l.sandboxesFile).filter { it.isNotBlank() })
    }

    @Test fun `sandboxes file is append-before-create, growing with each new sandbox`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        l.beforeSandboxCreate(spec("rz-abc12345-2"))
        assertEquals(listOf("rz-abc12345-1", "rz-abc12345-2"),
            Files.readAllLines(l.sandboxesFile).filter { it.isNotBlank() })
    }

    @Test fun `removing a sandbox after stop removes just that line`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        l.beforeSandboxCreate(spec("rz-abc12345-2"))
        l.afterSandboxRemoved("rz-abc12345-1")
        assertEquals(listOf("rz-abc12345-2"), Files.readAllLines(l.sandboxesFile).filter { it.isNotBlank() })
    }

    @Test fun `clean shutdown - removing the last sandbox with no networks deletes all three files`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        assertTrue(Files.exists(l.recordFile))
        l.afterSandboxRemoved("rz-abc12345-1")
        assertFalse(Files.exists(l.recordFile))
        assertFalse(Files.exists(l.sandboxesFile))
        assertFalse(Files.exists(l.networksFile))
    }

    @Test fun `a later container start recreates the deleted files`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        l.afterSandboxRemoved("rz-abc12345-1")
        assertFalse(Files.exists(l.recordFile))
        l.beforeSandboxCreate(spec("rz-abc12345-2"))
        assertTrue(Files.exists(l.recordFile))
        assertEquals(listOf("rz-abc12345-2"), Files.readAllLines(l.sandboxesFile).filter { it.isNotBlank() })
    }

    @Test fun `a network still open keeps the files alive after the last sandbox is removed`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        l.beforeNetworkCreate("rz-net-1")
        l.afterSandboxRemoved("rz-abc12345-1")
        assertTrue(Files.exists(l.recordFile), "record must survive while a network is still tracked")
        l.afterNetworkRemoved("rz-net-1")
        assertFalse(Files.exists(l.recordFile))
    }

    @Test fun `keepAlive specs are never appended to the sandboxes file`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-reuse-1", keepAlive = true))
        assertFalse(Files.exists(l.recordFile), "a keepAlive-only ledger must not even write a record")
        assertFalse(Files.exists(l.sandboxesFile))
    }

    @Test fun `deleteFiles is best-effort idempotent even with nothing on disk`() {
        val l = ledger()
        l.deleteFiles()   // never wrote anything: must not throw
        assertFalse(Files.exists(l.recordFile))
    }

    @Test fun `removing an unknown sandbox name is a no-op, not an error`() {
        val l = ledger()
        l.beforeSandboxCreate(spec("rz-abc12345-1"))
        l.afterSandboxRemoved("rz-does-not-exist")
        assertEquals(listOf("rz-abc12345-1"), Files.readAllLines(l.sandboxesFile).filter { it.isNotBlank() })
    }

    @Test fun `msb backend record carries msbPath`() {
        val cacheDir = Files.createTempDirectory("rz-ledger-msb")
        val l = RunLedger("run1", cacheDir, pid = 1, startedIso = "2026-07-11T10:00:00Z",
            backend = "msb", msbPath = "/opt/msb/bin/msb")
        l.beforeSandboxCreate(ContainerSpec(name = "rz-run1-1", image = "x", runId = "run1"))
        val record = RunRecord.parse(Files.readString(l.recordFile))
        assertEquals("/opt/msb/bin/msb", record?.msbPath)
    }
}
