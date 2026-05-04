package com.openclaw.android.core.install

/**
 * InstallProgress — shared progress reporting contract.
 *
 * Used by all install flows (offline, online, proot) to report
 * progress back to the UI layer without coupling to Android UI classes.
 */
interface InstallProgress {
    /** percent: 0–100 */
    fun onProgress(percent: Int, message: String)
    fun onSuccess()
    fun onError(message: String, cause: Throwable? = null)
}

/**
 * Simple no-op implementation for testing or fire-and-forget installs.
 */
object NoOpProgress : InstallProgress {
    override fun onProgress(percent: Int, message: String) {}
    override fun onSuccess() {}
    override fun onError(message: String, cause: Throwable?) {}
}
