package com.openclaw.android

import android.content.Context
import com.openclaw.android.core.proot.*
import java.io.File

/**
 * ProotManager — fachada para la gestión de proot y rootfs Ubuntu.
 *
 * Esta clase actúa como fachada (Facade pattern): mantiene la API pública intacta
 * y delega cada responsabilidad a un componente especializado:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │                    ProotManager (fachada)                   │
 *   │                                                             │
 *   │  ProotPathResolver        → getPaths(), isProotReady()      │
 *   │  ProotBinaryDownloader    → downloadProot()                 │
 *   │  ProotRootfsDownloader    → downloadAndExtractRootfs()      │
 *   │  ProotRootfsConfigurator  → setupRootfsDirs()               │
 *   │  ProotCommandBuilder      → buildProotCommand(), buildProotEnv()
 *   │  ProotCommandExecutor     → runInProot(), launchGatewayInProot()
 *   │  ProotFileDownloader      → downloadFile()                  │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Por qué proot resuelve el Phantom Process Killer:
 *   - proot es un binario estático compilado con NDK (no un proceso hijo de shell)
 *   - Se ejecuta como proceso nativo de la app, no como subproceso de bash
 *   - Android 12+ solo mata procesos hijos de procesos que NO son foreground services
 *   - Al correr proot desde un foreground service (OpenClawService), sobrevive
 *
 * Arquitectura:
 *   filesDir/
 *   ├── bin/
 *   │   └── proot          ← binario estático arm64 descargado
 *   └── ubuntu-rootfs/     ← rootfs Ubuntu minimal extraído
 *       ├── bin/
 *       ├── usr/
 *       └── ...
 */
object ProotManager {

    // ── Componentes internos ───────────────────────────────────────────────

    private lateinit var pathResolver: ProotPathResolver
    private lateinit var fileDownloader: ProotFileDownloader
    private lateinit var binaryDownloader: ProotBinaryDownloader
    private lateinit var rootfsDownloader: ProotRootfsDownloader
    private lateinit var rootfsConfigurator: ProotRootfsConfigurator
    private lateinit var commandBuilder: ProotCommandBuilder
    private lateinit var commandExecutor: ProotCommandExecutor

    private fun ensureInitialized(context: Context) {
        if (!::pathResolver.isInitialized) {
            pathResolver = ProotPathResolver(context)
            fileDownloader = ProotFileDownloader()
            binaryDownloader = ProotBinaryDownloader(pathResolver, fileDownloader)
            rootfsConfigurator = ProotRootfsConfigurator()
            rootfsDownloader = ProotRootfsDownloader(pathResolver, fileDownloader, rootfsConfigurator)
            commandBuilder = ProotCommandBuilder(pathResolver)
            commandExecutor = ProotCommandExecutor(context, pathResolver, commandBuilder)
        }
    }

    // ── API pública ────────────────────────────────────────────────────────

    fun getPaths(context: Context): ProotPaths {
        ensureInitialized(context)
        val paths = pathResolver.getPaths()
        return ProotPaths(paths.prootBin, paths.rootfsDir, paths.homeDir)
    }

    fun isProotReady(context: Context): Boolean {
        ensureInitialized(context)
        return pathResolver.isProotReady()
    }

    fun isRootfsReady(context: Context): Boolean {
        ensureInitialized(context)
        return pathResolver.isRootfsReady()
    }

    /**
     * Descarga el binario proot desde Termux packages.
     */
    fun downloadProot(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean {
        ensureInitialized(context)
        return binaryDownloader.downloadProot(onProgress)
    }

    /**
     * Descarga y extrae el rootfs Ubuntu minimal para arm64.
     */
    fun downloadAndExtractRootfs(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean {
        ensureInitialized(context)
        return rootfsDownloader.downloadAndExtractRootfs(onProgress)
    }

    /**
     * Construye el comando proot para ejecutar un comando dentro del rootfs Ubuntu.
     */
    fun buildProotCommand(
        context: Context,
        command: String,
        extraBinds: List<String> = emptyList(),
    ): List<String> {
        ensureInitialized(context)
        return commandBuilder.buildProotCommand(command, extraBinds)
    }

    /**
     * Ejecuta un comando dentro del rootfs Ubuntu via proot.
     */
    fun runInProot(
        context: Context,
        command: String,
        env: Map<String, String> = buildProotEnv(context),
        onOutput: (String) -> Unit,
    ): Int {
        ensureInitialized(context)
        return commandExecutor.runInProot(command, env, onOutput)
    }

    /**
     * Lanza el gateway de OpenClaw dentro de proot como proceso persistente.
     */
    fun launchGatewayInProot(context: Context): Process? {
        ensureInitialized(context)
        return commandExecutor.launchGatewayInProot()
    }

    fun buildProotEnv(context: Context): Map<String, String> {
        ensureInitialized(context)
        return commandBuilder.buildProotEnv(context)
    }

    // ── Data class pública (mantenida por compatibilidad) ─────────────────

    data class ProotPaths(
        val prootBin: File,
        val rootfsDir: File,
        val homeDir: File,
    )
}
