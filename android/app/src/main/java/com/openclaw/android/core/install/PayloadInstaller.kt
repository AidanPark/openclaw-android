package com.openclaw.android.core.install

import android.content.Context
import android.net.Uri
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.PayloadExtractor
import java.io.File
import java.net.URL

/**
 * Ejecuta la instalación del payload desde distintas fuentes:
 *   - Assets del APK (offline)
 *   - URI externa seleccionada por el usuario
 *   - Descarga desde red (online)
 *
 * Responsabilidad única: obtener y extraer el payload en disco.
 * La configuración post-extracción la delega a [EnvironmentConfigurator].
 */
internal class PayloadInstaller(
    private val context: Context,
    private val paths: InstallPathResolver,
    private val assetResolver: PayloadAssetResolver,
    private val configurator: EnvironmentConfigurator,
) {

    private val TAG = "PayloadInstaller"

    /**
     * Instala el payload desde los assets del APK.
     */
    fun installOffline(listener: InstallerManager.ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde assets...")

        try {
            paths.homeDir.mkdirs()
            File(paths.filesDir, "tmp").mkdirs()

            val assetPath = assetResolver.getPayloadAssetPath()
            if (assetPath == null) {
                val available = try {
                    context.assets.list("")?.joinToString(", ") ?: "(vacío)"
                } catch (e: Exception) { "(error: ${e.message})" }
                AppLogger.e(TAG, "No payload asset found. Available root assets: $available")
                listener.onError(
                    "No se encontró payload en el APK.\n" +
                    "Assets disponibles: $available\n" +
                    "Esperado: payload.tar.gz en assets/"
                )
                return
            }

            AppLogger.i(TAG, "Payload asset found: $assetPath")

            val payloadDest = File(paths.homeDir, "openclaw-payload.tar.gz")
            if (!payloadDest.exists() || payloadDest.length() < 1_000_000) {
                listener.onProgress(2, "Copiando payload desde APK...")
                AppLogger.i(TAG, "Copying asset '$assetPath' → ${payloadDest.absolutePath}")
                context.assets.open(assetPath).use { input ->
                    payloadDest.outputStream().use { output -> input.copyTo(output) }
                }
                AppLogger.i(TAG, "Payload copied: ${payloadDest.length() / 1024 / 1024}MB")
            } else {
                AppLogger.i(TAG, "Payload already copied: ${payloadDest.length() / 1024 / 1024}MB")
            }

            listener.onProgress(5, "Extrayendo payload (puede tardar 1-2 min)...")
            AppLogger.i(TAG, "Extracting payload to ${paths.homeDir.absolutePath}")
            val count = PayloadExtractor.extractTarGzFile(payloadDest, paths.homeDir)
            AppLogger.i(TAG, "Payload extracted: $count entries to ${paths.homeDir.absolutePath}")

            val payloadDir = paths.resolvePayloadDir()
            AppLogger.i(TAG, "Resolved payload dir: ${payloadDir.absolutePath} (exists=${payloadDir.exists()})")

            listener.onProgress(75, "Payload extraído ($count archivos). Configurando entorno...")
            configurator.completeInstallation(listener)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Offline extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo payload: ${e.message}", e)
        }
    }

    /**
     * Instala el payload desde un URI externo seleccionado por el usuario.
     */
    fun installFromCustomPayload(uri: Uri, listener: InstallerManager.ProgressListener) {
        listener.onProgress(0, "Preparando instalación desde archivo externo...")
        try {
            listener.onProgress(5, "Abriendo archivo seleccionado...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                listener.onProgress(10, "Extrayendo contenido (streaming)...")
                val count = PayloadExtractor.extractTarGzStream(input, paths.filesDir)
                AppLogger.i(TAG, "Custom payload extracted: $count entries")
            }
            configurator.completeInstallation(listener)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Custom payload extraction failed: ${e.message}", e)
            listener.onError("Error extrayendo archivo externo: ${e.message}", e)
        }
    }

    /**
     * Descarga e instala el payload desde la red.
     */
    suspend fun installOnline(listener: InstallerManager.ProgressListener) {
        listener.onProgress(0, "Iniciando descarga de payload...")

        val payloadUrl = "https://github.com/desarrollo032/openclaw-android/releases/download/latest/openclaw-payload.tar.gz"
        val tempFile = File(paths.cacheDir, "downloaded-payload.tar.gz")

        try {
            listener.onProgress(5, "Conectando con el servidor...")
            val url = URL(payloadUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000

            if (connection.responseCode != 200) {
                throw Exception("Error del servidor: ${connection.responseCode} ${connection.responseMessage}")
            }

            val totalSize = connection.contentLength.toLong()
            var downloaded = 0L

            listener.onProgress(10, "Descargando payload (${totalSize / 1024 / 1024}MB)...")

            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = 10 + (downloaded * 60 / totalSize).toInt()
                            listener.onProgress(
                                pct,
                                "Descargando... ${downloaded / 1024 / 1024}MB / ${totalSize / 1024 / 1024}MB"
                            )
                        }
                    }
                }
            }

            listener.onProgress(70, "Descarga completada. Extrayendo...")
            tempFile.inputStream().use { input ->
                val count = PayloadExtractor.extractTarGzStream(input, paths.filesDir)
                AppLogger.i(TAG, "Online payload extracted: $count entries")
            }

            configurator.completeInstallation(listener)

        } catch (e: Exception) {
            AppLogger.e(TAG, "Online install failed: ${e.message}", e)
            listener.onError("Error en la instalación online: ${e.message}", e)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Instala glibc desde un archivo externo proporcionado por el usuario.
     * @return true si la instalación fue exitosa.
     */
    fun installGlibcFromFile(archiveFile: File): Boolean {
        AppLogger.i(TAG, "Installing glibc from: ${archiveFile.absolutePath}")
        if (!archiveFile.exists() || archiveFile.length() < 10_000) return false

        val payloadDir = paths.resolvePayloadDir()
        val dest = File(paths.homeDir, "glibc-aarch64.tar.xz")
        if (archiveFile.absolutePath != dest.absolutePath) {
            try { archiveFile.copyTo(dest, overwrite = true) } catch (_: Exception) {}
        }

        return try {
            PayloadExtractor.extractTarXzFile(archiveFile, payloadDir)
            val ldso = File(payloadDir, "glibc/lib/ld-linux-aarch64.so.1")
            if (ldso.exists()) {
                ldso.setExecutable(true, false)
                PayloadExtractor.repairGlibcSymlinks(File(payloadDir, "glibc/lib"))
                true
            } else false
        } catch (e: Exception) {
            AppLogger.e(TAG, "glibc install failed: ${e.message}", e)
            false
        }
    }
}
