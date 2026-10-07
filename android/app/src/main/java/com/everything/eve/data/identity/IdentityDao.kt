package com.everything.eve.data.identity

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Upsert
    suspend fun upsert(entity: IdentityEntity)

    @Query("SELECT * FROM identity WHERE deleted = 0 ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identity WHERE id = :id AND deleted = 0 LIMIT 1")
    suspend fun getById(id: String): IdentityEntity?

    @Query("UPDATE identity SET deleted = 1, dirty = 1, updated_at = :now WHERE id = :id")
    suspend fun markDeleted(id: String, now: Long)

    @Query("UPDATE identity SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Boolean)
}
