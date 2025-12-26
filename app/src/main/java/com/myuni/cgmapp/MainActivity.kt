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
import android.os.Handler
import android.os.Looper
import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var cgmValueTextView: TextView
    private lateinit var arrowTextView: TextView
    private lateinit var sampleAgeTextView: TextView
    private lateinit var pendingSamplesTextView: TextView
    private lateinit var nextUploadTextView: TextView
    
    private val handler = Handler(Looper.getMainLooper())
    private var nextUploadTime: Long = 0

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
                CgmService.ACTION_UPLOAD_STATUS -> {
                    val pendingCount = intent.getIntExtra(CgmService.EXTRA_PENDING_COUNT, 0)
                    nextUploadTime = intent.getLongExtra(CgmService.EXTRA_NEXT_UPLOAD_TIME, 0)
                    pendingSamplesTextView.text = "Pending: $pendingCount"
                    updateNextUploadText()
                }
            }
        }
    }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateNextUploadText()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateNextUploadText() {
        if (nextUploadTime == 0L) {
            nextUploadTextView.text = "Next upload: --:--"
            return
        }

        val now = System.currentTimeMillis()
        val diff = nextUploadTime - now
        
        if (diff <= 0) {
            nextUploadTextView.text = "Next upload: Soon..."
        } else {
            val minutes = diff / (60 * 1000)
            val seconds = (diff / 1000) % 60
            nextUploadTextView.text = String.format(Locale.getDefault(), "Next upload: %02d:%02d", minutes, seconds)
        }
    }

    private fun updateColors(value: Double, arrow: String) {
        val color = when {
            value <= 3.9 || arrow == "↓" || arrow == "↓↓" -> Color.RED
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
        pendingSamplesTextView = findViewById(R.id.pending_samples)
        nextUploadTextView = findViewById(R.id.next_upload)
        
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.settings_button)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        loadLastValueFromDb()
        
        // Start service if widget is present
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val ids = appWidgetManager.getAppWidgetIds(ComponentName(this, CgmWidget::class.java))
        if (ids.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Widget present, ensuring service is started")
            startCgmService()
        }

        checkPermissionsAndStartService()
        
        handler.post(updateTimeRunnable)
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

        // Count pending
        val pendingCursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${GlucoseContract.GlucoseEntry.TABLE_NAME} WHERE ${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} = 0",
            null
        )
        if (pendingCursor.moveToFirst()) {
            val count = pendingCursor.getInt(0)
            pendingSamplesTextView.text = "Pending: $count"
        }
        pendingCursor.close()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(CgmService.ACTION_CGM_UPDATE)
            addAction(CgmService.ACTION_ARROW_UPDATE)
            addAction(CgmService.ACTION_UPLOAD_STATUS)
        }
        ContextCompat.registerReceiver(this, cgmReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.post(updateTimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(cgmReceiver)
        handler.removeCallbacks(updateTimeRunnable)
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