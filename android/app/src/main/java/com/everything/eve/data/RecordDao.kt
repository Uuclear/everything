package com.everything.eve.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<RecordEntity>)

    @Query("SELECT * FROM records WHERE module = :module AND deleted = 0 ORDER BY updatedAt DESC")
    fun observeByModule(module: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE dirty = 1")
    suspend fun dirtyRecords(): List<RecordEntity>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM records")
    suspend fun maxUpdatedAt(): Long

    @Query("UPDATE records SET dirty = 0 WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>)
}
