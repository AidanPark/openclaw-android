package com.openclaw.android.ui.webview

import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.openclaw.android.AppLogger
import com.openclaw.android.BuildConfig
import com.openclaw.android.JsBridge

/**
 * Configura el WebView con JavaScript habilitado, debugging (en DEBUG),
 * y los clientes apropiados.
 *
 * Responsabilidad única: configurar el WebView una sola vez.
 */
internal class WebViewConfigurator {

    companion object {
        private const val TAG = "WebViewConfigurator"

        /**
         * Configura un WebView con la configuración estándar de OpenClaw.
         *
         * @param webView El WebView a configurar
         * @param jsBridge El JsBridge a inyectar (puede ser null si se configura después)
         */
        fun configure(webView: WebView, jsBridge: JsBridge?) {
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webView.apply {
                setBackgroundColor(android.graphics.Color.parseColor("#0d1117"))
                clearCache(true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
                settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE

                jsBridge?.let {
                    addJavascriptInterface(it, "OpenClaw")
                }

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
                            AppLogger.d(
                                "WebViewJS",
                                "${it.sourceId()}:${it.lineNumber()} ${it.message()}"
                            )
                        }
                        return true
                    }
                }
            }
        }
    }
}
