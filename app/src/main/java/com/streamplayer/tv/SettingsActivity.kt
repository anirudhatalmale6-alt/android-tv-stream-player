package com.streamplayer.tv

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Minimal settings screen for entering/updating the stream URL.
 * Accessible via D-pad ENTER/OK or MENU key from playback screen.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
        const val REQUEST_CODE = 1001
        // Common USB mount paths on Android TV / STB devices
        private val USB_PATHS = listOf(
            "/storage/usb0",
            "/storage/usb1",
            "/storage/USB",
            "/mnt/usb_storage",
            "/mnt/usb",
            "/mnt/media_rw/usb",
            "/sdcard/usb",
            "/storage/udisk",
            "/storage/usbdisk",
            "/storage/external_storage/sda1",
            "/storage/external_storage/sdb1"
        )
        private const val CONFIG_FILENAME = "stream.txt"
    }

    private lateinit var urlInput: EditText
    private lateinit var autoPlaySwitch: Switch
    private lateinit var saveButton: Button
    private lateinit var changePinButton: Button
    private lateinit var loadFromUsbButton: Button

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
            loadStreamUrlFromUsb()
        }

        // Focus the URL input for immediate D-pad editing
        urlInput.requestFocus()
    }

    private fun loadStreamUrlFromUsb() {
        Log.i(TAG, "Searching for stream.txt on USB drives...")

        // Also check dynamically mounted storage
        val storageDirs = mutableListOf<File>()

        // Add known USB paths
        USB_PATHS.forEach { path ->
            storageDirs.add(File(path))
        }

        // Check /storage/ for any mounted volumes
        val storageRoot = File("/storage")
        if (storageRoot.exists() && storageRoot.isDirectory) {
            storageRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "emulated" && dir.name != "self") {
                    storageDirs.add(dir)
                }
            }
        }

        // Check /mnt/ for mounted volumes
        val mntRoot = File("/mnt")
        if (mntRoot.exists() && mntRoot.isDirectory) {
            mntRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && (dir.name.contains("usb", ignoreCase = true) ||
                    dir.name.contains("media", ignoreCase = true))) {
                    storageDirs.add(dir)
                    // Also check subdirectories
                    dir.listFiles()?.forEach { subdir ->
                        if (subdir.isDirectory) {
                            storageDirs.add(subdir)
                        }
                    }
                }
            }
        }

        // Search for stream.txt in all potential locations
        for (dir in storageDirs) {
            val configFile = File(dir, CONFIG_FILENAME)
            Log.d(TAG, "Checking: ${configFile.absolutePath}")

            if (configFile.exists() && configFile.canRead()) {
                try {
                    val url = configFile.readLines().firstOrNull()?.trim()
                    if (!url.isNullOrBlank()) {
                        Log.i(TAG, "Found stream URL in ${configFile.absolutePath}")
                        urlInput.setText(url)
                        Toast.makeText(this, "Loaded URL from USB: $CONFIG_FILENAME", Toast.LENGTH_SHORT).show()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading ${configFile.absolutePath}: ${e.message}")
                }
            }
        }

        Toast.makeText(this, "No $CONFIG_FILENAME found on USB drive", Toast.LENGTH_LONG).show()
        Log.w(TAG, "stream.txt not found in any USB location")
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
