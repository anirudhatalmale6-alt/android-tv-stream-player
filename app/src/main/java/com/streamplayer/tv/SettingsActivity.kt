package com.streamplayer.tv

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal settings screen for entering/updating the stream URL.
 * Accessible via D-pad ENTER/OK or MENU key from playback screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var autoPlaySwitch: Switch
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        urlInput = findViewById(R.id.url_input)
        autoPlaySwitch = findViewById(R.id.auto_play_switch)
        saveButton = findViewById(R.id.save_button)

        // Load current values
        urlInput.setText(StreamPrefs.getStreamUrl(this))
        autoPlaySwitch.isChecked = StreamPrefs.isAutoPlayEnabled(this)

        saveButton.setOnClickListener {
            saveSettings()
        }

        // Focus the URL input for immediate D-pad editing
        urlInput.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun saveSettings() {
        val url = urlInput.text.toString().trim()
        StreamPrefs.setStreamUrl(this, url)
        StreamPrefs.setAutoPlay(this, autoPlaySwitch.isChecked)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
