package com.openclaw.android.core.bootstrap

import android.os.Build

/**
 * Detecta la arquitectura del dispositivo para descargar el bootstrap correcto.
 */
class TermuxArchitectureDetector {

    fun getArchitecture(): String {
        return when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "i686"
            else -> "aarch64" // Default
        }
    }

    fun isSupported(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        return abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
