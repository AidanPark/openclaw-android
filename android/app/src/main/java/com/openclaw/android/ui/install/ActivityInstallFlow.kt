package com.openclaw.android.ui.install

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.TerminalManager

/**
 * ActivityInstallFlow — handles the installation flow from the Activity UI.
 *
 * Responsibilities:
 *   - Check installation state on startup
 *   - Handle APK upgrades
 *   - Start installation in any mode (auto, online, offline, termux-bootstrap, proot)
 *   - Run online install in the embedded terminal
 */
internal class ActivityInstallFlow(
    private val activity: AppCompatActivity,
    private val installerManager: InstallerManager,
    private val installOverlay: InstallOverlayController,
) {
    private val TAG = "ActivityInstallFlow"

    /**
     * Called from onStoragePermissionsGranted(). Decides what to show on startup.
     */
    fun checkAndStartInstallation(intent: Intent?) {
        val isInstalled = installerManager.isInstalled()
        AppLogger.i(TAG, "checkAndStartInstallation: installed=$isInstalled")

        if (isInstalled) checkForApkUpgrade()

        when {
            !isInstalled -> {
                AppLogger.i(TAG, "Not installed — showing setup wizard")
                (activity as? MainActivity)?.showWebView()
            }
            isBootIntent(intent) -> {
                AppLogger.i(TAG, "Boot launch — auto-starting gateway")
                val startScript = installerManager.getRunScriptPath()
                (activity as? MainActivity)?.showTerminal()
                val session = (activity as? MainActivity)?.sessionManager?.createSession()
                session?.let {
                    activity.findViewById<com.termux.view.TerminalView>(com.openclaw.android.R.id.terminalView)
                        .post { it.write("\"${startScript.absolutePath}\"\n") }
                }
            }
            else -> {
                AppLogger.i(TAG, "Already installed — showing dashboard")
                (activity as? MainActivity)?.showWebView()
            }
        }
    }

    /**
     * Starts installation from the UI (called by JsBridge or buttons).
     *
     * Mode "online": installs bootstrap first (overlay), then opens terminal for curl | bash.
     * All other modes: run through the overlay directly.
     */
    fun startInstallFromUi(
        mode: String = "auto",
        onComplete: ((success: Boolean) -> Unit)? = null,
    ) {
        installOverlay.show()
        installOverlay.runInstall(
            activity,
            installerManager,
            mode,
            (activity as? MainActivity)?.selectedPayloadUri,
        ) { success ->
            activity.runOnUiThread {
                installOverlay.hide()
                if (success) {
                    if (mode == "online") {
                        runOnlineInstallInTerminal()
                    } else {
                        (activity as? MainActivity)?.reloadWebView()
                    }
                } else {
                    Toast.makeText(activity, "Installation failed", Toast.LENGTH_LONG).show()
                }
                onComplete?.invoke(success)
            }
        }
    }

    /**
     * Runs the online install inside the embedded terminal.
     *
     * Flow:
     *   1. Show terminal
     *   2. Ensure an active session exists
     *   3. Inject env + run: curl -sL myopenclawhub.com/install | bash
     *   4. If dpkg fails → dpkg --configure -a (auto-answers N)
     *   5. Retry curl | bash
     *   6. source ~/.bashrc when done
     *
     * Requires Termux Bootstrap to be installed first.
     */
    fun runOnlineInstallInTerminal() {
        AppLogger.i(TAG, "Starting online install in terminal")
        (activity as? MainActivity)?.showTerminal()

        val session = (activity as? MainActivity)?.sessionManager?.activeSession
            ?: (activity as? MainActivity)?.sessionManager?.createSession()

        session?.let {
            val terminalManager = TerminalManager(activity, activity.filesDir)
            terminalManager.runOnlineInstall(it) {
                AppLogger.i(TAG, "Online install commands sent to terminal session")
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun checkForApkUpgrade() {
        val prefs = activity.getSharedPreferences("openclaw", 0)
        val savedVersionCode = prefs.getInt("versionCode", 0)
        val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val currentVersionCode = PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        if (currentVersionCode > savedVersionCode) {
            AppLogger.i(TAG, "APK upgrade: $savedVersionCode → $currentVersionCode")
            prefs.edit().putInt("versionCode", currentVersionCode).apply()
        }
    }

    private fun isBootIntent(intent: Intent?): Boolean =
        intent?.getBooleanExtra("boot", false) == true ||
            intent?.action == "com.openclaw.android.BOOT"
}
