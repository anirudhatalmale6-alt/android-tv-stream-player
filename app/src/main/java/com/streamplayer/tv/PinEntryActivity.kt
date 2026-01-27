package com.streamplayer.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * PIN entry screen to protect settings access.
 * Requires 6-digit PIN before allowing access to settings.
 */
class PinEntryActivity : AppCompatActivity() {

    private lateinit var pinInput: EditText
    private lateinit var titleText: TextView
    private lateinit var submitButton: Button
    private lateinit var cancelButton: Button

    private var mode = MODE_VERIFY
    private var newPinFirstEntry: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin_entry)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_VERIFY)

        pinInput = findViewById(R.id.pin_input)
        titleText = findViewById(R.id.title_text)
        submitButton = findViewById(R.id.submit_button)
        cancelButton = findViewById(R.id.cancel_button)

        updateUI()

        submitButton.setOnClickListener { onSubmit() }
        cancelButton.setOnClickListener { finish() }

        pinInput.requestFocus()
    }

    private fun updateUI() {
        when (mode) {
            MODE_VERIFY -> {
                titleText.text = "Enter PIN to access settings"
                submitButton.text = "Unlock"
            }
            MODE_CHANGE_CURRENT -> {
                titleText.text = "Enter current PIN"
                submitButton.text = "Continue"
            }
            MODE_CHANGE_NEW -> {
                titleText.text = "Enter new 6-digit PIN"
                submitButton.text = "Continue"
            }
            MODE_CHANGE_CONFIRM -> {
                titleText.text = "Confirm new PIN"
                submitButton.text = "Save PIN"
            }
        }
        pinInput.setText("")
    }

    private fun onSubmit() {
        val pin = pinInput.text.toString().trim()

        if (pin.length != 6 || !pin.all { it.isDigit() }) {
            Toast.makeText(this, "PIN must be exactly 6 digits", Toast.LENGTH_SHORT).show()
            pinInput.setText("")
            return
        }

        when (mode) {
            MODE_VERIFY -> {
                if (StreamPrefs.verifyPin(this, pin)) {
                    // PIN correct - proceed to settings
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    pinInput.setText("")
                }
            }
            MODE_CHANGE_CURRENT -> {
                if (StreamPrefs.verifyPin(this, pin)) {
                    mode = MODE_CHANGE_NEW
                    updateUI()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    pinInput.setText("")
                }
            }
            MODE_CHANGE_NEW -> {
                newPinFirstEntry = pin
                mode = MODE_CHANGE_CONFIRM
                updateUI()
            }
            MODE_CHANGE_CONFIRM -> {
                if (pin == newPinFirstEntry) {
                    StreamPrefs.setPin(this, pin)
                    Toast.makeText(this, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "PINs do not match, try again", Toast.LENGTH_SHORT).show()
                    newPinFirstEntry = null
                    mode = MODE_CHANGE_NEW
                    updateUI()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_MODE = "pin_mode"
        const val MODE_VERIFY = 0
        const val MODE_CHANGE_CURRENT = 1
        const val MODE_CHANGE_NEW = 2
        const val MODE_CHANGE_CONFIRM = 3
    }
}
