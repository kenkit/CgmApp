package com.myuni.cgmapp

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import java.util.Calendar

class CgmWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        Log.d("CgmWidget", "onUpdate called for ${appWidgetIds.size} widgets")
        val dbHelper = DatabaseHelper(context)
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            arrayOf(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE, GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP),
            null, null, null, null,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} DESC", "1"
        )

        if (cursor.moveToFirst()) {
            val valIndex = cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE)
            val timeIndex = cursor.getColumnIndexOrThrow(GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP)
            val lastValue = cursor.getDouble(valIndex)
            val lastTime = cursor.getLong(timeIndex)
            val age = (Calendar.getInstance().timeInMillis - lastTime) / (60 * 1000)
            
            Log.d("CgmWidget", "Found data in DB: $lastValue, age: $age. Updating widgets...")
            // Update all widgets with the data from DB
            updateWidget(context, lastValue, "→", age.toInt())
        } else {
            Log.d("CgmWidget", "No data found in DB during onUpdate")
        }
        cursor.close()
    }

    companion object {
        fun updateWidget(context: Context, value: Double, arrow: String, age: Int) {
            Log.d("CgmWidget", "updateWidget static called: $value $arrow ($age mins ago)")
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, CgmWidget::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            Log.d("CgmWidget", "Found ${allWidgetIds.size} widget instances to update")

            for (widgetId in allWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.cgm_widget)
                
                views.setTextViewText(R.id.widget_cgm_value, value.toString())
                views.setTextViewText(R.id.widget_arrow, arrow)
                views.setTextViewText(R.id.widget_sample_age, "$age mins ago")

                // Color coding
                val color = when {
                    value <= 3.9 || arrow == "↓" || arrow == "↓↓" -> Color.RED
                    value >= 10.0 -> Color.parseColor("#FFA500") // Orange
                    else -> Color.parseColor("#008000") // Green
                }
                
                views.setTextColor(R.id.widget_cgm_value, color)
                views.setTextColor(R.id.widget_arrow, color)

                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
