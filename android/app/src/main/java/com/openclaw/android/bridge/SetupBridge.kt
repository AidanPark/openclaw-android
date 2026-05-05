package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.ProotManager
import com.openclaw.android.TerminalSessionManager
import com.openclaw.android.TermuxBootstrapManager
import com.openclaw.android.core.env.EnvironmentResolver
import com.openclaw.android.core.install.InstallProgress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * SetupBridge — WebView ↔ Kotlin bridge for installation and setup operations.
 *
 * Handles: install status, triggering installs, payload/rootfs flows, glibc management.
 */
class SetupBridge(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
    private val ioScope: CoroutineScope,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "SetupBridge"
        private const val SHELL_INIT_DELAY_MS = 500L
        private const val PROGRESS_START = 0f
        private const val PROGRESS_DONE = 1f
    }

    private fun launchIO(
        errorEvent: String = "error",
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val handler = CoroutineExceptionHandler { _, t ->
            AppLogger.e(TAG, "Coroutine error [$errorEvent]: ${t.message}", t)
            eventBridge.emit(errorEvent, mapOf("error" to (t.message ?: "Unknown error"), "progress" to PROGRESS_START))
        }
        ioScope.launch(handler, block = block)
    }

    // ── Status queries ─────────────────────────────────────────────────────

    @JavascriptInterface
    fun getSetupStatus(): String {
        val status = installerManager.getDetailedStatus()
        val isInstalled = status.isInstalled
        
        val openclawReady = if (status.source == "proot") {
            ProotManager.isRootfsReady(activity) &&
                ProotManager.getPaths(activity).rootfsDir
                    .resolve("usr/local/lib/node_modules/openclaw/openclaw.mjs").exists()
        } else {
            status.isOpenClawInstalled
        }

        return gson.toJson(mapOf(
            "bootstrapInstalled" to isInstalled,
            "runtimeInstalled" to isInstalled,
            "wwwInstalled" to isInstalled,
            "platformInstalled" to isInstalled,
            "source" to status.source,
            "prootReady" to status.prootReady,
            "rootfsReady" to status.rootfsReady,
            "openclawReady" to openclawReady,
        ))
    }

    @JavascriptInterface
    fun getBootstrapStatus(): String {
        val bootstrapInstalled = TermuxBootstrapManager(activity).isInstalled()
        val config = EnvironmentResolver.resolve(activity.filesDir)
        return gson.toJson(mapOf(
            "installed" to bootstrapInstalled,
            "openclawInstalled" to installerManager.isInstalled(),
            "prefixPath" to config.prefix.absolutePath,
            "homePath" to config.homeDir.absolutePath,
            "source" to "payload",
        ))
    }

    @JavascriptInterface
    fun getAppFilesDir(): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)
        return gson.toJson(mapOf(
            "filesDir" to config.filesDir.absolutePath,
            "prefix" to config.prefix.absolutePath,
            "home" to config.homeDir.absolutePath,
        ))
    }

    @JavascriptInterface
    fun hasPayloadAsset(): String =
        gson.toJson(mapOf("hasPayload" to installerManager.hasPayloadAsset()))

    @JavascriptInterface
    fun getPayloadStatus(): String {
        // Usar InstallerManager directamente — PayloadManager eliminado (era shim puro)
        return gson.toJson(installerManager.getStatus())
    }

    @JavascriptInterface
    fun getRootfsStatus(): String {
        // Usar InstallerManager directamente — RootfsManager eliminado (lógica integrada)
        val isInstalled = installerManager.isInstalled()
        val config = EnvironmentResolver.resolve(activity.filesDir)
        return gson.toJson(mapOf(
            "rootfsExtracted" to File(activity.filesDir, ".rootfs-extracted").exists(),
            "rootfsInitialized" to isInstalled,
            "openclawInstalled" to installerManager.isOpenClawInstalled(),
            "wwwInstalled" to File(config.prefix, "share/openclaw-app/www/index.html").exists(),
            "prefixPath" to config.prefix.absolutePath,
            "homePath" to config.homeDir.absolutePath,
        ))
    }

    // ── Install triggers ───────────────────────────────────────────────────

    @JavascriptInterface
    fun startSetup(mode: String = "auto") {
        activity.runOnUiThread {
            activity.startInstallFromUi(mode) { success ->
                AppLogger.i(TAG, "Setup finished: success=$success")
            }
        }
    }

    @JavascriptInterface
    fun startPayloadInstall() {
        activity.runOnUiThread {
            activity.startInstallFromUi { success ->
                if (success) {
                    eventBridge.emit("setup_progress", mapOf("progress" to PROGRESS_DONE, "message" to "Instalación completada"))
                    activity.showTerminal()
                    val session = sessionManager.createSession()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val runFile = installerManager.getRunScriptPath()
                        if (runFile.exists()) {
                            runFile.setExecutable(true)
                            session.write("${runFile.absolutePath}\n")
                        } else {
                            session.write("echo 'Instalación completada. Escribe openclaw para iniciar.'\n")
                        }
                    }, SHELL_INIT_DELAY_MS)
                } else {
                    eventBridge.emit("setup_progress", mapOf("progress" to PROGRESS_START, "message" to "Error en la instalación"))
                }
            }
        }
    }

    @JavascriptInterface
    fun startRootfsInstall() {
        // Delegar a InstallerManager con modo "proot" — instalación avanzada Ubuntu via proot
        launchIO(errorEvent = "setup_progress") {
            installerManager.install("proot", null, object : InstallerManager.ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    eventBridge.emit("setup_progress", mapOf("progress" to percent / 100f, "message" to message))
                }
                override fun onSuccess() {
                    activity.runOnUiThread { activity.showTerminal() }
                    val session = sessionManager.createSession()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val startScript = File(activity.filesDir, "home/openclaw-start.sh")
                        session.write("${startScript.absolutePath}\n")
                    }, SHELL_INIT_DELAY_MS)
                }
                override fun onError(message: String, cause: Throwable?) {
                    eventBridge.emit("setup_progress", mapOf("progress" to PROGRESS_START, "message" to message))
                }
            })
        }
    }

    @JavascriptInterface
    fun pickPayloadFile() = activity.runOnUiThread { activity.pickPayloadFile() }

    // ── glibc management ───────────────────────────────────────────────────

    @JavascriptInterface
    fun getGlibcStatus(): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)
        return gson.toJson(mapOf(
            "installed" to config.isGlibcReady,
            "linkerPath" to config.linker.absolutePath,
            "linkerSize" to config.linker.length(),
            "nodeInGlibc" to config.isNodeReady,
            "openclawMjs" to config.isOpenClawReady,
            "payloadDir" to config.payloadDir.absolutePath,
            "manualInstallPath" to config.homeDir.absolutePath,
        ))
    }

    @JavascriptInterface
    fun getVersionInfo(): String {
        val config = EnvironmentResolver.resolve(activity.filesDir)
        val versions = com.openclaw.android.core.install.VersionReader.readAll(config)
        val marker = com.openclaw.android.core.install.VersionReader.readInstalledMarker(config)
        return gson.toJson(mapOf(
            "openclaw" to mapOf(
                "version" to versions.openclaw,
                "installed" to (versions.openclaw != "not installed"),
                "path" to config.openClawMjs.absolutePath,
            ),
            "node" to mapOf(
                "version" to versions.node,
                "installed" to (versions.node != "unknown"),
                "path" to config.nodeBin.absolutePath,
            ),
            "npm" to mapOf(
                "version" to versions.npm,
                "installed" to (versions.npm != "unknown"),
            ),
            "glibc" to mapOf(
                "ok" to config.isGlibcReady,
                "linkerPath" to config.linker.absolutePath,
                "linkerSize" to config.linker.length(),
            ),
            "installedAt" to (marker["installedAt"] ?: ""),
            "source" to (marker["source"] ?: "unknown"),
            "payloadDir" to config.payloadDir.absolutePath,
        ))
    }

    @JavascriptInterface
    fun installGlibcManually() {
        launchIO(errorEvent = "glibc_install") {
            val config = EnvironmentResolver.resolve(activity.filesDir)
            val candidates = listOf(
                File(config.homeDir, "glibc-aarch64.tar.xz"),
                File(config.filesDir, "glibc-aarch64.tar.xz"),
                File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "glibc-aarch64.tar.xz"),
            )
            val archive = candidates.firstOrNull { it.exists() && it.length() > 10_000 }
            if (archive == null) {
                eventBridge.emit("glibc_install", mapOf(
                    "success" to false,
                    "error" to "glibc-aarch64.tar.xz no encontrado. Colócalo en: ${config.homeDir.absolutePath}",
                    "searchedPaths" to candidates.map { it.absolutePath },
                ))
                return@launchIO
            }
            eventBridge.emit("glibc_install", mapOf("success" to null, "message" to "Instalando glibc desde ${archive.absolutePath}..."))
            val ok = installerManager.installGlibcFromFile(archive)
            eventBridge.emit("glibc_install", mapOf(
                "success" to ok,
                "message" to if (ok) "glibc instalado correctamente" else "Error instalando glibc",
            ))
        }
    }

    @JavascriptInterface
    fun pickGlibcFile() = activity.runOnUiThread { activity.pickGlibcFile() }

    // ── Config persistence ─────────────────────────────────────────────────

    @JavascriptInterface
    fun saveInstallPath(path: String) {
        activity.getSharedPreferences("openclaw", 0).edit()
            .putString("install_path", path).apply()
    }

    @JavascriptInterface
    fun saveToolSelections(json: String) {
        val configFile = File(installerManager.getHomeDir(), ".openclaw-android/tool-selections.conf")
        configFile.parentFile?.mkdirs()
        try {
            val gson2 = Gson()
            @Suppress("UNCHECKED_CAST")
            val selections = gson2.fromJson(json, Map::class.java) as? Map<*, *> ?: return
            val lines = selections.entries.joinToString("\n") { (key, value) ->
                val envKey = "INSTALL_${(key as String).uppercase().replace("-", "_")}"
                "$envKey=$value"
            }
            configFile.writeText(lines + "\n")
        } catch (e: Exception) {
            AppLogger.e(TAG, "saveToolSelections failed: ${e.message}", e)
        }
    }
}
