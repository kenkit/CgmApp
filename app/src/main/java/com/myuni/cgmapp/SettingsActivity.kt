package com.myuni.cgmapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val urlEditText = findViewById<TextInputEditText>(R.id.nightscout_url)
        val secretEditText = findViewById<TextInputEditText>(R.id.api_secret)
        val intervalEditText = findViewById<TextInputEditText>(R.id.upload_interval)
        val uploadSwitch = findViewById<SwitchMaterial>(R.id.enable_upload)
        val selectDeviceButton = findViewById<Button>(R.id.select_device_button)
        val saveButton = findViewById<Button>(R.id.save_settings)

        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        
        // Load current settings
        urlEditText.setText(sharedPref.getString("nightscout_url", ""))
        secretEditText.setText(sharedPref.getString("api_secret", ""))
        intervalEditText.setText(sharedPref.getInt("upload_interval", 5).toString())
        uploadSwitch.isChecked = sharedPref.getBoolean("enable_upload", false)

        selectDeviceButton.setOnClickListener {
            startActivity(Intent(this, DeviceSelectionActivity::class.java))
        }

        saveButton.setOnClickListener {
            val url = urlEditText.text.toString().trim()
            val secret = secretEditText.text.toString().trim()
            val intervalStr = intervalEditText.text.toString().trim()
            val enabled = uploadSwitch.isChecked

            if (enabled && (url.isEmpty() || secret.isEmpty())) {
                Toast.makeText(this, "URL and API Secret are required if upload is enabled", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val interval = intervalStr.toIntOrNull() ?: 5

            with(sharedPref.edit()) {
                putString("nightscout_url", url)
                putString("api_secret", secret)
                putInt("upload_interval", interval)
                putBoolean("enable_upload", enabled)
                apply()
            }

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        val selectedName = sharedPref.getString("selected_device_name", "None")
        val selectedMac = sharedPref.getString("selected_device_mac", "")
        
        val textView = findViewById<android.widget.TextView>(R.id.selected_device_text)
        if (selectedName == "None") {
            textView.text = "Selected Device: None"
        } else {
            textView.text = "Selected Device Serial: $selectedName\n($selectedMac)"
        }
    }
}
