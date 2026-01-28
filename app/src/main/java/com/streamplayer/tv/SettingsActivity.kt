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
        // Common USB mount paths on Android TV / STB devices (including Amlogic X98Q)
        private val USB_PATHS = listOf(
            // Amlogic devices (X98Q, etc.)
            "/storage/udisk0",
            "/storage/udisk1",
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usb-storage",
            "/storage/USB",
            "/storage/usbdisk",
            "/storage/usbdisk0",
            "/storage/udisk",
            "/storage/external_storage/udisk0",
            "/storage/external_storage/sda1",
            "/storage/external_storage/sdb1",
            // Generic Android paths
            "/mnt/usb_storage",
            "/mnt/usb",
            "/mnt/usb0",
            "/mnt/usb1",
            "/mnt/media_rw",
            "/mnt/media_rw/usb",
            "/mnt/sdcard/usb",
            // Other common paths
            "/sdcard/usb",
            "/data/usb"
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

        // Collect all potential storage directories
        val storageDirs = mutableSetOf<File>()
        val checkedPaths = mutableListOf<String>()

        // Add known USB paths
        USB_PATHS.forEach { path ->
            storageDirs.add(File(path))
        }

        // Scan /storage/ for any mounted volumes (this catches UUID-named mounts like /storage/1234-ABCD)
        val storageRoot = File("/storage")
        if (storageRoot.exists() && storageRoot.isDirectory) {
            storageRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "emulated" && dir.name != "self") {
                    storageDirs.add(dir)
                    Log.d(TAG, "Found storage mount: ${dir.absolutePath}")
                }
            }
        }

        // Scan /mnt/ for mounted volumes
        val mntRoot = File("/mnt")
        if (mntRoot.exists() && mntRoot.isDirectory) {
            mntRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    storageDirs.add(dir)
                    // Check subdirectories (e.g., /mnt/media_rw/XXXX)
                    dir.listFiles()?.forEach { subdir ->
                        if (subdir.isDirectory) {
                            storageDirs.add(subdir)
                        }
                    }
                }
            }
        }

        // Also scan /mnt/media_rw/ which is common on Android TV
        val mediaRwRoot = File("/mnt/media_rw")
        if (mediaRwRoot.exists() && mediaRwRoot.isDirectory) {
            mediaRwRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    storageDirs.add(dir)
                    Log.d(TAG, "Found media_rw mount: ${dir.absolutePath}")
                }
            }
        }

        // Search for stream.txt in all potential locations
        for (dir in storageDirs) {
            if (!dir.exists() || !dir.isDirectory) continue

            val configFile = File(dir, CONFIG_FILENAME)
            checkedPaths.add(dir.absolutePath)
            Log.d(TAG, "Checking: ${configFile.absolutePath}")

            if (configFile.exists() && configFile.canRead()) {
                try {
                    val url = configFile.readLines().firstOrNull()?.trim()
                    if (!url.isNullOrBlank()) {
                        Log.i(TAG, "Found stream URL in ${configFile.absolutePath}")
                        urlInput.setText(url)
                        Toast.makeText(this, "Loaded from: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading ${configFile.absolutePath}: ${e.message}")
                }
            }
        }

        // Show which paths were checked (helpful for debugging)
        val existingPaths = checkedPaths.filter { File(it).exists() }
        Log.w(TAG, "stream.txt not found. Checked ${checkedPaths.size} paths. Existing: $existingPaths")

        if (existingPaths.isNotEmpty()) {
            Toast.makeText(this, "No stream.txt found. Checked: ${existingPaths.joinToString(", ")}", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "No USB drive detected", Toast.LENGTH_LONG).show()
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
