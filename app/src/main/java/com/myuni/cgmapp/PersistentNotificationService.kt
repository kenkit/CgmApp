package com.myuni.cgmapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

class PersistentNotificationService(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "CgmServiceChannel"
        const val NOTIFICATION_ID = 1
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "CGM Status Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    fun updateNotification(value: Double, arrow: String, age: Int) {
        val title = "$value $arrow"
        val text = "Updated $age mins ago"
        val notification = createNotification(title, text)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun createNotification(title: String, contentText: String): Notification {
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun getInitialNotification(dbHelper: DatabaseHelper): Notification {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            arrayOf(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE, GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP),
            null, null, null, null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC", "1"
        )
        
        var initialTitle = "CGM Service"
        var initialText = "Scanning for CGM values..."
        if (cursor.moveToFirst()) {
            val valIndex = cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE)
            val timeIndex = cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP)
            val lastValue = cursor.getDouble(valIndex)
            val lastTime = cursor.getLong(timeIndex)
            val age = (Calendar.getInstance().timeInMillis - lastTime) / (60 * 1000)
            initialTitle = "$lastValue →"
            initialText = "$age mins ago"
        }
        cursor.close()
        
        return createNotification(initialTitle, initialText)
    }
}
