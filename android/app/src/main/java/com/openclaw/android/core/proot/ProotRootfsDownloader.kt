package com.openclaw.android.core.proot

import com.openclaw.android.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Descarga y extrae el rootfs Ubuntu minimal para arm64.
 *
 * Responsabilidad única: obtener el rootfs Ubuntu (~80MB comprimido,
 * ~250MB extraído) y colocarlo en la ubicación correcta.
 */
internal class ProotRootfsDownloader(
    private val pathResolver: ProotPathResolver,
    private val fileDownloader: ProotFileDownloader,
    private val rootfsConfigurator: ProotRootfsConfigurator,
) {

    private val TAG = ProotConstants.TAG

    /**
     * Descarga y extrae el rootfs Ubuntu minimal para arm64.
     *
     * @param onProgress Callback de progreso (porcentaje, mensaje)
     * @return true si la descarga y extracción fueron exitosas
     */
    fun downloadAndExtractRootfs(onProgress: (Int, String) -> Unit): Boolean {
        val paths = pathResolver.getPaths()

        if (pathResolver.isRootfsReady()) {
            AppLogger.i(TAG, "Ubuntu rootfs already ready at ${paths.rootfsDir.absolutePath}")
            return true
        }

        paths.rootfsDir.mkdirs()
        val tarFile = pathResolver.getRootfsTarCacheFile()

        return try {
            onProgress(10, "Descargando Ubuntu rootfs (~80MB)...")
            AppLogger.i(TAG, "Downloading Ubuntu rootfs from ${ProotConstants.UBUNTU_ROOTFS_URL}")

            val downloadOk = fileDownloader.downloadFileWithFallback(
                primaryUrl = ProotConstants.UBUNTU_ROOTFS_URL,
                fallbackUrl = ProotConstants.UBUNTU_ROOTFS_URL_FALLBACK,
                dest = tarFile,
                onProgress = { downloaded, total ->
                    if (total > 0) {
                        val pct = 10 + (downloaded * 40 / total).toInt().coerceIn(0, 40)
                        onProgress(pct, "Descargando Ubuntu... ${downloaded / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                    } else {
                        onProgress(20, "Descargando Ubuntu... ${downloaded / 1024 / 1024}MB")
                    }
                }
            )

            if (!downloadOk || !tarFile.exists() || tarFile.length() < 10_000_000) {
                AppLogger.e(TAG, "Rootfs download failed — file too small: ${tarFile.length()}")
                return false
            }

            onProgress(50, "Extrayendo rootfs Ubuntu (~250MB, puede tardar 2-3 min)...")
            AppLogger.i(TAG, "Extracting rootfs to ${paths.rootfsDir.absolutePath}")

            val count = extractTarXzOrGz(tarFile, paths.rootfsDir) { entriesProcessed ->
                // Actualizar progreso cada 500 entradas
                if (entriesProcessed % 500 == 0) {
                    val pct = (50 + (entriesProcessed / 200).coerceAtMost(28)).toInt()
                    onProgress(pct, "Extrayendo rootfs... $entriesProcessed archivos")
                }
            }

            AppLogger.i(TAG, "Rootfs extracted: $count entries")

            // Crear directorios necesarios para proot
            rootfsConfigurator.setupRootfsDirs(paths.rootfsDir)

            onProgress(80, "Rootfs Ubuntu listo ($count archivos)")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadAndExtractRootfs failed: ${e.message}", e)
            false
        } finally {
            tarFile.delete()
        }
    }

    /**
     * Extrae un tar.xz o tar.gz al directorio destino.
     * Detecta el formato por los primeros bytes.
     */
    private fun extractTarXzOrGz(
        tarFile: File,
        destDir: File,
        onEntry: (Int) -> Unit,
    ): Int {
        var count = 0
        tarFile.inputStream().use { raw ->
            BufferedInputStream(raw).use { buf ->
                // Detectar formato: XZ empieza con FD 37 7A 58 5A 00, GZ con 1F 8B
                buf.mark(6)
                val header = ByteArray(6)
                buf.read(header)
                buf.reset()

                val isXz = header[0] == 0xFD.toByte() && header[1] == 0x37.toByte()
                val decompressed = if (isXz) {
                    org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buf)
                } else {
                    GZIPInputStream(buf)
                }

                decompressed.use { decomp ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(decomp).use { tar ->
                        var entry = tar.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                        while (entry != null) {
                            val destFile = File(destDir, entry.name)
                            try {
                                when {
                                    entry.isDirectory -> destFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        destFile.parentFile?.mkdirs()
                                        destFile.delete()
                                        try {
                                            android.system.Os.symlink(entry.linkName, destFile.absolutePath)
                                        } catch (_: Exception) {}
                                    }
                                    else -> {
                                        destFile.parentFile?.mkdirs()
                                        destFile.outputStream().use { out -> tar.copyTo(out) }
                                        if ((entry.mode and 0b001_001_001) != 0) {
                                            destFile.setExecutable(true, false)
                                        }
                                    }
                                }
                                count++
                                onEntry(count)
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "Skip entry ${entry.name}: ${e.message}")
                            }
                            entry = tar.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                        }
                    }
                }
            }
        }
        return count
    }
}
