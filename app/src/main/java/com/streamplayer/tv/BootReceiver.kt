package com.streamplayer.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Launches the player automatically on device boot.
 * Handles various boot intents for compatibility with different Android TV boxes.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        // Delay before launching to let the system fully initialize
        private const val LAUNCH_DELAY_MS = 5000L
        // Track if we've already launched to avoid duplicate launches
        @Volatile
        private var hasLaunched = false
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
            Intent.ACTION_USER_UNLOCKED
        )

        if (action in bootActions) {
            // Prevent duplicate launches
            if (hasLaunched) {
                Log.i(TAG, "Already launched, ignoring duplicate boot broadcast")
                return
            }
            hasLaunched = true

            Log.i(TAG, "Boot event detected ($action) — scheduling StreamPlayer launch in ${LAUNCH_DELAY_MS}ms")

            // Use a delay to let the system fully boot before launching
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "Launching StreamPlayer now")
                    val launchIntent = Intent(context, PlaybackActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch StreamPlayer: ${e.message}", e)
                    hasLaunched = false // Reset so it can try again
                }
            }, LAUNCH_DELAY_MS)
        }
    }
}
