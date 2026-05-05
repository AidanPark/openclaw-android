package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga el archivo ZIP del bootstrap de Termux desde packages.termux.dev.
 *
 * Responsabilidad única: descargar el archivo con manejo de progreso,
 * reutilización de descargas existentes y manejo de errores HTTP.
 */
internal class TermuxBootstrapDownloader(
    private val cacheDir: File,
) {

    private val TAG = "TermuxBootstrapDownloader"

    /**
     * Descarga el bootstrap ZIP desde la URL especificada.
     *
     * @param url URL del bootstrap ZIP
     * @param dest Archivo de destino
     * @param onProgress Callback con bytes descargados y total (total puede ser -1)
     * @throws IllegalStateException si la respuesta HTTP no es 200 OK
     */
    fun downloadBootstrap(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()

        // Reutilizar si ya está descargado y tiene tamaño razonable (>5MB)
        if (dest.exists() && dest.length() > 5_000_000) {
            AppLogger.i(TAG, "Bootstrap already downloaded: ${dest.length()} bytes")
            onProgress(dest.length(), dest.length())
            return
        }

        AppLogger.i(TAG, "Downloading bootstrap from $url")

        // GitHub releases redirect from github.com to objects.githubusercontent.com.
        // HttpURLConnection does not follow cross-domain HTTPS redirects automatically,
        // so we resolve the final URL manually before downloading.
        val finalUrl = resolveRedirects(url)
        AppLogger.i(TAG, "Final download URL: $finalUrl")

        val conn = URL(finalUrl).openConnection() as HttpURLConnection
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
                BufferedInputStream(input, 32 * 1024).use { buf ->
                    FileOutputStream(dest).use { out ->
                        val buffer = ByteArray(32 * 1024)
                        var n: Int
                        while (buf.read(buffer).also { n = it } != -1) {
                            out.write(buffer, 0, n)
                            downloaded += n
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            AppLogger.i(TAG, "Downloaded: ${dest.length()} bytes")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Obtiene la ruta del archivo ZIP en cache para una arquitectura específica.
     */
    fun getBootstrapCacheFile(arch: String): File {
        return File(cacheDir, "termux-bootstrap-$arch.zip")
    }

    /**
     * Resuelve redirects HTTP/HTTPS manualmente hasta llegar a la URL final.
     *
     * HttpURLConnection en Android no sigue redirects cross-domain automáticamente
     * (e.g. github.com → objects.githubusercontent.com). Este método los resuelve
     * manualmente con un máximo de 10 saltos para evitar bucles infinitos.
     */
    private fun resolveRedirects(startUrl: String, maxHops: Int = 10): String {
        var currentUrl = startUrl
        repeat(maxHops) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        AppLogger.i(TAG, "Redirect $code: $currentUrl → $location")
                        currentUrl = location
                        return@repeat
                    }
                }
                // Not a redirect — this is the final URL
                return currentUrl
            } finally {
                conn.disconnect()
            }
        }
        AppLogger.w(TAG, "Max redirect hops reached, using: $currentUrl")
        return currentUrl
    }
}
