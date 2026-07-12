package dev.rightsize.core.reaper

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BackendIdTest {
    @Test fun `microsandbox backend name maps to the shared ledger vocabulary msb`() {
        assertEquals("msb", canonicalBackendId("microsandbox"))
        assertEquals("msb", canonicalBackendId("MicroSandbox"))
    }

    @Test fun `docker passes through lowercased`() {
        assertEquals("docker", canonicalBackendId("docker"))
        assertEquals("docker", canonicalBackendId("Docker"))
    }

    @Test fun `anything else passes through lowercased, not remapped`() {
        assertEquals("fake", canonicalBackendId("fake"))
    }
}
