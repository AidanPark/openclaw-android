package com.openclaw.android

import android.util.Log
import com.openclaw.android.core.env.EnvironmentConfig
import com.openclaw.android.core.install.ScriptWriter
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for ScriptWriter — verifies generated shell script content and file creation.
 */
class ScriptWriterTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildConfig(
        withLinker: Boolean = true,
        withNode: Boolean = true,
        withRunScript: Boolean = false,
        withCompatJs: Boolean = false,
    ): EnvironmentConfig {
        val filesDir = tempDir
        val homeDir = File(filesDir, "home").also { it.mkdirs() }
        val payloadDir = File(homeDir, "payload").also { it.mkdirs() }
        val prefix = File(filesDir, "usr").also { it.mkdirs() }
        val tmpDir = File(filesDir, "tmp").also { it.mkdirs() }
        val ocaDir = File(homeDir, ".openclaw-android").also { it.mkdirs() }
        val glibcLib = File(prefix, "glibc/lib").also { it.mkdirs() }
        val linker = File(glibcLib, "ld-linux-aarch64.so.1")
        val nodeBin = File(prefix, "bin/node")
        val certPem = File(prefix, "etc/tls/cert.pem")
        val openClawMjs = File(prefix, "lib/node_modules/openclaw/openclaw.mjs")

        if (withLinker) {
            // Write a fake linker > 100KB so isGlibcReady = true
            linker.writeBytes(ByteArray(110_000) { 0x7f })
        }
        if (withNode) {
            File(prefix, "bin").mkdirs()
            // Write a fake node > 1MB so isNodeReady = true
            nodeBin.writeBytes(ByteArray(1_100_000) { 0x00 })
        }
        if (withRunScript) {
            File(payloadDir, "run-openclaw.sh").writeText("#!/system/bin/sh\necho run")
        }
        if (withCompatJs) {
            File(ocaDir, "patches").mkdirs()
            File(ocaDir, "patches/glibc-compat.js").writeText("// compat")
        }

        return EnvironmentConfig(
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

    // ─── writeGatewayScript ───────────────────────────────────────────────────

    @Test
    fun `writeGatewayScript creates openclaw-start sh in homeDir`() {
        val config = buildConfig()
        val result = ScriptWriter.writeGatewayScript(config)
        assertTrue(result)
        assertTrue(File(config.homeDir, "openclaw-start.sh").exists())
    }

    @Test
    fun `writeGatewayScript script is executable`() {
        val config = buildConfig()
        ScriptWriter.writeGatewayScript(config)
        val script = File(config.homeDir, "openclaw-start.sh")
        assertTrue(script.canExecute())
    }

    @Test
    fun `writeGatewayScript script starts with shebang`() {
        val config = buildConfig()
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        assertTrue(content.startsWith("#!/system/bin/sh"))
    }

    @Test
    fun `writeGatewayScript script contains unset LD_PRELOAD`() {
        val config = buildConfig()
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        assertTrue(content.contains("unset LD_PRELOAD"))
    }

    @Test
    fun `writeGatewayScript script exports OA_GLIBC=1`() {
        val config = buildConfig()
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        assertTrue(content.contains("OA_GLIBC=1"))
    }

    @Test
    fun `writeGatewayScript script exports CONTAINER=1`() {
        val config = buildConfig()
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        assertTrue(content.contains("CONTAINER=1"))
    }

    @Test
    fun `writeGatewayScript uses run-openclaw sh when present`() {
        val config = buildConfig(withRunScript = true)
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        assertTrue(content.contains("run-openclaw.sh"))
    }

    @Test
    fun `writeGatewayScript uses direct launch when run-openclaw sh absent`() {
        val config = buildConfig(withRunScript = false)
        ScriptWriter.writeGatewayScript(config)
        val content = File(config.homeDir, "openclaw-start.sh").readText()
        // Direct launch uses the linker or node wrapper
        assertTrue(content.contains("exec") || content.contains("node"))
    }

    // ─── writeNodeWrapper ─────────────────────────────────────────────────────

    @Test
    fun `writeNodeWrapper returns false when node binary absent`() {
        val config = buildConfig(withNode = false)
        val result = ScriptWriter.writeNodeWrapper(config)
        assertFalse(result)
    }

    @Test
    fun `writeNodeWrapper creates node wrapper in ocaDir bin`() {
        val config = buildConfig(withNode = true)
        val result = ScriptWriter.writeNodeWrapper(config)
        assertTrue(result)
        assertTrue(File(config.ocaDir, "bin/node").exists())
    }

    @Test
    fun `writeNodeWrapper script starts with shebang`() {
        val config = buildConfig(withNode = true)
        ScriptWriter.writeNodeWrapper(config)
        val content = File(config.ocaDir, "bin/node").readText()
        assertTrue(content.startsWith("#!/system/bin/sh"))
    }

    @Test
    fun `writeNodeWrapper script contains unset LD_PRELOAD`() {
        val config = buildConfig(withNode = true)
        ScriptWriter.writeNodeWrapper(config)
        val content = File(config.ocaDir, "bin/node").readText()
        assertTrue(content.contains("unset LD_PRELOAD"))
    }

    @Test
    fun `writeNodeWrapper script is executable`() {
        val config = buildConfig(withNode = true)
        ScriptWriter.writeNodeWrapper(config)
        val wrapper = File(config.ocaDir, "bin/node")
        assertTrue(wrapper.canExecute())
    }

    @Test
    fun `writeNodeWrapper includes glibc linker exec when glibc ready`() {
        val config = buildConfig(withLinker = true, withNode = true)
        ScriptWriter.writeNodeWrapper(config)
        val content = File(config.ocaDir, "bin/node").readText()
        // Should use the linker for glibc-wrapped execution
        assertTrue(content.contains("exec") && content.contains("library-path"))
    }

    @Test
    fun `writeNodeWrapper includes compat js when present`() {
        val config = buildConfig(withNode = true, withCompatJs = true)
        ScriptWriter.writeNodeWrapper(config)
        val content = File(config.ocaDir, "bin/node").readText()
        assertTrue(content.contains("glibc-compat.js"))
    }

    // ─── writeNpmWrapper ──────────────────────────────────────────────────────

    @Test
    fun `writeNpmWrapper returns false when npm-cli js absent`() {
        val config = buildConfig()
        val result = ScriptWriter.writeNpmWrapper(config)
        assertFalse(result)
    }

    @Test
    fun `writeNpmWrapper creates npm wrapper when npm-cli js present`() {
        val config = buildConfig(withNode = true)
        // Create npm-cli.js in the expected location
        val npmCliDir = File(config.prefix, "lib/node_modules/npm/bin")
        npmCliDir.mkdirs()
        File(npmCliDir, "npm-cli.js").writeText("// npm cli")

        // Also need node wrapper to exist
        File(config.ocaDir, "bin").mkdirs()
        File(config.ocaDir, "bin/node").writeText("#!/system/bin/sh\nexec node")

        val result = ScriptWriter.writeNpmWrapper(config)
        assertTrue(result)
        assertTrue(File(config.ocaDir, "bin/npm").exists())
    }

    @Test
    fun `writeNpmWrapper script delegates to node wrapper`() {
        val config = buildConfig(withNode = true)
        val npmCliDir = File(config.prefix, "lib/node_modules/npm/bin")
        npmCliDir.mkdirs()
        File(npmCliDir, "npm-cli.js").writeText("// npm cli")
        File(config.ocaDir, "bin").mkdirs()
        File(config.ocaDir, "bin/node").writeText("#!/system/bin/sh\nexec node")

        ScriptWriter.writeNpmWrapper(config)
        val content = File(config.ocaDir, "bin/npm").readText()
        assertTrue(content.contains("npm-cli.js"))
        assertTrue(content.contains("node"))
    }

    // ─── writeInstalledMarker ─────────────────────────────────────────────────

    @Test
    fun `writeInstalledMarker creates installed json`() {
        val config = buildConfig()
        val result = ScriptWriter.writeInstalledMarker(config, "1.0.0", "v20.0.0")
        assertTrue(result)
        assertTrue(File(config.ocaDir, "installed.json").exists())
    }

    @Test
    fun `writeInstalledMarker contains correct version fields`() {
        val config = buildConfig()
        ScriptWriter.writeInstalledMarker(config, "2.1.0", "v22.0.0", "online")
        val content = File(config.ocaDir, "installed.json").readText()
        assertTrue(content.contains("\"openclawVersion\": \"2.1.0\""))
        assertTrue(content.contains("\"nodeVersion\": \"v22.0.0\""))
        assertTrue(content.contains("\"source\": \"online\""))
        assertTrue(content.contains("\"installed\": true"))
    }

    @Test
    fun `writeInstalledMarker contains installedAt timestamp`() {
        val config = buildConfig()
        ScriptWriter.writeInstalledMarker(config, "1.0.0", "v20.0.0")
        val content = File(config.ocaDir, "installed.json").readText()
        assertTrue(content.contains("installedAt"))
    }

    @Test
    fun `writeInstalledMarker default source is payload`() {
        val config = buildConfig()
        ScriptWriter.writeInstalledMarker(config, "1.0.0", "v20.0.0")
        val content = File(config.ocaDir, "installed.json").readText()
        assertTrue(content.contains("\"source\": \"payload\""))
    }
}
