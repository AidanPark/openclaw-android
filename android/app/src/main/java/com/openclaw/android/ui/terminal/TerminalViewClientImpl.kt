package com.openclaw.android.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openclaw.android.AppLogger
import com.openclaw.android.MainActivity
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalViewClient

/**
 * TerminalViewClientImpl — handles terminal view events for OpenClaw.
 *
 * Single responsibility: react to view events
 * (scale, tap, modifier keys, logging).
 */
internal class TerminalViewClientImpl(
    private val activity: MainActivity,
) : TerminalViewClient {

    private val MIN_TEXT_SIZE = 8
    private val MAX_TEXT_SIZE = 32
    private var currentTextSize = 32

    override fun onScale(scale: Float): Float {
        val newSize = if (scale > 1f) currentTextSize + 1 else currentTextSize - 1
        currentTextSize = newSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        activity.binding.terminalView.setTextSize(currentTextSize)
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val imeVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
        if (imeVisible) {
            controller.hide(WindowInsetsCompat.Type.ime())
        } else {
            activity.binding.terminalView.requestFocus()
            controller.show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean =
        activity.binding.terminalContainer.visibility == View.VISIBLE

    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean {
        val v = activity.ctrlDown
        if (v) {
            activity.ctrlDown = false
            activity.runOnUiThread {
                activity.updateModifierButton(
                    activity.findViewById(com.openclaw.android.R.id.btnCtrl), false
                )
            }
        }
        return v
    }

    override fun readAltKey(): Boolean {
        val v = activity.altDown
        if (v) {
            activity.altDown = false
            activity.runOnUiThread {
                activity.updateModifierButton(
                    activity.findViewById(com.openclaw.android.R.id.btnAlt), false
                )
            }
        }
        return v
    }

    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit

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
