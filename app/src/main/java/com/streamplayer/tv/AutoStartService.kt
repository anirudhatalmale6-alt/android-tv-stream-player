package com.streamplayer.tv

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Persistent foreground service that ensures the app auto-starts.
 * This service:
 * 1. Starts on boot via BootReceiver
 * 2. Launches PlaybackActivity after a delay
 * 3. Sets up an alarm as backup to restart if killed
 */
class AutoStartService : Service() {

    companion object {
        private const val TAG = "AutoStartService"
        private const val CHANNEL_ID = "autostart_channel"
        private const val NOTIFICATION_ID = 2
        private const val LAUNCH_DELAY_MS = 8000L
        private const val ALARM_INTERVAL_MS = 60000L // Check every minute

        fun start(context: Context) {
            val intent = Intent(context, AutoStartService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AutoStartService: ${e.message}", e)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AutoStartService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        setupAlarmBackup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "AutoStartService onStartCommand")

        // Schedule the activity launch with a delay
        handler.postDelayed({
            launchPlaybackActivity()
        }, LAUNCH_DELAY_MS)

        // Return START_STICKY so the service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "AutoStartService destroyed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun launchPlaybackActivity() {
        try {
            Log.i(TAG, "Launching PlaybackActivity from AutoStartService")
            val launchIntent = Intent(this, PlaybackActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(launchIntent)

            // Stop the service after launching - PlaybackService will take over
            handler.postDelayed({
                stopSelf()
            }, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch PlaybackActivity: ${e.message}", e)
        }
    }

    private fun setupAlarmBackup() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, BootReceiver::class.java).apply {
                action = "com.streamplayer.tv.ALARM_RESTART"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Set a repeating alarm as backup
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                pendingIntent
            )
            Log.i(TAG, "Alarm backup set up")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up alarm backup: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Start Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ensures the stream player starts automatically"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlaybackActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Stream Player")
            .setContentText("Starting...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
