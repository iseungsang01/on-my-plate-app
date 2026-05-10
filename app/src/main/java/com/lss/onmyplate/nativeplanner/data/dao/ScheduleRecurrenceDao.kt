package com.lss.onmyplate.nativeplanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceExceptionEntity
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleRecurrenceRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleRecurrenceDao {
    @Query("SELECT * FROM schedule_recurrence_rules")
    fun observeRules(): Flow<List<ScheduleRecurrenceRuleEntity>>

    @Query("SELECT * FROM schedule_recurrence_exceptions")
    fun observeExceptions(): Flow<List<ScheduleRecurrenceExceptionEntity>>

    @Query("SELECT * FROM schedule_recurrence_rules WHERE scheduleId = :scheduleId")
    suspend fun getRule(scheduleId: String): ScheduleRecurrenceRuleEntity?

    @Query("SELECT * FROM schedule_recurrence_rules")
    suspend fun getRules(): List<ScheduleRecurrenceRuleEntity>

    @Query("SELECT * FROM schedule_recurrence_exceptions")
    suspend fun getExceptions(): List<ScheduleRecurrenceExceptionEntity>

    @Query("SELECT * FROM schedule_recurrence_exceptions WHERE scheduleId = :scheduleId ORDER BY occurrenceStartAt ASC")
    suspend fun getExceptions(scheduleId: String): List<ScheduleRecurrenceExceptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(rule: ScheduleRecurrenceRuleEntity)

    @Query("DELETE FROM schedule_recurrence_rules WHERE scheduleId = :scheduleId")
    suspend fun deleteRule(scheduleId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertException(exception: ScheduleRecurrenceExceptionEntity)
}
