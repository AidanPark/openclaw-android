package com.openclaw.android.core.bootstrap

import android.os.Build
import com.openclaw.android.AppLogger

/**
 * Detecta la arquitectura del dispositivo para descargar el bootstrap correcto.
 *
 * Responsabilidad única: mapear Build.SUPPORTED_ABIS a los nombres
 * de arquitectura soportados por Termux, y construir la URL de descarga.
 *
 * URL pattern:
 *   https://github.com/termux/termux-packages/releases/download/{TAG}/bootstrap-{ARCH}.zip
 *
 * La versión se obtiene dinámicamente desde la API de GitHub releases.
 * Si la API falla, se usa FALLBACK_TAG como respaldo.
 */
internal object TermuxArchitectureDetector {

    private const val TAG = "TermuxArchitectureDetector"

    // Última versión conocida — se usa si la API de GitHub no responde.
    // Actualizar cuando salga una nueva versión estable.
    private const val FALLBACK_TAG = "bootstrap-2026.05.03-r1+apt.android-7"

    // URL de la API de GitHub para obtener el último release
    private const val GITHUB_LATEST_API =
        "https://api.github.com/repos/termux/termux-packages/releases/latest"

    // Base URL para descargas de releases de GitHub
    private const val GITHUB_DOWNLOAD_BASE =
        "https://github.com/termux/termux-packages/releases/download"

    /**
     * Detecta la arquitectura del dispositivo basándose en Build.SUPPORTED_ABIS.
     */
    fun detectArchitecture(): String {
        val abis = Build.SUPPORTED_ABIS
        AppLogger.i(TAG, "Device ABIs: ${abis.joinToString(", ")}")
        return when {
            abis.any { it.startsWith("arm64") || it == "aarch64" } -> "aarch64"
            abis.any { it.startsWith("armeabi") }                  -> "arm"
            abis.any { it == "x86_64" }                            -> "x86_64"
            abis.any { it == "x86" }                               -> "i686"
            else -> {
                AppLogger.w(TAG, "Unknown ABI, defaulting to aarch64")
                "aarch64"
            }
        }
    }

    /**
     * Construye la URL de descarga del bootstrap para la arquitectura dada.
     *
     * Intenta obtener el tag del último release desde la API de GitHub.
     * Si falla (sin internet, timeout, etc.), usa FALLBACK_TAG.
     *
     * @param arch Arquitectura (aarch64, arm, x86_64, i686)
     * @return URL completa del ZIP del bootstrap
     * @throws IllegalStateException si la arquitectura no está soportada
     */
    fun getBootstrapUrl(arch: String): String {
        val supportedArchs = setOf("aarch64", "arm", "x86_64", "i686")
        if (arch !in supportedArchs) {
            throw IllegalStateException("Arquitectura no soportada: $arch")
        }

        val tag = resolveLatestTag()
        val encodedTag = tag.replace("+", "%2B")
        val url = "$GITHUB_DOWNLOAD_BASE/$encodedTag/bootstrap-$arch.zip"
        AppLogger.i(TAG, "Bootstrap URL: $url")
        return url
    }

    /**
     * Obtiene el tag del último release de termux-packages desde la API de GitHub.
     * Retorna FALLBACK_TAG si la petición falla por cualquier motivo.
     */
    private fun resolveLatestTag(): String {
        return try {
            val conn = java.net.URL(GITHUB_LATEST_API).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "OpenClaw-Android/1.0")

            if (conn.responseCode != 200) {
                AppLogger.w(TAG, "GitHub API returned ${conn.responseCode}, using fallback tag")
                return FALLBACK_TAG
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Extraer "tag_name" del JSON sin dependencia de Gson
            val match = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(body)
            val tag = match?.groupValues?.getOrNull(1)

            if (tag.isNullOrBlank()) {
                AppLogger.w(TAG, "Could not parse tag_name from GitHub API, using fallback")
                FALLBACK_TAG
            } else {
                AppLogger.i(TAG, "Latest bootstrap tag from GitHub: $tag")
                tag
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "GitHub API request failed: ${e.message} — using fallback tag")
            FALLBACK_TAG
        }
    }
}
