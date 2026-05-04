package com.openclaw.android.ui.install

import android.view.View
import com.openclaw.android.AppLogger
import com.openclaw.android.EnvironmentBuilder
import com.openclaw.android.InstallerManager
import com.openclaw.android.R
import com.openclaw.android.TerminalSessionManager
import com.openclaw.android.databinding.ActivityMainBinding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * InstallOverlayController — manages the installation progress overlay UI.
 *
 * Completely decoupled from the terminal shell lifecycle.
 * Progress is shown in a dedicated overlay (ProgressBar + TextViews),
 * NOT via session.write() to a shell — this was the root cause of the
 * "freeze at 70%" bug documented in OPENCLAW_ANDROID_FINAL.md §5.
 *
 * Also manages the mini-terminal inside the overlay for real-time log output.
 */
class InstallOverlayController(
    private val binding: ActivityMainBinding,
    private val activity: android.app.Activity,
    private val sessionClient: TerminalSessionClient,
    private val viewClient: TerminalViewClient,
    private val onOpenTerminal: () -> Unit,
) {
    companion object {
        private const val TAG = "InstallOverlay"
    }

    private var installTerminalSession: TerminalSession? = null

    // ── Show / Hide ────────────────────────────────────────────────────────

    fun show() {
        activity.runOnUiThread {
            binding.webView.visibility = View.GONE
            binding.terminalContainer.visibility = View.GONE
            binding.installOverlay.visibility = View.VISIBLE
            binding.installProgressBar.progress = 0
            binding.installProgressPercent.text = "0%"
            binding.installProgressMessage.text = "Preparando..."
            binding.installErrorText.visibility = View.GONE
            binding.btnOpenTerminalOnError.visibility = View.GONE
            setupMiniTerminal()
        }
    }

    fun hide() {
        activity.runOnUiThread {
            binding.installOverlay.visibility = View.GONE
            binding.btnOpenTerminalOnError.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
        }
    }

    // ── Progress updates ───────────────────────────────────────────────────

    fun updateProgress(percent: Int, message: String) {
        activity.runOnUiThread {
            binding.installProgressBar.progress = percent
            binding.installProgressPercent.text = "$percent%"
            binding.installProgressMessage.text = message
            installTerminalSession?.write("[$percent%] $message\r\n")
        }
    }

    fun showError(message: String) {
        activity.runOnUiThread {
            binding.installErrorText.text = message
            binding.installErrorText.visibility = View.VISIBLE
            binding.installProgressMessage.text = "Instalación fallida"
            binding.btnOpenTerminalOnError.visibility = View.VISIBLE
        }
    }

    // ── Error button setup ─────────────────────────────────────────────────

    fun setupErrorButton() {
        binding.btnOpenTerminalOnError.setOnClickListener {
            onOpenTerminal()
        }
    }

    // ── Run installation ───────────────────────────────────────────────────

    fun runInstall(
        activity: android.app.Activity,
        installer: InstallerManager,
        mode: String,
        selectedPayloadUri: android.net.Uri?,
        onComplete: ((success: Boolean) -> Unit)?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            installer.install(mode, selectedPayloadUri, object : InstallerManager.ProgressListener {
                override fun onProgress(percent: Int, message: String) {
                    updateProgress(percent, message)
                }

                override fun onSuccess() {
                    AppLogger.i(TAG, "Installation completed successfully")
                    activity.runOnUiThread {
                        hide()
                        onComplete?.invoke(true)
                    }
                }

                override fun onError(message: String, cause: Throwable?) {
                    if (cause != null) AppLogger.e(TAG, "Installation error: $message", cause)
                    else AppLogger.e(TAG, "Installation error: $message")
                    showError(message)
                    activity.runOnUiThread { onComplete?.invoke(false) }
                }
            })
        }
    }

    // ── Private ────────────────────────────────────────────────────────────

    private fun setupMiniTerminal() {
        if (installTerminalSession == null) {
            val filesDir = activity.filesDir
            val homeDir = File(filesDir, "home").also { it.mkdirs() }
            val envMap = EnvironmentBuilder.buildEnvironment(filesDir, activity.packageName)
                .toMutableMap()

            // CRITICAL: /system/bin/sh is a Bionic binary. If LD_LIBRARY_PATH
            // contains the glibc lib dir, Android's linker finds glibc's libc.so
            // there and fails with:
            //   CANNOT LINK EXECUTABLE "sh": cannot find "libc.so" from verneed[0]
            // Strip any glibc-related library paths — they are only valid inside
            // glibc processes launched via ld-linux-aarch64.so.1, never in Bionic shells.
            envMap.remove("LD_LIBRARY_PATH")
            envMap.remove("LD_PRELOAD")

            val env = envMap.entries.map { "${it.key}=${it.value}" }.toTypedArray()

            installTerminalSession = TerminalSession(
                "/system/bin/sh",
                homeDir.absolutePath,
                arrayOf("/system/bin/sh"),
                env,
                1000,
                sessionClient,
            )
            binding.installTerminalView.setTerminalViewClient(viewClient)
            binding.installTerminalView.setTextSize(12)
            binding.installTerminalView.attachSession(installTerminalSession)
            installTerminalSession?.write("\u001b[2J\u001b[H") // ANSI clear
        }
        installTerminalSession?.write("=== Iniciando Instalación de OpenClaw ===\r\n")
    }
}
