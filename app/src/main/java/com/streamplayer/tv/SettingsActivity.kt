package com.streamplayer.tv

import android.content.Intent
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
    private lateinit var changePinButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        urlInput = findViewById(R.id.url_input)
        autoPlaySwitch = findViewById(R.id.auto_play_switch)
        saveButton = findViewById(R.id.save_button)
        changePinButton = findViewById(R.id.change_pin_button)

        // Load current values
        urlInput.setText(StreamPrefs.getStreamUrl(this))
        autoPlaySwitch.isChecked = StreamPrefs.isAutoPlayEnabled(this)

        saveButton.setOnClickListener {
            saveSettings()
        }

        changePinButton.setOnClickListener {
            val intent = Intent(this, PinEntryActivity::class.java)
            intent.putExtra(PinEntryActivity.EXTRA_MODE, PinEntryActivity.MODE_CHANGE_CURRENT)
            startActivity(intent)
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
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val REQUEST_CODE = 1001
    }
}
