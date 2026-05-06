package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the semver comparison logic extracted from JsBridge.compareVersions.
 * Validates version comparison used for OTA update checks.
 */
class VersionCompareTest {
    // Replicate the compareVersions logic for isolated testing
    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val diff = (aParts.getOrElse(i) { 0 }) - (bParts.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    @Test
    fun `equal versions return 0`() {
        assertEquals(0, compareVersions("1.0.0", "1.0.0"))
        assertEquals(0, compareVersions("0.4.0", "0.4.0"))
        assertEquals(0, compareVersions("1.0.27", "1.0.27"))
    }

    @Test
    fun `newer major version is greater`() {
        assertTrue(compareVersions("2.0.0", "1.0.0") > 0)
        assertTrue(compareVersions("1.0.0", "2.0.0") < 0)
    }

    @Test
    fun `newer minor version is greater`() {
        assertTrue(compareVersions("1.1.0", "1.0.0") > 0)
        assertTrue(compareVersions("1.0.0", "1.1.0") < 0)
    }

    @Test
    fun `newer patch version is greater`() {
        assertTrue(compareVersions("1.0.1", "1.0.0") > 0)
        assertTrue(compareVersions("1.0.0", "1.0.1") < 0)
    }

    @Test
    fun `script version 1 0 27 is greater than 1 0 12`() {
        assertTrue(compareVersions("1.0.27", "1.0.12") > 0)
    }

    @Test
    fun `app version 0 4 0 is greater than 0 3 0`() {
        assertTrue(compareVersions("0.4.0", "0.3.0") > 0)
    }

    @Test
    fun `handles versions with different segment counts`() {
        assertTrue(compareVersions("1.1", "1.0.0") > 0)
        assertEquals(0, compareVersions("1.0", "1.0.0"))
    }

    @Test
    fun `handles non-numeric segments gracefully`() {
        // Non-numeric treated as 0
        assertEquals(0, compareVersions("1.0.abc", "1.0.0"))
    }
}
