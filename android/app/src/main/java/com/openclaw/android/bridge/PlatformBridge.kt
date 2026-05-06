package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.CommandRunner
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.OpenClawManager
import com.openclaw.android.core.env.EnvironmentResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * PlatformBridge — WebView ↔ Kotlin bridge for platform management.
 *
 * Handles: listing, installing, uninstalling, and switching platforms (e.g. openclaw).
 */
class PlatformBridge(
    private val activity: MainActivity,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
    private val ioScope: CoroutineScope,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "PlatformBridge"
        private const val PLATFORM_LIST_TIMEOUT_MS = 10_000L
        private const val PROGRESS_START = 0f
        private const val PROGRESS_HALF = 0.5f
        private const val PROGRESS_DONE = 1f
    }

    private fun launchIO(errorEvent: String = "error", block: suspend CoroutineScope.() -> Unit) {
        val handler = CoroutineExceptionHandler { _, t ->
            AppLogger.e(TAG, "Coroutine error [$errorEvent]: ${t.message}", t)
            eventBridge.emit(errorEvent, mapOf("error" to (t.message ?: "Unknown error"), "progress" to PROGRESS_START))
        }
        ioScope.launch(handler, block = block)
    }

    @JavascriptInterface
    fun getAvailablePlatforms(): String = gson.toJson(listOf(
        mapOf("id" to "openclaw", "name" to "OpenClaw", "icon" to "/openclaw.svg", "desc" to "AI agent platform"),
    ))

    @JavascriptInterface
    fun getInstalledPlatforms(): String {
        val env = CommandRunner.buildTermuxEnv(activity)
        val result = CommandRunner.runSync(
            "npm list -g --depth=0 --json 2>/dev/null",
            env,
            installerManager.getPrefixDir(),
            timeoutMs = PLATFORM_LIST_TIMEOUT_MS,
        )
        return result.stdout.ifBlank { "[]" }
    }

    @JavascriptInterface
    fun installPlatform(id: String) {
        val safeId = id.replace(Regex("[^a-zA-Z0-9@/_.-]"), "")
        launchIO(errorEvent = "install_progress") {
            if (safeId == "openclaw") {
                val result = OpenClawManager(activity).installOrUpdate { percent, message ->
                    eventBridge.emit("install_progress", mapOf("target" to safeId, "progress" to percent / 100f, "message" to message))
                }
                if (!result.first) {
                    eventBridge.emit("install_progress", mapOf(
                        "target" to safeId, "progress" to PROGRESS_START,
                        "message" to (result.second ?: "OpenClaw install failed"),
                    ))
                }
                return@launchIO
            }

            val env = CommandRunner.buildTermuxEnv(activity)
            val config = EnvironmentResolver.resolve(activity.filesDir)
            val workDir = config.homeDir.also { it.mkdirs() }

            eventBridge.emit("install_progress", mapOf("target" to safeId, "progress" to PROGRESS_START, "message" to "Installing $safeId..."))
            val result = CommandRunner.runStreaming(
                "npm install -g $safeId@latest --ignore-scripts --no-fund --no-audit",
                env, workDir,
            ) { output ->
                eventBridge.emit("install_progress", mapOf("target" to safeId, "progress" to PROGRESS_HALF, "message" to output))
            }
            if (result.exitCode != 0) {
                eventBridge.emit("install_progress", mapOf("target" to safeId, "progress" to PROGRESS_START, "message" to result.stderr))
                return@launchIO
            }
            eventBridge.emit("install_progress", mapOf("target" to safeId, "progress" to PROGRESS_DONE, "message" to "$safeId installed"))
        }
    }

    @JavascriptInterface
    fun uninstallPlatform(id: String) {
        launchIO(errorEvent = "install_progress") {
            val env = CommandRunner.buildTermuxEnv(activity)
            val config = EnvironmentResolver.resolve(activity.filesDir)
            CommandRunner.runSync("npm uninstall -g $id", env, config.homeDir)
        }
    }

    @JavascriptInterface
    fun switchPlatform(id: String) {
        val markerFile = File(installerManager.getHomeDir(), ".openclaw-android/.platform")
        markerFile.parentFile?.mkdirs()
        markerFile.writeText(id)
    }

    @JavascriptInterface
    fun getActivePlatform(): String {
        val markerFile = File(installerManager.getHomeDir(), ".openclaw-android/.platform")
        val id = if (markerFile.exists()) markerFile.readText().trim() else "openclaw"
        return gson.toJson(mapOf("id" to id, "name" to id.replaceFirstChar { it.uppercase() }))
    }
}
