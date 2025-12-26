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
import android.widget.TableLayout
import android.widget.TableRow
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
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var cgmValueTextView: TextView
    private lateinit var arrowTextView: TextView
    private lateinit var sampleAgeTextView: TextView
    private lateinit var pendingSamplesTextView: TextView
    private lateinit var nextUploadTextView: TextView
    private lateinit var chart: LineChart
    private lateinit var pendingTable: TableLayout
    private lateinit var pendingUploadsTitleTextView: TextView
    private lateinit var selectedDeviceStatusTextView: TextView
    private var chartMode = 0 // 0 = 24h, 1 = 6h, 2 = History
    private var historyStart: Long = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private var nextUploadTime: Long = 0

    private val cgmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CgmService.ACTION_CGM_UPDATE -> {
                    val value = intent.getDoubleExtra(CgmService.EXTRA_CGM_VALUE, 0.0)
                    val age = intent.getIntExtra(CgmService.EXTRA_CGM_AGE, 0)
                    val rssi = intent.getIntExtra(CgmService.EXTRA_RSSI, 0)
                    cgmValueTextView.text = value.toString()
                    sampleAgeTextView.text = "$age mins ago"
                    updateColors(value, arrowTextView.text.toString())
                    loadChartData() // Refresh chart
                    loadPendingTable() // Refresh table
                    
                    val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
                    val deviceName = sharedPref.getString("selected_device_name", "Unknown")
                    selectedDeviceStatusTextView.text = "Connected: $deviceName (RSSI: $rssi dBm)"
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
                    loadPendingTable() // Refresh table as upload status changed
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
        chart = findViewById(R.id.chart)
        pendingTable = findViewById(R.id.pending_table)
        pendingUploadsTitleTextView = findViewById(R.id.pending_uploads_title)
        selectedDeviceStatusTextView = findViewById(R.id.selected_device_status)
        
        val settingsButton = findViewById<android.widget.ImageButton>(R.id.settings_button)
        val btn24h = findViewById<android.widget.Button>(R.id.btn_last_24h)
        val btn6h = findViewById<android.widget.Button>(R.id.btn_last_6h)
        val btnHistory = findViewById<android.widget.Button>(R.id.btn_history)

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        btn24h.setOnClickListener {
            chartMode = 0
            loadChartData()
        }
        
        btn6h.setOnClickListener {
            chartMode = 1
            loadChartData()
        }
        
        btnHistory.setOnClickListener {
            val c = Calendar.getInstance()
            val year = c.get(Calendar.YEAR)
            val month = c.get(Calendar.MONTH)
            val day = c.get(Calendar.DAY_OF_MONTH)

            android.app.DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
                val cal = Calendar.getInstance()
                cal.set(selectedYear, selectedMonth, selectedDay, 0, 0, 0)
                cal.set(Calendar.MILLISECOND, 0)
                historyStart = cal.timeInMillis
                chartMode = 2
                loadChartData()
            }, year, month, day).show()
        }

        loadLastValueFromDb()
        loadChartData()
        loadPendingTable()
        
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

    private fun loadChartData() {
        val dbHelper = DatabaseHelper(this)
        val db = dbHelper.readableDatabase
        
        var startTime: Long = 0
        var endTime: Long = System.currentTimeMillis()
        
        if (chartMode == 0) {
            // Last 24 hours
            startTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        } else if (chartMode == 1) {
            // Last 6 hours
            startTime = System.currentTimeMillis() - (6 * 60 * 60 * 1000)
        } else {
            // History
            startTime = historyStart
            endTime = historyStart + (24 * 60 * 60 * 1000)
        }
        
        val selection: String
        val selectionArgs: Array<String>
        
        if (chartMode == 2) {
             selection = "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} >= ? AND ${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} < ?"
             selectionArgs = arrayOf(startTime.toString(), endTime.toString())
        } else {
             selection = "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} > ?"
             selectionArgs = arrayOf(startTime.toString())
        }
        
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            arrayOf(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP, GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE),
            selection,
            selectionArgs,
            null, null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} ASC"
        )

        val entries = ArrayList<Entry>()
        val colors = ArrayList<Int>()
        
        while(cursor.moveToNext()) {
            val ts = cursor.getLong(0)
            val value = cursor.getDouble(1)
            entries.add(Entry(ts.toFloat(), value.toFloat()))
            
            // Color coding
            if (value <= 3.9) {
                colors.add(Color.RED)
            } else if (value >= 10.0) {
                colors.add(Color.parseColor("#FFA500")) // Orange
            } else {
                colors.add(Color.parseColor("#008000")) // Green
            }
        }
        cursor.close()

        if (entries.isEmpty()) {
            chart.clear()
            return
        }
        
        chart.visibility = android.view.View.VISIBLE

        val dataSet = LineDataSet(entries, "Glucose (mmol/L)")
        dataSet.color = Color.BLUE
        dataSet.setCircleColors(colors) // Set the list of colors for circles
        dataSet.lineWidth = 2f
        dataSet.circleRadius = 4f
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER

        val lineData = LineData(dataSet)
        chart.data = lineData
        
        // Format X Axis
        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = object : ValueFormatter() {
            private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            override fun getFormattedValue(value: Float): String {
                return sdf.format(Date(value.toLong()))
            }
        }
        
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.invalidate()
    }

    private fun loadPendingTable() {
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        val isUploadEnabled = sharedPref.getBoolean("enable_upload", false)
        
        if (!isUploadEnabled) {
            pendingTable.visibility = android.view.View.GONE
            pendingUploadsTitleTextView.visibility = android.view.View.GONE
            pendingSamplesTextView.visibility = android.view.View.GONE
            nextUploadTextView.visibility = android.view.View.GONE
            return
        }
        pendingTable.visibility = android.view.View.VISIBLE
        pendingUploadsTitleTextView.visibility = android.view.View.VISIBLE
        pendingSamplesTextView.visibility = android.view.View.VISIBLE
        nextUploadTextView.visibility = android.view.View.VISIBLE

        pendingTable.removeAllViews()
        
        // Header
        val headerRow = TableRow(this)
        headerRow.addView(TextView(this).apply { text = "Time"; setPadding(16,16,16,16); setTypeface(null, android.graphics.Typeface.BOLD) })
        headerRow.addView(TextView(this).apply { text = "Value"; setPadding(16,16,16,16); setTypeface(null, android.graphics.Typeface.BOLD) })
        headerRow.setBackgroundColor(Color.LTGRAY)
        pendingTable.addView(headerRow)

        val dbHelper = DatabaseHelper(this)
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            arrayOf(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP, GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE),
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} = 0",
            null,
            null, null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC"
        )

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        while(cursor.moveToNext()) {
            val ts = cursor.getLong(0)
            val value = cursor.getDouble(1)
            
            val row = TableRow(this)
            row.addView(TextView(this).apply { text = sdf.format(Date(ts)); setPadding(16,16,16,16) })
            row.addView(TextView(this).apply { text = String.format("%.1f", value); setPadding(16,16,16,16) })
            pendingTable.addView(row)
        }
        cursor.close()
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
        loadChartData()
        loadPendingTable()
        
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        val deviceName = sharedPref.getString("selected_device_name", null)
        if (deviceName != null) {
            selectedDeviceStatusTextView.text = "Connected: $deviceName"
        } else {
            selectedDeviceStatusTextView.text = "No Device Selected"
        }
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