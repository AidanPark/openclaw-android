package com.openclaw.android.core.install

import com.openclaw.android.AppLogger
import com.openclaw.android.core.env.EnvironmentConfig
import com.openclaw.android.core.process.GlibcRunner
import java.io.File

/**
 * VersionReader — reads installed component versions without shell execution.
 *
 * Reads from package.json and installed.json files where possible.
 * Falls back to GlibcRunner for node --version (correct approach, not /system/bin/sh).
 */
object VersionReader {

    private const val TAG = "VersionReader"

    data class Versions(
        val openclaw: String,
        val node: String,
        val npm: String,
        val glibc: String,
    )

    fun readAll(config: EnvironmentConfig): Versions = Versions(
        openclaw = readOpenClawVersion(config),
        node = readNodeVersion(config),
        npm = readNpmVersion(config),
        glibc = if (config.isGlibcReady) "present (${config.linker.length() / 1024}KB)" else "missing",
    )

    fun readOpenClawVersion(config: EnvironmentConfig): String {
        val candidates = listOf(
            File(config.payloadDir, "openclaw/package.json"),
            File(config.payloadDir, "lib/openclaw/package.json"),
            File(config.prefix, "lib/node_modules/openclaw/package.json"),
        )
        for (pkg in candidates) {
            if (pkg.exists()) {
                val version = extractJsonField(pkg, "version")
                if (version != null) return version
            }
        }
        return "not installed"
    }

    fun readNodeVersion(config: EnvironmentConfig): String {
        // First try installed.json (fast, no process spawn)
        val installedJson = File(config.ocaDir, "installed.json")
        if (installedJson.exists()) {
            val version = extractJsonField(installedJson, "nodeVersion")
            if (version != null && version != "unknown") return version
        }

        // Fall back to running node --version via glibc linker (correct approach)
        if (config.isGlibcReady && config.isNodeReady) {
            val version = GlibcRunner.checkNodeVersion(config.payloadDir)
            if (version.isNotEmpty() && !version.startsWith("error") && !version.startsWith("not")) {
                return version
            }
        }

        return "unknown"
    }

    fun readNpmVersion(config: EnvironmentConfig): String {
        val candidates = listOf(
            File(config.payloadDir, "glibc/lib/node_modules/npm/package.json"),
            File(config.prefix, "lib/node_modules/npm/package.json"),
        )
        for (pkg in candidates) {
            if (pkg.exists()) {
                val version = extractJsonField(pkg, "version")
                if (version != null) return version
            }
        }
        return "unknown"
    }

    fun readInstalledMarker(config: EnvironmentConfig): Map<String, String> {
        val marker = File(config.ocaDir, "installed.json")
        if (!marker.exists()) return emptyMap()
        return try {
            val content = marker.readText()
            mapOf(
                "openclawVersion" to (extractJsonField(content, "openclawVersion") ?: "unknown"),
                "nodeVersion" to (extractJsonField(content, "nodeVersion") ?: "unknown"),
                "source" to (extractJsonField(content, "source") ?: "unknown"),
                "installedAt" to (extractJsonField(content, "installedAt") ?: ""),
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun extractJsonField(file: File, field: String): String? =
        try { extractJsonField(file.readText(), field) } catch (_: Exception) { null }

    private fun extractJsonField(content: String, field: String): String? =
        Regex(""""$field"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.getOrNull(1)
}
