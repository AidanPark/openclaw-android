package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.openclaw.android.AppLogger
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.TerminalSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * JsBridgeFacade — single @JavascriptInterface object registered with WebView.
 *
 * Composes all domain bridges into one facade so the WebView only needs
 * one addJavascriptInterface() call: window.OpenClaw.<method>().
 *
 * Architecture:
 *   JsBridgeFacade
 *   ├── TerminalBridge  — show/hide, sessions, write
 *   ├── SetupBridge     — install status, triggers, glibc, versions
 *   ├── PlatformBridge  — list, install, uninstall, switch platforms
 *   ├── ToolsBridge     — list, install, uninstall CLI tools
 *   └── SystemBridge    — app info, battery, storage, clipboard, OTA
 *
 * All bridges share a single CoroutineScope(Dispatchers.IO + SupervisorJob)
 * so a failure in one coroutine doesn't cancel others.
 */
@Suppress("TooManyFunctions") // Facade by design — delegates to domain bridges
class JsBridgeFacade(
    activity: MainActivity,
    sessionManager: TerminalSessionManager,
    installerManager: InstallerManager,
    eventBridge: EventBridge,
) {
    companion object {
        private const val TAG = "JsBridgeFacade"
    }

    // Shared IO scope — one pool for all bridges
    private val supervisorJob = SupervisorJob()
    private val ioScope = CoroutineScope(Dispatchers.IO + supervisorJob)

    private val terminal = TerminalBridge(activity, sessionManager, installerManager, eventBridge)
    private val setup = SetupBridge(activity, sessionManager, installerManager, eventBridge, ioScope)
    private val platform = PlatformBridge(activity, installerManager, eventBridge, ioScope)
    private val tools = ToolsBridge(activity, installerManager, eventBridge, ioScope)
    private val system = SystemBridge(activity, installerManager, eventBridge)

    /** Cancel all pending coroutines — call from MainActivity.onDestroy(). */
    fun cancel() {
        supervisorJob.cancel()
        AppLogger.d(TAG, "JsBridgeFacade cancelled")
    }

    // ═══════════════════════════════════════════
    // Terminal domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun showTerminal() = terminal.showTerminal()
    @JavascriptInterface fun showWebView() = terminal.showWebView()
    @JavascriptInterface fun createSession(): String = terminal.createSession()
    @JavascriptInterface fun switchSession(id: String) = terminal.switchSession(id)
    @JavascriptInterface fun closeSession(id: String) = terminal.closeSession(id)
    @JavascriptInterface fun getTerminalSessions(): String = terminal.getTerminalSessions()
    @JavascriptInterface fun writeToTerminal(id: String, data: String) = terminal.writeToTerminal(id, data)
    @JavascriptInterface fun runInNewSession(command: String) = terminal.runInNewSession(command)

    // ═══════════════════════════════════════════
    // Setup domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getSetupStatus(): String = setup.getSetupStatus()
    @JavascriptInterface fun getBootstrapStatus(): String = setup.getBootstrapStatus()
    @JavascriptInterface fun getAppFilesDir(): String = setup.getAppFilesDir()
    @JavascriptInterface fun hasPayloadAsset(): String = setup.hasPayloadAsset()
    @JavascriptInterface fun getPayloadStatus(): String = setup.getPayloadStatus()
    @JavascriptInterface fun getRootfsStatus(): String = setup.getRootfsStatus()
    @JavascriptInterface fun startSetup(mode: String = "auto") = setup.startSetup(mode)
    @JavascriptInterface fun startPayloadInstall() = setup.startPayloadInstall()
    @JavascriptInterface fun startRootfsInstall() = setup.startRootfsInstall()
    @JavascriptInterface fun pickPayloadFile() = setup.pickPayloadFile()
    @JavascriptInterface fun getGlibcStatus(): String = setup.getGlibcStatus()
    @JavascriptInterface fun getVersionInfo(): String = setup.getVersionInfo()
    @JavascriptInterface fun installGlibcManually() = setup.installGlibcManually()
    @JavascriptInterface fun pickGlibcFile() = setup.pickGlibcFile()
    @JavascriptInterface fun saveInstallPath(path: String) = setup.saveInstallPath(path)
    @JavascriptInterface fun saveToolSelections(json: String) = setup.saveToolSelections(json)

    // ═══════════════════════════════════════════
    // Platform domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getAvailablePlatforms(): String = platform.getAvailablePlatforms()
    @JavascriptInterface fun getInstalledPlatforms(): String = platform.getInstalledPlatforms()
    @JavascriptInterface fun installPlatform(id: String) = platform.installPlatform(id)
    @JavascriptInterface fun uninstallPlatform(id: String) = platform.uninstallPlatform(id)
    @JavascriptInterface fun switchPlatform(id: String) = platform.switchPlatform(id)
    @JavascriptInterface fun getActivePlatform(): String = platform.getActivePlatform()

    // ═══════════════════════════════════════════
    // Tools domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getInstalledTools(): String = tools.getInstalledTools()
    @JavascriptInterface fun getEnvironmentInfo(): String = tools.getEnvironmentInfo()
    @JavascriptInterface fun installTool(id: String) = tools.installTool(id)
    @JavascriptInterface fun uninstallTool(id: String) = tools.uninstallTool(id)

    // ═══════════════════════════════════════════
    // System domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getAppInfo(): String = system.getAppInfo()
    @JavascriptInterface fun getBatteryInfo(): String = system.getBatteryInfo()
    @JavascriptInterface fun getStorageInfo(): String = system.getStorageInfo()
    @JavascriptInterface fun getPermissionsStatus(): String = system.getPermissionsStatus()
    @JavascriptInterface fun requestBatteryOptimizationExemption() = system.requestBatteryOptimizationExemption()
    @JavascriptInterface fun requestBatteryOptimizationExclusion() = system.requestBatteryOptimizationExclusion()
    @JavascriptInterface fun getBatteryOptimizationStatus(): String = system.getBatteryOptimizationStatus()
    @JavascriptInterface fun openSystemSettings(page: String) = system.openSystemSettings(page)
    @JavascriptInterface fun copyToClipboard(text: String) = system.copyToClipboard(text)
    @JavascriptInterface fun getClipboardText(): String = system.getClipboardText()
    @JavascriptInterface fun checkForUpdates(): String = system.checkForUpdates()
    @JavascriptInterface fun applyUpdate(component: String) = system.applyUpdate(component)
    @JavascriptInterface fun getApkUpdateInfo(): String = system.getApkUpdateInfo()
    @JavascriptInterface fun applyWwwUpdate(zipPath: String) = system.applyWwwUpdate(zipPath)
    @JavascriptInterface fun getWwwInfo(): String = system.getWwwInfo()
    @JavascriptInterface fun setupStorage() = system.setupStorage()
    @JavascriptInterface fun clearCache() = system.clearCache()
    @JavascriptInterface fun openUrl(url: String) = system.openUrl(url)
    @JavascriptInterface fun runCommand(cmd: String): String = system.runCommand(cmd)
    @JavascriptInterface fun runCommandAsync(callbackId: String, cmd: String) = system.runCommandAsync(callbackId, cmd)
    @JavascriptInterface fun launchGateway() = system.launchGateway()
    @JavascriptInterface fun isToolInstalled(id: String): String = tools.isToolInstalled(id)
    @JavascriptInterface fun fixScriptPermissions(): String = system.fixScriptPermissions()
    @JavascriptInterface fun getDetailedVersionInfo(): String = system.getDetailedVersionInfo()
}
