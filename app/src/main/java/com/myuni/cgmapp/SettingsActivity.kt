package com.myuni.cgmapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val healthConnectManager by lazy { HealthConnectManager(this) }

    private val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

    private val requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
        if (granted.containsAll(healthConnectManager.getWritePermissions())) {
            Log.d("SettingsActivity", "Health Connect permissions granted")
            findViewById<Button>(R.id.grant_health_permissions).visibility = View.GONE
            Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Log.w("SettingsActivity", "Health Connect permissions denied")
            Toast.makeText(this, "Permissions denied. Please try 'Open Settings' if the dialog didn't appear.", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestHealthConnectPermissions() {
        val status = healthConnectManager.getStatus()
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val message = when (status) {
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update"
                HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect is not available"
                else -> "Health Connect status unknown: $status"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                if (!healthConnectManager.hasWritePermission()) {
                    requestPermissions.launch(healthConnectManager.getWritePermissions())
                } else {
                    findViewById<Button>(R.id.grant_health_permissions).visibility = View.GONE
                    Toast.makeText(this@SettingsActivity, "Permissions already granted", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error requesting Health Connect permissions", e)
                Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openHealthConnectSettings() {
        try {
            val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Could not open Health Connect settings", e)
            Toast.makeText(this, "Could not open Health Connect settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateGrantButtonVisibility(isEnabled: Boolean) {
        val grantButton = findViewById<Button>(R.id.grant_health_permissions)
        val settingsButton = findViewById<Button>(R.id.open_health_settings)
        
        if (isEnabled) {
            settingsButton.visibility = View.VISIBLE
            val status = healthConnectManager.getStatus()
            if (status == HealthConnectClient.SDK_AVAILABLE) {
                lifecycleScope.launch {
                    if (!healthConnectManager.hasWritePermission()) {
                        grantButton.visibility = View.VISIBLE
                    } else {
                        grantButton.visibility = View.GONE
                    }
                }
            } else {
                grantButton.visibility = View.VISIBLE
            }
        } else {
            grantButton.visibility = View.GONE
            settingsButton.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val urlEditText = findViewById<TextInputEditText>(R.id.nightscout_url)
        val secretEditText = findViewById<TextInputEditText>(R.id.api_secret)
        
        val uploadIntervalLabel = findViewById<TextView>(R.id.upload_interval_label)
        val uploadIntervalSlider = findViewById<Slider>(R.id.upload_interval_slider)
        
        val scanDurationLabel = findViewById<TextView>(R.id.scan_duration_label)
        val scanDurationSlider = findViewById<Slider>(R.id.scan_duration_slider)
        
        val scanIntervalLabel = findViewById<TextView>(R.id.scan_interval_label)
        val scanIntervalSlider = findViewById<Slider>(R.id.scan_interval_slider)
        
        val uploadSwitch = findViewById<SwitchMaterial>(R.id.enable_upload)
        val healthConnectSwitch = findViewById<SwitchMaterial>(R.id.enable_health_connect)
        val grantButton = findViewById<Button>(R.id.grant_health_permissions)
        val settingsButton = findViewById<Button>(R.id.open_health_settings)
        val selectDeviceButton = findViewById<Button>(R.id.select_device_button)

        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        
        // Load current settings
        urlEditText.setText(sharedPref.getString("nightscout_url", ""))
        secretEditText.setText(sharedPref.getString("api_secret", ""))
        
        val savedInterval = sharedPref.getInt("upload_interval", 5)
        uploadIntervalSlider.value = savedInterval.toFloat().coerceIn(uploadIntervalSlider.valueFrom, uploadIntervalSlider.valueTo)
        uploadIntervalLabel.text = "Upload Interval: $savedInterval minutes"
        
        val savedDuration = sharedPref.getInt("scan_duration", 5)
        scanDurationSlider.value = savedDuration.toFloat().coerceIn(scanDurationSlider.valueFrom, scanDurationSlider.valueTo)
        scanDurationLabel.text = "Scan Duration: $savedDuration seconds"
        
        val savedScanInterval = sharedPref.getInt("scan_interval", 1)
        scanIntervalSlider.value = savedScanInterval.toFloat().coerceIn(scanIntervalSlider.valueFrom, scanIntervalSlider.valueTo)
        scanIntervalLabel.text = "Scan Interval: $savedScanInterval minutes"
        
        val healthEnabled = sharedPref.getBoolean("enable_health_connect", false)
        uploadSwitch.isChecked = sharedPref.getBoolean("enable_upload", false)
        healthConnectSwitch.isChecked = healthEnabled
        
        updateGrantButtonVisibility(healthEnabled)

        // Helper to save current state
        fun saveSettings() {
            with(sharedPref.edit()) {
                putString("nightscout_url", urlEditText.text.toString().trim())
                putString("api_secret", secretEditText.text.toString().trim())
                putInt("upload_interval", uploadIntervalSlider.value.toInt())
                putInt("scan_duration", scanDurationSlider.value.toInt())
                putInt("scan_interval", scanIntervalSlider.value.toInt())
                putBoolean("enable_upload", uploadSwitch.isChecked)
                putBoolean("enable_health_connect", healthConnectSwitch.isChecked)
                apply()
            }
        }

        // Text change listeners for auto-save
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                saveSettings()
            }
        }
        urlEditText.addTextChangedListener(textWatcher)
        secretEditText.addTextChangedListener(textWatcher)

        // Slider listeners for UI update and auto-save
        uploadIntervalSlider.addOnChangeListener { _, value, _ ->
            uploadIntervalLabel.text = "Upload Interval: ${value.toInt()} minutes"
            saveSettings()
        }
        
        scanDurationSlider.addOnChangeListener { _, value, _ ->
            scanDurationLabel.text = "Scan Duration: ${value.toInt()} seconds"
            saveSettings()
        }
        
        scanIntervalSlider.addOnChangeListener { _, value, _ ->
            scanIntervalLabel.text = "Scan Interval: ${value.toInt()} minutes"
            saveSettings()
        }

        // Switch listeners for auto-save
        uploadSwitch.setOnCheckedChangeListener { _, _ ->
            saveSettings()
        }

        healthConnectSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSettings()
            updateGrantButtonVisibility(isChecked)
            if (isChecked) {
                checkAndRequestHealthConnectPermissions()
            }
        }

        grantButton.setOnClickListener {
            checkAndRequestHealthConnectPermissions()
        }

        settingsButton.setOnClickListener {
            openHealthConnectSettings()
        }

        selectDeviceButton.setOnClickListener {
            startActivity(Intent(this, DeviceSelectionActivity::class.java))
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
        
        val healthEnabled = sharedPref.getBoolean("enable_health_connect", false)
        updateGrantButtonVisibility(healthEnabled)
    }
}