package com.openclaw.android

import android.webkit.WebView
import com.google.gson.Gson

/**
 * Kotlin → WebView event dispatch (§2.8).
 * Uses evaluateJavascript + CustomEvent pattern.
 *
 * WebView side (index.html) must include:
 *   window.__oc = {
 *     emit(type, data) {
 *       window.dispatchEvent(new CustomEvent(`native:${type}`, { detail: data }));
 *     }
 *   };
 */
class EventBridge(
    private val webView: WebView,
) {
    private val gson = Gson()

    /**
     * Emit a named event to the WebView.
     * React side listens via: useNativeEvent('type', handler)
     */
    fun emit(
        type: String,
        data: Any?,
    ) {
        val json = gson.toJson(data ?: emptyMap<String, Any>())
        val script = "window.__oc&&window.__oc.emit('$type',$json)"
        webView.post { webView.evaluateJavascript(script, null) }
    }
}
