package com.openclaw.android.core.install

import android.content.Context
import android.net.Uri
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.ProotManager
import com.openclaw.android.TermuxBootstrapManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * InstallationOrchestrator — orquestador unificado de instalación.
 * 
 * Unifica todos los flujos de instalación mediante un sealed class InstallationMode:
 *   - AUTO: Bootstrap + Payload offline (si existe) o online
 *   - TERMUX_BOOTSTRAP: Solo Termux Bootstrap  
 *   - OFFLINE: Bootstrap + Payload desde assets
 *   - ONLINE: Bootstrap (si no está) + instalación online en terminal
 *   - PROOT: Ubuntu completo via proot
 *   - FORCE: Fuerza reinstalación ignorando el marcador
 * 
 * Este orchestrator reemplaza a InstallOrchestrator, SetupManager (lógica de proot),
 * y RootfsManager con modo ROOTFS.
 */
class InstallationOrchestrator(
    private val context: Context,
) {

    companion object {
        private const val TAG = "InstallationOrchestrator"
    }

    // Componentes internos
    private val paths = InstallPathResolver(context)
    private val stateChecker = InstallStateChecker(context, paths)
    private val assetResolver = PayloadAssetResolver(context)
    private val markerWriter = InstallMarkerWriter(paths)
    private val configurator = EnvironmentConfigurator(context, paths, markerWriter)
    private val payloadInstaller = PayloadInstaller(context, paths, assetResolver, configurator)

    /**
     * Modos de instalación soportados.
     */
    sealed class InstallationMode {
        /** Modo normal: Entorno nativo de Termux */
        object TermuxBootstrap : InstallationMode()
        
        /** Modo avanzado: Ubuntu completo via proot (resistente a Phantom Process Killer) */
        object ProotUbuntu : InstallationMode()
        
        /** Offline: requiere payload en assets */
        object OfflinePayload : InstallationMode()
        
        /** Online: abre terminal para instalación interactiva */
        object OnlineOnly : InstallationMode()
        
        /** Fuerza reinstalación ignorando marcadores existentes */
        object Force : InstallationMode()

        companion object {
            fun fromString(mode: String, hasPayload: Boolean = false): InstallationMode = when (mode.lowercase()) {
                "termux-bootstrap", "bootstrap" -> TermuxBootstrap
                "proot", "ubuntu", "rootfs"     -> ProotUbuntu
                "offline"                       -> OfflinePayload
                "online"                        -> OnlineOnly
                "force"                         -> Force
                // "auto" always starts with Termux Bootstrap — it is the
                // mandatory first step. The UI then triggers payload/online
                // separately as a second independent step.
                "auto"                          -> TermuxBootstrap
                else                            -> TermuxBootstrap
            }
        }
    }

    /**
     * Listener para el progreso de instalación.
     */
    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── API pública ───────────────────────────────────────────────────────

    /**
     * Ejecuta la instalación según el modo especificado.
     * @param mode Modo de instalación (auto, termux-bootstrap, offline, online, proot, force)
     * @param customUri URI personalizado para payload (opcional)
     * @param listener Listener para progreso y resultados
     */
    suspend fun install(
        mode: String,
        customUri: Uri? = null,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val installationMode = InstallationMode.fromString(mode, assetResolver.hasPayloadAsset())
        val isForce = mode == "force" || installationMode is InstallationMode.Force

        try {
            if (isForce) {
                cleanInstallation()
            }

            // Each mode checks its OWN marker — they are independent.
            // Do NOT use a shared isInstalled() check that mixes all markers.
            when (installationMode) {
                is InstallationMode.TermuxBootstrap -> {
                    if (!isForce && TermuxBootstrapManager(context).isInstalled()) {
                        AppLogger.i(TAG, "Termux Bootstrap already installed — skipping")
                        listener.onSuccess()
                        return@withContext
                    }
                    installTermuxBootstrap(listener)
                }
                is InstallationMode.ProotUbuntu -> installProot(listener)
                is InstallationMode.OfflinePayload -> {
                    if (!isForce && isPayloadInstalled()) {
                        AppLogger.i(TAG, "Payload already installed — skipping")
                        listener.onSuccess()
                        return@withContext
                    }
                    installOffline(customUri, listener)
                }
                is InstallationMode.OnlineOnly -> installOnline(listener)
                is InstallationMode.Force -> installTermuxBootstrap(listener)
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Installation failed: ${e.message}", e)
            listener.onError(
                "Installation failed: ${e.message ?: "unknown error"}",
                e,
            )
        }
    }

    /**
     * Limpia la instalación actual.
     * @return true si la limpieza fue exitosa
     */
    suspend fun cleanInstallation(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Eliminar marcadores
            File(context.filesDir, ".installed").delete()
            File(context.filesDir, ".proot-installed").delete()
            File(context.filesDir, ".termux-bootstrap-installed").delete()
            File(context.filesDir, ".rootfs-extracted").delete()
            File(context.filesDir, ".payload-installed").delete()

            // Eliminar directorio de datos
            File(context.filesDir, "home/.openclaw-android").deleteRecursively()

            AppLogger.i(TAG, "Installation cleaned")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "cleanInstallation failed: ${e.message}", e)
            false
        }
    }

    /**
     * Obtiene el estado actual de la instalación.
     */
    fun getStatus(): InstallationStatus = InstallationStatus(
        isInstalled = stateChecker.isInstalled(),
        isReady = stateChecker.isReady(),
        isOpenClawInstalled = stateChecker.isOpenClawInstalled(),
        hasPayloadAsset = assetResolver.hasPayloadAsset(),
        source = detectSource(),
        // Use ProotManager directly — SetupManager removed
        prootReady = ProotManager.isProotReady(context) && ProotManager.isRootfsReady(context),
        rootfsReady = File(context.filesDir, ".rootfs-extracted").exists(),
    )

    data class InstallationStatus(
        val isInstalled: Boolean,
        val isReady: Boolean,
        val isOpenClawInstalled: Boolean,
        val hasPayloadAsset: Boolean,
        val source: String,
        val prootReady: Boolean,
        val rootfsReady: Boolean,
    )

    // ── Flujos de instalación privados ───────────────────────────────────

    private suspend fun installTermuxBootstrap(listener: ProgressListener) {
        val bootstrapManager = TermuxBootstrapManager(context)
        
        if (bootstrapManager.isInstalled()) {
            listener.onProgress(100, "Termux Bootstrap ya instalado")
            listener.onSuccess()
            return
        }

        val deferred = CompletableDeferred<Boolean>()

        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
                try {
                    markerWriter.writeMarker()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write marker: ${e.message}")
                }
                deferred.complete(true)
            }

            override fun onError(message: String, cause: Throwable?) {
                listener.onError(message, cause)
                deferred.complete(false)
            }
        })

        if (!deferred.await()) {
            throw Exception("Termux Bootstrap installation failed")
        }
    }

    private suspend fun installOffline(customUri: Uri?, listener: ProgressListener) {
        // Offline payload installs OpenClaw (node + glibc) from the bundled asset.
        // This is INDEPENDENT of Termux Bootstrap — the payload extracts to
        // homeDir/payload/ and does NOT require bash or apt from the bootstrap.
        // Do NOT call installTermuxBootstrap() here.
        val bridgeListener = object : com.openclaw.android.InstallerManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) = listener.onProgress(percent, message)
            override fun onSuccess() {
                // Write the payload-specific marker so isPayloadInstalled() works correctly.
                // This is separate from .installed (written by bootstrap) to avoid false positives.
                try {
                    File(context.filesDir, ".payload-installed")
                        .writeText("${System.currentTimeMillis()}\n")
                    AppLogger.i(TAG, "Payload marker written: .payload-installed")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Could not write payload marker: ${e.message}")
                }
                listener.onSuccess()
            }
            override fun onError(message: String, cause: Throwable?) = listener.onError(message, cause)
        }

        if (customUri != null) {
            payloadInstaller.installFromCustomPayload(customUri, bridgeListener)
        } else if (assetResolver.hasPayloadAsset()) {
            payloadInstaller.installOffline(bridgeListener)
        } else {
            listener.onError("No payload available for offline installation")
        }
    }

    private suspend fun installOnline(listener: ProgressListener) {
        // Online mode installs OpenClaw via curl | bash run inside the terminal.
        // It REQUIRES Termux Bootstrap to be installed first because the install
        // script needs bash, curl, and apt from the bootstrap environment.
        if (!TermuxBootstrapManager(context).isInstalled()) {
            installTermuxBootstrap(listener)
            if (!TermuxBootstrapManager(context).isInstalled()) {
                throw Exception("Termux Bootstrap installation failed — required for online install")
            }
        }

        listener.onProgress(100, "Bootstrap ready. Open the terminal to run the online install.")
        listener.onSuccess()
    }

    private suspend fun installProot(listener: ProgressListener) {
        // Use ProotManager directly — SetupManager removed
        val prootReady = ProotManager.isProotReady(context) && ProotManager.isRootfsReady(context)

        if (prootReady) {
            listener.onProgress(100, "Proot + Ubuntu already installed")
            listener.onSuccess()
            return
        }

        // Step 1: Download proot binary
        if (!ProotManager.isProotReady(context)) {
            listener.onProgress(5, "Downloading proot binary...")
            val ok = ProotManager.downloadProot(context) { pct, msg ->
                listener.onProgress(pct, msg)
            }
            if (!ok) {
                listener.onError("Failed to download proot. Check internet connection.")
                return
            }
        }

        // Step 2: Download and extract Ubuntu rootfs
        if (!ProotManager.isRootfsReady(context)) {
            listener.onProgress(10, "Downloading Ubuntu rootfs (~80MB)...")
            val ok = ProotManager.downloadAndExtractRootfs(context) { pct, msg ->
                listener.onProgress(pct, msg)
            }
            if (!ok) {
                listener.onError("Failed to download Ubuntu rootfs. Check internet connection.")
                return
            }
        }

        // Write markers
        try {
            markerWriter.writeMarker()
            val prootMarker = File(context.filesDir, ".proot-installed")
            prootMarker.writeText("proot\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not write proot marker: ${e.message}")
        }

        listener.onProgress(100, "Proot + Ubuntu installed successfully")
        listener.onSuccess()
    }

    // ── Helpers privados ─────────────────────────────────────────────────

    /**
     * Verifica si el payload offline está realmente instalado.
     * NO usa el marcador .installed (que también escribe el bootstrap).
     * Comprueba que el directorio del payload exista con archivos reales.
     */
    private fun isPayloadInstalled(): Boolean {
        // Marker específico del payload offline
        val payloadMarker = File(context.filesDir, ".payload-installed")
        if (!payloadMarker.exists()) return false

        // Verificar que el directorio del payload realmente existe con contenido
        val payloadDir = paths.resolvePayloadDir()
        if (!payloadDir.isDirectory) return false

        // Debe tener al menos el linker de glibc o el binario de node
        val hasGlibc = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasNode = File(payloadDir, "lib/node/bin/node.real").exists() ||
                      File(payloadDir, "glibc/bin/node").exists()

        return hasGlibc || hasNode
    }

    private fun detectSource(): String {
        return when {
            File(context.filesDir, ".proot-installed").exists() -> "proot"
            File(context.filesDir, ".termux-bootstrap-installed").exists() -> "termux-bootstrap"
            File(context.filesDir, ".rootfs-extracted").exists() -> "rootfs"
            stateChecker.isOnlineInstallPresent() -> "online"
            stateChecker.isInstalled() -> "payload"
            else -> "none"
        }
    }
}