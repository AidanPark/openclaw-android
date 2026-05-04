package com.openclaw.android

import android.content.Context
import com.openclaw.android.core.bootstrap.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TermuxBootstrapManager — fachada para la instalación del bootstrap oficial de Termux.
 *
 * Esta clase actúa como fachada (Facade pattern): mantiene la API pública intacta
 * y delega cada responsabilidad a un componente especializado:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │            TermuxBootstrapManager (fachada)                 │
 *   │                                                             │
 *   │  TermuxBootstrapOrchestrator → install(), getStatus(), uninstall()
 *   │  TermuxArchitectureDetector  → detectArchitecture()         │
 *   │  TermuxBootstrapDownloader   → downloadBootstrap()          │
 *   │  TermuxBootstrapExtractor    → extractBootstrap()           │
 *   │  TermuxEnvironmentConfigurator → setupEnvironment()         │
 *   │  TermuxDpkgManager           → runDpkgConfigure()           │
 *   │  TermuxPkgManager            → runPkgUpdateWithRetry()      │
 *   │  TermuxPackageInstaller      → installAdditionalPackages()  │
 *   │  TermuxBootstrapMarker       → isInstalled(), writeMarker() │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Flujo de instalación online (cuando el payload offline no está disponible):
 *   1. Detectar arquitectura del dispositivo
 *   2. Descargar bootstrap ZIP desde packages.termux.dev
 *   3. Extraer al PREFIX preservando permisos y symlinks
 *   4. Configurar entorno (DNS, DEBIAN_FRONTEND, etc.)
 *   5. Ejecutar dpkg --configure -a (no-interactivo) para reparar estado
 *   6. Ejecutar pkg update con reintentos automáticos
 *   7. Instalar paquetes adicionales (git, curl, wget)
 */
class TermuxBootstrapManager(private val context: Context) {

    // ── Componentes internos ───────────────────────────────────────────────

    private val filesDir: File = context.filesDir
    private val cacheDir: File = context.cacheDir
    private val homeDir = File(filesDir, "home")

    // PREFIX donde se instala el bootstrap — se resuelve dinámicamente
    private val prefix: File
        get() {
            val candidates = listOf(
                File(homeDir, "payload"),
                File(homeDir, "openclaw-payload"),
                File(filesDir, "payload"),
                File(filesDir, "openclaw-payload"),
                File(filesDir, "usr"),
            )
            return candidates.firstOrNull { it.isDirectory } ?: File(filesDir, "usr")
        }

    // Instanciar componentes
    private val architectureDetector = TermuxArchitectureDetector
    private val downloader = TermuxBootstrapDownloader(cacheDir)
    private val extractor = TermuxBootstrapExtractor()
    private val envConfigurator = TermuxEnvironmentConfigurator(context, prefix, homeDir)
    private val marker = TermuxBootstrapMarker(filesDir, prefix)
    private val dpkgManager = TermuxDpkgManager(prefix, homeDir, envConfigurator)
    private val pkgManager = TermuxPkgManager(prefix, homeDir, envConfigurator, dpkgManager)
    private val packageInstaller = TermuxPackageInstaller(prefix, homeDir, envConfigurator)
    private val orchestrator = TermuxBootstrapOrchestrator(
        context, filesDir, cacheDir, homeDir, prefix,
        architectureDetector, downloader, extractor, envConfigurator,
        dpkgManager, pkgManager, packageInstaller, marker
    )

    // ── Interfaz de progreso ──────────────────────────────────────────────────

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    // ── API pública ───────────────────────────────────────────────────────────

    fun isInstalled(): Boolean = marker.isInstalled()

    fun detectArchitecture(): String = architectureDetector.detectArchitecture()

    /**
     * Instala el bootstrap de Termux.
     * Debe ejecutarse en Dispatchers.IO.
     */
    suspend fun install(listener: ProgressListener) = withContext(Dispatchers.IO) {
        orchestrator.install(listener)
    }

    fun getStatus(): Map<String, Any> = orchestrator.getStatus()

    fun uninstall() = orchestrator.uninstall()
}
