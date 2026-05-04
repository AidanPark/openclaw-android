package com.openclaw.android.core.install

import android.content.Context
import java.io.File

/**
 * Resuelve todas las rutas del sistema de archivos usadas durante la instalación.
 *
 * Centraliza la lógica de resolución de directorios para que el resto de
 * componentes no necesiten conocer la estructura interna del filesystem.
 */
internal class InstallPathResolver(private val context: Context) {

    val filesDir: File = context.filesDir
    val homeDir: File = File(filesDir, "home")
    val cacheDir: File = context.cacheDir

    /**
     * Directorio prefix dinámico — coincide con la lógica de EnvironmentBuilder.
     * Se recalcula en cada acceso porque puede cambiar tras la extracción.
     */
    val prefix: File
        get() {
            val payloadDir = listOf(
                File(homeDir, "payload"),
                File(homeDir, "openclaw-payload"),
                File(filesDir, "payload"),
                File(filesDir, "openclaw-payload")
            ).find { it.isDirectory }
            return payloadDir ?: File(filesDir, "usr")
        }

    val markerInstalled: File
        get() = File(filesDir, ".installed")

    /**
     * Resuelve el directorio del payload extraído.
     * Si no existe ninguno, crea y devuelve homeDir/payload como fallback.
     */
    fun resolvePayloadDir(): File {
        return listOf(
            File(homeDir, "payload"),
            File(homeDir, "openclaw-payload"),
            File(filesDir, "payload"),
            File(filesDir, "openclaw-payload"),
        ).firstOrNull { it.isDirectory }
            ?: File(homeDir, "payload").also { it.mkdirs() }
    }

    /**
     * Busca glibc-aarch64.tar.xz como fallback si glibc no está en el payload.
     * Incluye Descargas del teléfono para instalación manual.
     */
    fun findGlibcArchive(payloadDir: File): File? {
        val candidates = listOf(
            File(payloadDir, "glibc-aarch64.tar.xz"),
            File(homeDir, "glibc-aarch64.tar.xz"),
            File(filesDir, "glibc-aarch64.tar.xz"),
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "glibc-aarch64.tar.xz"
            ),
        )
        return candidates.firstOrNull { it.exists() && it.length() > 10_000 }
    }

    /**
     * Devuelve la ruta del script principal de lanzamiento de OpenClaw.
     * Prioriza el flujo proot, luego el payload offline.
     */
    fun getRunScriptPath(): File {
        // Flujo proot: script generado por SetupManager
        val prootScript = File(filesDir, "rootfs/start-openclaw.sh")
        if (prootScript.exists()) return prootScript

        // Flujo online (curl | bash)
        val onlineScript = File(homeDir, ".openclaw-android/bin/openclaw")
        if (onlineScript.exists()) return onlineScript

        // Flujo payload offline
        val payloadDir = resolvePayloadDir()
        val payloadScript = File(payloadDir, "run-openclaw.sh")
        if (payloadScript.exists()) return payloadScript

        // Fallback: script en homeDir
        return File(homeDir, "openclaw-start.sh")
    }

    fun getWwwDir(): File = File(prefix, "share/openclaw-app/www")
    fun getPrefixDir(): File = prefix
    fun getHomeDir(): File = homeDir
}
