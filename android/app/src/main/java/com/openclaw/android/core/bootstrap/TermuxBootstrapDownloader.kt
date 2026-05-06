package com.openclaw.android.core.bootstrap

import com.openclaw.android.AppLogger
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Descarga el bootstrap de Termux desde GitHub.
 */
class TermuxBootstrapDownloader {

    companion object {
        private const val TAG = "TermuxBootstrapDownloader"
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT = 30000
        private const val READ_TIMEOUT = 120000
    }

    fun download(
        url: URL,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        return try {
            AppLogger.i(TAG, "Downloading from: $url")

            val connection = url.openConnection() as HttpsURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("User-Agent", "OpenClaw-Android")

            val totalSize = connection.contentLength
            var downloaded = 0

            connection.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        if (totalSize > 0) {
                            val progress = (downloaded * 100 / totalSize).coerceIn(0, 100)
                            onProgress(progress)
                        }
                    }
                }
            }

            AppLogger.i(TAG, "Download complete: ${outputFile.length()} bytes")
            true

        } catch (e: Exception) {
            AppLogger.e(TAG, "Download failed: ${e.message}", e)
            outputFile.delete()
            false
        }
    }
}
