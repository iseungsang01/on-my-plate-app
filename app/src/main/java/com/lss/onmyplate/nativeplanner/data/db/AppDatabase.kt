package com.lss.onmyplate.nativeplanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lss.onmyplate.nativeplanner.data.dao.AppointmentCandidateDao
import com.lss.onmyplate.nativeplanner.data.dao.ScheduleDao
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity

@Database(
    entities = [ScheduleEntity::class, AppointmentCandidateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun appointmentCandidateDao(): AppointmentCandidateDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("appointment_candidates", "sourceApp")) {
                    db.execSQL("ALTER TABLE appointment_candidates ADD COLUMN sourceApp TEXT")
                }
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "on_my_plate_native.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private fun SupportSQLiteDatabase.hasColumn(tableName: String, columnName: String): Boolean {
            query("PRAGMA table_info(`$tableName`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) return true
                }
            }
            return false
        }
    }
}
