package com.openclaw.android.core.install

import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallValidator
import com.openclaw.android.ProotManager
import java.io.File

/**
 * Checks installation state without modifying anything on disk.
 * Uses ProotManager directly — SetupManager removed (was a shim over ProotManager).
 */
internal class InstallStateChecker(
    private val context: Context,
    private val paths: InstallPathResolver,
) {
    private val TAG = "InstallStateChecker"

    fun isInstalled(): Boolean {
        // Check proot installation — use ProotManager directly
        val prootInstalled = File(paths.filesDir, ".proot-installed").exists() &&
            ProotManager.isProotReady(context) &&
            ProotManager.isRootfsReady(context)
        if (prootInstalled) return true

        if (isOnlineInstallPresent()) return true

        return paths.prefix.isDirectory && paths.markerInstalled.exists()
    }

    fun isReady(): Boolean {
        if (File(paths.filesDir, ".proot-installed").exists()) {
            return isOpenClawInstalledInProot()
        }
        if (isOnlineInstallPresent()) return true
        return isInstalled() && InstallValidator.isStructurallyComplete(paths.prefix)
    }

    fun isOpenClawInstalled(): Boolean {
        if (File(paths.filesDir, ".proot-installed").exists()) {
            return isOpenClawInstalledInProot()
        }
        if (File(paths.prefix, "lib/node_modules/openclaw/openclaw.mjs").exists()) return true
        val ocaPayloadDir = paths.resolvePayloadDir()
        return File(paths.prefix, "bin/openclaw").exists() ||
            File(ocaPayloadDir, "run-openclaw.sh").exists() ||
            File(ocaPayloadDir, "lib/openclaw/openclaw.mjs").exists()
    }

    fun getStatus(): String = when {
        !isInstalled() -> "not_installed"
        !isReady()     -> "installed_not_ready"
        else           -> "ready"
    }

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

    /** Check if OpenClaw is installed inside the proot rootfs. */
    private fun isOpenClawInstalledInProot(): Boolean {
        val prootPaths = ProotManager.getPaths(context)
        return prootPaths.rootfsDir.resolve("usr/local/lib/node_modules/openclaw/openclaw.mjs").exists() ||
            prootPaths.rootfsDir.resolve("usr/local/bin/openclaw").exists()
    }
}
