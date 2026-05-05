package com.openclaw.android.ui.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.openclaw.android.EventBridge
import com.openclaw.android.InstallerManager
import com.openclaw.android.MainActivity
import com.openclaw.android.OpenClawService
import com.openclaw.android.TerminalSessionManager
import com.openclaw.android.bridge.JsBridgeFacade
import com.openclaw.android.databinding.ActivityMainBinding
import com.openclaw.android.ui.install.InstallOverlayController
import com.openclaw.android.ui.permissions.ModernPermissionManager
import com.openclaw.android.ui.terminal.TerminalSessionClientImpl
import com.openclaw.android.ui.terminal.TerminalViewClientImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ActivityInitializer — creates and wires all managers and controllers for MainActivity.
 *
 * Single responsibility: initialize components in onCreate() and clean up in onDestroy().
 * Terminal clients are created here (after sessionManager exists) to avoid lateinit issues.
 */
internal class ActivityInitializer(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
) {
    private val TAG = "ActivityInitializer"

    // Public managers — accessed by MainActivity
    lateinit var sessionManager: TerminalSessionManager
    lateinit var installerManager: InstallerManager
    lateinit var eventBridge: EventBridge
    lateinit var jsBridge: JsBridgeFacade

    // Controllers
    lateinit var installOverlay: InstallOverlayController
    lateinit var permissionManager: ModernPermissionManager

    // Terminal clients — created after sessionManager is ready
    lateinit var terminalSessionClient: TerminalSessionClientImpl
    lateinit var terminalViewClient: TerminalViewClientImpl

    fun onCreate(savedInstanceState: Bundle?) {
        setupBackPressedHandler()
        initializeManagers()
        initializeTerminalClients()
        initializeControllers()
        startOpenClawService()
    }

    fun onDestroy() {
        jsBridge.cancel()
    }

    private fun setupBackPressedHandler() {
        activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.terminalContainer.visibility == View.VISIBLE -> {
                        (activity as? MainActivity)?.showWebView()
                    }
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        activity.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
    }

    private fun initializeManagers() {
        installerManager = InstallerManager(activity)
        eventBridge = EventBridge(binding.webView)
        // sessionManager needs a client — use a temporary placeholder, replaced after client init
        // We create a real client below in initializeTerminalClients()
    }

    private fun initializeTerminalClients() {
        val mainActivity = activity as MainActivity

        // Create a temporary sessionManager with a forward-reference client pattern:
        // TerminalSessionClientImpl needs sessionManager, sessionManager needs a client.
        // Solution: create sessionManager first with a stub, then replace the client reference.
        // Simpler: create the client with a lazy reference via the activity.
        terminalViewClient = TerminalViewClientImpl(mainActivity)

        // Create sessionManager with a temporary client that delegates to the real one
        // once it's created. We use a wrapper that holds a mutable reference.
        val clientHolder = ClientHolder()
        sessionManager = TerminalSessionManager(mainActivity, clientHolder, eventBridge)

        // Now create the real client and inject it into the holder
        terminalSessionClient = TerminalSessionClientImpl(mainActivity, sessionManager)
        clientHolder.delegate = terminalSessionClient

        jsBridge = JsBridgeFacade(mainActivity, sessionManager, installerManager, eventBridge)
    }

    private fun initializeControllers() {
        installOverlay = InstallOverlayController(
            binding,
            activity,
            terminalSessionClient,
            terminalViewClient,
        ) {
            (activity as? MainActivity)?.showTerminal()
            val session = sessionManager.createSession()
            session.write("echo '=== Recovery terminal ==='\n")
        }

        permissionManager = ModernPermissionManager(activity)
        permissionManager.initialize()

        permissionManager.onStorageRationale = {
            activity.runOnUiThread {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                    .setTitle("Storage Permission Required")
                    .setMessage("OpenClaw needs storage access to install the runtime environment.")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }

        installOverlay.setupErrorButton()
        sessionManager.onSessionsChanged = {
            (activity as? MainActivity)?.updateSessionTabs()
        }
    }

    private fun startOpenClawService() {
        activity.startService(Intent(activity, OpenClawService::class.java))
    }

    fun requestInitialPermissions() {
        CoroutineScope(Dispatchers.Main).launch {
            permissionManager.requestStorage()
            permissionManager.requestNotifications()
            (activity as? MainActivity)?.onStoragePermissionsGranted()
        }
    }

    /**
     * Mutable holder that lets us break the circular dependency between
     * TerminalSessionManager (needs a client) and TerminalSessionClientImpl
     * (needs sessionManager). The holder is passed to sessionManager at
     * construction time; the real client is injected immediately after.
     */
    private class ClientHolder : com.termux.terminal.TerminalSessionClient {
        var delegate: com.termux.terminal.TerminalSessionClient? = null

        override fun onTextChanged(changedSession: com.termux.terminal.TerminalSession) =
            delegate?.onTextChanged(changedSession) ?: Unit

        override fun onTitleChanged(changedSession: com.termux.terminal.TerminalSession) =
            delegate?.onTitleChanged(changedSession) ?: Unit

        override fun onSessionFinished(finishedSession: com.termux.terminal.TerminalSession) =
            delegate?.onSessionFinished(finishedSession) ?: Unit

        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String) =
            delegate?.onCopyTextToClipboard(session, text) ?: Unit

        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) =
            delegate?.onPasteTextFromClipboard(session) ?: Unit

        override fun onBell(session: com.termux.terminal.TerminalSession) =
            delegate?.onBell(session) ?: Unit

        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) =
            delegate?.onColorsChanged(session) ?: Unit

        override fun onTerminalCursorStateChange(state: Boolean) =
            delegate?.onTerminalCursorStateChange(state) ?: Unit

        override fun setTerminalShellPid(session: com.termux.terminal.TerminalSession, pid: Int) =
            delegate?.setTerminalShellPid(session, pid) ?: Unit

        override fun getTerminalCursorStyle(): Int = delegate?.getTerminalCursorStyle() ?: 0

        override fun logError(tag: String, message: String) = delegate?.logError(tag, message) ?: Unit
        override fun logWarn(tag: String, message: String) = delegate?.logWarn(tag, message) ?: Unit
        override fun logInfo(tag: String, message: String) = delegate?.logInfo(tag, message) ?: Unit
        override fun logDebug(tag: String, message: String) = delegate?.logDebug(tag, message) ?: Unit
        override fun logVerbose(tag: String, message: String) = delegate?.logVerbose(tag, message) ?: Unit
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
            delegate?.logStackTraceWithMessage(tag, message, e) ?: Unit
        override fun logStackTrace(tag: String, e: Exception) =
            delegate?.logStackTrace(tag, e) ?: Unit
    }
}
