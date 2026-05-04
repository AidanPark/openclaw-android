package com.openclaw.android.core.proot

import com.openclaw.android.AppLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga archivos desde URLs con manejo de progreso.
 *
 * Responsabilidad única: descargar archivos con timeout, redirecciones
 * y reporte de progreso.
 */
internal class ProotFileDownloader {

    private val TAG = ProotConstants.TAG

    /**
     * Descarga un archivo desde una URL.
     *
     * @param urlStr URL del archivo
     * @param dest Archivo de destino
     * @param onProgress Callback con bytes descargados y total (total puede ser -1)
     * @throws IllegalStateException si la respuesta HTTP no es 200 OK
     */
    fun downloadFile(
        urlStr: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode}: ${conn.responseMessage}")
            }

            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(32 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            AppLogger.i(TAG, "Downloaded: ${dest.length()} bytes from $urlStr")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Descarga un archivo con reintento en caso de fallo.
     *
     * @param primaryUrl URL principal
     * @param fallbackUrl URL alternativa (opcional)
     * @param dest Archivo de destino
     * @param onProgress Callback de progreso
     * @return true si la descarga fue exitosa
     */
    fun downloadFileWithFallback(
        primaryUrl: String,
        fallbackUrl: String?,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        return try {
            downloadFile(primaryUrl, dest, onProgress)
            true
        } catch (e: Exception) {
            AppLogger.w(TAG, "Primary URL failed: ${e.message}")
            if (fallbackUrl != null) {
                try {
                    downloadFile(fallbackUrl, dest, onProgress)
                    true
                } catch (e2: Exception) {
                    AppLogger.e(TAG, "Fallback URL also failed: ${e2.message}")
                    false
                }
            } else {
                false
            }
        }
    }
}
