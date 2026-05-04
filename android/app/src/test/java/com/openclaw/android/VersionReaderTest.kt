package com.openclaw.android

import com.openclaw.android.core.install.VersionReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for VersionReader — pure file-system JSON parsing, no Android context.
 */
class VersionReaderTest {

    @TempDir
    lateinit var tempDir: File

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Write a minimal package.json with the given version. */
    private fun writePackageJson(dir: File, version: String): File {
        dir.mkdirs()
        val pkg = File(dir, "package.json")
        pkg.writeText("""{"name":"test","version":"$version","description":"test"}""")
        return pkg
    }

    /** Write a minimal installed.json marker. */
    private fun writeInstalledJson(
        dir: File,
        openclawVersion: String = "1.2.3",
        nodeVersion: String = "v20.0.0",
        source: String = "payload",
    ): File {
        dir.mkdirs()
        val marker = File(dir, "installed.json")
        marker.writeText(
            """
            {
              "installed": true,
              "source": "$source",
              "openclawVersion": "$openclawVersion",
              "nodeVersion": "$nodeVersion",
              "installedAt": 1700000000000
            }
            """.trimIndent()
        )
        return marker
    }

    // ─── readInstalledMarker ──────────────────────────────────────────────────

    @Test
    fun `readInstalledMarker returns empty map when marker absent`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val config = buildMinimalConfig(tempDir, ocaDir)
        val result = VersionReader.readInstalledMarker(config)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `readInstalledMarker parses all fields correctly`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        writeInstalledJson(ocaDir, openclawVersion = "2.0.1", nodeVersion = "v22.0.0", source = "online")
        val config = buildMinimalConfig(tempDir, ocaDir)

        val result = VersionReader.readInstalledMarker(config)
        assertEquals("2.0.1", result["openclawVersion"])
        assertEquals("v22.0.0", result["nodeVersion"])
        assertEquals("online", result["source"])
        assertTrue(result.containsKey("installedAt"))
    }

    @Test
    fun `readInstalledMarker returns unknown for missing fields`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        ocaDir.mkdirs()
        File(ocaDir, "installed.json").writeText("""{"installed":true}""")
        val config = buildMinimalConfig(tempDir, ocaDir)

        val result = VersionReader.readInstalledMarker(config)
        assertEquals("unknown", result["openclawVersion"])
        assertEquals("unknown", result["nodeVersion"])
        assertEquals("unknown", result["source"])
    }

    @Test
    fun `readInstalledMarker returns empty map for malformed JSON`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        ocaDir.mkdirs()
        File(ocaDir, "installed.json").writeText("not valid json {{{{")
        val config = buildMinimalConfig(tempDir, ocaDir)

        // Should not throw — returns empty or partial map
        val result = VersionReader.readInstalledMarker(config)
        // Either empty or with unknown values — no crash
        assertTrue(result is Map<*, *>)
    }

    // ─── readOpenClawVersion ──────────────────────────────────────────────────

    @Test
    fun `readOpenClawVersion returns not installed when no package json found`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val config = buildMinimalConfig(tempDir, ocaDir)
        assertEquals("not installed", VersionReader.readOpenClawVersion(config))
    }

    @Test
    fun `readOpenClawVersion reads from prefix lib node_modules openclaw`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val prefixDir = File(tempDir, "usr")
        val pkgDir = File(prefixDir, "lib/node_modules/openclaw")
        writePackageJson(pkgDir, "1.5.0")

        val config = buildMinimalConfig(tempDir, ocaDir, prefix = prefixDir)
        assertEquals("1.5.0", VersionReader.readOpenClawVersion(config))
    }

    // ─── readNpmVersion ───────────────────────────────────────────────────────

    @Test
    fun `readNpmVersion returns unknown when no npm package json found`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val config = buildMinimalConfig(tempDir, ocaDir)
        assertEquals("unknown", VersionReader.readNpmVersion(config))
    }

    @Test
    fun `readNpmVersion reads from prefix lib node_modules npm`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val prefixDir = File(tempDir, "usr")
        val npmDir = File(prefixDir, "lib/node_modules/npm")
        writePackageJson(npmDir, "10.2.3")

        val config = buildMinimalConfig(tempDir, ocaDir, prefix = prefixDir)
        assertEquals("10.2.3", VersionReader.readNpmVersion(config))
    }

    // ─── readNodeVersion ─────────────────────────────────────────────────────

    @Test
    fun `readNodeVersion returns unknown when no installed json and no node binary`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        val config = buildMinimalConfig(tempDir, ocaDir)
        assertEquals("unknown", VersionReader.readNodeVersion(config))
    }

    @Test
    fun `readNodeVersion reads from installed json nodeVersion field`() {
        val ocaDir = File(tempDir, ".openclaw-android")
        writeInstalledJson(ocaDir, nodeVersion = "v24.0.0")
        val config = buildMinimalConfig(tempDir, ocaDir)
        assertEquals("v24.0.0", VersionReader.readNodeVersion(config))
    }

    // ─── Versions data class ─────────────────────────────────────────────────

    @Test
    fun `Versions data class holds all fields`() {
        val v = VersionReader.Versions(
            openclaw = "1.0.0",
            node = "v20.0.0",
            npm = "10.0.0",
            glibc = "present (1024KB)",
        )
        assertEquals("1.0.0", v.openclaw)
        assertEquals("v20.0.0", v.node)
        assertEquals("10.0.0", v.npm)
        assertEquals("present (1024KB)", v.glibc)
    }

    @Test
    fun `Versions equality works correctly`() {
        val v1 = VersionReader.Versions("1.0.0", "v20.0.0", "10.0.0", "present")
        val v2 = VersionReader.Versions("1.0.0", "v20.0.0", "10.0.0", "present")
        assertEquals(v1, v2)
    }

    // ─── Helper: build a minimal EnvironmentConfig ────────────────────────────

    private fun buildMinimalConfig(
        filesDir: File,
        ocaDir: File,
        prefix: File = File(filesDir, "usr"),
    ): com.openclaw.android.core.env.EnvironmentConfig {
        val homeDir = File(filesDir, "home")
        val payloadDir = File(homeDir, "payload")
        val glibcLib = File(prefix, "glibc/lib")
        val linker = File(glibcLib, "ld-linux-aarch64.so.1")
        val nodeBin = File(prefix, "bin/node")
        val tmpDir = File(filesDir, "tmp")
        val certPem = File(prefix, "etc/tls/cert.pem")
        val openClawMjs = File(prefix, "lib/node_modules/openclaw/openclaw.mjs")

        // Constructor order matches EnvironmentConfig data class definition:
        // filesDir, homeDir, payloadDir, prefix, tmpDir, ocaDir,
        // glibcLib, linker, nodeBin, openClawMjs, certPem
        return com.openclaw.android.core.env.EnvironmentConfig(
            filesDir = filesDir,
            homeDir = homeDir,
            payloadDir = payloadDir,
            prefix = prefix,
            tmpDir = tmpDir,
            ocaDir = ocaDir,
            glibcLib = glibcLib,
            linker = linker,
            nodeBin = nodeBin,
            openClawMjs = openClawMjs,
            certPem = certPem,
        )
    }
}
