package com.streamplayer.tv

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView

@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlaybackActivity"
        private const val INITIAL_RETRY_DELAY_MS = 2000L
        private const val MAX_RETRY_DELAY_MS = 30000L
        private const val STATUS_DISPLAY_DURATION_MS = 3000L
        // A/V sync correction settings
        private const val SYNC_CHECK_INTERVAL_MS = 30_000L // Check every 30 seconds
        private const val MAX_DRIFT_THRESHOLD_MS = 3000L // Resync if drift exceeds 3 seconds
        // Watchdog to detect stuck player after connection loss
        private const val WATCHDOG_INTERVAL_MS = 10_000L // Check every 10 seconds
        private const val MAX_BUFFERING_TIME_MS = 60_000L // Force restart if buffering > 60 seconds
        // Full app restart after repeated failures
        private const val MAX_RETRY_ATTEMPTS = 5 // After 5 failed retries, restart the entire app
    }

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView

    private var player: ExoPlayer? = null
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private val handler = Handler(Looper.getMainLooper())
    private var isReconnecting = false
    private var settingsOpen = false
    private var retryAttempts = 0 // Track consecutive failed connection attempts

    // Use fullRestartPlayback for reconnect to properly close SRT sockets
    private val retryRunnable = Runnable { fullRestartPlayback() }

    // Periodic sync check to prevent A/V drift over long playback sessions
    private val syncCheckRunnable = object : Runnable {
        override fun run() {
            checkAndCorrectSync()
            handler.postDelayed(this, SYNC_CHECK_INTERVAL_MS)
        }
    }

    // Watchdog to detect and recover from stuck player states
    private var bufferingStartTime = 0L
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkPlayerHealth()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // Launched when returning from SettingsActivity
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        settingsOpen = false
        // Always restart playback when returning from settings,
        // whether the user saved (RESULT_OK) or pressed back
        Log.i(TAG, "Returned from settings — restarting playback")
        fullRestartPlayback()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on and go full-screen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        setContentView(R.layout.activity_playback)

        playerView = findViewById(R.id.player_view)
        loadingIndicator = findViewById(R.id.loading_indicator)
        statusText = findViewById(R.id.status_text)

        // Start the foreground service to keep playback alive
        PlaybackService.start(this)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't release player on pause — we want background playback on Android TV
    }

    override fun onStop() {
        super.onStop()
        // Only release if the activity is finishing
        if (isFinishing) {
            releasePlayer()
            PlaybackService.stop(this)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(syncCheckRunnable)
        handler.removeCallbacks(watchdogRunnable)
        releasePlayer()
        PlaybackService.stop(this)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            // MENU or DPAD_CENTER opens settings
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                openSettings()
                true
            }
            // DPAD_CENTER also opens settings (when no transport controls)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                openSettings()
                true
            }
            // BACK exits the app
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun openSettings() {
        if (settingsOpen) return
        settingsOpen = true
        // Launch PIN entry first — it will open SettingsActivity on success
        val intent = Intent(this, PinEntryActivity::class.java)
        intent.putExtra(PinEntryActivity.EXTRA_MODE, PinEntryActivity.MODE_VERIFY)
        settingsLauncher.launch(intent)
    }

    private fun initializePlayer() {
        if (player != null) return

        // Optimized load control for live streaming
        // Reduced buffer sizes to minimize A/V drift accumulation while still preventing audio dropouts
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,     // Reduced from 30s to minimize drift
                /* maxBufferMs = */ 30_000,     // Reduced from 90s to minimize drift
                /* bufferForPlaybackMs = */ 3_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            // Smaller back buffer - we don't need to seek backwards in live streams
            .setBackBuffer(/* backBufferDurationMs = */ 10_000, /* retainBackBufferFromKeyframe = */ true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF

                // Configure for live playback - keep close to live edge
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        handlePlaybackState(playbackState)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handlePlayerError(error)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            currentRetryDelay = INITIAL_RETRY_DELAY_MS
                            isReconnecting = false
                            retryAttempts = 0 // Reset retry counter on successful playback
                            loadingIndicator.visibility = View.GONE
                            // Start periodic sync checking when playback begins
                            startSyncChecking()
                        } else {
                            stopSyncChecking()
                        }
                    }
                })
            }

        playerView.player = player
        playerView.useController = false // No transport controls visible

        // Start watchdog to monitor player health
        startWatchdog()

        startPlayback()
    }

    private fun startPlayback() {
        val url = StreamPrefs.getStreamUrl(this)
        if (url.isBlank()) {
            showStatus("No stream URL configured. Press OK/Enter to open settings.")
            loadingIndicator.visibility = View.GONE
            return
        }

        Log.i(TAG, "Starting playback: $url")
        showStatus("Connecting…")
        loadingIndicator.visibility = View.VISIBLE

        val mediaSource = buildMediaSource(url)
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
        }
    }

    private fun restartPlayback() {
        handler.removeCallbacks(retryRunnable)
        isReconnecting = false
        currentRetryDelay = INITIAL_RETRY_DELAY_MS

        player?.stop()
        player?.clearMediaItems()
        startPlayback()
    }

    /**
     * Fully release the player and recreate it from scratch.
     * Required for SRT: the old SrtDataSource may still be blocking on recv(),
     * so stop/clear alone isn't enough — we need to release() to force-close
     * the socket, then build a fresh player instance.
     */
    private fun fullRestartPlayback() {
        handler.removeCallbacks(retryRunnable)
        isReconnecting = false
        currentRetryDelay = INITIAL_RETRY_DELAY_MS

        releasePlayer()
        initializePlayer()
    }

    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: ""

        return when {
            // RTMP
            scheme == "rtmp" || scheme == "rtmps" -> {
                val rtmpFactory = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            // HLS
            url.contains(".m3u8", ignoreCase = true) || scheme == "hls" -> {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true)
                HlsMediaSource.Factory(httpFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            // SRT — via srtdroid native libsrt bindings
            scheme == "srt" -> {
                val srtFactory = SrtDataSource.Factory()
                // SRT carries MPEG-TS — tell ExoPlayer explicitly so it
                // doesn't waste time sniffing the container format
                val tsExtractorFactory = DefaultExtractorsFactory()
                    .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
                    .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
                ProgressiveMediaSource.Factory(srtFactory, tsExtractorFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            // Default: progressive (also catches HTTP/HTTPS direct streams)
            else -> {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setAllowCrossProtocolRedirects(true)
                val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
        }
    }

    private fun handlePlaybackState(state: Int) {
        when (state) {
            Player.STATE_BUFFERING -> {
                loadingIndicator.visibility = View.VISIBLE
                if (!isReconnecting) {
                    showStatus("Buffering…")
                }
                // Track when buffering started for watchdog
                if (bufferingStartTime == 0L) {
                    bufferingStartTime = System.currentTimeMillis()
                    Log.i(TAG, "Buffering started")
                }
            }
            Player.STATE_READY -> {
                loadingIndicator.visibility = View.GONE
                showStatus("Playing")
                // Reset buffering timer
                bufferingStartTime = 0L
            }
            Player.STATE_ENDED -> {
                Log.w(TAG, "Stream ended — attempting reconnect")
                bufferingStartTime = 0L
                scheduleReconnect()
            }
            Player.STATE_IDLE -> {
                // Player is idle - might be stuck after error
                if (!isReconnecting && player != null) {
                    Log.w(TAG, "Player went IDLE unexpectedly — attempting reconnect")
                    scheduleReconnect()
                }
            }
        }
    }

    private fun handlePlayerError(error: PlaybackException) {
        Log.e(TAG, "Playback error: ${error.message}", error)
        showStatus("Stream error — reconnecting…")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (isReconnecting) return
        isReconnecting = true

        val url = StreamPrefs.getStreamUrl(this)
        if (url.isBlank()) {
            showStatus("No stream URL configured. Press OK/Enter to open settings.")
            loadingIndicator.visibility = View.GONE
            return
        }

        retryAttempts++
        Log.i(TAG, "Retry attempt $retryAttempts of $MAX_RETRY_ATTEMPTS")

        // If we've tried too many times, restart the entire app
        if (retryAttempts >= MAX_RETRY_ATTEMPTS) {
            Log.w(TAG, "Max retry attempts reached ($retryAttempts), restarting entire app")
            showStatus("Connection failed — restarting app…")
            handler.postDelayed({ restartApp() }, 2000) // Give user time to see message
            return
        }

        Log.i(TAG, "Scheduling reconnect in ${currentRetryDelay}ms")
        showStatus("Reconnecting (${retryAttempts}/$MAX_RETRY_ATTEMPTS)…")
        loadingIndicator.visibility = View.VISIBLE

        handler.postDelayed(retryRunnable, currentRetryDelay)

        // Exponential backoff
        currentRetryDelay = (currentRetryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun showStatus(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE

        // Auto-hide after a delay (unless it's an error/no-url message)
        if (message == "Playing") {
            handler.postDelayed({
                statusText.visibility = View.GONE
            }, STATUS_DISPLAY_DURATION_MS)
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun startSyncChecking() {
        handler.removeCallbacks(syncCheckRunnable)
        handler.postDelayed(syncCheckRunnable, SYNC_CHECK_INTERVAL_MS)
        Log.i(TAG, "Started periodic sync checking")
    }

    private fun stopSyncChecking() {
        handler.removeCallbacks(syncCheckRunnable)
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        Log.i(TAG, "Started player health watchdog")
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
    }

    /**
     * Watchdog function that detects stuck player states and forces recovery.
     * This handles cases where the player gets stuck buffering forever after
     * connection loss (especially common with SRT protocol).
     */
    private fun checkPlayerHealth() {
        val p = player ?: return

        // Check if stuck buffering for too long
        if (bufferingStartTime > 0) {
            val bufferingDuration = System.currentTimeMillis() - bufferingStartTime
            if (bufferingDuration > MAX_BUFFERING_TIME_MS) {
                Log.w(TAG, "Watchdog: Stuck buffering for ${bufferingDuration}ms, forcing full restart")
                bufferingStartTime = 0L
                handler.removeCallbacks(retryRunnable)
                isReconnecting = false
                showStatus("Connection lost — restarting…")
                // Post to handler to avoid reentrant issues
                handler.post { fullRestartPlayback() }
                return
            }
        }

        // Check if player is in unexpected IDLE state (not during reconnect)
        if (!isReconnecting && p.playbackState == Player.STATE_IDLE) {
            val url = StreamPrefs.getStreamUrl(this)
            if (url.isNotBlank()) {
                Log.w(TAG, "Watchdog: Player stuck in IDLE state, forcing reconnect")
                scheduleReconnect()
            }
        }

        // Check if player is in ERROR state but error wasn't handled
        if (!isReconnecting && p.playerError != null) {
            Log.w(TAG, "Watchdog: Unhandled player error detected, forcing reconnect")
            scheduleReconnect()
        }
    }

    /**
     * Checks for A/V drift and corrects it by seeking to live edge.
     * This prevents audio from drifting ahead of video during long playback sessions.
     * The drift occurs because audio and video decoders can process at slightly different rates,
     * and over hours this can accumulate to many seconds of desync.
     */
    private fun checkAndCorrectSync() {
        val p = player ?: return

        // Only check when actually playing
        if (!p.isPlaying) return

        val currentPosition = p.currentPosition
        val duration = p.duration
        val bufferedPosition = p.bufferedPosition

        // For live streams, we want to stay close to the buffered edge
        // If we've fallen behind too much, seek forward to catch up
        if (bufferedPosition > 0 && currentPosition > 0) {
            val behindLiveEdge = bufferedPosition - currentPosition

            if (behindLiveEdge > MAX_DRIFT_THRESHOLD_MS) {
                Log.w(TAG, "Detected drift: ${behindLiveEdge}ms behind live edge, seeking to catch up")
                // Seek to near the live edge (leave a small buffer)
                val targetPosition = bufferedPosition - 1000 // 1 second buffer from edge
                p.seekTo(targetPosition)
                showStatus("Syncing…")
            } else {
                Log.d(TAG, "Sync check OK: ${behindLiveEdge}ms behind live edge")
            }
        }

        // Additional check: if playback speed has drifted, reset it
        if (abs(p.playbackParameters.speed - 1.0f) > 0.01f) {
            Log.w(TAG, "Playback speed drifted to ${p.playbackParameters.speed}, resetting to 1.0")
            p.setPlaybackSpeed(1.0f)
        }
    }

    private fun releasePlayer() {
        stopSyncChecking()
        stopWatchdog()
        bufferingStartTime = 0L
        player?.release()
        player = null
    }

    /**
     * Fully restart the app when reconnection attempts fail repeatedly.
     * This clears all network state, SRT sockets, and gives Android a fresh start.
     */
    private fun restartApp() {
        Log.i(TAG, "Performing full app restart")

        // Create an intent to restart the app
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        // Schedule restart using AlarmManager for reliability
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC,
            System.currentTimeMillis() + 500, // Restart in 500ms
            pendingIntent
        )

        // Kill the current process
        releasePlayer()
        PlaybackService.stop(this)
        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
