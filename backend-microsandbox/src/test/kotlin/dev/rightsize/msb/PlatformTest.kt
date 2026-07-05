package dev.rightsize.msb

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformTest {
    @Test fun `windows x64 asset names match the pinned release`() {
        val p = Platform.WINDOWS_X64
        assertEquals("msb-windows-x86_64.exe", p.msbAsset)
        assertEquals("libkrunfw-windows-x86_64.dll", p.krunAsset)
        assertEquals("libkrunfw.dll", p.krunInstallName)
        assertEquals("msb.exe", p.msbBinaryName)
        assertTrue(p.isWindows)
    }

    @Test fun `windows arm64 asset names match the pinned release`() {
        val p = Platform.WINDOWS_ARM64
        assertEquals("msb-windows-aarch64.exe", p.msbAsset)
        assertEquals("libkrunfw-windows-aarch64.dll", p.krunAsset)
        assertEquals("libkrunfw.dll", p.krunInstallName)
        assertEquals("msb.exe", p.msbBinaryName)
        assertTrue(p.isWindows)
    }

    @Test fun `non-windows platforms keep a suffixless binary name`() {
        assertEquals("msb", Platform.DARWIN_ARM64.msbBinaryName)
        assertEquals("msb", Platform.LINUX_X64.msbBinaryName)
        assertEquals("msb", Platform.LINUX_ARM64.msbBinaryName)
        assertFalse(Platform.DARWIN_ARM64.isWindows)
        assertFalse(Platform.LINUX_X64.isWindows)
        assertFalse(Platform.LINUX_ARM64.isWindows)
    }

    @Test fun `existing macOS-Linux asset names are unchanged`() {
        assertEquals("msb-darwin-aarch64", Platform.DARWIN_ARM64.msbAsset)
        assertEquals("libkrunfw-darwin-aarch64.dylib", Platform.DARWIN_ARM64.krunAsset)
        assertEquals("msb-linux-x86_64", Platform.LINUX_X64.msbAsset)
        assertEquals("libkrunfw-linux-x86_64.so", Platform.LINUX_X64.krunAsset)
        assertEquals("msb-linux-aarch64", Platform.LINUX_ARM64.msbAsset)
        assertEquals("libkrunfw-linux-aarch64.so", Platform.LINUX_ARM64.krunAsset)
    }
}
