package com.openclaw.android.core.bootstrap

import android.content.Context
import android.os.Build
import com.openclaw.android.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * TermuxBootstrapOrchestrator — Orquesta la descarga e instalación de Termux Bootstrap.
 *
 * Descarga el bootstrap oficial de Termux desde GitHub y lo extrae.
 * Este es un sistema ONLINE - requiere conexión a internet.
 */
class TermuxBootstrapOrchestrator(private val context: Context) {

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    companion object {
        private const val TAG = "TermuxBootstrapOrchestrator"

        // URL del bootstrap oficial de Termux (aarch64)
        private const val BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    }

    private val architectureDetector = TermuxArchitectureDetector()
    private val downloader = TermuxBootstrapDownloader()
    private val extractor = TermuxBootstrapExtractor()
    private val marker = TermuxBootstrapMarker(context)
    private val pkgManager = TermuxPkgManager(context)

    fun install(listener: ProgressListener) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    listener.onProgress(5, "Detecting architecture...")
                }

                val arch = architectureDetector.getArchitecture()
                AppLogger.i(TAG, "Architecture detected: $arch")

                withContext(Dispatchers.Main) {
                    listener.onProgress(10, "Downloading Termux bootstrap...")
                }

                val bootstrapFile = File(context.cacheDir, "bootstrap-$arch.zip")

                // Descargar
                val downloaded = downloader.download(
                    url = URL(BOOTSTRAP_URL),
                    outputFile = bootstrapFile,
                    onProgress = { pct ->
                        val overall = 10 + (pct * 40 / 100)
                        listener.onProgress(overall, "Downloading: $pct%")
                    }
                )

                if (!downloaded) {
                    throw Exception("Bootstrap download failed")
                }

                withContext(Dispatchers.Main) {
                    listener.onProgress(55, "Extracting bootstrap...")
                }

                // Extraer
                extractor.extract(
                    zipFile = bootstrapFile,
                    outputDir = context.filesDir,
                    onProgress = { pct ->
                        val overall = 55 + (pct * 35 / 100)
                        listener.onProgress(overall, "Extracting: $pct%")
                    }
                )

                withContext(Dispatchers.Main) {
                    listener.onProgress(95, "Configuring environment...")
                }

                // Configurar
                pkgManager.setupPackageManager()

                // Limpiar
                bootstrapFile.delete()

                withContext(Dispatchers.Main) {
                    listener.onProgress(100, "Termux Bootstrap installed")
                    listener.onSuccess()
                }

            } catch (e: Exception) {
                AppLogger.e(TAG, "Installation failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    listener.onError("Installation failed: ${e.message}", e)
                }
            }
        }
    }
}
