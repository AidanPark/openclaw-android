package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import com.openclaw.android.TermuxBootstrapManager
import java.io.File

/**
 * Maneja la ejecución de `dpkg --configure -a` de forma no-interactiva.
 *
 * Responsabilidad única: ejecutar dpkg para reparar el estado de paquetes
 * después de la extracción, respondiendo automáticamente a prompts interactivos.
 *
 * Problema: al configurar apt por primera vez, dpkg puede preguntar:
 *   "What would you like to do about sources.list? [Y/I/N/O/D/Z]"
 *
 * Solución:
 *   1. DEBIAN_FRONTEND=noninteractive suprime la mayoría de prompts
 *   2. Pasamos "N\n" por stdin como respuesta por defecto (mantener versión actual)
 *   3. Usamos `--force-confold` para que dpkg no pregunte sobre archivos de config
 *
 * "N" = mantener el sources.list actual (correcto para Termux).
 */
internal class TermuxDpkgManager(
    private val prefix: File,
    private val homeDir: File,
    private val envConfigurator: TermuxEnvironmentConfigurator,
) {

    private val TAG = "TermuxDpkgManager"

    /**
     * Ejecuta `dpkg --configure -a` de forma completamente no-interactiva.
     *
     * @param listener Para reportar progreso (opcional)
     */
    fun runDpkgConfigure(listener: TermuxBootstrapManager.ProgressListener? = null) {
        val dpkg = File(prefix, "bin/dpkg")
        if (!dpkg.exists()) {
            AppLogger.w(TAG, "dpkg not found, skipping configure")
            return
        }

        val env = envConfigurator.buildTermuxEnv()
        // DEBIAN_FRONTEND=noninteractive es la clave para evitar prompts
        val fullEnv = env + mapOf(
            "DEBIAN_FRONTEND" to "noninteractive",
            "DEBCONF_NONINTERACTIVE_SEEN" to "true",
            "DPKG_FRONTEND_LOCKED" to "1",
        )

        AppLogger.i(TAG, "Running dpkg --configure -a (non-interactive)")

        try {
            val pb = ProcessBuilder(dpkg.absolutePath, "--configure", "-a", "--force-confold")
            pb.environment().clear()
            pb.environment().putAll(fullEnv)
            pb.directory(homeDir)
            pb.redirectErrorStream(true)

            val process = pb.start()

            // Responder "N" a cualquier prompt interactivo que dpkg envíe
            // "--force-confold" debería evitarlo, pero por si acaso:
            Thread {
                try {
                    process.outputStream.bufferedWriter().use { writer ->
                        // Esperar un poco y enviar "N" repetidamente como seguro
                        Thread.sleep(500)
                        repeat(10) {
                            writer.write("N\n")
                            writer.flush()
                            Thread.sleep(200)
                        }
                    }
                } catch (_: Exception) {}
            }.start()

            val outputThread = Thread {
                process.inputStream.bufferedReader().forEachLine { line ->
                    AppLogger.d(TAG, "[dpkg-configure] $line")
                    // Si dpkg pregunta sobre sources.list, loguear para diagnóstico
                    if (line.contains("sources.list", ignoreCase = true) ||
                        line.contains("What would you like", ignoreCase = true)) {
                        AppLogger.w(TAG, "dpkg interactive prompt detected: $line")
                        listener?.onProgress(76, "Configurando dpkg (respondiendo N)...")
                    }
                }
            }
            outputThread.start()

            val finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            outputThread.join(3000)

            if (!finished) {
                process.destroyForcibly()
                AppLogger.w(TAG, "dpkg --configure -a timed out (non-fatal)")
            } else {
                AppLogger.i(TAG, "dpkg --configure -a exited with code ${process.exitValue()}")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "dpkg --configure -a failed (non-fatal): ${e.message}")
        }
    }
}
