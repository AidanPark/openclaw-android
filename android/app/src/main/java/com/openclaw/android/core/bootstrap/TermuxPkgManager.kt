package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import com.openclaw.android.TermuxBootstrapManager
import java.io.File

/**
 * Maneja la ejecución de `pkg update` con reintentos automáticos.
 *
 * Responsabilidad única: ejecutar pkg update para actualizar repositorios,
 * con reintentos y reparación automática de dpkg entre intentos.
 */
internal class TermuxPkgManager(
    private val prefix: File,
    private val homeDir: File,
    private val envConfigurator: TermuxEnvironmentConfigurator,
    private val dpkgManager: TermuxDpkgManager,
) {

    private val TAG = "TermuxPkgManager"

    /**
     * Ejecuta `pkg update` con hasta [maxAttempts] reintentos.
     *
     * Si falla, ejecuta `dpkg --configure -a` antes de reintentar.
     * Esto resuelve el caso donde dpkg quedó en estado inconsistente.
     *
     * @return true si pkg update tuvo éxito en algún intento
     */
    fun runPkgUpdateWithRetry(
        listener: TermuxBootstrapManager.ProgressListener,
        maxAttempts: Int = 3,
    ): Boolean {
        val bash = File(prefix, "bin/bash")
        if (!bash.exists()) {
            AppLogger.w(TAG, "bash not found, skipping pkg update")
            return false
        }

        repeat(maxAttempts) { attempt ->
            val attemptNum = attempt + 1
            listener.onProgress(80 + attempt * 2, "pkg update (intento $attemptNum/$maxAttempts)...")
            AppLogger.i(TAG, "pkg update attempt $attemptNum/$maxAttempts")

            val success = runSinglePkgUpdate(bash)
            if (success) {
                AppLogger.i(TAG, "pkg update succeeded on attempt $attemptNum")
                return true
            }

            // Falló — ejecutar dpkg --configure -a antes de reintentar
            if (attemptNum < maxAttempts) {
                AppLogger.w(TAG, "pkg update failed, running dpkg --configure -a before retry...")
                listener.onProgress(81 + attempt * 2, "Reparando dpkg antes de reintentar...")
                dpkgManager.runDpkgConfigure(listener)
                Thread.sleep(1000)
            }
        }

        return false
    }

    /**
     * Ejecuta un único intento de `pkg update`.
     *
     * Usa DEBIAN_FRONTEND=noninteractive y --force-confold para evitar
     * cualquier prompt interactivo, incluyendo el de sources.list.
     */
    private fun runSinglePkgUpdate(bash: File): Boolean {
        val env = envConfigurator.buildTermuxEnv() + mapOf(
            "DEBIAN_FRONTEND"              to "noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN"  to "true",
        )

        // El script pasa "N" a stdin como respuesta por defecto a cualquier prompt
        // y usa --force-confold para que dpkg no pregunte sobre archivos de config
        val script = """
            export DEBIAN_FRONTEND=noninteractive
            export DEBCONF_NONINTERACTIVE_SEEN=true
            export PATH="${prefix.absolutePath}/bin:${'$'}PATH"
            export PREFIX="${prefix.absolutePath}"
            export HOME="${homeDir.absolutePath}"
            export TMPDIR="${prefix.absolutePath}/tmp"

            # Responder N a cualquier prompt de dpkg sobre archivos de configuración
            yes N | pkg update -y -o Dpkg::Options::="--force-confold" 2>&1
        """.trimIndent()

        return try {
            val pb = ProcessBuilder(bash.absolutePath, "-c", script)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()

            val output = StringBuilder()
            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[pkg update] $line")
                    output.appendLine(line)
                }
            }
            outputThread.start()

            val finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            outputThread.join(5000)

            if (!finished) {
                process.destroyForcibly()
                AppLogger.w(TAG, "pkg update timed out")
                return false
            }

            val exitCode = process.exitValue()
            AppLogger.i(TAG, "pkg update exit code: $exitCode")

            // Considerar éxito si el exit code es 0 o si la salida indica éxito
            exitCode == 0 || output.contains("Reading package lists") || output.contains("All packages are up to date")
        } catch (e: Exception) {
            AppLogger.e(TAG, "pkg update exception: ${e.message}", e)
            false
        }
    }
}
