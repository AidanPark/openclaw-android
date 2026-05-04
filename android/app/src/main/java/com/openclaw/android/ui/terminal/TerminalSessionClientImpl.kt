package com.openclaw.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.openclaw.android.AppLogger
import com.openclaw.android.MainActivity
import com.openclaw.android.TerminalSessionManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Implementación de TerminalSessionClient para OpenClaw.
 *
 * Responsabilidad única: manejar eventos de sesión de terminal
 * (texto cambiado, título cambiado, sesión finalizada, copiar/pegar).
 */
internal class TerminalSessionClientImpl(
    private val activity: MainActivity,
    private val sessionManager: TerminalSessionManager,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        // Notificar a la vista que la pantalla ha cambiado
        activity.binding.terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        activity.runOnUiThread { activity.updateSessionTabs() }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        sessionManager.onSessionFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text ?: return
        session?.write(text.toString())
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int = 0

    // ── Logging ────────────────────────────────────────────────────────────

    override fun logError(tag: String, message: String) { AppLogger.e(tag, message) }
    override fun logWarn(tag: String, message: String) { AppLogger.w(tag, message) }
    override fun logInfo(tag: String, message: String) { AppLogger.i(tag, message) }
    override fun logDebug(tag: String, message: String) { AppLogger.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { AppLogger.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        AppLogger.e(tag, message, e)
    }
    override fun logStackTrace(tag: String, e: Exception) { AppLogger.e(tag, "Exception", e) }
}
