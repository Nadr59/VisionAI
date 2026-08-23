package com.nadrlab.visionai.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insert(entity: AnalysisEntity): Long

    @Query("SELECT * FROM analyses ORDER BY timestamp DESC")
    suspend fun getAll(): List<AnalysisEntity>

    @Delete
    suspend fun delete(entity: AnalysisEntity)

    @Query("DELETE FROM analyses")
    suspend fun deleteAll()
}
