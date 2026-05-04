package com.openclaw.android.core.install

import com.openclaw.android.AppLogger
import java.io.File

/**
 * Escribe y lee los marcadores de instalación en disco.
 *
 * Responsabilidad única: persistir el estado de instalación y leer
 * metadatos de versión del payload extraído.
 */
internal class InstallMarkerWriter(
    private val paths: InstallPathResolver,
) {

    private val TAG = "InstallMarkerWriter"

    /**
     * Escribe el marcador legado (.installed) y el marcador de shell
     * (~/.openclaw-android/installed.json) con versión fija.
     */
    fun writeMarker() {
        try {
            paths.markerInstalled.writeText("0.4.0\n")

            val ocaDir = File(paths.homeDir, ".openclaw-android")
            ocaDir.mkdirs()
            val ocaMarker = File(ocaDir, "installed.json")
            ocaMarker.writeText("{\"version\": \"0.4.0\", \"status\": \"success\"}\n")

            AppLogger.i(
                TAG,
                "Installed markers written at ${paths.markerInstalled.absolutePath}" +
                    " and ${ocaMarker.absolutePath}"
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write marker: ${e.message}", e)
        }
    }

    /**
     * Escribe installed.json con las versiones detectadas del payload.
     */
    fun writeInstalledJson(payloadDir: File) {
        val ocaDir = File(paths.homeDir, ".openclaw-android")
        ocaDir.mkdirs()
        val marker = File(ocaDir, "installed.json")

        val openclawVersion = readOpenClawVersionFromPayload(payloadDir)
        val nodeVersion = readNodeVersionFromPayload(payloadDir)

        marker.writeText(
            """
            {
              "installed": true,
              "source": "payload",
              "openclawVersion": "$openclawVersion",
              "nodeVersion": "$nodeVersion",
              "payloadDir": "${payloadDir.absolutePath}",
              "installedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent()
        )
        AppLogger.i(TAG, "installed.json written: openclaw=$openclawVersion node=$nodeVersion")
    }

    /**
     * Lee la versión de OpenClaw desde el package.json del payload.
     * payload-final.tar.gz: payload/lib/openclaw/package.json
     */
    fun readOpenClawVersionFromPayload(payloadDir: File): String {
        val candidates = listOf(
            File(payloadDir, "lib/openclaw/package.json"),
            File(payloadDir, "openclaw/package.json"),
        )
        for (pkg in candidates) {
            if (pkg.exists()) {
                return try {
                    val match = Regex(""""version"\s*:\s*"([^"]+)"""").find(pkg.readText())
                    match?.groupValues?.getOrNull(1) ?: continue
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return "unknown"
    }

    /**
     * Lee la versión de Node.js ejecutando el binario del payload.
     * payload-final.tar.gz: payload/lib/node/bin/node.real
     */
    fun readNodeVersionFromPayload(payloadDir: File): String {
        val nodeReal = listOf(
            File(payloadDir, "lib/node/bin/node.real"),
            File(payloadDir, "glibc/bin/node"),
        ).firstOrNull { it.exists() && it.length() > 1_000_000 } ?: return "unknown"

        val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")

        return try {
            val cmd = if (ldso.exists()) {
                listOf(
                    ldso.absolutePath,
                    "--library-path",
                    File(payloadDir, "glibc/lib").absolutePath,
                    nodeReal.absolutePath,
                    "--version"
                )
            } else {
                listOf(nodeReal.absolutePath, "--version")
            }
            val pb = ProcessBuilder(cmd)
            pb.environment().apply {
                clear()
                put("PATH", "/system/bin:/bin")
                put("LD_LIBRARY_PATH", File(payloadDir, "glibc/lib").absolutePath)
            }
            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.removePrefix("v").trim().ifEmpty { "unknown" }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
