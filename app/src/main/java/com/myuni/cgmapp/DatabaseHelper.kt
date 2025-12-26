package com.myuni.cgmapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns

object GlucoseContract {
    object GlucoseEntry : BaseColumns {
        const val TABLE_NAME = "glucose_readings"
        const val COLUMN_NAME_TIMESTAMP = "timestamp"
        const val COLUMN_NAME_VALUE = "value"
        const val COLUMN_NAME_UPLOADED = "uploaded"
        const val COLUMN_NAME_HEALTH_SYNCED = "health_synced"
        const val COLUMN_NAME_RSSI = "rssi"
        const val COLUMN_NAME_DIRECTION = "direction"
    }
}

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val sql_create_entries = "CREATE TABLE ${GlucoseContract.GlucoseEntry.TABLE_NAME} (" +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} INTEGER PRIMARY KEY," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE} FLOAT," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} INTEGER DEFAULT 0," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_HEALTH_SYNCED} INTEGER DEFAULT 0," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_RSSI} INTEGER," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_DIRECTION} TEXT)"
        db.execSQL(sql_create_entries)
        
        // Add index for faster upload lookups
        db.execSQL("CREATE INDEX idx_uploaded ON ${GlucoseContract.GlucoseEntry.TABLE_NAME} (${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED})")
        db.execSQL("CREATE INDEX idx_health_synced ON ${GlucoseContract.GlucoseEntry.TABLE_NAME} (${GlucoseContract.GlucoseEntry.COLUMN_NAME_HEALTH_SYNCED})")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 7) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_uploaded ON ${GlucoseContract.GlucoseEntry.TABLE_NAME} (${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED})")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE ${GlucoseContract.GlucoseEntry.TABLE_NAME} ADD COLUMN ${GlucoseContract.GlucoseEntry.COLUMN_NAME_HEALTH_SYNCED} INTEGER DEFAULT 0")
            db.execSQL("CREATE INDEX idx_health_synced ON ${GlucoseContract.GlucoseEntry.TABLE_NAME} (${GlucoseContract.GlucoseEntry.COLUMN_NAME_HEALTH_SYNCED})")
        }
        if (oldVersion >= 8) {
            // Future-proofing or full reset logic if needed
        }
    }

    fun cleanupOldRecords() {
        val db = writableDatabase
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        db.delete(
            GlucoseContract.GlucoseEntry.TABLE_NAME,
            "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} < ?",
            arrayOf(thirtyDaysAgo.toString())
        )
    }

    companion object {
        const val DATABASE_VERSION = 8
        const val DATABASE_NAME = "CgmApp.db"
    }
}
