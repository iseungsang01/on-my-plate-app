package com.lss.onmyplate.nativeplanner.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lss.onmyplate.nativeplanner.data.dao.AppointmentCandidateDao
import com.lss.onmyplate.nativeplanner.data.dao.ScheduleDao
import com.lss.onmyplate.nativeplanner.data.dao.ScheduleRecurrenceDao
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity

@Database(
    entities = [
        ScheduleEntity::class,
        AppointmentCandidateEntity::class,
        ScheduleRecurrenceRuleEntity::class,
        ScheduleRecurrenceExceptionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun appointmentCandidateDao(): AppointmentCandidateDao
    abstract fun scheduleRecurrenceDao(): ScheduleRecurrenceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!db.hasColumn("appointment_candidates", "sourceApp")) {
                    db.execSQL("ALTER TABLE appointment_candidates ADD COLUMN sourceApp TEXT")
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_recurrence_rules` (
                        `scheduleId` TEXT NOT NULL,
                        `frequency` TEXT NOT NULL,
                        `intervalWeeks` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        `untilAt` INTEGER,
                        `count` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`scheduleId`),
                        FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_recurrence_rules_scheduleId` ON `schedule_recurrence_rules` (`scheduleId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_recurrence_exceptions` (
                        `scheduleId` TEXT NOT NULL,
                        `occurrenceStartAt` INTEGER NOT NULL,
                        `action` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`scheduleId`, `occurrenceStartAt`),
                        FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_recurrence_exceptions_scheduleId` ON `schedule_recurrence_exceptions` (`scheduleId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `schedule_recurrence_rules_new` (
                        `scheduleId` TEXT NOT NULL,
                        `frequency` TEXT NOT NULL,
                        `interval` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER,
                        `dayOfMonth` INTEGER,
                        `untilAt` INTEGER,
                        `count` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`scheduleId`),
                        FOREIGN KEY(`scheduleId`) REFERENCES `schedules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `schedule_recurrence_rules_new` (
                        `scheduleId`, `frequency`, `interval`, `dayOfWeek`, `dayOfMonth`, `untilAt`, `count`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `scheduleId`, `frequency`, `intervalWeeks`, `dayOfWeek`, NULL, `untilAt`, `count`, `createdAt`, `updatedAt`
                    FROM `schedule_recurrence_rules`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `schedule_recurrence_rules`")
                db.execSQL("ALTER TABLE `schedule_recurrence_rules_new` RENAME TO `schedule_recurrence_rules`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_recurrence_rules_scheduleId` ON `schedule_recurrence_rules` (`scheduleId`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "on_my_plate_native.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
