package com.lss.onmyplate.nativeplanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lss.onmyplate.nativeplanner.data.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY startAt ASC")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules WHERE (:newStart < COALESCE(endAt, startAt + 3600000)) AND (:newEnd > startAt) ORDER BY startAt ASC")
    suspend fun findConflicts(newStart: Long, newEnd: Long): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(schedule: ScheduleEntity)

    @Update
    suspend fun update(schedule: ScheduleEntity)
}
