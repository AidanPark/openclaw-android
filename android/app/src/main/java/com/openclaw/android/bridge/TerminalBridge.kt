package com.openclaw.android.bridge

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import com.openclaw.android.AppLogger
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.TerminalSessionManager

/**
 * TerminalBridge — WebView ↔ Kotlin bridge for terminal operations.
 *
 * Handles: show/hide terminal, session lifecycle, writing to sessions.
 * All methods are @JavascriptInterface and callable as window.OpenClaw.<method>().
 */
class TerminalBridge(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
    private val installerManager: InstallerManager,
    private val eventBridge: EventBridge,
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "TerminalBridge"
        private const val SHELL_INIT_DELAY_MS = 500L
    }

    @JavascriptInterface
    fun showTerminal() {
        if (sessionManager.activeSession == null) {
            val session = sessionManager.createSession()
            if (!installerManager.isInstalled()) {
                session.write("echo 'Entorno no instalado. Usa el Dashboard para instalar.'\n")
            }
        }
        activity.showTerminal()
    }

    @JavascriptInterface
    fun showWebView() = activity.showWebView()

    @JavascriptInterface
    fun createSession(): String {
        val session = sessionManager.createSession()
        return gson.toJson(mapOf("id" to session.mHandle, "name" to (session.title ?: "Terminal")))
    }

    @JavascriptInterface
    fun switchSession(id: String) =
        activity.runOnUiThread { sessionManager.switchSession(id) }

    @JavascriptInterface
    fun closeSession(id: String) = sessionManager.closeSession(id)

    @JavascriptInterface
    fun getTerminalSessions(): String = gson.toJson(sessionManager.getSessionsInfo())

    @JavascriptInterface
    fun writeToTerminal(id: String, data: String) {
        val session = if (id.isBlank()) {
            sessionManager.activeSession
        } else {
            sessionManager.getSessionById(id) ?: sessionManager.activeSession
        }
        session?.write(data)
    }

    @JavascriptInterface
    fun runInNewSession(command: String) {
        val session = sessionManager.createSession()
        activity.showTerminal()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            session.write(command)
        }, SHELL_INIT_DELAY_MS)
    }
}
