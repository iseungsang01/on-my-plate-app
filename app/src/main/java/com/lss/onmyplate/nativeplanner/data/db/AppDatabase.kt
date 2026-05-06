package com.lss.onmyplate.nativeplanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lss.onmyplate.nativeplanner.data.dao.AppointmentCandidateDao
import com.lss.onmyplate.nativeplanner.data.dao.ScheduleDao
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity

@Database(
    entities = [ScheduleEntity::class, AppointmentCandidateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun appointmentCandidateDao(): AppointmentCandidateDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "on_my_plate_native.db").build()
    }
}
