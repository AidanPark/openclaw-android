package com.openclaw.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts MainActivity on device boot so terminal sessions
 * and openclaw gateway can auto-launch.
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AppLogger.i(TAG, "Boot completed — launching OpenClaw")
            val launchIntent =
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("from_boot", true)
                }
            context.startActivity(launchIntent)
        }
    }
}
