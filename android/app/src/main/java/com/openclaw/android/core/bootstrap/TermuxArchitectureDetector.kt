package com.openclaw.android.core.bootstrap

import android.os.Build
import com.openclaw.android.AppLogger

/**
 * Detecta la arquitectura del dispositivo para descargar el bootstrap correcto.
 *
 * Responsabilidad única: mapear Build.SUPPORTED_ABIS a los nombres
 * de arquitectura soportados por Termux.
 */
internal object TermuxArchitectureDetector {

    private const val TAG = "TermuxArchitectureDetector"

    // URLs del bootstrap oficial por arquitectura
    val BOOTSTRAP_URLS = mapOf(
        "aarch64" to "https://packages.termux.dev/bootstrap/bootstrap-aarch64.zip",
        "arm"     to "https://packages.termux.dev/bootstrap/bootstrap-arm.zip",
        "x86_64"  to "https://packages.termux.dev/bootstrap/bootstrap-x86_64.zip",
        "i686"    to "https://packages.termux.dev/bootstrap/bootstrap-i686.zip",
    )

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
     * Obtiene la URL del bootstrap para la arquitectura detectada.
     * @throws IllegalStateException si la arquitectura no está soportada
     */
    fun getBootstrapUrl(arch: String): String {
        return BOOTSTRAP_URLS[arch]
            ?: throw IllegalStateException("Arquitectura no soportada: $arch")
    }
}
