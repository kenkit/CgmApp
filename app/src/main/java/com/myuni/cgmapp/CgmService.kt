package com.myuni.cgmapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.welie.blessed.BluetoothCentralManager
import com.welie.blessed.BluetoothCentralManagerCallback
import com.welie.blessed.BluetoothPeripheral
import java.util.UUID

class CgmService : Service() {

    companion object {
        const val CHANNEL_ID = "CgmServiceChannel"
        const val ACTION_CGM_UPDATE = "com.myuni.cgmapp.CGM_UPDATE"
        const val EXTRA_CGM_VALUE = "EXTRA_CGM_VALUE"
        const val EXTRA_CGM_AGE = "EXTRA_CGM_AGE"
        // Scan for 1.25 seconds as requested
        private const val SCAN_DURATION: Long = 5250
        private const val SCAN_INTERVAL: Long = 60* 1000 // 3 minutes
    }

    private lateinit var centralManager: BluetoothCentralManager
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    // Service UUID
    private val SERVICE_UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")

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

        // Based on aidex.cpp:
        // POSITIONAL_CORRECTION = 2
        // glucose = data[POSITIONAL_CORRECTION + 10] => index 12
        // age = data[POSITIONAL_CORRECTION + 1] => index 3
        // phase = data[POSITIONAL_CORRECTION + 9] => index 11
        
        if (data.size >= 13) {
            val glucoseByte = data[12]
            val glucoseVal = (glucoseByte.toInt() and 0xFF) / 10.0
            
            val phase = data[11].toInt() and 0xFF
            val age = (data[3].toInt() and 0xFF) / 6
            
            Log.d("CgmService", "Glucose Found: $glucoseVal, Phase: $phase, Age: $age (mins)")

            // Broadcast the value
            val intent = Intent(ACTION_CGM_UPDATE)
            intent.putExtra(EXTRA_CGM_VALUE, glucoseVal)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } else {
            Log.d("CgmService", "Nordic block too short for glucose data (${data.size} bytes)")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("CgmService", "CgmService onCreate")
        createNotificationChannel()

        try {
            // Initialize Blessed Central Manager
            centralManager = BluetoothCentralManager(this, centralManagerCallback, Handler(Looper.getMainLooper()))
            Log.d("CgmService", "CentralManager initialized")
        } catch (e: Exception) {
            Log.e("CgmService", "Error initializing CentralManager", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CgmService", "CgmService onStartCommand")
        val notification = createNotification()
        startForeground(1, notification)

        startPeriodicScan()

        return START_STICKY
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CGM Service")
            .setContentText("Scanning for CGM values...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "CGM Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
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
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
