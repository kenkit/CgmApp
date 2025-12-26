package com.myuni.cgmapp

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.units.BloodGlucose
import java.time.Instant
import java.time.ZoneOffset

class HealthConnectManager(private val context: Context) {

    fun getStatus(): Int {
        return HealthConnectClient.getSdkStatus(context)
    }

    fun isAvailable(): Boolean {
        return getStatus() == HealthConnectClient.SDK_AVAILABLE
    }

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasWritePermission(): Boolean {
        val permissions = setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class))
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(permissions)
    }

    fun getWritePermissions(): Set<String> {
        return setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class))
    }

    suspend fun writeBloodGlucose(valueMmol: Double, timestamp: Long) {
        writeBloodGlucoseBatch(listOf(Pair(timestamp, valueMmol)))
    }

    suspend fun writeBloodGlucoseBatch(recordsData: List<Pair<Long, Double>>) {
        try {
            if (!isAvailable()) return
            
            if (!hasWritePermission()) {
                Log.w("HealthConnectManager", "No write permission for Health Connect")
                return
            }

            if (recordsData.isEmpty()) return

            val records = recordsData.map { (timestamp, valueMmol) ->
                BloodGlucoseRecord(
                    time = Instant.ofEpochMilli(timestamp),
                    zoneOffset = ZoneOffset.systemDefault().rules.getOffset(Instant.now()),
                    level = BloodGlucose.millimolesPerLiter(valueMmol)
                )
            }

            healthConnectClient.insertRecords(records)
            Log.d("HealthConnectManager", "Successfully wrote ${records.size} blood glucose records to Health Connect")
        } catch (e: Exception) {
            Log.e("HealthConnectManager", "Error writing to Health Connect", e)
        }
    }
}