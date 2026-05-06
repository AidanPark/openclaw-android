package com.openclaw.android.core.bootstrap

import android.content.Context
import com.openclaw.android.AppLogger
import java.io.File

/**
 * Configura el gestor de paquetes (apt) para Termux.
 */
class TermuxPkgManager(private val context: Context) {

    companion object {
        private const val TAG = "TermuxPkgManager"
    }

    fun setupPackageManager() {
        try {
            // Crear directorios necesarios
            val usrDir = File(context.filesDir, "usr")
            val binDir = File(usrDir, "bin")
            val libDir = File(usrDir, "lib")
            val etcDir = File(usrDir, "etc")

            binDir.mkdirs()
            libDir.mkdirs()
            etcDir.mkdirs()

            // Configurar apt sources si es necesario
            val sourcesList = File(etcDir, "apt/sources.list")
            if (!sourcesList.exists()) {
                sourcesList.parentFile?.mkdirs()
                sourcesList.writeText("deb https://packages.termux.dev/apt/termux-main stable main\n")
            }

            // Crear link simbólico o script wrapper para termux-fix-shebang si existe
            val fixShebang = File(binDir, "termux-fix-shebang")
            if (!fixShebang.exists()) {
                // Crear un script simple
                fixShebang.writeText("#!/bin/sh\n# termux-fix-shebang placeholder\nexec \"\$@\"\n")
                fixShebang.setExecutable(true, false)
            }

            AppLogger.i(TAG, "Package manager setup complete")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Setup failed: ${e.message}", e)
        }
    }

    /**
     * Verifica si apt está disponible.
     */
    fun isAptAvailable(): Boolean {
        val apt = File(context.filesDir, "usr/bin/apt")
        return apt.exists() && apt.canExecute()
    }

    /**
     * Obtiene la versión de apt.
     */
    fun getAptVersion(): String {
        return try {
            val process = ProcessBuilder(
                File(context.filesDir, "usr/bin/apt").absolutePath,
                "--version"
            )
                .directory(context.filesDir)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            "unknown"
        }
    }
}
