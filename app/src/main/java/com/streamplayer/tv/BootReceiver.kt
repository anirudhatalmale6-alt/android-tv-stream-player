package com.streamplayer.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Launches the player automatically on device boot.
 * Handles various boot intents for compatibility with different Android TV boxes.
 * Uses AutoStartService for more reliable startup on restricted devices.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        // Track if we've already started to avoid duplicate launches
        @Volatile
        private var hasStarted = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        val bootActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_USER_UNLOCKED,
            "com.streamplayer.tv.ALARM_RESTART"
        )

        if (action in bootActions) {
            // Prevent duplicate launches
            if (hasStarted) {
                Log.i(TAG, "Already started, ignoring duplicate broadcast")
                return
            }
            hasStarted = true

            Log.i(TAG, "Boot event detected ($action) — starting AutoStartService")

            try {
                // Start the AutoStartService which will handle launching the activity
                AutoStartService.start(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AutoStartService: ${e.message}", e)
                // Fallback: try to launch activity directly
                tryDirectLaunch(context)
            }
        }
    }

    private fun tryDirectLaunch(context: Context) {
        try {
            Log.i(TAG, "Attempting direct activity launch as fallback")
            val launchIntent = Intent(context, PlaybackActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Direct launch also failed: ${e.message}", e)
            hasStarted = false // Reset so alarm can try again
        }
    }
}
