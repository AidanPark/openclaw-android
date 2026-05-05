package com.openclaw.android.core.proot

import com.openclaw.android.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Descarga y extrae el binario proot desde paquetes .deb de Termux.
 *
 * Responsabilidad única: obtener el binario proot estático desde
 * un paquete .deb y colocarlo en la ubicación correcta.
 */
internal class ProotBinaryDownloader(
    private val pathResolver: ProotPathResolver,
    private val fileDownloader: ProotFileDownloader,
) {

    private val TAG = ProotConstants.TAG

    /**
     * Descarga el binario proot desde Termux packages.
     *
     * @param onProgress Callback de progreso (porcentaje, mensaje)
     * @return true si la descarga y extracción fueron exitosas
     */
    fun downloadProot(onProgress: (Int, String) -> Unit): Boolean {
        val paths = pathResolver.getPaths()
        val binDir = paths.prootBin.parentFile!!
        binDir.mkdirs()

        // Si ya existe y es ejecutable, no re-descargar
        if (paths.prootBin.exists() && paths.prootBin.canExecute() && paths.prootBin.length() > 100_000) {
            AppLogger.i(TAG, "proot already present: ${paths.prootBin.absolutePath}")
            return true
        }

        val debFile = pathResolver.getProotDebCacheFile()
        val dataTar = pathResolver.getProotDataTarCacheFile()

        return try {
            onProgress(2, "Descargando proot (binario nativo)...")
            AppLogger.i(TAG, "Downloading proot from ${ProotConstants.PROOT_URL_ARM64}")

            fileDownloader.downloadFile(ProotConstants.PROOT_URL_ARM64, debFile) { downloaded, total ->
                if (total > 0) {
                    val pct = (downloaded * 8 / total).toInt().coerceIn(2, 8)
                    onProgress(pct, "Descargando proot... ${downloaded / 1024}KB / ${total / 1024}KB")
                }
            }

            onProgress(8, "Extrayendo proot del paquete .deb...")
            extractProotFromDeb(debFile, dataTar, paths.prootBin)

            if (!paths.prootBin.exists() || paths.prootBin.length() < 100_000) {
                AppLogger.e(TAG, "proot extraction failed — binary missing or too small")
                return false
            }

            paths.prootBin.setExecutable(true, false)
            AppLogger.i(TAG, "proot ready: ${paths.prootBin.absolutePath} (${paths.prootBin.length()} bytes)")
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "downloadProot failed: ${e.message}", e)
            false
        } finally {
            debFile.delete()
            dataTar.delete()
        }
    }

    /**
     * Extrae el binario proot de un paquete .deb de Termux.
     *
     * Estructura de un .deb:
     *   ar archive:
     *     debian-binary
     *     control.tar.xz
     *     data.tar.xz   ← aquí está el binario
     *
     * El binario está en: data/data/com.termux/files/usr/bin/proot
     */
    private fun extractProotFromDeb(debFile: File, dataTarDest: File, prootDest: File) {
        // Parsear el formato ar manualmente (es simple: magic + entries)
        debFile.inputStream().use { fis ->
            val magic = ByteArray(8)
            fis.read(magic)
            val magicStr = String(magic)
            if (!magicStr.startsWith("!<arch>")) {
                throw IllegalStateException("Not a valid .deb file (bad ar magic: $magicStr)")
            }

            // Leer entradas ar hasta encontrar data.tar.xz
            while (true) {
                val header = ByteArray(60)
                val read = fis.read(header)
                if (read < 60) break

                val name = String(header, 0, 16).trim()
                val sizeStr = String(header, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: break

                AppLogger.d(TAG, "ar entry: '$name' size=$size")

                if (name.startsWith("data.tar")) {
                    // Encontrado — copiar a archivo temporal
                    dataTarDest.parentFile?.mkdirs()
                    val buf = ByteArray(32 * 1024)
                    var remaining = size
                    dataTarDest.outputStream().use { out ->
                        while (remaining > 0) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val n = fis.read(buf, 0, toRead)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    break
                } else {
                    // Saltar esta entrada (con padding a 2 bytes)
                    val toSkip = size + (size % 2)
                    fis.skip(toSkip)
                }
            }
        }

        if (!dataTarDest.exists() || dataTarDest.length() == 0L) {
            throw IllegalStateException("data.tar.xz not found in .deb")
        }

        // Extraer el binario proot del data.tar.xz
        dataTarDest.inputStream().use { raw ->
            BufferedInputStream(raw).use { buf ->
                org.apache.commons.compress.compressors.xz.XZCompressorInputStream(buf).use { xz ->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            val entryName = entry.name.trimStart('/')
                            AppLogger.d(TAG, "tar entry: $entryName")
                            if (ProotConstants.PROOT_BINARY_PATHS_IN_DEB.any { entryName.endsWith(it.trimStart('.', '/')) }) {
                                prootDest.parentFile?.mkdirs()
                                prootDest.outputStream().use { out ->
                                    tar.copyTo(out)
                                }
                                AppLogger.i(TAG, "Extracted proot binary: $entryName → ${prootDest.absolutePath}")
                                break
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
    }
}
