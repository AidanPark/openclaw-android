package com.openclaw.android

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.openclaw.android.databinding.ActivityMainBinding
import com.openclaw.android.ui.install.InstallOverlayController
import com.openclaw.android.ui.permissions.PermissionsController
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainActivity - app entry point.
 *
 * Responsibilities (after refactoring):
 *   - Initialize all managers and controllers
 *   - Setup WebView, TerminalView, extra keys
 *   - Delegate install logic to InstallOverlayController
 *   - Delegate permission logic to PermissionsController
 *   - Provide public API for JsBridge (showTerminal, showWebView, etc.)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_TEXT_SIZE = 32
        private const val MIN_TEXT_SIZE = 8
        private const val MAX_TEXT_SIZE = 32
        private const val KEYBOARD_SHOW_DELAY_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding

    // Core managers
    lateinit var sessionManager: TerminalSessionManager
    lateinit var installerManager: InstallerManager
    lateinit var eventBridge: EventBridge
    private lateinit var jsBridge: JsBridge

    // UI controllers
    private lateinit var installOverlay: InstallOverlayController
    private lateinit var permissionsController: PermissionsController

    // Terminal state
    private var currentTextSize = DEFAULT_TEXT_SIZE
    private var ctrlDown = false
    private var altDown = false
    private val terminalSessionClient = OpenClawSessionClient()
    private val terminalViewClient = OpenClawViewClient()

    // File pickers
    var selectedPayloadUri: Uri? = null

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        permissionsController.onStoragePermissionActivityResult()
    }

    private val payloadFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedPayloadUri = it
            AppLogger.i(TAG, "Payload file selected: $it")
            eventBridge.emit("payload_file_selected", mapOf(
                "uri" to it.toString(),
                "name" to (it.path?.split("/")?.last() ?: "payload.tar.gz"),
            ))
        }
    }

    private val glibcFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri -> onGlibcFileSelected(selectedUri) }
    }

    private fun onGlibcFileSelected(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppLogger.i(TAG, "glibc file selected: $uri")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val homeDir = filesDir.resolve("home").also { it.mkdirs() }
                val dest = homeDir.resolve("glibc-aarch64.tar.xz")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                val ok = InstallerManager(this@MainActivity).installGlibcFromFile(dest)
                eventBridge.emit("glibc_install", mapOf(
                    "success" to ok,
                    "message" to if (ok) "glibc instalado desde archivo" else "Error instalando glibc",
                ))
            } catch (e: Exception) {
                AppLogger.e(TAG, "glibc file install failed: ${e.message}", e)
                eventBridge.emit("glibc_install", mapOf(
                    "success" to false,
                    "error" to (e.message ?: "Error desconocido"),
                ))
            }
        }
    }

    // --- Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.terminalContainer.isVisible -> showWebView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        installerManager = InstallerManager(this)
        eventBridge = EventBridge(binding.webView)
        sessionManager = TerminalSessionManager(this, terminalSessionClient, eventBridge)
        jsBridge = JsBridge(this, sessionManager, installerManager, eventBridge)

        installOverlay = InstallOverlayController(
            binding, this, terminalSessionClient, terminalViewClient,
        ) {
            showTerminal()
            val session = sessionManager.createSession()
            session.write("echo '=== Terminal de recuperacion ==='\n")
            session.write("echo 'Ejecuta comandos para corregir el error.'\n")
        }

        permissionsController = PermissionsController(this, storagePermissionLauncher) {
            onStoragePermissionsGranted()
        }

        setupTerminalView()
        setupWebView()
        setupExtraKeys()
        installOverlay.setupErrorButton()
        sessionManager.onSessionsChanged = { updateSessionTabs() }

        startService(Intent(this, OpenClawService::class.java))

        permissionsController.requestStorage()
        permissionsController.requestNotifications()
    }

    override fun onDestroy() {
        super.onDestroy()
        jsBridge.cancel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsController.onRequestPermissionsResult(requestCode, grantResults)
    }

    // --- Install flow ---

    private fun checkAndStartInstallation() {
        val isInstalled = installerManager.isInstalled()
        AppLogger.i(TAG, "checkAndStartInstallation: installed=$isInstalled")

        if (isInstalled) {
            val prefs = getSharedPreferences("openclaw", 0)
            val savedVersionCode = prefs.getInt("versionCode", 0)
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(pInfo).toInt()
            if (currentVersionCode > savedVersionCode) {
                AppLogger.i(TAG, "APK upgrade: $savedVersionCode to $currentVersionCode")
                prefs.edit { putInt("versionCode", currentVersionCode) }
            }
        }

        when {
            !isInstalled -> {
                AppLogger.i(TAG, "Not installed - showing setup wizard")
                showWebView()
            }
            isBootIntent(intent) -> {
                val startScript = installerManager.getRunScriptPath()
                AppLogger.i(TAG, "Boot launch - auto-starting gateway")
                showTerminal()
                val session = sessionManager.createSession()
                binding.terminalView.post {
                    session.write("\"${startScript.absolutePath}\"\n")
                }
            }
            else -> {
                AppLogger.i(TAG, "Already installed - showing dashboard")
                showWebView()
            }
        }
    }

    private fun isBootIntent(intent: Intent): Boolean =
        intent.getBooleanExtra("boot", false) ||
            intent.action == "com.openclaw.android.BOOT"

    fun startInstallFromUi(mode: String = "auto", onComplete: ((success: Boolean) -> Unit)? = null) {
        // Modo "online": instalar bootstrap primero, luego curl | bash en terminal
        if (mode == "online") {
            // Paso 1: instalar Termux Bootstrap en background (necesario para curl, bash, apt)
            installOverlay.show()
            installOverlay.runInstall(this, installerManager, "online", null) { bootstrapOk ->
                // Paso 2: independientemente del resultado del bootstrap,
                // abrir terminal y ejecutar la instalación online
                runOnUiThread {
                    installOverlay.hide()
                    runOnlineInstallInTerminal()
                }
                onComplete?.invoke(bootstrapOk)
            }
            return
        }
        installOverlay.show()
        installOverlay.runInstall(this, installerManager, mode, selectedPayloadUri) { success ->
            if (success) reloadWebView()
            onComplete?.invoke(success)
        }
    }

    /**
     * Ejecuta la instalación online dentro del terminal embebido.
     *
     * Flujo:
     *   1. Mostrar el terminal
     *   2. Crear sesión si no hay ninguna activa
     *   3. Inyectar entorno + ejecutar: curl -sL myopenclawhub.com/install | bash
     *   4. Si dpkg falla → ejecutar dpkg --configure -a (responde N automáticamente)
     *   5. Reintentar curl | bash
     *   6. source ~/.bashrc al finalizar
     *
     * Nota: el Termux Bootstrap debe estar instalado antes de llamar esto.
     * El modo "online" en startInstallFromUi() lo garantiza.
     */
    fun runOnlineInstallInTerminal() {
        AppLogger.i(TAG, "Starting online install in terminal")
        showTerminal()

        // Asegurar que hay una sesión activa
        val session = sessionManager.activeSession ?: sessionManager.createSession()

        // Usar TerminalManager para el flujo completo con recuperación de dpkg
        val terminalManager = TerminalManager(this, filesDir)
        terminalManager.runOnlineInstall(session) {
            AppLogger.i(TAG, "Online install commands sent to terminal session")
        }
    }

    private fun onStoragePermissionsGranted() {
        checkAndStartInstallation()
    }

    // --- File pickers (public for JsBridge) ---

    fun pickPayloadFile() {
        payloadFilePickerLauncher.launch(arrayOf(
            "application/gzip", "application/x-gzip", "application/x-tgz",
        ))
    }

    fun pickGlibcFile() {
        glibcFilePickerLauncher.launch(arrayOf(
            "application/x-xz", "application/x-tar", "application/octet-stream",
        ))
    }

    // --- View switching ---

    fun showTerminal() {
        runOnUiThread {
            binding.installOverlay.visibility = View.GONE
            binding.webView.visibility = View.GONE
            binding.terminalContainer.isVisible = true
            binding.terminalView.requestFocus()
            updateSessionTabs()
            binding.terminalView.postDelayed({
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }, KEYBOARD_SHOW_DELAY_MS)
        }
    }

    fun showWebView() {
        runOnUiThread {
            setupWebView()
            val wwwDir = installerManager.getWwwDir()
            val url = if (wwwDir.resolve("index.html").exists()) {
                "file://${wwwDir.absolutePath}/index.html"
            } else {
                "file:///android_asset/www/index.html"
            }
            if (binding.webView.url != url) {
                AppLogger.i(TAG, "Loading WebView URL: $url")
                binding.webView.loadUrl(url)
            }
            binding.installOverlay.visibility = View.GONE
            binding.terminalContainer.isVisible = false
            binding.webView.isVisible = true
        }
    }

    fun reloadWebView() {
        binding.webView.reload()
    }

    // --- Terminal setup ---

    private fun setupTerminalView() {
        binding.terminalView.setTerminalViewClient(terminalViewClient)
        binding.terminalView.setTextSize(currentTextSize)
    }

    // --- WebView setup ---

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0d1117"))
            clearCache(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            addJavascriptInterface(jsBridge, "OpenClaw")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AppLogger.i(TAG, "WebView loaded: $url")
                }
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    AppLogger.e(TAG, "WebView error ($errorCode): $description at $failingUrl")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    msg?.let {
                        AppLogger.d("WebViewJS", "${it.sourceId()}:${it.lineNumber()} ${it.message()}")
                    }
                    return true
                }
            }
        }
    }

    // --- Extra keys ---

    private val pressedAlpha = 0.5f
    private val normalAlpha = 1.0f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeys() {
        val keyMap = mapOf(
            R.id.btnEsc to KeyEvent.KEYCODE_ESCAPE,
            R.id.btnTab to KeyEvent.KEYCODE_TAB,
            R.id.btnHome to KeyEvent.KEYCODE_MOVE_HOME,
            R.id.btnEnd to KeyEvent.KEYCODE_MOVE_END,
            R.id.btnUp to KeyEvent.KEYCODE_DPAD_UP,
            R.id.btnDown to KeyEvent.KEYCODE_DPAD_DOWN,
            R.id.btnLeft to KeyEvent.KEYCODE_DPAD_LEFT,
            R.id.btnRight to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        for ((btnId, keyCode) in keyMap) {
            setupExtraKeyTouch(findViewById(btnId)) { sendExtraKey(keyCode) }
        }
        setupExtraKeyTouch(findViewById(R.id.btnDash)) { sessionManager.activeSession?.write("-") }
        setupExtraKeyTouch(findViewById(R.id.btnPipe)) { sessionManager.activeSession?.write("|") }
        setupExtraKeyTouch(findViewById(R.id.btnPaste)) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) sessionManager.activeSession?.write(text)
        }
        setupModifierTouch(findViewById(R.id.btnCtrl)) { ctrlDown = !ctrlDown; ctrlDown }
        setupModifierTouch(findViewById(R.id.btnAlt)) { altDown = !altDown; altDown }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupExtraKeyTouch(btn: Button, action: () -> Unit) {
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupModifierTouch(btn: Button, toggle: () -> Boolean) {
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
        if (ctrlDown) metaState = metaState or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (altDown) metaState = metaState or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        val ev = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState)
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

    private fun updateModifierButton(button: Button, active: Boolean) {
        val bgColor = if (active) R.color.extraKeyActive else R.color.extraKeyDefault
        val txtColor = if (active) R.color.extraKeyActiveText else R.color.extraKeyText
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColor))
        button.setTextColor(ContextCompat.getColor(this, txtColor))
    }

    // --- Session tab bar ---

    private fun updateSessionTabs() {
        val tabsLayout = binding.tabsLayout
        tabsLayout.removeAllViews()
        val sessions = sessionManager.getSessionsInfo()
        for (info in sessions) {
            val tabWrapper = createSessionTab(info)
            tabsLayout.addView(tabWrapper)
            if (info["active"] as Boolean) {
                binding.sessionTabBar.post {
                    binding.sessionTabBar.smoothScrollTo(tabWrapper.left, 0)
                }
            }
        }
        tabsLayout.addView(createAddButton())
    }

    private fun createSessionTab(info: Map<String, Any>): LinearLayout {
        val id = info["id"] as String
        val name = info["name"] as String
        val active = info["active"] as Boolean
        val finished = info["finished"] as Boolean

        val tabWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { marginEnd = resources.getDimensionPixelSize(R.dimen.tab_margin) }
            setBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (active) R.color.tabActiveBackground else R.color.tabInactiveBackground,
                )
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }

        tabWrapper.addView(createTabContent(name, active, finished, id))
        tabWrapper.addView(createTabIndicator(active))
        tabWrapper.setOnClickListener {
            sessionManager.switchSession(id)
            binding.terminalView.requestFocus()
        }
        return tabWrapper
    }

    private fun createTabContent(
        name: String,
        active: Boolean,
        finished: Boolean,
        id: String,
    ): LinearLayout {
        val hPad = resources.getDimensionPixelSize(R.dimen.tab_padding_h)
        val vPad = resources.getDimensionPixelSize(R.dimen.tab_padding_v)
        val closePad = resources.getDimensionPixelSize(R.dimen.tab_close_size) / 4

        val tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(hPad, vPad, closePad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f,
            )
            isFocusable = false
            isFocusableInTouchMode = false
        }

        val nameView = TextView(this).apply {
            text = name
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_name_text_size))
            val textColor = when {
                finished -> R.color.tabTextFinished
                active -> R.color.tabTextPrimary
                else -> R.color.tabTextSecondary
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, textColor))
            if (finished) setTypeface(typeface, Typeface.ITALIC)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val closeView = TextView(this).apply {
            text = getString(R.string.close_session)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_close_text_size))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tabTextSecondary))
            setPadding(closePad, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isFocusable = false
            isFocusableInTouchMode = false
            setOnClickListener { closeSessionFromTab(id) }
        }

        tabContent.addView(nameView)
        tabContent.addView(closeView)
        return tabContent
    }

    private fun createTabIndicator(active: Boolean): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.tab_indicator_height),
        )
        setBackgroundColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (active) R.color.tabAccent else android.R.color.transparent,
            )
        )
    }

    private fun createAddButton(): TextView = TextView(this).apply {
        text = getString(R.string.add_session)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.tab_add_text_size))
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.tabAddButton))
        val pad = resources.getDimensionPixelSize(R.dimen.tab_add_padding)
        setPadding(pad, 0, pad, 0)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        isFocusable = false
        isFocusableInTouchMode = false
        setOnClickListener {
            sessionManager.createSession()
            binding.terminalView.requestFocus()
        }
    }

    private fun closeSessionFromTab(handleId: String) {
        if (sessionManager.sessionCount <= 1) sessionManager.createSession()
        sessionManager.closeSession(handleId)
        binding.terminalView.requestFocus()
    }

    // --- Terminal session callbacks ---

    private inner class OpenClawSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            binding.terminalView.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            runOnUiThread { updateSessionTabs() }
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {
            sessionManager.onSessionFinished(finishedSession)
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenClaw", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text ?: return
            session?.write(text.toString())
        }
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int = 0
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

    // --- Terminal view callbacks ---

    @Suppress("TooManyFunctions")
    private inner class OpenClawViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val newSize = if (scale > 1f) currentTextSize + 1 else currentTextSize - 1
            currentTextSize = newSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
            binding.terminalView.setTextSize(currentTextSize)
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val rootInsets = ViewCompat.getRootWindowInsets(window.decorView)
            val isVisible = rootInsets?.isVisible(WindowInsetsCompat.Type.ime()) ?: false
            if (isVisible) {
                controller.hide(WindowInsetsCompat.Type.ime())
            } else {
                binding.terminalView.requestFocus()
                controller.show(WindowInsetsCompat.Type.ime())
            }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = binding.terminalContainer.isVisible
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
        override fun onLongPress(event: MotionEvent): Boolean = false
        override fun readControlKey(): Boolean {
            val v = ctrlDown
            if (v) {
                ctrlDown = false
                runOnUiThread { updateModifierButton(findViewById(R.id.btnCtrl), false) }
            }
            return v
        }
        override fun readAltKey(): Boolean {
            val v = altDown
            if (v) {
                altDown = false
                runOnUiThread { updateModifierButton(findViewById(R.id.btnAlt), false) }
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
}
