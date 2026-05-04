package com.openclaw.android.core.install

import android.content.Context
import android.net.Uri
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.SetupManager
import com.openclaw.android.TermuxBootstrapManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orquesta los flujos de instalación según el modo seleccionado.
 *
 * Responsabilidad única: decidir qué pasos ejecutar en cada modo y
 * coordinar los componentes especializados. No contiene lógica de
 * extracción, configuración ni detección de estado.
 *
 * Modos soportados:
 *   "auto"             → Bootstrap + Payload offline (si existe)
 *   "termux-bootstrap" → Solo Bootstrap
 *   "offline"          → Bootstrap + Payload offline (requiere payload en assets)
 *   "online"           → Bootstrap (si no está) — la instalación online se ejecuta en terminal
 *   "proot"            → Ubuntu completo via proot
 *   "force"            → Fuerza reinstalación ignorando el marcador
 */
internal class InstallOrchestrator(
    private val context: Context,
    private val paths: InstallPathResolver,
    private val stateChecker: InstallStateChecker,
    private val assetResolver: PayloadAssetResolver,
    private val payloadInstaller: PayloadInstaller,
    private val markerWriter: InstallMarkerWriter,
) {

    private val TAG = "InstallOrchestrator"

    /**
     * Punto de entrada principal. Selecciona el flujo según [mode] y delega.
     * Se ejecuta en [Dispatchers.IO] — nunca bloquea el hilo principal.
     */
    suspend fun install(
        mode: String,
        customUri: Uri?,
        listener: InstallerManager.ProgressListener,
    ) = withContext(Dispatchers.IO) {
        try {
            if (stateChecker.isInstalled() && mode != "force") {
                AppLogger.i(TAG, "Already installed — skipping")
                listener.onSuccess()
                return@withContext
            }

            when (mode) {
                "auto" -> {
                    AppLogger.i(TAG, "Auto mode: step 1 — Termux Bootstrap")
                    installViaTermuxBootstrap(listener)

                    if (assetResolver.hasPayloadAsset()) {
                        AppLogger.i(TAG, "Auto mode: step 2 — OpenClaw payload from assets")
                        payloadInstaller.installOffline(listener)
                    } else {
                        AppLogger.i(TAG, "Auto mode: no payload asset — online install needed")
                    }
                }

                "termux-bootstrap" -> {
                    AppLogger.i(TAG, "Installing Termux Bootstrap only")
                    installViaTermuxBootstrap(listener)
                }

                "offline" -> {
                    if (customUri != null) {
                        payloadInstaller.installFromCustomPayload(customUri, listener)
                    } else if (assetResolver.hasPayloadAsset()) {
                        AppLogger.i(TAG, "Offline mode: step 1 — Termux Bootstrap")
                        installViaTermuxBootstrap(listener)
                        AppLogger.i(TAG, "Offline mode: step 2 — OpenClaw payload")
                        payloadInstaller.installOffline(listener)
                    } else {
                        listener.onError(
                            "No hay payload bundled en el APK.\n" +
                            "Usa 'auto' para instalar Termux Bootstrap, luego ejecuta\n" +
                            "la instalación online desde el terminal."
                        )
                    }
                }

                "online" -> {
                    if (!TermuxBootstrapManager(context).isInstalled()) {
                        AppLogger.i(TAG, "Online mode: installing Termux Bootstrap first")
                        // Instalación síncrona - esperar a que termine
                        installViaTermuxBootstrapSync(listener)
                        // Verificar que realmente se instaló
                        if (!TermuxBootstrapManager(context).isInstalled()) {
                            listener.onError("Falló la instalación de Termux Bootstrap")
                            return@withContext
                        }
                        listener.onProgress(100, "Bootstrap instalado. Abriendo terminal para instalación online...")
                    } else {
                        AppLogger.i(TAG, "Online mode: bootstrap already installed")
                        listener.onProgress(100, "Bootstrap ya instalado. Abriendo terminal para instalación online...")
                    }
                    // Nota: NO llamamos a listener.onSuccess() aquí
                    // La UI debe detectar el progreso 100 y abrir el terminal
                }

                "proot" -> {
                    AppLogger.i(TAG, "Installing Proot + Ubuntu (advanced option)")
                    installViaProot(listener)
                }

                else -> {
                    listener.onError("Modo de instalación desconocido: $mode")
                }
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Install failed fatally: ${e.message}", e)
            listener.onError(
                "Instalación falló: ${e.message ?: "error desconocido"}",
                e,
            )
        }
    }

    // ── Flujos de instalación de entorno base ──────────────────────────────

    private suspend fun installViaTermuxBootstrap(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Starting Termux bootstrap installation")
        val bootstrapManager = TermuxBootstrapManager(context)

        if (bootstrapManager.isInstalled()) {
            AppLogger.i(TAG, "Termux bootstrap already installed")
            listener.onSuccess()
            return
        }

        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                try {
                    markerWriter.writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write legacy marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message", cause)
                } else {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message")
                }
                listener.onError(message, cause)
            }
        })
    }

    private suspend fun installViaTermuxBootstrapSync(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Starting Termux bootstrap installation (sync)")
        val bootstrapManager = TermuxBootstrapManager(context)

        // Usar CompletableDeferred para esperar
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()

        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                try {
                    markerWriter.writeMarker()
                    deferred.complete(true)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write legacy marker: ${e.message}")
                    deferred.complete(true)
                }
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message", cause)
                } else {
                    AppLogger.e(TAG, "Termux bootstrap install error: $message")
                }
                deferred.complete(false)
            }
        })

        val success = deferred.await()
        if (!success) {
            throw Exception("Termux Bootstrap installation failed")
        }
    }

    private fun installViaProot(listener: InstallerManager.ProgressListener) {
        AppLogger.i(TAG, "Starting proot-based installation")
        val setupManager = SetupManager(context)

        if (setupManager.isInstalled()) {
            AppLogger.i(TAG, "proot installation already complete")
            listener.onSuccess()
            return
        }

        setupManager.install(object : SetupManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                try {
                    markerWriter.writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write legacy marker: ${e.message}")
                }
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                if (cause != null) {
                    AppLogger.e(TAG, "proot install error: $message", cause)
                } else {
                    AppLogger.e(TAG, "proot install error: $message")
                }
                listener.onError(message, cause)
            }
        })
    }
}
