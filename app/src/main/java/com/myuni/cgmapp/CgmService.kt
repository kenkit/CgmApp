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
        // Scan for 1.25 seconds as requested
        private const val SCAN_DURATION: Long = 1000
        private const val SCAN_INTERVAL: Long = 1 * 60 * 1000 // 3 minutes
    }

    private lateinit var centralManager: BluetoothCentralManager
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    // Service UUID
    private val SERVICE_UUID = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")

    private val centralManagerCallback = object : BluetoothCentralManagerCallback() {
        override fun onDiscovered(peripheral: BluetoothPeripheral, scanResult: ScanResult) {
            // Parse manufacturer data from the native ScanResult
            val record = scanResult.scanRecord
            if (record != null) {
                val manufacturerData = record.manufacturerSpecificData
                if (manufacturerData != null && manufacturerData.size() > 0) {
                     processManufacturerData(manufacturerData)
                }
            }
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
            //centralManager.scanForPeripheralsWithAddresses(listOf("EE:1E:D0:FA:05:39"))
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

    private fun processManufacturerData(manufacturerData: android.util.SparseArray<ByteArray>) {
        for (i in 0 until manufacturerData.size()) {
            val bytes = manufacturerData.valueAt(i)
            
            // Logic from aidex.cpp:
            // C++ strManufacturerData[POSITIONAL_CORRECTION+10] where POSITIONAL_CORRECTION = 2
            // This is index 12 in the raw manufacturer data (including 2-byte Company ID).
            // In Android, 'bytes' excludes the Company ID, so we use index 10.
            
            if (bytes.size > 10) {
                val glucoseByte = bytes[10]
                // Convert to unsigned int and divide by 10.0
                val glucoseVal = (glucoseByte.toInt() and 0xFF) / 10.0

                Log.d("CgmService", "Glucose Found: $glucoseVal")

                // Broadcast the value
                val intent = Intent(ACTION_CGM_UPDATE)
                intent.putExtra(EXTRA_CGM_VALUE, glucoseVal)
                intent.setPackage(packageName)
                sendBroadcast(intent)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        handler.removeCallbacks(scanRunnable)
        // centralManager.close() // Blessed 3.0 might not have close() or it might be implicit
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
