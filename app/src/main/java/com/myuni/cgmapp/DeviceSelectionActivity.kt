package com.myuni.cgmapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID

class DeviceSelectionActivity : AppCompatActivity() {

    private lateinit var deviceAdapter: DeviceAdapter
    private val devices = mutableListOf<ScanResult>()
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private lateinit var bluetoothAdapter: BluetoothAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_selection)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        val recyclerView = findViewById<RecyclerView>(R.id.device_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        deviceAdapter = DeviceAdapter(devices) { result ->
            val address = result.device.address
            var name = result.scanRecord?.deviceName
            if (name.isNullOrEmpty()) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                     name = result.device.name
                }
            }
            name = name ?: "Unknown"
            
            val sharedPref = getSharedPreferences("CgmAppSettings", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("selected_device_mac", address)
                putString("selected_device_name", name)
                apply()
            }
            Toast.makeText(this, "Selected $name ($address)", Toast.LENGTH_SHORT).show()
            finish()
        }
        recyclerView.adapter = deviceAdapter

        startScan()
    }

    private fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                 Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show()
                 return
            }
        } else {
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                 Toast.makeText(this, "Permission missing", Toast.LENGTH_SHORT).show()
                 return
            }
        }
        
        isScanning = true
        
        val serviceUuid = UUID.fromString("0000f000-0000-1000-8000-00805f9b34fb")
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUuid))
            .build()
            
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothAdapter.bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
        
        handler.postDelayed({
            stopScan()
        }, 10000) // Scan for 10 seconds
    }

    private fun stopScan() {
        if (!isScanning) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        }
        
        isScanning = false
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
        findViewById<android.widget.ProgressBar>(R.id.scan_progress).visibility = View.GONE
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            
            val existing = devices.indexOfFirst { it.device.address == device.address }
            if (existing >= 0) {
                devices[existing] = result
                deviceAdapter.notifyItemChanged(existing)
            } else {
                devices.add(result)
                deviceAdapter.notifyItemInserted(devices.size - 1)
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
    }
    
    class DeviceAdapter(private val devices: List<ScanResult>, private val onClick: (ScanResult) -> Unit) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.device_name)
            val mac: TextView = view.findViewById(R.id.device_mac)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = devices[position]
            var deviceName = result.scanRecord?.deviceName
            
            if (deviceName.isNullOrEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(holder.itemView.context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        deviceName = result.device.name
                    }
                } else {
                    deviceName = result.device.name 
                }
            }
            
            holder.name.text = deviceName ?: "Unknown Device"
            holder.mac.text = "${result.device.address}\nRSSI: ${result.rssi} dBm"
            
            holder.itemView.setOnClickListener { onClick(result) }
        }

        override fun getItemCount() = devices.size
    }
}