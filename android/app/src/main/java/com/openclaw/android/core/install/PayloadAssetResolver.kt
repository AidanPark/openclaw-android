package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger

/**
 * Resuelve la presencia y ruta de los assets de payload empaquetados en el APK.
 *
 * Responsabilidad única: saber si hay un payload en assets y cuál es su nombre.
 */
internal class PayloadAssetResolver(private val context: Context) {

    private val TAG = "PayloadAssetResolver"

    private val CANDIDATE_NAMES = listOf(
        "payload.tar.gz",
        "payload-final.tar.gz",
        "openclaw-payload.tar.gz",
        "payload/openclaw-payload.tar.gz",
        "payload/payload.tar.gz",
    )

    /** True si el APK contiene algún asset de payload reconocido. */
    fun hasPayloadAsset(): Boolean {
        for (name in CANDIDATE_NAMES) {
            try {
                context.assets.open(name).use {
                    AppLogger.i(TAG, "Payload asset found: $name")
                    return true
                }
            } catch (_: Exception) {
            }
        }
        // Diagnóstico: listar assets disponibles en la raíz
        try {
            val rootAssets = context.assets.list("")?.joinToString(", ") ?: "(vacío)"
            AppLogger.w(TAG, "No payload asset found. Root assets: [$rootAssets]")
        } catch (e: Exception) {
            AppLogger.w(TAG, "No payload asset found. Could not list assets: ${e.message}")
        }
        return false
    }

    /**
     * Devuelve el nombre del primer asset de payload encontrado, o null si no hay ninguno.
     */
    fun getPayloadAssetPath(): String? {
        for (name in CANDIDATE_NAMES) {
            try {
                context.assets.open(name).use { return name }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
