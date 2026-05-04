package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.openclaw.android.databinding.ActivityMainBinding
import com.openclaw.android.ui.activity.ActivityInitializer
import com.openclaw.android.ui.activity.ActivityPermissionHandler
import com.openclaw.android.ui.activity.ActivityViewSwitcher
import com.openclaw.android.ui.install.ActivityInstallFlow
import com.openclaw.android.ui.terminal.TerminalSessionClientImpl
import com.openclaw.android.ui.terminal.TerminalTabManager
import com.openclaw.android.ui.terminal.TerminalViewClientImpl
import com.openclaw.android.ui.webview.WebViewConfigurator

/**
 * MainActivity - punto de entrada de la app.
 *
 * Esta clase actúa como fachada (Facade pattern): mantiene la API pública intacta
 * y delega cada responsabilidad a un componente especializado:
 *
 *   ┌─────────────────────────────────────────────────────────────┐
 *   │                    MainActivity (fachada)                   │
 *   │                                                             │
 *   │  ActivityInitializer      → onCreate(), onDestroy()         │
 *   │  ActivityPermissionHandler → permisos y file pickers        │
 *   │  ActivityViewSwitcher     → showTerminal(), showWebView()   │
 *   │  ActivityInstallFlow      → flujo de instalación            │
 *   │  TerminalTabManager       → pestañas de sesiones            │
 *   │  TerminalSessionClientImpl → callbacks de sesión            │
 *   │  TerminalViewClientImpl   → callbacks de vista              │
 *   │  WebViewConfigurator      → setupWebView()                  │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * Design principles:
 *   1. Activities deben ser delgadas — solo setup de vistas y observadores.
 *   2. La lógica de negocio va a ViewModels o managers especializados.
 *   3. Mantener la API pública intacta para JsBridge y otros callers.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_TEXT_SIZE = 32
        private const val MIN_TEXT_SIZE = 8
        private const val MAX_TEXT_SIZE = 32
    }

    private lateinit var binding: ActivityMainBinding

    // Componentes internos
    private lateinit var initializer: ActivityInitializer
    private lateinit var permissionHandler: ActivityPermissionHandler
    private lateinit var viewSwitcher: ActivityViewSwitcher
    private lateinit var installFlow: ActivityInstallFlow
    private lateinit var tabManager: TerminalTabManager
    private lateinit var terminalSessionClient: TerminalSessionClientImpl
    private lateinit var terminalViewClient: TerminalViewClientImpl

    // State para teclas modificadoras (compartido con TerminalViewClientImpl)
    var ctrlDown = false
    var altDown = false

    // ── API pública (accesible desde JsBridge) ─────────────────────────────

    lateinit var sessionManager: TerminalSessionManager
    lateinit var installerManager: InstallerManager
    lateinit var eventBridge: EventBridge
    lateinit var jsBridge: JsBridge

    var selectedPayloadUri: android.net.Uri? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Crear clientes de terminal
        terminalSessionClient = TerminalSessionClientImpl(this, sessionManager)
        terminalViewClient = TerminalViewClientImpl(this)

        // Inicializar componentes
        initializer = ActivityInitializer(this, binding, terminalSessionClient, terminalViewClient)
        initializer.onCreate(savedInstanceState)

        // Obtener referencias a managers
        sessionManager = initializer.sessionManager
        installerManager = initializer.installerManager
        eventBridge = initializer.eventBridge
        jsBridge = initializer.jsBridge

        // Configurar resto de componentes
        permissionHandler = ActivityPermissionHandler(this) {
            onStoragePermissionsGranted()
        }
        viewSwitcher = ActivityViewSwitcher(this, binding, installerManager)
        installFlow = ActivityInstallFlow(this, installerManager, initializer.installOverlay)
        tabManager = TerminalTabManager(this, binding, sessionManager)

        // Configurar vistas
        setupTerminalView()
        setupWebView()
        setupExtraKeys()

        // Solicitar permisos iniciales
        initializer.requestInitialPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        initializer.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHandler.onRequestPermissionsResult(requestCode, grantResults)
    }

    // ── API pública — instalación ──────────────────────────────────────────

    fun startInstallFromUi(mode: String = "auto", onComplete: ((success: Boolean) -> Unit)? = null) {
        installFlow.startInstallFromUi(mode, onComplete)
    }

    fun runOnlineInstallInTerminal() {
        installFlow.runOnlineInstallInTerminal()
    }

    // ── API pública — file pickers ─────────────────────────────────────────

    fun pickPayloadFile() {
        permissionHandler.pickPayloadFile()
    }

    fun pickGlibcFile() {
        permissionHandler.pickGlibcFile()
    }

    // ── API pública — view switching ───────────────────────────────────────

    fun showTerminal() {
        viewSwitcher.showTerminal()
    }

    fun showWebView() {
        viewSwitcher.showWebView()
    }

    fun reloadWebView() {
        viewSwitcher.reloadWebView()
    }

    // ── API pública — session tabs ─────────────────────────────────────────

    fun updateSessionTabs() {
        tabManager.updateSessionTabs()
    }

    // ── API pública — extra keys helpers ───────────────────────────────────

    fun updateModifierButton(button: Button, active: Boolean) {
        val bgColor = if (active) R.color.extraKeyActive else R.color.extraKeyDefault
        val txtColor = if (active) R.color.extraKeyActiveText else R.color.extraKeyText
        button.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, bgColor)
        )
        button.setTextColor(ContextCompat.getColor(this, txtColor))
    }

    // ── Internal callbacks ─────────────────────────────────────────────────

    internal fun onStoragePermissionsGranted() {
        installFlow.checkAndStartInstallation(intent)
    }

    // ── Setup helpers ──────────────────────────────────────────────────────

    private fun setupTerminalView() {
        binding.terminalView.setTerminalViewClient(terminalViewClient)
        binding.terminalView.setTextSize(DEFAULT_TEXT_SIZE)
    }

    private fun setupWebView() {
        WebViewConfigurator.configure(binding.webView, jsBridge)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeys() {
        val keyMap = mapOf(
            R.id.btnEsc to android.view.KeyEvent.KEYCODE_ESCAPE,
            R.id.btnTab to android.view.KeyEvent.KEYCODE_TAB,
            R.id.btnHome to android.view.KeyEvent.KEYCODE_MOVE_HOME,
            R.id.btnEnd to android.view.KeyEvent.KEYCODE_MOVE_END,
            R.id.btnUp to android.view.KeyEvent.KEYCODE_DPAD_UP,
            R.id.btnDown to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            R.id.btnLeft to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            R.id.btnRight to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        for ((btnId, keyCode) in keyMap) {
            setupExtraKeyTouch(findViewById(btnId)) { sendExtraKey(keyCode) }
        }
        setupExtraKeyTouch(findViewById(R.id.btnDash)) { sessionManager.activeSession?.write("-") }
        setupExtraKeyTouch(findViewById(R.id.btnPipe)) { sessionManager.activeSession?.write("|") }
        setupExtraKeyTouch(findViewById(R.id.btnPaste)) {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) sessionManager.activeSession?.write(text)
        }
        setupModifierTouch(findViewById(R.id.btnCtrl)) { ctrlDown = !ctrlDown; ctrlDown }
        setupModifierTouch(findViewById(R.id.btnAlt)) { altDown = !altDown; altDown }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeyTouch(btn: Button, action: () -> Unit) {
        val pressedAlpha = 0.5f
        val normalAlpha = 1.0f
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = pressedAlpha
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = normalAlpha
                    if (event.action == MotionEvent.ACTION_UP) action()
                }
            }
            true
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupModifierTouch(btn: Button, toggle: () -> Boolean) {
        val pressedAlpha = 0.5f
        val normalAlpha = 1.0f
        btn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.alpha = pressedAlpha
                MotionEvent.ACTION_UP -> {
                    val active = toggle()
                    updateModifierButton(v as Button, active)
                    v.alpha = normalAlpha
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = normalAlpha
            }
            true
        }
    }

    private fun sendExtraKey(keyCode: Int) {
        var metaState = 0
        if (ctrlDown) metaState = metaState or (android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
        if (altDown) metaState = metaState or (android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON)
        val ev = android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, keyCode, 0, metaState)
        binding.terminalView.onKeyDown(keyCode, ev)
        if (ctrlDown) {
            ctrlDown = false
            updateModifierButton(findViewById(R.id.btnCtrl), false)
        }
        if (altDown) {
            altDown = false
            updateModifierButton(findViewById(R.id.btnAlt), false)
        }
    }
}
