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
     *   - Crea bash.bashrc y .bashrc con el entorno correcto
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

        // bash.bashrc — bash is compiled with /data/data/com.termux/files/usr
        // hardcoded as prefix, so it looks for bash.bashrc at that path.
        // We create it at the real path so bash finds it without errors.
        // Also set up PATH and PREFIX correctly for interactive sessions.
        val bashRc = File(etcDir, "bash.bashrc")
        if (!bashRc.exists()) {
            bashRc.writeText(buildString {
                appendLine("# OpenClaw bash.bashrc — auto-generated")
                appendLine("export PREFIX=\"${prefix.absolutePath}\"")
                appendLine("export HOME=\"${homeDir.absolutePath}\"")
                appendLine("export TMPDIR=\"${File(prefix, "tmp").absolutePath}\"")
                appendLine("export PATH=\"${prefix.absolutePath}/bin:${prefix.absolutePath}/bin/applets:/system/bin:/bin\"")
                appendLine("export LD_LIBRARY_PATH=\"${prefix.absolutePath}/lib\"")
                appendLine("export LANG=en_US.UTF-8")
                appendLine("export TERM=xterm-256color")
            })
            AppLogger.i(TAG, "Created bash.bashrc at ${bashRc.absolutePath}")
        }

        // .bashrc in homeDir — loaded by interactive bash sessions
        val homeBashRc = File(homeDir, ".bashrc")
        if (!homeBashRc.exists()) {
            homeBashRc.writeText(buildString {
                appendLine("# OpenClaw .bashrc — auto-generated")
                appendLine("export PS1='\\u@openclaw:\\w\\$ '")
                appendLine("alias ls='ls --color=auto'")
                appendLine("alias ll='ls -la'")
            })
            AppLogger.i(TAG, "Created .bashrc at ${homeBashRc.absolutePath}")
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
