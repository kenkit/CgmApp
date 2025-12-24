package com.myuni.cgmapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var cgmValueTextView: TextView
    private lateinit var arrowTextView: TextView
    private lateinit var sampleAgeTextView: TextView
    
    private val cgmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CgmService.ACTION_CGM_UPDATE -> {
                    val value = intent.getDoubleExtra(CgmService.EXTRA_CGM_VALUE, 0.0)
                    val age = intent.getIntExtra(CgmService.EXTRA_CGM_AGE, 0)
                    cgmValueTextView.text = value.toString()
                    sampleAgeTextView.text = "$age mins ago"
                    updateColors(value, arrowTextView.text.toString())
                }
                CgmService.ACTION_ARROW_UPDATE -> {
                    val arrow = intent.getStringExtra(CgmService.EXTRA_ARROW_VALUE) ?: "→"
                    arrowTextView.text = arrow
                    // Re-run color update when arrow changes
                    val currentValueStr = cgmValueTextView.text.toString()
                    val currentValue = currentValueStr.toDoubleOrNull() ?: 0.0
                    updateColors(currentValue, arrow)
                }
            }
        }
    }

    private fun updateColors(value: Double, arrow: String) {
        val color = when {
            value <= 3.9 || arrow == "↓" -> Color.RED
            value >= 10.0 -> Color.parseColor("#FFA500") // Orange/Yellow
            else -> Color.parseColor("#008000") // Green
        }
        cgmValueTextView.setTextColor(color)
        arrowTextView.setTextColor(color)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                android.util.Log.d("MainActivity", "Permissions granted, starting service")
                startCgmService()
            } else {
                android.util.Log.e("MainActivity", "Permissions denied: ${permissions.filter { !it.value }.keys}")
                Toast.makeText(this, "Permissions required for BLE scan", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "onCreate")
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cgmValueTextView = findViewById(R.id.cgmvalue)
        arrowTextView = findViewById(R.id.arrow)
        sampleAgeTextView = findViewById(R.id.sample_age)

        loadLastValueFromDb()
        checkPermissionsAndStartService()
    }

    private fun loadLastValueFromDb() {
        val dbHelper = DatabaseHelper(this)
        val db = dbHelper.readableDatabase
        val projection = arrayOf(
            GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE,
            GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP
        )
        val sortOrder = "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC"
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder,
            "1"
        )

        with(cursor) {
            if (moveToNext()) {
                val value = getDouble(getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE))
                val timestamp = getLong(getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP))
                val currentTime = Calendar.getInstance().timeInMillis
                val ageInMinutes = (currentTime - timestamp) / (60 * 1000)
                
                cgmValueTextView.text = value.toString()
                sampleAgeTextView.text = "$ageInMinutes mins ago"
                
                // We don't have the last arrow in DB, default to horizontal for coloring if not known
                updateColors(value, "→")
            }
            close()
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(CgmService.ACTION_CGM_UPDATE)
            addAction(CgmService.ACTION_ARROW_UPDATE)
        }
        ContextCompat.registerReceiver(this, cgmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(cgmReceiver)
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // ACCESS_FINE_LOCATION not needed for BLE scan with 'neverForLocation' flag on Android 12+
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            android.util.Log.d("MainActivity", "All permissions granted, starting service directly")
            startCgmService()
        } else {
            android.util.Log.d("MainActivity", "Requesting permissions: $missing")
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCgmService() {
        android.util.Log.d("MainActivity", "Attempting to start CgmService")
        val serviceIntent = Intent(this, CgmService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}