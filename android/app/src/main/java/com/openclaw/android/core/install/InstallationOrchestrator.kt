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
        /** Installación automática: intenta offline primero, luego online si es necesario */
        object Auto : InstallationMode()
        
        /** Solo Termux Bootstrap */
        object TermuxBootstrap : InstallationMode()
        
        /** Offline: requiere payload en assets */
        object Offline : InstallationMode()
        
        /** Online: abre terminal para instalación interactive */
        object Online : InstallationMode()
        
        /** Proot: Ubuntu completo via proot (resistente a Phantom Process Killer) */
        object Proot : InstallationMode()
        
        /** Rootfs: extrae rootfs pre-configurado desde assets */
        object Rootfs : InstallationMode()
        
        /** Fuerza reinstalación ignorando marcadores existentes */
        object Force : InstallationMode()

        companion object {
            fun fromString(mode: String): InstallationMode = when (mode.lowercase()) {
                "auto" -> Auto
                "termux-bootstrap", "bootstrap" -> TermuxBootstrap
                "offline" -> Offline
                "online" -> Online
                "proot" -> Proot
                "rootfs" -> Rootfs
                "force" -> Force
                else -> Auto
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
     * @param mode Modo de instalación (auto, termux-bootstrap, offline, online, proot, rootfs, force)
     * @param customUri URI personalizado para payload (opcional)
     * @param listener Listener para progreso y resultados
     */
    suspend fun install(
        mode: String,
        customUri: Uri? = null,
        listener: ProgressListener,
    ) = withContext(Dispatchers.IO) {
        val installationMode = InstallationMode.fromString(mode)
        val forceMode = mode == "force"

        try {
            // Verificar si ya está instalado (excepto en modo force)
            if (stateChecker.isInstalled() && !forceMode) {
                AppLogger.i(TAG, "Already installed — skipping")
                listener.onSuccess()
                return@withContext
            }

            when (installationMode) {
                is InstallationMode.Auto -> installAuto(listener)
                is InstallationMode.TermuxBootstrap -> installTermuxBootstrap(listener)
                is InstallationMode.Offline -> installOffline(customUri, listener)
                is InstallationMode.Online -> installOnline(listener)
                is InstallationMode.Proot -> installProot(listener)
                is InstallationMode.Rootfs -> installRootfs(listener)
                is InstallationMode.Force -> installForce(listener)
            }

        } catch (e: Exception) {
            AppLogger.e(TAG, "Installation failed: ${e.message}", e)
            listener.onError(
                "Instalación falló: ${e.message ?: "error desconocido"}",
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

            // Eliminar directorio de datos
            context.filesDir.resolve("home/.openclaw-android").deleteRecursively()

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

    private suspend fun installAuto(listener: ProgressListener) {
        AppLogger.i(TAG, "Auto mode: starting")
        
        // Paso 1: Instalar Termux Bootstrap
        listener.onProgress(0, "Instalando Termux Bootstrap...")
        installTermuxBootstrap(listener)
        
        // Paso 2: Si hay payload en assets, instalar offline
        if (assetResolver.hasPayloadAsset()) {
            listener.onProgress(50, "Extrayendo entorno OpenClaw...")
            payloadInstaller.installOffline(object : ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    listener.onProgress(50 + percent / 2, message)
                }
                override fun onSuccess() = listener.onSuccess()
                override fun onError(message: String, cause: Throwable?) = listener.onError(message, cause)
            })
        } else {
            // No hay payload - indicar que se necesita instalación online
            listener.onProgress(100, "Bootstrap instalado. Ejecuta 'curl -sL myopenclawhub.com/install | bash' en el terminal.")
            listener.onSuccess()
        }
    }

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
        // Primero instalar bootstrap si no está
        if (!TermuxBootstrapManager(context).isInstalled()) {
            installTermuxBootstrap(listener)
        }

        // Luego instalar payload
        if (customUri != null) {
            payloadInstaller.installFromCustomPayload(customUri, listener)
        } else if (assetResolver.hasPayloadAsset()) {
            payloadInstaller.installOffline(listener)
        } else {
            listener.onError("No hay payload disponible para instalación offline")
        }
    }

    private suspend fun installOnline(listener: ProgressListener) {
        // Instalar bootstrap primero si no está
        if (!TermuxBootstrapManager(context).isInstalled()) {
            installTermuxBootstrap(listener)
            // Verificar que realmente se instaló
            if (!TermuxBootstrapManager(context).isInstalled()) {
                throw Exception("Falló la instalación de Termux Bootstrap")
            }
        }
        
        listener.onProgress(100, "Bootstrap instalado. Abre el terminal para completar la instalación online.")
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
            File(context.filesDir, ".proot-installed").writeText("proot\n")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not write proot marker: ${e.message}")
        }

        listener.onProgress(100, "Proot + Ubuntu installed successfully")
        listener.onSuccess()
    }

    private suspend fun installRootfs(listener: ProgressListener) {
        // Rootfs installation — extract pre-configured rootfs from assets
        if (File(context.filesDir, ".rootfs-extracted").exists()) {
            listener.onProgress(100, "Rootfs already installed")
            listener.onSuccess()
            return
        }

        // Delegate to PayloadInstaller for asset extraction
        payloadInstaller.installOffline(object : ProgressListener {
            override fun onProgress(percent: Int, message: String) = listener.onProgress(percent, message)
            override fun onSuccess() {
                File(context.filesDir, ".rootfs-extracted").writeText("extracted\n")
                listener.onSuccess()
            }
            override fun onError(message: String, cause: Throwable?) = listener.onError(message, cause)
        })
    }

    private suspend fun installForce(listener: ProgressListener) {
        // Limpiar instalación existente
        cleanInstallation()
        
        // Reinstalar en modo auto
        installAuto(listener)
    }

    // ── Helpers privados ─────────────────────────────────────────────────

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