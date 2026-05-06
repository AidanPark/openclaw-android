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
 *   - TERMUX_BOOTSTRAP: Termux environment ONLINE (curl, bash, apt)
 *   - OFFLINE: Payload embebido offline desde assets
 *   - PROOT: Ubuntu mini via proot ONLINE (alternativa a Termux)
 *   - FORCE: Fuerza reinstalación ignorando el marcador
 *
 * ⚠️ SISTEMAS MUTUAMENTE EXCLUYENTES:
 *   - Termux Bootstrap ≠ Proot ≠ Payload
 *   - Solo UNO puede estar instalado a la vez
 *   - Instalar uno requiere desinstalar el otro primero
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
        /** Modo Termux: Entorno nativo con bash, curl, apt (ONLINE) */
        object TermuxBootstrap : InstallationMode()

        /** Modo avanzado: Ubuntu completo via proot (ONLINE alternativa a Termux) */
        object ProotUbuntu : InstallationMode()

        /** Offline: Payload embebido en assets (sin internet) */
        object OfflinePayload : InstallationMode()

        /** Fuerza reinstalación ignorando marcadores existentes */
        object Force : InstallationMode()

        companion object {
            fun fromString(mode: String, hasPayload: Boolean = false): InstallationMode = when (mode.lowercase()) {
                "termux", "bootstrap", "termux-bootstrap" -> TermuxBootstrap
                "proot", "ubuntu", "rootfs"     -> ProotUbuntu
                "offline", "auto"               -> OfflinePayload
                "force"                         -> Force
                else                            -> OfflinePayload
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
                    if (!isForce && hasConflictingSystem()) {
                        listener.onError("Cannot install Termux: ${getConflictMessage()}", null)
                        return@withContext
                    }
                    installTermuxBootstrap(listener)
                }
                is InstallationMode.ProotUbuntu -> {
                    if (!isForce && hasConflictingSystem()) {
                        listener.onError("Cannot install Proot: ${getConflictMessage()}", null)
                        return@withContext
                    }
                    installProot(listener)
                }
                is InstallationMode.OfflinePayload -> {
                    if (!isForce) {
                        if (hasConflictingSystem()) {
                            listener.onError("Cannot install Payload: ${getConflictMessage()}", null)
                            return@withContext
                        }
                        if (isPayloadInstalled()) {
                            AppLogger.i(TAG, "Payload already installed — skipping")
                            listener.onSuccess()
                            return@withContext
                        }
                    }
                    installOffline(customUri, listener)
                }
                is InstallationMode.Force -> installOffline(null, listener)
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
            // Eliminar TODOS los marcadores de todos los sistemas
            File(context.filesDir, ".installed").delete()
            File(context.filesDir, ".proot-installed").delete()
            File(context.filesDir, ".termux-bootstrap-installed").delete()
            File(context.filesDir, ".rootfs-extracted").delete()
            File(context.filesDir, ".payload-installed").delete()

            // Eliminar directorios de datos de todos los sistemas
            File(context.filesDir, "home/.openclaw-android").deleteRecursively()
            File(context.filesDir, "usr").deleteRecursively()
            File(context.filesDir, "ubuntu-rootfs").deleteRecursively()

            AppLogger.i(TAG, "All installations cleaned")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "cleanInstallation failed: ${e.message}", e)
            false
        }
    }

    /**
     * Verifica si existe un sistema instalado que cause conflicto.
     * Los sistemas son mutuamente excluyentes.
     */
    private fun hasConflictingSystem(): Boolean {
        // Contar cuántos marcadores existen
        val termuxInstalled = File(context.filesDir, ".termux-bootstrap-installed").exists() ||
                File(context.filesDir, "usr/bin/bash").exists()
        val prootInstalled = File(context.filesDir, ".proot-installed").exists() ||
                File(context.filesDir, "ubuntu-rootfs").exists()
        val payloadInstalled = File(context.filesDir, ".payload-installed").exists() &&
                File(context.filesDir, "home/payload").exists()

        return termuxInstalled || prootInstalled || payloadInstalled
    }

    /**
     * Obtiene mensaje descriptivo del conflicto detectado.
     */
    private fun getConflictMessage(): String {
        return when {
            File(context.filesDir, ".termux-bootstrap-installed").exists() ->
                "Termux Bootstrap is already installed. Uninstall it first."
            File(context.filesDir, "usr/bin/bash").exists() ->
                "Termux environment detected. Uninstall it first."
            File(context.filesDir, ".proot-installed").exists() ->
                "Proot is already installed. Uninstall it first."
            File(context.filesDir, "ubuntu-rootfs").exists() ->
                "Proot rootfs detected. Uninstall it first."
            File(context.filesDir, ".payload-installed").exists() ->
                "Payload (offline) is already installed. Uninstall it first."
            File(context.filesDir, "home/payload").exists() ->
                "Payload directory detected. Uninstall it first."
            else -> "Another system is already installed. Clean install required."
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

    private suspend fun installProot(listener: ProgressListener) {
        // Use ProotManager directly — SetupManager removed
        val prootReady = ProotManager.isProotReady(context) && ProotManager.isRootfsReady(context)

        if (prootReady) {
            listener.onProgress(100, "Proot + Ubuntu already installed")
            listener.onSuccess()
            return
        }

        // Step 1: Extract proot binary from assets (offline)
        if (!ProotManager.isProotReady(context)) {
            listener.onProgress(5, "Extrayendo proot desde assets...")
            val ok = ProotManager.extractProotFromAssets(context) { pct, msg ->
                listener.onProgress(pct, msg)
            }
            if (!ok) {
                listener.onError("Failed to extract proot from assets. Check APK includes payload-proot.tar.xz.")
                return
            }
        }

        // Step 2: Extract Ubuntu rootfs from assets (offline)
        if (!ProotManager.isRootfsReady(context)) {
            listener.onProgress(10, "Extrayendo Ubuntu rootfs desde assets...")
            val ok = ProotManager.extractRootfsFromAssets(context) { pct, msg ->
                listener.onProgress(pct, msg)
            }
            if (!ok) {
                listener.onError("Failed to extract rootfs from assets. Check APK includes payload-rootfs.tar.xz.")
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
            File(context.filesDir, ".termux-bootstrap-installed").exists() -> "termux-bootstrap"
            File(context.filesDir, ".proot-installed").exists() -> "proot"
            File(context.filesDir, ".rootfs-extracted").exists() -> "rootfs"
            File(context.filesDir, ".payload-installed").exists() -> "payload"
            stateChecker.isInstalled() -> "payload"
            else -> "none"
        }
    }

    // ── Instalación Termux Bootstrap ───────────────────────────────────────

    private suspend fun installTermuxBootstrap(listener: ProgressListener) {
        val bootstrapManager = TermuxBootstrapManager(context)

        if (bootstrapManager.isInstalled()) {
            listener.onProgress(100, "Termux Bootstrap ya instalado")
            listener.onSuccess()
            return
        }

        // Validar conflicto antes de instalar
        if (bootstrapManager.hasConflictingSystem()) {
            val msg = TermuxBootstrapManager.getConflictMessage(context)
            listener.onError(msg, null)
            return
        }

        val deferred = CompletableDeferred<Boolean>()

        bootstrapManager.install(object : TermuxBootstrapManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(percent, message)
            }

            override fun onSuccess() {
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
}