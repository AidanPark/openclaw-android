package com.openclaw.android.core.bootstrap

import android.content.Context
import com.openclaw.android.AppLogger
import java.io.File

/**
 * Gestiona los marcadores de instalación de Termux Bootstrap.
 */
class TermuxBootstrapMarker(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBootstrapMarker"
        const val MARKER_FILE = ".termux-bootstrap-installed"
    }

    fun isInstalled(): Boolean {
        val marker = File(context.filesDir, MARKER_FILE)
        val usrDir = File(context.filesDir, "usr")
        return marker.exists() && usrDir.exists()
    }

    fun writeMarker() {
        try {
            File(context.filesDir, MARKER_FILE).writeText("${System.currentTimeMillis()}")
            AppLogger.i(TAG, "Marker written")
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not write marker: ${e.message}")
        }
    }

    fun clearMarker() {
        File(context.filesDir, MARKER_FILE).delete()
    }

    fun getInstallTime(): Long {
        val marker = File(context.filesDir, MARKER_FILE)
        return if (marker.exists()) {
            try {
                marker.readText().trim().toLong()
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
    }
}
