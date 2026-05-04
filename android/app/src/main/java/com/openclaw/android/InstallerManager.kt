package com.openclaw.android

import android.content.Context
import android.net.Uri
import com.openclaw.android.core.install.EnvironmentConfigurator
import com.openclaw.android.core.install.InstallMarkerWriter
import com.openclaw.android.core.install.InstallOrchestrator
import com.openclaw.android.core.install.InstallPathResolver
import com.openclaw.android.core.install.InstallStateChecker
import com.openclaw.android.core.install.PayloadAssetResolver
import com.openclaw.android.core.install.PayloadInstaller
import java.io.File

/**
 * InstallerManager — punto de entrada único para todos los flujos de instalación.
 *
 * Esta clase actúa como fachada (Facade pattern): mantiene la API pública intacta
 * y delega cada responsabilidad a un componente especializado:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │                    InstallerManager (fachada)               │
 *   │                                                             │
 *   │  InstallOrchestrator   → decide qué flujo ejecutar          │
 *   │  InstallStateChecker   → isInstalled(), isReady(), status   │
 *   │  PayloadAssetResolver  → hasPayloadAsset()                  │
 *   │  PayloadInstaller      → extrae payload (offline/online/uri)│
 *   │  EnvironmentConfigurator → configura glibc, node, DNS, SSL  │
 *   │  InstallMarkerWriter   → escribe marcadores en disco        │
 *   │  InstallPathResolver   → resuelve rutas del filesystem      │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Design principles:
 *   1. NO shell writes — todo el progreso via [ProgressListener].
 *   2. NO archivos temporales para reconstrucción — streaming via SequenceInputStream.
 *   3. Marcador (.installed) escrito SOLO tras validación exitosa.
 *   4. Corre en Dispatchers.IO — nunca bloquea el hilo principal.
 */
class InstallerManager(private val context: Context) {

    // ── Componentes internos ───────────────────────────────────────────────

    private val paths = InstallPathResolver(context)
    private val stateChecker = InstallStateChecker(context, paths)
    private val assetResolver = PayloadAssetResolver(context)
    private val markerWriter = InstallMarkerWriter(paths)
    private val configurator = EnvironmentConfigurator(context, paths, markerWriter)
    private val payloadInstaller = PayloadInstaller(context, paths, assetResolver, configurator)
    private val orchestrator = InstallOrchestrator(
        context, paths, stateChecker, assetResolver, payloadInstaller, markerWriter
    )

    // ── Interfaz de progreso ───────────────────────────────────────────────

    /**
     * Listener para el progreso de instalación.
     * Llamado desde Dispatchers.IO — la capa UI debe hacer post al hilo principal.
     */
    interface ProgressListener {
        /** Paso de instalación cambiado. percent es 0–100. */
        fun onProgress(percent: Int, message: String)

        /** Instalación completada con éxito. */
        fun onSuccess()

        /** Instalación fallida. [message] es para el usuario. [cause] para logging. */
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── API pública — estado ───────────────────────────────────────────────

    /** True si el entorno está estructuralmente instalado (marcador + dirs). */
    fun isInstalled(): Boolean = stateChecker.isInstalled()

    /** True si glibc y node están presentes (entorno completamente funcional). */
    fun isReady(): Boolean = stateChecker.isReady()

    /** True si openclaw.mjs o el script de lanzamiento están presentes. */
    fun isOpenClawInstalled(): Boolean = stateChecker.isOpenClawInstalled()

    /** Resumen de estado para la UI / JsBridge. */
    fun getStatus(): String = stateChecker.getStatus()

    /** True si el APK contiene algún asset de payload reconocido. */
    fun hasPayloadAsset(): Boolean = assetResolver.hasPayloadAsset()

    // ── API pública — instalación ──────────────────────────────────────────

    /**
     * Punto de entrada principal: decide offline vs online, ejecuta, valida.
     *
     * Modos:
     *   "auto"             → Bootstrap + Payload offline (si existe)
     *   "termux-bootstrap" → Solo Bootstrap
     *   "offline"          → Bootstrap + Payload offline (requiere payload en assets)
     *   "online"           → Bootstrap (si no está) — la instalación online va al terminal
     *   "proot"            → Ubuntu completo via proot
     *   "force"            → Fuerza reinstalación ignorando el marcador
     */
    suspend fun install(mode: String, customUri: Uri?, listener: ProgressListener) {
        orchestrator.install(mode, customUri, listener)
    }

    /**
     * Instala glibc desde un archivo externo proporcionado por el usuario.
     * @return true si la instalación fue exitosa.
     */
    fun installGlibcFromFile(archiveFile: File): Boolean =
        payloadInstaller.installGlibcFromFile(archiveFile)

    // ── API pública — rutas y utilidades ──────────────────────────────────

    fun getRunScriptPath(): File = paths.getRunScriptPath()
    fun getWwwDir(): File = paths.getWwwDir()
    fun getPrefixDir(): File = paths.getPrefixDir()
    fun getHomeDir(): File = paths.getHomeDir()

    /**
     * Actualiza scripts desde assets (run.sh, post-setup.sh, run-openclaw.sh, glibc-compat.js).
     */
    fun applyScriptUpdate() = configurator.applyScriptUpdate()

    /** Sincroniza los assets www del APK al directorio share. */
    fun syncWwwFromAssets() {
        val wwwDest = getWwwDir()
        if (!wwwDest.exists()) wwwDest.mkdirs()
        copyAssetFolder(context.assets, "www", wwwDest.absolutePath)
    }

    // ── Helpers privados ───────────────────────────────────────────────────

    private fun copyAssetFolder(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        destPath: String,
    ) {
        try {
            val assets = assetManager.list(assetPath)
            if (assets.isNullOrEmpty()) {
                val destFile = File(destPath)
                assetManager.open(assetPath).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                val destDir = File(destPath)
                if (!destDir.exists()) destDir.mkdirs()
                for (asset in assets) {
                    copyAssetFolder(assetManager, "$assetPath/$asset", "$destPath/$asset")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("InstallerManager", "Failed to copy asset $assetPath: ${e.message}")
        }
    }
}
