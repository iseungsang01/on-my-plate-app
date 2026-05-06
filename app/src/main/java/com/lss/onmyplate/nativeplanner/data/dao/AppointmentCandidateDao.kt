package com.lss.onmyplate.nativeplanner.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lss.onmyplate.nativeplanner.data.entity.AppointmentCandidateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentCandidateDao {
    @Query("SELECT * FROM appointment_candidates WHERE id = :id")
    suspend fun get(id: String): AppointmentCandidateEntity?

    @Query("SELECT * FROM appointment_candidates WHERE id = :id")
    fun observe(id: String): Flow<AppointmentCandidateEntity?>

    @Query("SELECT * FROM appointment_candidates WHERE status = 'pending' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<AppointmentCandidateEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(candidate: AppointmentCandidateEntity)

    @Update
    suspend fun update(candidate: AppointmentCandidateEntity)
}
