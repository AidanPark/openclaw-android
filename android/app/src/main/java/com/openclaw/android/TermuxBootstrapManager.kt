package com.openclaw.android

import android.content.Context
import com.openclaw.android.core.bootstrap.TermuxBootstrapOrchestrator
import java.io.File

/**
 * TermuxBootstrapManager — Fachada para instalación de Termux Bootstrap (ONLINE).
 *
 * Este sistema permite la instalación ONLINE de OpenClaw via curl | bash.
 * Provee: curl, bash, sh, apt - necesarios para scripts de instalación online.
 *
 * ⚠️ MUTUAMENTE EXCLUYENTE con:
 *   - Proot (sistema mini Linux independiente)
 *   - Payload (OpenClaw embebido offline)
 *
 * NO instalar si ya existe Proot o Payload instalado.
 */
class TermuxBootstrapManager(private val context: Context) {

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    companion object {
        private const val TAG = "TermuxBootstrapManager"
        private const val MARKER_FILE = ".termux-bootstrap-installed"

        /**
         * Verifica si Termux Bootstrap está instalado.
         */
        fun isInstalled(context: Context): Boolean {
            val marker = File(context.filesDir, MARKER_FILE)
            val prefixDir = File(context.filesDir, "usr")
            return marker.exists() && prefixDir.exists() && prefixDir.isDirectory
        }

        /**
         * Verifica si existe un sistema CONFLICTIVO instalado (Proot o Payload).
         * Termux NO debe instalarse si existe otro sistema.
         */
        fun hasConflictingSystem(context: Context): Boolean {
            // Proot instalado
            val prootMarker = File(context.filesDir, ".proot-installed")
            val rootfsDir = File(context.filesDir, "ubuntu-rootfs")
            if (prootMarker.exists() || rootfsDir.exists()) {
                AppLogger.w(TAG, "Conflicting system detected: Proot")
                return true
            }

            // Payload instalado
            val payloadMarker = File(context.filesDir, ".payload-installed")
            val payloadDir = File(context.filesDir, "home/payload")
            if (payloadMarker.exists() && payloadDir.exists()) {
                AppLogger.w(TAG, "Conflicting system detected: Payload")
                return true
            }

            return false
        }

        /**
         * Obtiene mensaje de error apropiado para el conflicto detectado.
         */
        fun getConflictMessage(context: Context): String {
            return when {
                File(context.filesDir, ".proot-installed").exists() ->
                    "Cannot install Termux: Proot system is already installed. Uninstall Proot first."
                File(context.filesDir, ".payload-installed").exists() ->
                    "Cannot install Termux: Payload (offline) system is already installed. Uninstall it first."
                else -> "Cannot install Termux: Another system is already installed."
            }
        }
    }

    /**
     * Verifica si Termux Bootstrap está instalado (instancia).
     */
    fun isInstalled(): Boolean = isInstalled(context)

    /**
     * Verifica si existe sistema conflictivo.
     */
    fun hasConflictingSystem(): Boolean = hasConflictingSystem(context)

    /**
     * Instala Termux Bootstrap desde la URL configurada.
     * Requiere conexión a internet.
     *
     * @throws IllegalStateException si existe un sistema conflictivo
     */
    fun install(listener: ProgressListener) {
        // Validación: No instalar si existe otro sistema
        if (hasConflictingSystem()) {
            val msg = getConflictMessage(context)
            AppLogger.e(TAG, msg)
            listener.onError(msg, IllegalStateException(msg))
            return
        }

        // Validación: Ya instalado
        if (isInstalled()) {
            AppLogger.i(TAG, "Termux Bootstrap already installed")
            listener.onSuccess()
            return
        }

        val orchestrator = TermuxBootstrapOrchestrator(context)
        orchestrator.install(object : TermuxBootstrapOrchestrator.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                // Escribir marcador propio
                try {
                    File(context.filesDir, MARKER_FILE).writeText("${System.currentTimeMillis()}")
                    AppLogger.i(TAG, "Termux Bootstrap marker written")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                listener.onError(message, cause)
            }
        })
    }

    /**
     * Desinstala Termux Bootstrap.
     */
    fun uninstall(): Boolean {
        return try {
            File(context.filesDir, MARKER_FILE).delete()
            File(context.filesDir, "usr").deleteRecursively()
            File(context.filesDir, "home").deleteRecursively()
            AppLogger.i(TAG, "Termux Bootstrap uninstalled")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Uninstall failed: ${e.message}", e)
            false
        }
    }
}
