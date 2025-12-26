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
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        const val ACTION_UPLOAD_STATUS = "com.myuni.cgmapp.UPLOAD_STATUS"
        const val EXTRA_CGM_VALUE = "EXTRA_CGM_VALUE"
        const val EXTRA_ARROW_VALUE = "EXTRA_ARROW_VALUE"
        const val EXTRA_CGM_AGE = "EXTRA_CGM_AGE"
        const val EXTRA_RSSI = "EXTRA_RSSI"
        const val EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME"
        const val EXTRA_PENDING_COUNT = "EXTRA_PENDING_COUNT"
        const val EXTRA_NEXT_UPLOAD_TIME = "EXTRA_NEXT_UPLOAD_TIME"
        // Scan for 3.25 seconds as requested
        private const val SCAN_DURATION: Long = 5000
        private const val SCAN_INTERVAL: Long = 60 * 1000 // 5 minutes
    }

    private lateinit var centralManager: BluetoothCentralManager
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var last_cgm_value = 0.0
    private var wakeLock: PowerManager.WakeLock? = null
    // Service UUID
    private val SERVICE_UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var notificationService: PersistentNotificationService
    private val client = OkHttpClient()
    private val gson = Gson()
    private val isoFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"))
    private var jwtToken: String? = null
    private var nextUploadTime: Long = 0

    data class NightscoutEntry(
        val type: String = "sgv",
        val dateString: String,
        val date: Long,
        val sgv: Int,
        val direction: String,
        val noise: Int = 1,
        val filtered: Int = 0,
        val unfiltered: Int = 0,
        val rssi: Int = 100
    )

    data class AuthResponse(
        val token: String,
        val exp: Long
    )

    private fun mapArrowToDirection(arrow: String): String {
        return when (arrow) {
            "↑↑" -> "DoubleUp"
            "↑"  -> "SingleUp"
            "↗"  -> "FortyFiveUp"
            "→"  -> "Flat"
            "↘"  -> "FortyFiveDown"
            "↓"  -> "SingleDown"
            "↓↓" -> "DoubleDown"
            else -> "None"
        }
    }

    private fun isTokenExpired(): Boolean {
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        val exp = sharedPref.getLong("jwt_exp", 0)
        // Check if token expires in the next 60 seconds to be safe
        return (System.currentTimeMillis() / 1000) > (exp - 60)
    }

    private fun getPendingCount(): Int {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT COUNT(*) FROM ${GlucoseContract.GlucoseEntry.TABLE_NAME} WHERE ${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} = 0",
            null
        )
        var count = 0
        if (cursor.moveToFirst()) {
            count = cursor.getInt(0)
        }
        cursor.close()
        return count
    }

    private fun broadcastUploadStatus() {
        val count = getPendingCount()
        val intent = Intent(ACTION_UPLOAD_STATUS)
        intent.putExtra(EXTRA_PENDING_COUNT, count)
        intent.putExtra(EXTRA_NEXT_UPLOAD_TIME, nextUploadTime)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun uploadToNightscout() {
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        if (!sharedPref.getBoolean("enable_upload", false)) return

        val url = sharedPref.getString("nightscout_url", "") ?: ""
        val apiSecret = sharedPref.getString("api_secret", "") ?: ""

        if (url.isEmpty() || apiSecret.isEmpty()) return

        jwtToken = sharedPref.getString("jwt_token", null)

        // If we don't have a token or it's expired, fetch it first
        if (jwtToken == null || isTokenExpired()) {
            fetchJwtAndUpload(url, apiSecret)
            return
        }

        performUpload(url)
    }

    private fun fetchJwtAndUpload(url: String, apiSecret: String) {
        val authUrl = "${url.trimEnd('/')}/api/v2/authorization/request/$apiSecret"
        val request = Request.Builder()
            .url(authUrl)
            .get()
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("CgmService", "Nightscout authorization failed", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        try {
                            val authResponse = gson.fromJson(body, AuthResponse::class.java)
                            jwtToken = authResponse.token
                            
                            // Save to preferences
                            val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
                            with(sharedPref.edit()) {
                                putString("jwt_token", authResponse.token)
                                putLong("jwt_exp", authResponse.exp)
                                apply()
                            }
                            
                            Log.d("CgmService", "Nightscout authorization successful, expires at ${authResponse.exp}")
                            // Now perform the actual upload
                            performUpload(url)
                        } catch (e: Exception) {
                            Log.e("CgmService", "Error parsing auth response", e)
                        }
                    } else {
                        Log.e("CgmService", "Auth request failed: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e("CgmService", "Error in auth response callback", e)
                } finally {
                    response.close()
                }
            }
        })
    }

    private fun performUpload(url: String) {
        val token = jwtToken ?: return
        
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
            val rssi = cursor.getInt(cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_RSSI))
            val arrowSymbol = cursor.getString(cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_DIRECTION)) ?: "→"
            
            val valueMgdl = (valueMmol * 18.0182).toInt()
            val dateString = isoFormatter.format(Instant.ofEpochMilli(timestamp))
            
            entries.add(NightscoutEntry(
                sgv = valueMgdl, 
                date = timestamp, 
                dateString = dateString,
                direction = mapArrowToDirection(arrowSymbol),
                rssi = rssi
            ))
            timestampsToMark.add(timestamp)
        }
        cursor.close()

        if (entries.isEmpty()) {
            // Even if empty, we might want to update status to show 0 pending
            broadcastUploadStatus()
            return
        }

        val json = gson.toJson(entries)
        val body = json.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/api/v1/entries")
            .post(body)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e("CgmService", "Nightscout upload failed", e)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    if (response.code == 401) {
                        Log.w("CgmService", "JWT expired or invalid, clearing from prefs and retrying next time")
                        jwtToken = null
                        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
                        with(sharedPref.edit()) {
                            remove("jwt_token")
                            remove("jwt_exp")
                            apply()
                        }
                    } else if (response.isSuccessful) {
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
                        // Update UI with new pending count (should be 0 or close to it)
                        broadcastUploadStatus()
                    } else {
                        Log.e("CgmService", "Nightscout upload failed with code: ${response.code} ${response.message}")
                    }
                } catch (e: Exception) {
                    Log.e("CgmService", "Error in upload response callback", e)
                } finally {
                    response.close()
                }
            }
        })
    }

    private val nightscoutRunnable = object : Runnable {
        override fun run() {
            uploadToNightscout()
            val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
            val intervalMins = sharedPref.getInt("upload_interval", 5)
            val delayMillis = intervalMins * 60 * 1000L
            nextUploadTime = System.currentTimeMillis() + delayMillis
            broadcastUploadStatus()
            handler.postDelayed(this, delayMillis)
        }
    }

    private fun startPeriodicNightscoutUpload() {
        // Initial run
        handler.post(nightscoutRunnable)
    }

    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
            val selectedMac = sharedPref.getString("selected_device_mac", "")

            if (!selectedMac.isNullOrEmpty() && peripheral.address != selectedMac) {
                // Log.d("CgmService", "Ignoring device ${peripheral.address} (Selected: $selectedMac)")
                return
            }

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
                                processNordicData(data, scanResult.rssi)
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

    private fun calculateVelocity(readings: List<Pair<Double, Long>>): Double {
        if (readings.size < 2) return 0.0

        val n = readings.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0

        // Use the oldest reading as the time origin (x=0) to keep numbers small
        val originTs = readings.last().second

        for (reading in readings) {
            val x = (reading.second - originTs).toDouble() / (60 * 1000) // Minutes from origin
            val y = reading.first // mmol/L
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
        }

        val denominator = (n * sumX2 - sumX * sumX)
        if (denominator == 0.0) return 0.0

        return (n * sumXY - sumX * sumY) / denominator
    }

    private fun loadRecentCgmData(limit: Int): List<Pair<Double, Long>> {
        val db = dbHelper.readableDatabase
        val projection = arrayOf(
            GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE,
            GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP
        )
        val sortOrder = "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC"
        val result = mutableListOf<Pair<Double, Long>>()
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            projection,
            null,
            null,
            null,
            null,
            sortOrder,
            limit.toString()
        )

        with(cursor) {
            while (moveToNext()) {
                val value = getDouble(getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE))
                val ts = getLong(getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP))
                result.add(Pair(value, ts))
            }
            close()
        }
        return result
    }

    private fun processNordicData(data: ByteArray, rssi: Int) {
        val hexString = data.joinToString(separator = " ") { String.format("%02X", it) }
        Log.d("CgmService", "Processing Nordic Mfg Data: $hexString")

        if (data.size >= 13) {
            val glucoseByte = data[12]
            val glucoseVal = (glucoseByte.toInt() and 0xFF) / 10.0

            val phase = data[11].toInt() and 0xFF
            val ageInMinutes = (data[3].toInt() and 0xFF) / 6
            
            // Calculate timestamp based on age
            val currentTime = Calendar.getInstance().timeInMillis
            val currentTimestamp = currentTime - (ageInMinutes * 60 * 1000)

            // Fetch last 3 readings + current 1 = 4 points for regression (15 min window)
            val readings = loadRecentCgmData(3).toMutableList()
            readings.add(0, Pair(glucoseVal, currentTimestamp))
            
            val velocity = calculateVelocity(readings)
            
            // Thresholds in mmol/L per minute
            val arrow = when {
                velocity >= 0.166 -> "↑↑" // DoubleUp (>3 mg/dL/min)
                velocity >= 0.111 -> "↑"  // SingleUp (>2 mg/dL/min)
                velocity >= 0.055 -> "↗"  // FortyFiveUp (>1 mg/dL/min)
                velocity <= -0.166 -> "↓↓" // DoubleDown
                velocity <= -0.111 -> "↓"  // SingleDown
                velocity <= -0.055 -> "↘"  // FortyFiveDown
                else -> "→"               // Flat
            }
            last_cgm_value = glucoseVal

            Log.d("CgmService", "Glucose: $glucoseVal, Velocity: ${String.format("%.3f", velocity)}, Arrow: $arrow, RSSI: $rssi")

            // Save to database
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP, currentTimestamp)
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE, glucoseVal)
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_RSSI, rssi)
                put(GlucoseContract.GlucoseEntry.COLUMN_NAME_DIRECTION, arrow)
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
            intent.putExtra(EXTRA_RSSI, rssi)
            intent.setPackage(packageName)
            sendBroadcast(intent)

            // Broadcast the arrow
            val arrowIntent = Intent(ACTION_ARROW_UPDATE)
            arrowIntent.putExtra(EXTRA_ARROW_VALUE, arrow)
            arrowIntent.setPackage(packageName)
            sendBroadcast(arrowIntent)

            // Notify about new pending count (it increased by 1)
            broadcastUploadStatus()

            // Trigger Nightscout upload
            uploadToNightscout()

        } else {
            Log.d("CgmService", "Nordic block too short for glucose data (${data.size} bytes)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CgmService", "CgmService onCreate")
        notificationService = PersistentNotificationService(this)
        dbHelper = DatabaseHelper(this)
        
        val lastData = loadRecentCgmData(1)
        last_cgm_value = if (lastData.isNotEmpty()) lastData[0].first else 0.0

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CgmApp::ScanWakeLock")

        try {
            // Initialize Blessed Central Manager
            centralManager = BluetoothCentralManager(this, centralManagerCallback, Handler(Looper.getMainLooper()))
            Log.d("CgmService", "CentralManager initialized")
        } catch (e: Exception) {
            Log.e("CgmService", "Error initializing CentralManager", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CgmService", "CgmService onStartCommand - Attempting to start foreground")
        
        val notification = notificationService.getInitialNotification(dbHelper)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PersistentNotificationService.NOTIFICATION_ID, 
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(PersistentNotificationService.NOTIFICATION_ID, notification)
            }
            Log.d("CgmService", "startForeground successful")
        } catch (e: Exception) {
            Log.e("CgmService", "Failed to start foreground service", e)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
                e is android.app.ForegroundServiceStartNotAllowedException) {
                // We are not allowed to start foreground. Stop the service.
                stopSelf()
                return START_NOT_STICKY
            }
        }

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
        
        val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
        val selectedMac = sharedPref.getString("selected_device_mac", null)
        
        if (selectedMac.isNullOrEmpty()) {
            Log.d("CgmService", "No device selected, skipping scan.")
            return
        }

        Log.d("CgmService", "Starting scan cycle...")
        try {
            // Acquire wake lock to ensure CPU doesn't sleep during scan
            wakeLock?.acquire(SCAN_DURATION + 1000)

            // Scan for peripherals with our specific Service UUID
            centralManager.scanForPeripheralsWithServices(listOf(SERVICE_UUID))
            isScanning = true

            // Stop scanning after SCAN_DURATION
            handler.postDelayed({
                stopScan()
            }, SCAN_DURATION)
        } catch (e: Exception) {
            Log.e("CgmService", "Error starting scan", e)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        if (!::centralManager.isInitialized) return
        
        Log.d("CgmService", "Stopping scan.")
        centralManager.stopScan()
        isScanning = false

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
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