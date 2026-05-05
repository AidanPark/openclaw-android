package com.openclaw.android

import android.content.Context
import android.net.Uri
import com.openclaw.android.core.install.EnvironmentConfigurator
import com.openclaw.android.core.install.InstallationOrchestrator
import com.openclaw.android.core.install.InstallMarkerWriter
import com.openclaw.android.core.install.InstallPathResolver
import com.openclaw.android.core.install.InstallStateChecker
import com.openclaw.android.core.install.PayloadAssetResolver
import com.openclaw.android.core.install.PayloadInstaller
import java.io.File

/**
 * InstallerManager — punto de entrada único para todos los flujos de instalación.
 *
 * Delega a InstallationOrchestrator (orquestador unificado que reemplaza
 * InstallOrchestrator, SetupManager y RootfsManager).
 */
class InstallerManager(private val context: Context) {

    private val paths = InstallPathResolver(context)
    private val stateChecker = InstallStateChecker(context, paths)
    private val assetResolver = PayloadAssetResolver(context)
    private val markerWriter = InstallMarkerWriter(paths)
    private val configurator = EnvironmentConfigurator(context, paths, markerWriter)
    private val payloadInstaller = PayloadInstaller(context, paths, assetResolver, configurator)
    // InstallationOrchestrator reemplaza InstallOrchestrator (eliminado) y unifica todos los modos
    private val orchestrator = InstallationOrchestrator(context)

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    fun isInstalled(): Boolean = stateChecker.isInstalled()
    fun isReady(): Boolean = stateChecker.isReady()
    fun isOpenClawInstalled(): Boolean = stateChecker.isOpenClawInstalled()
    fun getStatus(): String = stateChecker.getStatus()
    fun getDetailedStatus(): InstallationOrchestrator.InstallationStatus = orchestrator.getStatus()
    fun hasPayloadAsset(): Boolean = assetResolver.hasPayloadAsset()

    suspend fun install(mode: String, customUri: Uri?, listener: ProgressListener) {
        orchestrator.install(mode, customUri, object : InstallationOrchestrator.ProgressListener {
            override fun onProgress(percent: Int, message: String) = listener.onProgress(percent, message)
            override fun onSuccess() = listener.onSuccess()
            override fun onError(message: String, cause: Throwable?) = listener.onError(message, cause)
        })
    }

    fun installGlibcFromFile(archiveFile: File): Boolean =
        payloadInstaller.installGlibcFromFile(archiveFile)

    fun getRunScriptPath(): File = paths.getRunScriptPath()
    fun getWwwDir(): File = paths.getWwwDir()
    fun getPrefixDir(): File = paths.getPrefixDir()
    fun getHomeDir(): File = paths.getHomeDir()

    fun applyScriptUpdate() = configurator.applyScriptUpdate()

    fun syncWwwFromAssets() {
        val wwwDest = getWwwDir()
        if (!wwwDest.exists()) wwwDest.mkdirs()
        copyAssetFolder(context.assets, "www", wwwDest.absolutePath)
    }

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
