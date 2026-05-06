package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * Extrae el ZIP del bootstrap de Termux.
 */
class TermuxBootstrapExtractor {

    companion object {
        private const val TAG = "TermuxBootstrapExtractor"
        private const val BUFFER_SIZE = 8192
    }

    fun extract(
        zipFile: File,
        outputDir: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            AppLogger.i(TAG, "Extracting: ${zipFile.absolutePath}")

            BufferedInputStream(FileInputStream(zipFile)).use { bis ->
                ZipInputStream(bis).use { zis ->
                    var entry = zis.nextEntry
                    var entryCount = 0

                    while (entry != null) {
                        val outFile = File(outputDir, entry.name)

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output ->
                                zis.copyTo(output, BUFFER_SIZE)
                            }

                            // Preservar permisos de ejecución
                            if (entry.name.startsWith("usr/bin/") ||
                                entry.name.startsWith("usr/libexec/")) {
                                outFile.setExecutable(true, false)
                            }
                        }

                        entryCount++
                        if (entryCount % 50 == 0) {
                            onProgress((entryCount * 100 / 2000).coerceIn(0, 100))
                        }

                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            AppLogger.i(TAG, "Extraction complete: $entryCount entries")
            true

        } catch (e: Exception) {
            AppLogger.e(TAG, "Extraction failed: ${e.message}", e)
            false
        }
    }
}
