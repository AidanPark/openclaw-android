package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import com.openclaw.android.TermuxBootstrapManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orquesta el flujo completo de instalación del bootstrap de Termux.
 *
 * Responsabilidad única: coordinar todos los pasos en el orden correcto:
 *   1. Detectar arquitectura
 *   2. Descargar bootstrap
 *   3. Extraer ZIP
 *   4. Configurar entorno
 *   5. Ejecutar dpkg --configure -a
 *   6. Ejecutar pkg update con reintentos
 *   7. Instalar paquetes adicionales
 *   8. Escribir marcador
 */
internal class TermuxBootstrapOrchestrator(
    private val context: android.content.Context,
    private val filesDir: File,
    private val cacheDir: File,
    private val homeDir: File,
    private val prefix: File,
    private val architectureDetector: TermuxArchitectureDetector,
    private val downloader: TermuxBootstrapDownloader,
    private val extractor: TermuxBootstrapExtractor,
    private val envConfigurator: TermuxEnvironmentConfigurator,
    private val dpkgManager: TermuxDpkgManager,
    private val pkgManager: TermuxPkgManager,
    private val packageInstaller: TermuxPackageInstaller,
    private val marker: TermuxBootstrapMarker,
) {

    private val TAG = "TermuxBootstrapOrchestrator"

    /**
     * Ejecuta el flujo completo de instalación.
     * Debe ejecutarse en Dispatchers.IO.
     */
    suspend fun install(listener: TermuxBootstrapManager.ProgressListener) = withContext(Dispatchers.IO) {
        try {
            if (marker.isInstalled()) {
                AppLogger.i(TAG, "Already installed")
                listener.onSuccess()
                return@withContext
            }

            // Paso 1: Arquitectura
            listener.onProgress(1, "Detectando arquitectura...")
            val arch = architectureDetector.detectArchitecture()
            val bootstrapUrl = architectureDetector.getBootstrapUrl(arch)
            listener.onProgress(2, "Arquitectura: $arch")

            // Paso 2: Descargar
            val bootstrapZip = downloader.getBootstrapCacheFile(arch)
            downloader.downloadBootstrap(bootstrapUrl, bootstrapZip) { downloaded, total ->
                val pct = if (total > 0) 2 + (downloaded * 48 / total).toInt().coerceIn(0, 48) else 25
                listener.onProgress(
                    pct,
                    "Descargando... ${downloaded / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB" else ""}"
                )
            }

            // Paso 3: Extraer
            listener.onProgress(50, "Extrayendo bootstrap...")
            val count = extractor.extractBootstrap(bootstrapZip, prefix) { n ->
                if (n % 200 == 0) listener.onProgress(
                    50 + (n / 100).coerceAtMost(20),
                    "Extrayendo... $n archivos"
                )
            }
            listener.onProgress(70, "Extraídos $count archivos")

            // Paso 4: Configurar entorno base
            listener.onProgress(71, "Configurando entorno...")
            envConfigurator.setupEnvironment(context)

            // Paso 5: dpkg --configure -a (no-interactivo)
            listener.onProgress(75, "Configurando dpkg (no-interactivo)...")
            dpkgManager.runDpkgConfigure(listener)

            // Paso 6: pkg update con reintentos
            listener.onProgress(80, "Actualizando repositorios...")
            val updateOk = pkgManager.runPkgUpdateWithRetry(listener, maxAttempts = 3)
            if (!updateOk) {
                AppLogger.w(TAG, "pkg update failed after retries — continuing anyway")
                listener.onProgress(88, "Advertencia: pkg update falló, continuando...")
            }

            // Paso 7: Paquetes adicionales
            listener.onProgress(90, "Instalando paquetes base...")
            packageInstaller.installAdditionalPackages(listener)

            // Paso 8: Marcador y verificación
            marker.writeMarker(arch)
            listener.onProgress(98, "Verificando instalación...")
            if (!marker.isInstalled()) throw IllegalStateException("Verificación post-instalación falló")

            // Limpiar ZIP descargado
            bootstrapZip.delete()

            listener.onProgress(100, "¡Instalación completada!")
            listener.onSuccess()

        } catch (e: Exception) {
            AppLogger.e(TAG, "Bootstrap installation failed: ${e.message}", e)
            listener.onError("Error instalando bootstrap: ${e.message}", e)
        }
    }

    /**
     * Obtiene el estado actual del bootstrap.
     */
    fun getStatus(): Map<String, Any> = mapOf(
        "installed"    to marker.isInstalled(),
        "architecture" to architectureDetector.detectArchitecture(),
        "prefixPath"   to prefix.absolutePath,
        "prefixExists" to prefix.exists(),
        "prefixSizeMB" to if (prefix.exists()) prefix.walkTopDown().sumOf { it.length() } / 1024 / 1024 else 0L,
        "dpkgExists"   to File(prefix, "bin/dpkg").exists(),
        "aptExists"    to File(prefix, "bin/apt").exists(),
        "bashExists"   to File(prefix, "bin/bash").exists(),
    )

    /**
     * Desinstala el bootstrap.
     */
    fun uninstall() {
        try {
            if (prefix.exists()) prefix.deleteRecursively()
            marker.deleteMarker()
            AppLogger.i(TAG, "Bootstrap uninstalled")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Uninstall failed: ${e.message}", e)
        }
    }
}
