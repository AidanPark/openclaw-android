package com.openclaw.android

import android.webkit.JavascriptInterface
import com.openclaw.android.bridge.JsBridgeFacade

/**
 * JsBridge — backward-compatible entry point registered with WebView.
 *
 * Delegates all calls to [JsBridgeFacade] which composes domain-specific bridges:
 *   - TerminalBridge  (show/hide, sessions, write)
 *   - SetupBridge     (install status, triggers, glibc, versions)
 *   - PlatformBridge  (list, install, uninstall, switch platforms)
 *   - ToolsBridge     (list, install, uninstall CLI tools)
 *   - SystemBridge    (app info, battery, storage, clipboard, OTA)
 *
 * Registered in MainActivity as:
 *   webView.addJavascriptInterface(jsBridge, "OpenClaw")
 *
 * All methods callable from JS as: window.OpenClaw.<method>()
 */
@Suppress("TooManyFunctions", "LargeClass") // Facade by design
class JsBridge(
    activity: MainActivity,
    sessionManager: TerminalSessionManager,
    installerManager: InstallerManager,
    eventBridge: EventBridge,
) {
    private val facade = JsBridgeFacade(activity, sessionManager, installerManager, eventBridge)

    /** Cancel all pending coroutines — call from MainActivity.onDestroy(). */
    fun cancel() = facade.cancel()

    // ═══════════════════════════════════════════
    // Terminal domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun showTerminal() = facade.showTerminal()
    @JavascriptInterface fun showWebView() = facade.showWebView()
    @JavascriptInterface fun createSession(): String = facade.createSession()
    @JavascriptInterface fun switchSession(id: String) = facade.switchSession(id)
    @JavascriptInterface fun closeSession(id: String) = facade.closeSession(id)
    @JavascriptInterface fun getTerminalSessions(): String = facade.getTerminalSessions()
    @JavascriptInterface fun writeToTerminal(id: String, data: String) = facade.writeToTerminal(id, data)
    @JavascriptInterface fun runInNewSession(command: String) = facade.runInNewSession(command)

    // ═══════════════════════════════════════════
    // Setup domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getSetupStatus(): String = facade.getSetupStatus()
    @JavascriptInterface fun getBootstrapStatus(): String = facade.getBootstrapStatus()
    @JavascriptInterface fun getAppFilesDir(): String = facade.getAppFilesDir()
    @JavascriptInterface fun hasPayloadAsset(): String = facade.hasPayloadAsset()
    @JavascriptInterface fun getPayloadStatus(): String = facade.getPayloadStatus()
    @JavascriptInterface fun getRootfsStatus(): String = facade.getRootfsStatus()
    @JavascriptInterface fun startSetup(mode: String = "auto") = facade.startSetup(mode)
    @JavascriptInterface fun startPayloadInstall() = facade.startPayloadInstall()
    @JavascriptInterface fun startRootfsInstall() = facade.startRootfsInstall()
    @JavascriptInterface fun pickPayloadFile() = facade.pickPayloadFile()
    @JavascriptInterface fun getGlibcStatus(): String = facade.getGlibcStatus()
    @JavascriptInterface fun getVersionInfo(): String = facade.getVersionInfo()
    @JavascriptInterface fun installGlibcManually() = facade.installGlibcManually()
    @JavascriptInterface fun pickGlibcFile() = facade.pickGlibcFile()
    @JavascriptInterface fun saveInstallPath(path: String) = facade.saveInstallPath(path)
    @JavascriptInterface fun saveToolSelections(json: String) = facade.saveToolSelections(json)

    // ═══════════════════════════════════════════
    // Platform domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getAvailablePlatforms(): String = facade.getAvailablePlatforms()
    @JavascriptInterface fun getInstalledPlatforms(): String = facade.getInstalledPlatforms()
    @JavascriptInterface fun installPlatform(id: String) = facade.installPlatform(id)
    @JavascriptInterface fun uninstallPlatform(id: String) = facade.uninstallPlatform(id)
    @JavascriptInterface fun switchPlatform(id: String) = facade.switchPlatform(id)
    @JavascriptInterface fun getActivePlatform(): String = facade.getActivePlatform()

    // ═══════════════════════════════════════════
    // Tools domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getInstalledTools(): String = facade.getInstalledTools()
    @JavascriptInterface fun getEnvironmentInfo(): String = facade.getEnvironmentInfo()
    @JavascriptInterface fun installTool(id: String) = facade.installTool(id)
    @JavascriptInterface fun uninstallTool(id: String) = facade.uninstallTool(id)

    // ═══════════════════════════════════════════
    // System domain
    // ═══════════════════════════════════════════

    @JavascriptInterface fun getAppInfo(): String = facade.getAppInfo()
    @JavascriptInterface fun getBatteryInfo(): String = facade.getBatteryInfo()
    @JavascriptInterface fun getStorageInfo(): String = facade.getStorageInfo()
    @JavascriptInterface fun getPermissionsStatus(): String = facade.getPermissionsStatus()
    @JavascriptInterface fun requestBatteryOptimizationExemption() = facade.requestBatteryOptimizationExemption()
    @JavascriptInterface fun requestBatteryOptimizationExclusion() = facade.requestBatteryOptimizationExclusion()
    @JavascriptInterface fun getBatteryOptimizationStatus(): String = facade.getBatteryOptimizationStatus()
    @JavascriptInterface fun openSystemSettings(page: String) = facade.openSystemSettings(page)
    @JavascriptInterface fun copyToClipboard(text: String) = facade.copyToClipboard(text)
    @JavascriptInterface fun getClipboardText(): String = facade.getClipboardText()
    @JavascriptInterface fun checkForUpdates(): String = facade.checkForUpdates()
    @JavascriptInterface fun applyUpdate(component: String) = facade.applyUpdate(component)
    @JavascriptInterface fun getApkUpdateInfo(): String = facade.getApkUpdateInfo()
    @JavascriptInterface fun applyWwwUpdate(zipPath: String) = facade.applyWwwUpdate(zipPath)
    @JavascriptInterface fun getWwwInfo(): String = facade.getWwwInfo()
    @JavascriptInterface fun setupStorage() = facade.setupStorage()
    @JavascriptInterface fun clearCache() = facade.clearCache()
    @JavascriptInterface fun openUrl(url: String) = facade.openUrl(url)
    @JavascriptInterface fun runCommand(cmd: String): String = facade.runCommand(cmd)
    @JavascriptInterface fun runCommandAsync(callbackId: String, cmd: String) = facade.runCommandAsync(callbackId, cmd)
    @JavascriptInterface fun launchGateway() = facade.launchGateway()
    @JavascriptInterface fun isToolInstalled(id: String): String = facade.isToolInstalled(id)
    @JavascriptInterface fun fixScriptPermissions(): String = facade.fixScriptPermissions()
    @JavascriptInterface fun getDetailedVersionInfo(): String = facade.getDetailedVersionInfo()
    @JavascriptInterface fun testGrunNode(): String = facade.getVersionInfo() // alias
}
