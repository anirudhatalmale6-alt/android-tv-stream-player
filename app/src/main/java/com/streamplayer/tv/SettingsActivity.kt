package com.streamplayer.tv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Minimal settings screen for entering/updating the stream URL.
 * Accessible via D-pad ENTER/OK or MENU key from playback screen.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        const val REQUEST_CODE = 1001
    }

    private lateinit var urlInput: EditText
    private lateinit var autoPlaySwitch: Switch
    private lateinit var saveButton: Button
    private lateinit var changePinButton: Button
    private lateinit var loadFromUsbButton: Button

    // File picker launcher using Storage Access Framework
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadUrlFromFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        urlInput = findViewById(R.id.url_input)
        autoPlaySwitch = findViewById(R.id.auto_play_switch)
        saveButton = findViewById(R.id.save_button)
        changePinButton = findViewById(R.id.change_pin_button)
        loadFromUsbButton = findViewById(R.id.load_from_usb_button)

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

        loadFromUsbButton.setOnClickListener {
            openFilePicker()
        }

        // Focus the URL input for immediate D-pad editing
        urlInput.requestFocus()
    }

    private fun openFilePicker() {
        Log.i(TAG, "Opening file picker for stream.txt")
        try {
            // Launch file picker for text files
            filePickerLauncher.launch(arrayOf("text/plain", "*/*"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open file picker: ${e.message}", e)
            Toast.makeText(this, "Failed to open file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUrlFromFile(uri: Uri) {
        Log.i(TAG, "Loading URL from file: $uri")
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val url = reader.readLine()?.trim()

                if (!url.isNullOrBlank()) {
                    urlInput.setText(url)
                    Toast.makeText(this, "URL loaded successfully", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Loaded URL: $url")
                } else {
                    Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show()
                    Log.w(TAG, "File is empty")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file: ${e.message}", e)
            Toast.makeText(this, "Error reading file: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

}
