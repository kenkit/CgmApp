package com.myuni.cgmapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanResult
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import java.util.Calendar
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson

class CgmService : Service() {

    companion object {
        const val CHANNEL_ID = "CgmServiceChannel"
        const val ACTION_CGM_UPDATE = "com.myuni.cgmapp.CGM_UPDATE"
        const val ACTION_ARROW_UPDATE = "com.myuni.cgmapp.ARROW_UPDATE"
        const val EXTRA_CGM_VALUE = "EXTRA_CGM_VALUE"
        const val EXTRA_ARROW_VALUE = "EXTRA_ARROW_VALUE"
        const val EXTRA_CGM_AGE = "EXTRA_CGM_AGE"
        // Scan for 1.25 seconds as requested
        private const val SCAN_DURATION: Long = 5250
        private const val SCAN_INTERVAL: Long = 60 * 1000 // 1 minute
    }

    private lateinit var centralManager: BluetoothCentralManager
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var last_cgm_value = 0.0
    // Service UUID
    private val SERVICE_UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var notificationService: PersistentNotificationService
    private val client = OkHttpClient()
    private val gson = Gson()

    data class NightscoutEntry(
        val type: String = "sgv",
        val sgv: Int, // sgv should be in mg/dL for many NS consumers, but many accept mmol/L * 18
        val date: Long,
        val direction: String
    )

    private fun mapArrowToDirection(arrow: String): String {
        return when (arrow) {
            "↑" -> "DoubleUp"
            "↗" -> "SingleUp"
            "→" -> "Flat"
            "↘" -> "SingleDown"
            "↓" -> "DoubleDown"
            else -> "None"
        }
    }

    private fun uploadToNightscout() {
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        if (!sharedPref.getBoolean("enable_upload", false)) return

        val url = sharedPref.getString("nightscout_url", "") ?: ""
        val apiSecret = sharedPref.getString("api_secret", "") ?: ""

        if (url.isEmpty() || apiSecret.isEmpty()) return

        val db = dbHelper.readableDatabase
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} = 0",
            null,
            null, null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} ASC"
        )

        val entries = mutableListOf<NightscoutEntry>()
        val timestampsToMark = mutableListOf<Long>()

        while (cursor.moveToNext()) {
            val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP))
            val valueMmol = cursor.getDouble(cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE))
            
            // Nightscout SGV is typically mg/dL
            val valueMgdl = (valueMmol * 18.0182).toInt()
            
            entries.add(NightscoutEntry(sgv = valueMgdl, date = timestamp, direction = "None"))
            timestampsToMark.add(timestamp)
        }
        cursor.close()

        if (entries.isEmpty()) return

        val json = gson.toJson(entries)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/api/v1/entries")
            .post(body)
            .addHeader("api-secret", apiSecret)
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("CgmService", "Nightscout upload failed", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    Log.d("CgmService", "Nightscout upload successful, marking ${timestampsToMark.size} entries as uploaded")
                    val writeDb = dbHelper.writableDatabase
                    writeDb.beginTransaction()
                    try {
                        val values = ContentValues().apply {
                            put(GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED, 1)
                        }
                        for (ts in timestampsToMark) {
                            writeDb.update(
                                GlucoseContract.GlucoseEntry.TABLE_NAME,
                                values,
                                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} = ?",
                                arrayOf(ts.toString())
                            )
                        }
                        writeDb.setTransactionSuccessful()
                    } finally {
                        writeDb.endTransaction()
                    }
                } else {
                    Log.e("CgmService", "Nightscout upload failed with code: ${response.code} ${response.message}")
                }
                response.close()
            }
        })
    }

    private val nightscoutRunnable = object : Runnable {
        override fun run() {
            uploadToNightscout()
            val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
            val intervalMins = sharedPref.getInt("upload_interval", 5)
            handler.postDelayed(this, intervalMins * 60 * 1000L)
        }
    }

    private fun startPeriodicNightscoutUpload() {
        handler.post(nightscoutRunnable)
    }

    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            Log.d("CgmService", "Discovered: ${peripheral.name} (${peripheral.address})")

            val record = scanResult.scanRecord
            if (record != null) {
                // Log Raw Bytes
                val rawBytes = record.bytes
                if (rawBytes != null) {
                    val rawHex = rawBytes.joinToString(" ") { String.format("%02X", it) }
                    Log.d("CgmService", "Raw Scan Record: $rawHex")

                    // Manually parse raw bytes to find all manufacturer data blocks
                    // Android's ScanRecord.getManufacturerSpecificData() (SparseArray)
                    // overwrites if multiple blocks have the same Company ID.
                    val allMfgData = extractManufacturerData(rawBytes)
                    for (data in allMfgData) {
                        if (data.size >= 2) {
                            val id = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
                            if (id == 0x0059) {
                                processNordicData(data)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Extracts all Manufacturer Specific Data blocks (Type 0xFF) from raw scan record.
     * Each returned ByteArray starts with the 2-byte Company Identifier.
     */
    private fun extractManufacturerData(rawBytes: ByteArray): List<ByteArray> {
        val blocks = mutableListOf<ByteArray>()
        var i = 0
        while (i < rawBytes.size - 2) {
            val len = rawBytes[i].toInt() and 0xFF
            if (len == 0) break
            if (i + len >= rawBytes.size) break

            val type = rawBytes[i + 1].toInt() and 0xFF
            if (type == 0xFF && len >= 3) {
                // It's manufacturer data.
                // Copy from index i+2 (Company ID) for (len-1) bytes
                val data = rawBytes.copyOfRange(i + 2, i + len + 1)
                blocks.add(data)
            }
            i += len + 1
        }
        return blocks
    }

    private fun processNordicData(data: ByteArray) {
        val hexString = data.joinToString(separator = " ") { String.format("%02X", it) }
        Log.d("CgmService", "Processing Nordic Mfg Data: $hexString")

        // Fetch last value from DB for comparison
        val dbLastValue = loadLastCgmValue()

        // Based on aidex.cpp:
        // POSITIONAL_CORRECTION = 2
        // glucose = data[POSITIONAL_CORRECTION + 10] => index 12
        // age = data[POSITIONAL_CORRECTION + 1] => index 3
        // phase = data[POSITIONAL_CORRECTION + 9] => index 11
        
        if (data.size >= 13) {
            val glucoseByte = data[12]
            val glucoseVal = (glucoseByte.toInt() and 0xFF) / 10.0

            val phase = data[11].toInt() and 0xFF
            val ageInMinutes = (data[3].toInt() and 0xFF) / 6
            var arrow = ""
            
            val diff = if (dbLastValue > 0) glucoseVal - dbLastValue else 0.0
            
            // Thresholds for mmol/L per minute (assuming ~1 min intervals)
            arrow = when {
                diff >= 0.11 -> "↑"      // Rising fast
                diff >= 0.06 -> "↗"      // Rising slowly
                diff <= -0.11 -> "↓"     // Falling fast
                diff <= -0.06 -> "↘"     // Falling slowly
                else -> "→"              // Stable
            }
            last_cgm_value = glucoseVal

            Log.d("CgmService", "Glucose Found: $glucoseVal, diff: $diff, arrow: $arrow,  Phase: $phase, Age: $ageInMinutes (mins)")

            // Calculate timestamp based on age
            val currentTime = Calendar.getInstance().timeInMillis
            val timestamp = currentTime - (ageInMinutes * 60 * 1000)

            // Save to database
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP, timestamp)
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE, glucoseVal)
            }
            db.insertWithOnConflict(GlucoseContract.GlucoseEntry.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE)

            // Update Notification
            notificationService.updateNotification(glucoseVal, arrow, ageInMinutes)
            
            // Update Widget
            CgmWidget.updateWidget(this, glucoseVal, arrow, ageInMinutes)

            // Broadcast the value
            val intent = Intent(ACTION_CGM_UPDATE)
            intent.putExtra(EXTRA_CGM_VALUE, glucoseVal)
            intent.putExtra(EXTRA_CGM_AGE, ageInMinutes)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            // Broadcast the arrow
            val arrowIntent = Intent(ACTION_ARROW_UPDATE)
            arrowIntent.putExtra(EXTRA_ARROW_VALUE, arrow)
            arrowIntent.setPackage(packageName)
            sendBroadcast(arrowIntent)

        } else {
            Log.d("CgmService", "Nordic block too short for glucose data (${data.size} bytes)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CgmService", "CgmService onCreate")
        notificationService = PersistentNotificationService(this)
        dbHelper = DatabaseHelper(this)
        last_cgm_value = loadLastCgmValue()

        try {
            // Initialize Blessed Central Manager
            centralManager = BluetoothCentralManager(this, centralManagerCallback, Handler(Looper.getMainLooper()))
            Log.d("CgmService", "CentralManager initialized")
        } catch (e: Exception) {
            Log.e("CgmService", "Error initializing CentralManager", e)
        }
    }

    private fun loadLastCgmValue(): Double {
        val db = dbHelper.readableDatabase
        val projection = arrayOf(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE)
        val sortOrder = "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC"
        var lastValue = 0.0
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
                lastValue = getDouble(getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE))
                Log.d("CgmService", "Loaded last CGM value from DB: $lastValue")
            }
            close()
        }
        return lastValue
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CgmService", "CgmService onStartCommand - Attempting to start foreground")
        
        val notification = notificationService.getInitialNotification(dbHelper)
        Log.d("CgmService", "Got initial notification, calling startForeground")
        startForeground(PersistentNotificationService.NOTIFICATION_ID, notification)

        startPeriodicScan()
        startPeriodicNightscoutUpload()

        return START_STICKY
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            startScan()
            // Schedule next scan after INTERVAL
            handler.postDelayed(this, SCAN_INTERVAL)
        }
    }

    private fun startPeriodicScan() {
        // Run immediately
        handler.post(scanRunnable)
    }

    private fun startScan() {
        if (isScanning) return
        if (!::centralManager.isInitialized) {
            Log.e("CgmService", "CentralManager not initialized, cannot scan")
            return
        }

        Log.d("CgmService", "Starting scan cycle...")
        try {
            // Scan for peripherals with our specific Service UUID
            centralManager.scanForPeripheralsWithServices(listOf(SERVICE_UUID))
            isScanning = true

            // Stop scanning after SCAN_DURATION
            handler.postDelayed({
                stopScan()
            }, SCAN_DURATION)
        } catch (e: Exception) {
            Log.e("CgmService", "Error starting scan", e)
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        if (!::centralManager.isInitialized) return
        
        Log.d("CgmService", "Stopping scan.")
        centralManager.stopScan()
        isScanning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        handler.removeCallbacks(scanRunnable)
        handler.removeCallbacks(nightscoutRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
