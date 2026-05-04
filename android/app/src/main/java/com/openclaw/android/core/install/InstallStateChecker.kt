package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallValidator
import com.openclaw.android.SetupManager
import java.io.File

/**
 * Comprueba el estado de la instalación sin modificar nada en disco.
 *
 * Responsabilidad única: responder preguntas sobre si el entorno está
 * instalado, listo o en qué estado se encuentra.
 */
internal class InstallStateChecker(
    private val context: Context,
    private val paths: InstallPathResolver,
) {

    private val TAG = "InstallStateChecker"

    /** True si el entorno está estructuralmente instalado (marcador + dirs). */
    fun isInstalled(): Boolean {
        // Verificar instalación proot (nuevo flujo)
        val prootInstalled = File(paths.filesDir, ".proot-installed").exists() &&
            SetupManager(context).isInstalled()
        if (prootInstalled) return true

        // Verificar instalación online (curl -sL myopenclawhub.com/install | bash)
        if (isOnlineInstallPresent()) return true

        // Verificar instalación legada (payload)
        return paths.prefix.isDirectory && paths.markerInstalled.exists()
    }

    /** True si glibc y node están presentes (entorno completamente funcional). */
    fun isReady(): Boolean {
        // proot: verificar que openclaw está en el rootfs
        if (File(paths.filesDir, ".proot-installed").exists()) {
            return SetupManager(context).isOpenClawInstalledInRootfs()
        }
        // Online install: verificar node + openclaw en rutas del script online
        if (isOnlineInstallPresent()) return true

        return isInstalled() && InstallValidator.isStructurallyComplete(paths.prefix)
    }

    /** True si openclaw.mjs o el script de lanzamiento están presentes. */
    fun isOpenClawInstalled(): Boolean {
        if (File(paths.filesDir, ".proot-installed").exists()) {
            return SetupManager(context).isOpenClawInstalledInRootfs()
        }
        // Online install (curl -sL myopenclawhub.com/install | bash)
        if (File(paths.prefix, "lib/node_modules/openclaw/openclaw.mjs").exists()) return true

        // payload-final.tar.gz layout
        val ocaPayloadDir = paths.resolvePayloadDir()
        return File(paths.prefix, "bin/openclaw").exists() ||
            File(ocaPayloadDir, "run-openclaw.sh").exists() ||
            File(ocaPayloadDir, "lib/openclaw/openclaw.mjs").exists()
    }

    /** Resumen de estado para la UI / JsBridge. */
    fun getStatus(): String = when {
        !isInstalled() -> "not_installed"
        !isReady()     -> "installed_not_ready"
        else           -> "ready"
    }

    /**
     * Detecta si la instalación online (curl -sL myopenclawhub.com/install | bash)
     * está presente y funcional.
     *
     * El script online instala en:
     *   homeDir/.openclaw-android/node/bin/node.real  ← Node.js
     *   homeDir/.openclaw-android/installed.json      ← marcador
     *   prefix/lib/node_modules/openclaw/openclaw.mjs ← OpenClaw
     *   prefix/glibc/lib/ld-linux-aarch64.so.1        ← glibc linker
     */
    fun isOnlineInstallPresent(): Boolean {
        val ocaDir = File(paths.homeDir, ".openclaw-android")
        val nodeReal = File(ocaDir, "node/bin/node.real")
        val installedJson = File(ocaDir, "installed.json")
        val ocMjs = File(paths.prefix, "lib/node_modules/openclaw/openclaw.mjs")
        val ldso = File(paths.prefix, "glibc/lib/ld-linux-aarch64.so.1")

        val hasNode = nodeReal.exists() && nodeReal.length() > 1_000_000
        val hasMarker = installedJson.exists()
        val hasOpenClaw = ocMjs.exists()
        val hasGlibc = ldso.exists() && ldso.length() > 100_000

        if (hasNode && hasMarker && hasOpenClaw && hasGlibc) {
            AppLogger.i(TAG, "Online install detected: node=${nodeReal.absolutePath}")
            return true
        }
        return false
    }
}
