package com.streamplayer.tv

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple SharedPreferences wrapper for stream URL storage.
 */
object StreamPrefs {

    private const val PREFS_NAME = "stream_player_prefs"
    private const val KEY_STREAM_URL = "stream_url"
    private const val KEY_AUTO_PLAY = "auto_play"

    // Default placeholder — user replaces via settings screen
    private const val DEFAULT_URL = ""

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getStreamUrl(context: Context): String =
        prefs(context).getString(KEY_STREAM_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun setStreamUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_STREAM_URL, url).apply()
    }

    fun isAutoPlayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_PLAY, true)

    fun setAutoPlay(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_PLAY, enabled).apply()
    }
}
