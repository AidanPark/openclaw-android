package com.openclaw.android.ui.activity

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.openclaw.android.AppLogger
import com.openclaw.android.InstallerManager
import com.openclaw.android.databinding.ActivityMainBinding

/**
 * ActivityViewSwitcher — handles switching between TerminalView and WebView.
 */
internal class ActivityViewSwitcher(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val installerManager: InstallerManager,
) {
    private val TAG = "ActivityViewSwitcher"
    private val KEYBOARD_SHOW_DELAY_MS = 200L

    fun showTerminal() {
        activity.runOnUiThread {
            binding.installOverlay.visibility = View.GONE
            binding.webView.visibility = View.GONE
            binding.terminalContainer.visibility = View.VISIBLE
            binding.terminalView.requestFocus()
            binding.terminalView.postDelayed({
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }, KEYBOARD_SHOW_DELAY_MS)
        }
    }

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
            binding.terminalContainer.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
        }
    }

    fun reloadWebView() {
        activity.runOnUiThread { binding.webView.reload() }
    }

    private fun setupWebViewIfNeeded() {
        if (binding.webView.settings.javaScriptEnabled) return
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
