package dev.rightsize.core.reaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RunRecordTest {
    @Test fun `round-trips through toJson and parse, msbPath present`() {
        val r = RunRecord(pid = 4242, startedIso = "2026-07-11T10:00:00Z", backend = "msb",
            msbPath = "/home/me/.cache/rightsize/msb/0.6.6/bin/msb")
        val parsed = RunRecord.parse(r.toJson())
        assertEquals(r, parsed)
    }

    @Test fun `msbPath is omitted from the JSON and absent on parse for docker`() {
        val r = RunRecord(pid = 1, startedIso = "2026-07-11T10:00:00Z", backend = "docker")
        val json = r.toJson()
        assertFalse(json.contains("msbPath"))
        assertEquals(r, RunRecord.parse(json))
    }

    @Test fun `parse tolerates unknown extra keys (cross-language forward-compat)`() {
        val json = """{"pid":7,"startedIso":"2026-07-11T10:00:00Z","backend":"docker","future":"field"}"""
        val parsed = RunRecord.parse(json)
        assertEquals(RunRecord(7, "2026-07-11T10:00:00Z", "docker"), parsed)
    }

    @Test fun `parse tolerates key order`() {
        val json = """{"backend":"docker","pid":9,"startedIso":"2026-07-11T10:00:00Z"}"""
        assertEquals(RunRecord(9, "2026-07-11T10:00:00Z", "docker"), RunRecord.parse(json))
    }

    @Test fun `parse returns null on missing required fields or garbage`() {
        assertNull(RunRecord.parse("not json at all"))
        assertNull(RunRecord.parse("""{"pid":1,"backend":"docker"}"""))            // missing startedIso
        assertNull(RunRecord.parse("""{"startedIso":"x","backend":"docker"}"""))   // missing pid
        assertNull(RunRecord.parse("""{"pid":1,"startedIso":"x"}"""))              // missing backend
        assertNull(RunRecord.parse(""))
    }

    @Test fun `special characters in msbPath round-trip (Windows paths, spaces)`() {
        val r = RunRecord(pid = 1, startedIso = "2026-07-11T10:00:00Z", backend = "msb",
            msbPath = "C:\\Users\\a b\\AppData\\Local\\rightsize\\msb\\bin\\msb.exe")
        assertEquals(r, RunRecord.parse(r.toJson()))
    }
}
