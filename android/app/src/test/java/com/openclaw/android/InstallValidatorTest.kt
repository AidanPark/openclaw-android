package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for InstallValidator — pure file-system logic, no Android context needed.
 */
class InstallValidatorTest {

    @TempDir
    lateinit var prefixDir: File

    // ─── ValidationResult data class ─────────────────────────────────────────

    @Test
    fun `ValidationResult passed true means no errors`() {
        val result = InstallValidator.ValidationResult(passed = true, errors = emptyList(), warnings = emptyList())
        assertTrue(result.passed)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `ValidationResult passed false with errors`() {
        val result = InstallValidator.ValidationResult(
            passed = false,
            errors = listOf("bin/ directory missing"),
            warnings = emptyList(),
        )
        assertFalse(result.passed)
        assertEquals(1, result.errors.size)
        assertEquals("bin/ directory missing", result.errors[0])
    }

    // ─── validatePayload: empty prefix ───────────────────────────────────────

    @Test
    fun `validatePayload fails when prefix is empty`() {
        val result = InstallValidator.validatePayload(prefixDir)
        assertFalse(result.passed)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `validatePayload reports bin directory missing`() {
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.errors.any { it.contains("bin/") })
    }

    // ─── validatePayload: bin dir present but empty ───────────────────────────

    @Test
    fun `validatePayload fails when bin dir is empty`() {
        File(prefixDir, "bin").mkdirs()
        val result = InstallValidator.validatePayload(prefixDir)
        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("bin/ directory is empty") })
    }

    // ─── validatePayload: missing lib ────────────────────────────────────────

    @Test
    fun `validatePayload reports lib directory missing`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.errors.any { it.contains("lib/") })
    }

    // ─── validatePayload: missing glibc ──────────────────────────────────────

    @Test
    fun `validatePayload reports glibc lib missing`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.errors.any { it.contains("glibc/lib/") })
    }

    @Test
    fun `validatePayload reports ld-linux linker missing`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        // No ld-linux-aarch64.so.1
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.errors.any { it.contains("ld-linux-aarch64.so.1") })
    }

    // ─── validatePayload: warnings ────────────────────────────────────────────

    @Test
    fun `validatePayload warns when etc directory is missing`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()
        // No etc/
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.warnings.any { it.contains("etc/") })
    }

    @Test
    fun `validatePayload warns about missing SSL certs`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()
        File(prefixDir, "etc").mkdirs()
        // No cert.pem or certs dir
        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.warnings.any { it.contains("SSL") })
    }

    // ─── validatePayload: full valid layout ───────────────────────────────────

    @Test
    fun `validatePayload passes with complete minimal layout`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()
        File(prefixDir, "etc/tls").mkdirs()
        File(prefixDir, "etc/tls/cert.pem").createNewFile()

        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.passed)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `validatePayload passes with bash instead of sh`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/bash").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()
        File(prefixDir, "etc/tls").mkdirs()
        File(prefixDir, "etc/tls/cert.pem").createNewFile()

        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.passed)
    }

    @Test
    fun `validatePayload accepts certs directory instead of cert pem`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()
        File(prefixDir, "etc/tls/certs").mkdirs()

        val result = InstallValidator.validatePayload(prefixDir)
        assertTrue(result.passed)
        assertFalse(result.warnings.any { it.contains("SSL") })
    }

    // ─── isStructurallyComplete ───────────────────────────────────────────────

    @Test
    fun `isStructurallyComplete returns false for empty prefix`() {
        assertFalse(InstallValidator.isStructurallyComplete(prefixDir))
    }

    @Test
    fun `isStructurallyComplete returns false when glibc linker missing`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        // No glibc linker
        assertFalse(InstallValidator.isStructurallyComplete(prefixDir))
    }

    @Test
    fun `isStructurallyComplete returns true with shell and glibc linker`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/sh").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()

        // /system/bin/sh exists on the host machine running tests
        assertTrue(InstallValidator.isStructurallyComplete(prefixDir))
    }

    @Test
    fun `isStructurallyComplete returns true with node binary`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "bin/node").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()

        assertTrue(InstallValidator.isStructurallyComplete(prefixDir))
    }

    @Test
    fun `isStructurallyComplete returns true with openclaw mjs`() {
        File(prefixDir, "bin").mkdirs()
        File(prefixDir, "lib/node_modules/openclaw").mkdirs()
        File(prefixDir, "lib/node_modules/openclaw/openclaw.mjs").createNewFile()
        File(prefixDir, "lib").mkdirs()
        File(prefixDir, "glibc/lib").mkdirs()
        File(prefixDir, "glibc/lib/ld-linux-aarch64.so.1").createNewFile()

        assertTrue(InstallValidator.isStructurallyComplete(prefixDir))
    }
}
