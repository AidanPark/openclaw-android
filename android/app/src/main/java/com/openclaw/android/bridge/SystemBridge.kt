package com.openclaw.android.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.pm.PackageInfoCompat
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.CommandRunner
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.core.env.EnvironmentResolver
import java.io.File
import java.security.MessageDigest

/**
 * SystemBridge — WebView ↔ Kotlin bridge for system/device information.
 *
 * Handles: app info, battery, permissions, storage, clipboard, OTA updates.
 */
class SystemBridge(
    private val activity: MainActivity,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "SystemBridge"
    }

    // ── App info ───────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getAppInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val versionCode = PackageInfoCompat.getLongVersionCode(pInfo)
        return gson.toJson(mapOf(
            "packageName" to activity.packageName,
            "versionName" to pInfo.versionName,
            "versionCode" to versionCode,
            "filesDir" to activity.filesDir.absolutePath,
        ))
    }

    @JavascriptInterface
    fun getBatteryInfo(): String {
        val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val isCharging = bm?.isCharging ?: false
        return gson.toJson(mapOf("level" to level, "isCharging" to isCharging))
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        val filesDir = activity.filesDir
        // Device-level storage (total/free on the partition)
        val total = filesDir.totalSpace
        val free = filesDir.freeSpace
        val used = total - free

        // App-level storage: actual disk usage of the app sandbox
        val appUsedBytes = calculateDirSize(filesDir)

        return gson.toJson(mapOf(
            "total" to total,
            "free" to free,
            "used" to used,
            "totalMb" to total / 1024 / 1024,
            "freeMb" to free / 1024 / 1024,
            "usedMb" to used / 1024 / 1024,
            // App sandbox usage (what the user actually installed)
            "appUsedBytes" to appUsedBytes,
            "appUsedMb" to appUsedBytes / 1024 / 1024,
        ))
    }

    /** Recursively calculate directory size in bytes. */
    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (_: Exception) { 0L }
    }

    @JavascriptInterface
    fun getPermissionsStatus(): String {
        val hasStorage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        val hasNotifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true

        val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isBatteryOptimized = pm?.isIgnoringBatteryOptimizations(activity.packageName) == false

        return gson.toJson(mapOf(
            "storage" to hasStorage,
            "notifications" to hasNotifications,
            "batteryOptimized" to isBatteryOptimized,
        ))
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExemption() {
        activity.runOnUiThread {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Battery optimization request failed: ${e.message}", e)
            }
        }
    }

    // ── Clipboard ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }
    }

    @JavascriptInterface
    fun getClipboardText(): String {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    // ── OTA Updates ────────────────────────────────────────────────────────

    @JavascriptInterface
    fun checkForUpdates(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val currentVersion = pInfo.versionName ?: "0.0.0"
        return gson.toJson(mapOf(
            "currentVersion" to currentVersion,
            "updateUrl" to "https://github.com/AidanPark/openclaw-android/releases/latest",
        ))
    }

    @JavascriptInterface
    fun applyWwwUpdate(zipPath: String) {
        val zipFile = File(zipPath)
        if (!zipFile.exists()) {
            eventBridge.emit("update_progress", mapOf("success" to false, "error" to "File not found: $zipPath"))
            return
        }
        val wwwDir = installerManager.getWwwDir()
        try {
            // Atomic update: extract to temp, then rename
            val tempDir = File(wwwDir.parent, "www_tmp_${System.currentTimeMillis()}")
            tempDir.mkdirs()
            java.util.zip.ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val dest = File(tempDir, entry.name)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            if (wwwDir.exists()) wwwDir.deleteRecursively()
            tempDir.renameTo(wwwDir)
            eventBridge.emit("update_progress", mapOf("success" to true, "message" to "UI updated"))
            activity.runOnUiThread { activity.reloadWebView() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "applyWwwUpdate failed: ${e.message}", e)
            eventBridge.emit("update_progress", mapOf("success" to false, "error" to e.message))
        }
    }

    @JavascriptInterface
    fun getWwwInfo(): String {
        val wwwDir = installerManager.getWwwDir()
        val indexHtml = File(wwwDir, "index.html")
        val sha256 = if (indexHtml.exists()) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val bytes = digest.digest(indexHtml.readBytes())
                bytes.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) { "unknown" }
        } else "missing"
        return gson.toJson(mapOf(
            "wwwDir" to wwwDir.absolutePath,
            "exists" to wwwDir.exists(),
            "indexSha256" to sha256,
        ))
    }

    @JavascriptInterface
    fun getBatteryOptimizationStatus(): String {
        val pm = activity.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val isIgnoring = pm?.isIgnoringBatteryOptimizations(activity.packageName) ?: false
        return gson.toJson(mapOf("isIgnoring" to isIgnoring))
    }

    @JavascriptInterface
    fun requestBatteryOptimizationExclusion() = requestBatteryOptimizationExemption()

    @JavascriptInterface
    fun openSystemSettings(page: String) {
        activity.runOnUiThread {
            try {
                val intent = when (page) {
                    "developer" -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    "app_info" -> android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${activity.packageName}")
                    }
                    "battery" -> android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    else -> android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "openSystemSettings failed: ${e.message}", e)
            }
        }
    }

    @JavascriptInterface
    fun clearCache() {
        try {
            activity.cacheDir.deleteRecursively()
            activity.cacheDir.mkdirs()
        } catch (e: Exception) {
            AppLogger.e(TAG, "clearCache failed: ${e.message}", e)
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                activity.startActivity(intent)
            } catch (e: Exception) {
                AppLogger.e(TAG, "openUrl failed: ${e.message}", e)
            }
        }
    }

    @JavascriptInterface
    fun runCommand(cmd: String): String {
        val env = com.openclaw.android.CommandRunner.buildTermuxEnv(activity)
        val config = com.openclaw.android.core.env.EnvironmentResolver.resolve(activity.filesDir)
        val result = com.openclaw.android.CommandRunner.runSync(cmd, env, config.homeDir, timeoutMs = 10_000)
        return gson.toJson(mapOf(
            "exitCode" to result.exitCode,
            "stdout" to result.stdout,
            "stderr" to result.stderr,
        ))
    }

    @JavascriptInterface
    fun runCommandAsync(callbackId: String, cmd: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Thread {
                val env = com.openclaw.android.CommandRunner.buildTermuxEnv(activity)
                val config = com.openclaw.android.core.env.EnvironmentResolver.resolve(activity.filesDir)
                val result = com.openclaw.android.CommandRunner.runSync(cmd, env, config.homeDir, timeoutMs = 15_000)
                eventBridge.emit("command_result", mapOf(
                    "callbackId" to callbackId,
                    "result" to result.stdout.trim().ifEmpty { result.stderr.trim() },
                    "exitCode" to result.exitCode,
                ))
            }.start()
        }
    }

    @JavascriptInterface
    fun launchGateway() {
        Thread {
            com.openclaw.android.CommandRunner.launchGateway(activity.filesDir)
        }.start()
    }

    @JavascriptInterface
    fun applyUpdate(component: String) {
        // Run update in terminal
        eventBridge.emit("install_progress", mapOf("target" to component, "progress" to 0.1f, "message" to "Abriendo terminal para actualizar $component..."))
        activity.runOnUiThread {
            activity.showTerminal()
        }
        eventBridge.emit("install_progress", mapOf("target" to component, "progress" to 1.0f, "message" to "Ejecuta: openclaw update"))
    }

    @JavascriptInterface
    fun getApkUpdateInfo(): String {
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return gson.toJson(mapOf(
            "currentVersion" to (pInfo.versionName ?: "0.0.0"),
            "updateAvailable" to false,
            "updateUrl" to "https://github.com/AidanPark/openclaw-android/releases/latest",
        ))
    }

    // ── Storage setup ──────────────────────────────────────────────────────

    @JavascriptInterface
    fun setupStorage() {
        CommandRunner.setupAppStorage(activity) { msg ->
            AppLogger.d(TAG, "setupStorage: $msg")
        }
    }

    /**
     * Fix executable permissions on all .sh scripts in the app sandbox.
     * Returns a JSON summary of how many files were fixed.
     */
    @JavascriptInterface
    fun fixScriptPermissions(): String {
        val filesDir = activity.filesDir
        var fixed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        try {
            filesDir.walkTopDown()
                .filter { it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".mjs") || it.name == "node" || it.name == "npm" || it.name == "npx" || it.name == "openclaw") }
                .forEach { file ->
                    try {
                        if (!file.canExecute()) {
                            file.setExecutable(true, false)
                            fixed++
                        } else {
                            skipped++
                        }
                    } catch (e: Exception) {
                        errors.add("${file.name}: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "fixScriptPermissions failed: ${e.message}", e)
            return gson.toJson(mapOf("success" to false, "error" to e.message))
        }

        AppLogger.i(TAG, "fixScriptPermissions: fixed=$fixed skipped=$skipped errors=${errors.size}")
        return gson.toJson(mapOf(
            "success" to true,
            "fixed" to fixed,
            "skipped" to skipped,
            "errors" to errors,
        ))
    }

    /**
     * Get detailed version info for all components.
     * Uses VersionReader (no process spawn — reads package.json files).
     */
    @JavascriptInterface
    fun getDetailedVersionInfo(): String {
        val config = com.openclaw.android.core.env.EnvironmentResolver.resolve(activity.filesDir)
        val versions = com.openclaw.android.core.install.VersionReader.readAll(config)
        val marker = com.openclaw.android.core.install.VersionReader.readInstalledMarker(config)

        // npm wrapper path
        val npmWrapper = java.io.File(config.ocaDir, "bin/npm")
        val npmExists = npmWrapper.exists() && npmWrapper.canExecute()

        // node wrapper path
        val nodeWrapper = java.io.File(config.ocaDir, "bin/node")
        val nodeExists = nodeWrapper.exists() && nodeWrapper.canExecute()

        // openclaw wrapper
        val ocWrapper = java.io.File(config.ocaDir, "bin/openclaw")
        val ocWrapperExists = ocWrapper.exists() && ocWrapper.canExecute()

        // glibc linker
        val ldso = config.linker
        val ldsoExists = ldso.exists() && ldso.canExecute()

        return gson.toJson(mapOf(
            "openclaw" to mapOf(
                "version" to versions.openclaw,
                "installed" to (versions.openclaw != "not installed"),
                "path" to config.openClawMjs.absolutePath,
                "wrapperExists" to ocWrapperExists,
                "wrapperPath" to ocWrapper.absolutePath,
            ),
            "node" to mapOf(
                "version" to versions.node,
                "installed" to (versions.node != "unknown"),
                "path" to config.nodeBin.absolutePath,
                "wrapperExists" to nodeExists,
                "wrapperPath" to nodeWrapper.absolutePath,
            ),
            "npm" to mapOf(
                "version" to versions.npm,
                "installed" to (versions.npm != "unknown"),
                "wrapperExists" to npmExists,
                "wrapperPath" to npmWrapper.absolutePath,
            ),
            "glibc" to mapOf(
                "ok" to config.isGlibcReady,
                "linkerPath" to ldso.absolutePath,
                "linkerExists" to ldsoExists,
                "linkerSizeKb" to ldso.length() / 1024,
            ),
            "installedAt" to (marker["installedAt"] ?: ""),
            "source" to (marker["source"] ?: "unknown"),
            "payloadDir" to config.payloadDir.absolutePath,
            "prefixDir" to config.prefix.absolutePath,
            "homeDir" to config.homeDir.absolutePath,
        ))
    }
}
