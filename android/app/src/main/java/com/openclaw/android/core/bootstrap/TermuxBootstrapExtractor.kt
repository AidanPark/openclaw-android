package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.FileOutputStream

/**
 * Extrae el ZIP del bootstrap de Termux preservando permisos Unix y symlinks.
 *
 * Responsabilidad única: extraer el archivo ZIP con:
 *   - Preservación de permisos Unix (modo)
 *   - Creación de symlinks (isUnixSymlink y SYMLINKS.txt)
 *   - Ejecutables en bin/ marcados como ejecutables
 */
internal class TermuxBootstrapExtractor {

    private val tag = "TermuxBootstrapExtractor"

    /**
     * Extrae el ZIP del bootstrap al directorio de destino.
     *
     * @param zipFile Archivo ZIP del bootstrap
     * @param targetDir Directorio donde extraer (PREFIX)
     * @param onProgress Callback con número de archivos extraídos
     * @return Número total de entradas extraídas
     */
    fun extractBootstrap(
        zipFile: File,
        targetDir: File,
        onProgress: (Int) -> Unit,
    ): Int {
        targetDir.mkdirs()
        var count = 0

        ZipFile.builder().setFile(zipFile).get().use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val dest = File(targetDir, entry.name)

                try {
                    when {
                        entry.isDirectory -> dest.mkdirs()

                        entry.isUnixSymlink -> {
                            // El contenido del entry ES el target del symlink
                            dest.parentFile?.mkdirs()
                            val target = zip.getInputStream(entry).bufferedReader().readText().trim()
                            dest.delete()
                            try {
                                android.system.Os.symlink(target, dest.absolutePath)
                            } catch (e: Exception) {
                                AppLogger.w(tag, "Symlink failed: ${entry.name} -> $target: ${e.message}")
                            }
                        }

                        // Archivo especial: SYMLINKS.txt — procesar symlinks adicionales
                        entry.name == "SYMLINKS.txt" -> {
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { out -> input.copyTo(out) }
                            }
                            // Procesar el archivo de symlinks
                            processSymlinksFile(dest, targetDir)
                        }

                        else -> {
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { out -> input.copyTo(out) }
                            }
                            // Aplicar permisos Unix del ZIP
                            val mode = entry.unixMode
                            if (mode != 0 && (mode and 0b001_001_001) != 0) {
                                dest.setExecutable(true, false)
                            }
                            // bin/ siempre ejecutable
                            if (entry.name.contains("/bin/") || entry.name.startsWith("bin/")) {
                                dest.setExecutable(true, false)
                            }
                        }
                    }
                    count++
                    onProgress(count)
                } catch (e: Exception) {
                    AppLogger.e(tag, "Extract failed: ${entry.name}: ${e.message}")
                }
            }
        }

        AppLogger.i(tag, "Extracted $count entries to ${targetDir.absolutePath}")
        return count
    }

    /**
     * Procesa SYMLINKS.txt del bootstrap de Termux.
     * Formato: "target←linkpath" (separador: ← U+2190, una sola flecha)
     * Ejemplo: "libreadline.so.8.3←./lib/libreadline.so.8"
     *
     * El linkpath puede tener prefijo "./" que se elimina al construir la ruta.
     */
    private fun processSymlinksFile(symlinksFile: File, targetDir: File) {
        if (!symlinksFile.exists()) return
        var created = 0
        var failed = 0
        try {
            symlinksFile.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                // Separator is a single LEFT ARROW ← (U+2190)
                val sepIdx = line.indexOf('\u2190')
                if (sepIdx < 0) return@forEach

                val target   = line.substring(0, sepIdx).trim()
                val linkPath = line.substring(sepIdx + 1).trim()
                    .removePrefix("./")   // strip leading "./" if present

                val linkFile = File(targetDir, linkPath)
                linkFile.parentFile?.mkdirs()
                linkFile.delete()
                try {
                    android.system.Os.symlink(target, linkFile.absolutePath)
                    created++
                } catch (e: Exception) {
                    AppLogger.w(tag, "Symlink failed: $linkPath -> $target: ${e.message}")
                    failed++
                }
            }
            AppLogger.i(tag, "SYMLINKS.txt: $created created, $failed failed")
        } catch (e: Exception) {
            AppLogger.w(tag, "Failed to process SYMLINKS.txt: ${e.message}")
        }
    }
}
