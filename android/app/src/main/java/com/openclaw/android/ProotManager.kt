package com.openclaw.android

import android.content.Context
import com.openclaw.android.core.proot.ProotPathResolver
import com.openclaw.android.core.proot.ProotCommandBuilder
import com.openclaw.android.core.proot.ProotCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ProotManager — fachada para la gestión de proot y rootfs Ubuntu (versión embebida).
 *
 * Esta versión usa recursos offline embebidos en assets/ en lugar de descargar desde internet:
 *   - payload-proot.tar.xz: binario proot estático
 *   - payload-rootfs.tar.xz: rootfs Ubuntu minimal
 *
 * Arquitectura:
 *   filesDir/
 *   ├── bin/
 *   │   └── proot          ← binario estático arm64 extraído de assets
 *   └── ubuntu-rootfs/     ← rootfs Ubuntu minimal extraído de assets
 *       ├── bin/
 *       ├── usr/
 *       └── ...
 */
object ProotManager {

    // ── Componentes internos ───────────────────────────────────────────────

    private lateinit var pathResolver: ProotPathResolver
    private lateinit var commandBuilder: ProotCommandBuilder
    private lateinit var commandExecutor: ProotCommandExecutor

    private fun ensureInitialized(context: Context) {
        if (!::pathResolver.isInitialized) {
            pathResolver = ProotPathResolver(context)
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
     * Extrae el binario proot desde assets (payload-proot.tar.xz).
     * Versión offline - no requiere internet.
     */
    suspend fun extractProotFromAssets(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(10, "Extrayendo proot desde assets...")
            val assetManager = context.assets

            // Verificar que existe el asset
            val assetName = "payload-proot.tar.xz"
            val assetList = assetManager.list("") ?: emptyArray()
            if (!assetList.contains(assetName)) {
                AppLogger.e("ProotManager", "Asset no encontrado: $assetName")
                onProgress(0, "Error: proot no incluido en assets")
                return@withContext false
            }

            // Extraer proot
            val paths = ProotPathResolver(context).getPaths()
            paths.prootBin.parentFile?.mkdirs()

            assetManager.open(assetName).use { input ->
                java.util.zip.GZIPInputStream(input).use { gzip ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && entry.name.contains("proot")) {
                                paths.prootBin.outputStream().use { output ->
                                    tar.copyTo(output)
                                }
                                paths.prootBin.setExecutable(true, false)
                                break
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            onProgress(100, "Proot extraído correctamente")
            true
        } catch (e: Exception) {
            AppLogger.e("ProotManager", "Error extrayendo proot: ${e.message}", e)
            onProgress(0, "Error: ${e.message}")
            false
        }
    }

    /**
     * Extrae el rootfs Ubuntu desde assets (payload-rootfs.tar.xz).
     * Versión offline - no requiere internet.
     */
    suspend fun extractRootfsFromAssets(
        context: Context,
        onProgress: (Int, String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            onProgress(5, "Extrayendo Ubuntu rootfs desde assets...")
            val assetManager = context.assets

            val assetName = "payload-rootfs.tar.xz"
            val assetList = assetManager.list("") ?: emptyArray()
            if (!assetList.contains(assetName)) {
                AppLogger.e("ProotManager", "Asset no encontrado: $assetName")
                onProgress(0, "Error: rootfs no incluido en assets")
                return@withContext false
            }

            val paths = ProotPathResolver(context).getPaths()
            paths.rootfsDir.mkdirs()

            var entryCount = 0
            assetManager.open(assetName).use { input ->
                java.util.zip.GZIPInputStream(input).use { gzip ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val outFile = File(paths.rootfsDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { output ->
                                    tar.copyTo(output)
                                }
                            }
                            entryCount++
                            if (entryCount % 100 == 0) {
                                val progress = 5 + (entryCount * 95 / 1000).coerceAtMost(95)
                                onProgress(progress, "Extrayendo archivos... ($entryCount)")
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }

            // Escribir marcador
            File(context.filesDir, ".rootfs-extracted").writeText("${System.currentTimeMillis()}")

            onProgress(100, "Ubuntu rootfs extraído correctamente")
            true
        } catch (e: Exception) {
            AppLogger.e("ProotManager", "Error extrayendo rootfs: ${e.message}", e)
            onProgress(0, "Error: ${e.message}")
            false
        }
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
