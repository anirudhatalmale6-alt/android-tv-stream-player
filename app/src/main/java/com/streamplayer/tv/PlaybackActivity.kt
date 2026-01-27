package com.streamplayer.tv

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
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
    }

    private lateinit var playerView: PlayerView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var statusText: TextView

    private var player: ExoPlayer? = null
    private var currentRetryDelay = INITIAL_RETRY_DELAY_MS
    private val handler = Handler(Looper.getMainLooper())
    private var isReconnecting = false

    private val retryRunnable = Runnable { startPlayback() }

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
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    // Called when returning from SettingsActivity
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Reload with potentially new URL
        restartPlayback()
    }

    override fun onRestart() {
        super.onRestart()
        // User might have changed URL in settings
        restartPlayback()
    }

    private fun initializePlayer() {
        if (player != null) return

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF

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
                            loadingIndicator.visibility = View.GONE
                        }
                    }
                })
            }

        playerView.player = player
        playerView.useController = false // No transport controls visible

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
            }
            Player.STATE_READY -> {
                loadingIndicator.visibility = View.GONE
                showStatus("Playing")
            }
            Player.STATE_ENDED -> {
                Log.w(TAG, "Stream ended — attempting reconnect")
                scheduleReconnect()
            }
            Player.STATE_IDLE -> {
                // No-op
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

        Log.i(TAG, "Scheduling reconnect in ${currentRetryDelay}ms")
        showStatus("Reconnecting in ${currentRetryDelay / 1000}s…")
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

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
