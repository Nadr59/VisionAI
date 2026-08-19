package com.nadrlab.visionai.data

import androidx.room.*

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses ORDER BY timestamp DESC")
    suspend fun getAll(): List<AnalysisEntity>

    @Insert
    suspend fun insert(entity: AnalysisEntity): Long

    @Delete
    suspend fun delete(entity: AnalysisEntity)

    @Query("DELETE FROM analyses")
    suspend fun deleteAll()
}
