package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import java.io.File

/**
 * Maneja el marcador de instalación del bootstrap de Termux.
 *
 * Responsabilidad única: leer/escribir el archivo de marcador y
 * verificar si el bootstrap está instalado correctamente.
 */
internal class TermuxBootstrapMarker(
    private val filesDir: File,
    private val prefix: File,
) {

    private val TAG = "TermuxBootstrapMarker"
    private val markerFile = File(filesDir, ".termux-bootstrap-installed")

    /**
     * Verifica si el bootstrap está instalado correctamente.
     */
    fun isInstalled(): Boolean {
        if (!markerFile.exists()) return false
        return File(prefix, "bin/dpkg").exists() &&
               File(prefix, "bin/apt").exists() &&
               File(prefix, "bin/bash").exists()
    }

    /**
     * Escribe el marcador de instalación con metadatos.
     *
     * @param arch Arquitectura instalada
     */
    fun writeMarker(arch: String) {
        markerFile.writeText(
            "architecture=$arch\n" +
            "installed_at=${System.currentTimeMillis()}\n" +
            "prefix=${prefix.absolutePath}\n" +
            "version=termux-bootstrap-2026.02.12\n"
        )
        AppLogger.i(TAG, "Marker written: ${markerFile.absolutePath}")
    }

    /**
     * Elimina el marcador (para desinstalación).
     */
    fun deleteMarker() {
        markerFile.delete()
    }

    /**
     * Obtiene información del marcador si existe.
     */
    fun readMarker(): Map<String, String>? {
        if (!markerFile.exists()) return null
        return try {
            markerFile.readLines().associate { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else "" to ""
            }.filter { it.key.isNotEmpty() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read marker: ${e.message}")
            null
        }
    }
}
