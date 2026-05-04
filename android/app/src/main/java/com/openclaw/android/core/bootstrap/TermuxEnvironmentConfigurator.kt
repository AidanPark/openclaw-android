package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import java.io.File

/**
 * Configura el entorno base de Termux después de la extracción.
 *
 * Responsabilidad única: crear directorios necesarios, configurar DNS,
 * aplicar permisos y construir variables de entorno.
 */
internal class TermuxEnvironmentConfigurator(
    private val context: android.content.Context,
    private val prefix: File,
    private val homeDir: File,
) {

    private val TAG = "TermuxEnvironmentConfigurator"

    /**
     * Configura el entorno base:
     *   - Crea directorios tmp, etc
     *   - Configura resolv.conf (DNS)
     *   - Aplica permisos ejecutables en bin/
     *   - Copia el script de entrypoint
     */
    fun setupEnvironment(context: android.content.Context) {
        File(prefix, "tmp").mkdirs()
        homeDir.mkdirs()

        val etcDir = File(prefix, "etc")
        etcDir.mkdirs()

        // resolv.conf — DNS
        val resolvConf = File(etcDir, "resolv.conf")
        if (!resolvConf.exists() || resolvConf.length() == 0L) {
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
            AppLogger.i(TAG, "Created resolv.conf")
        }

        // Permisos en bin/
        File(prefix, "bin").listFiles()?.forEach { f ->
            if (f.isFile) f.setExecutable(true, false)
        }

        // Copiar script de entrypoint
        copyEntrypointScript(context)

        AppLogger.i(TAG, "Environment setup complete at ${prefix.absolutePath}")
    }

    /**
     * Copia el script de entrypoint desde assets al home directory.
     */
    private fun copyEntrypointScript(context: android.content.Context) {
        try {
            val entrypointScript = File(homeDir, "termux-entrypoint.sh")
            context.assets.open("termux-entrypoint.sh").use { input ->
                entrypointScript.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            entrypointScript.setExecutable(true, false)
            AppLogger.i(TAG, "Entrypoint script copied to ${entrypointScript.absolutePath}")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to copy entrypoint script: ${e.message}")
        }
    }

    /**
     * Variables de entorno base para ejecutar comandos de Termux.
     * DEBIAN_FRONTEND=noninteractive se agrega en cada llamada que lo necesite.
     */
    fun buildTermuxEnv(): Map<String, String> = mapOf(
        "HOME"         to homeDir.absolutePath,
        "PREFIX"       to prefix.absolutePath,
        "TMPDIR"       to File(prefix, "tmp").absolutePath,
        "PATH"         to "${prefix.absolutePath}/bin:/system/bin:/bin",
        "LANG"         to "en_US.UTF-8",
        "TERM"         to "xterm-256color",
        "ANDROID_DATA" to "/data",
        "ANDROID_ROOT" to "/system",
    )

    /**
     * Verifica que los binarios esenciales existen.
     */
    fun hasEssentialBinaries(): Boolean {
        return File(prefix, "bin/dpkg").exists() &&
               File(prefix, "bin/apt").exists() &&
               File(prefix, "bin/bash").exists()
    }
}
