package com.openclaw.android.ui.activity

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.databinding.ActivityMainBinding

/**
 * Maneja el cambio entre TerminalView y WebView.
 *
 * Responsabilidad única: mostrar/ocultar las vistas principales y
 * configurar el teclado virtual apropiadamente.
 */
internal class ActivityViewSwitcher(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val installerManager: InstallerManager,
) {

    private val TAG = "ActivityViewSwitcher"
    private val KEYBOARD_SHOW_DELAY_MS = 200L

    /**
     * Muestra el terminal y oculta el WebView.
     * Solicita el teclado virtual después de un breve retraso.
     */
    fun showTerminal() {
        activity.runOnUiThread {
            binding.installOverlay.visibility = View.GONE
            binding.webView.visibility = View.GONE
            binding.terminalContainer.isVisible = true
            binding.terminalView.requestFocus()
            // La actualización de tabs se delega al caller
            binding.terminalView.postDelayed({
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }, KEYBOARD_SHOW_DELAY_MS)
        }
    }

    /**
     * Muestra el WebView y oculta el terminal.
     * Carga la URL apropiada (assets o directorio instalado).
     */
    fun showWebView() {
        activity.runOnUiThread {
            setupWebViewIfNeeded()
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

    /**
     * Recarga el WebView.
     */
    fun reloadWebView() {
        binding.webView.reload()
    }

    /**
     * Configura el WebView si no está ya configurado.
     * Se llama internamente antes de mostrar el WebView.
     */
    private fun setupWebViewIfNeeded() {
        if (binding.webView.settings.javaScriptEnabled) return

        // Configuración básica del WebView
        binding.webView.apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0d1117"))
            clearCache(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
    }
}
