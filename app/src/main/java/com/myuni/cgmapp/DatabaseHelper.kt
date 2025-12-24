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
    }
}

class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        val sql_create_entries = "CREATE TABLE ${GlucoseContract.GlucoseEntry.TABLE_NAME} (" +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_TIMESTAMP} INTEGER PRIMARY KEY," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_VALUE} FLOAT," +
                "${GlucoseContract.GlucoseEntry.COLUMN_NAME_UPLOADED} INTEGER DEFAULT 0)"
        db.execSQL(sql_create_entries)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val sql_delete_entries = "DROP TABLE IF EXISTS ${GlucoseContract.GlucoseEntry.TABLE_NAME}"
        db.execSQL(sql_delete_entries)
        onCreate(db)
    }

    companion object {
        const val DATABASE_VERSION = 4
        const val DATABASE_NAME = "CgmApp.db"
    }
}
