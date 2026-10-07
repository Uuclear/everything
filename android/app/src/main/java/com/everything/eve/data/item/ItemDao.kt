package com.everything.eve.data.item

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 物品 DAO（阶段 5 items Task 4 / TR-4.1）。
 *
 * 表 `item` 是明文缓存：UI 订阅 Flow、同步对账、提醒调度读保修字段。
 * dirty 由 ItemsRepository 编排时显式标记；下行 LWW 后 SyncWorker 写 dirty=false。
 */
@Dao
interface ItemDao {

    /** 按 id REPLACE 写入一条物品。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ItemEntity)

    @Query("SELECT * FROM item WHERE id = :id")
    suspend fun getById(id: String): ItemEntity?

    @Query("SELECT * FROM item ORDER BY updated_ts DESC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE category = :category ORDER BY updated_ts DESC")
    fun observeByCategory(category: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE dirty = 1")
    suspend fun dirtyList(): List<ItemEntity>

    @Query("DELETE FROM item WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM item")
    suspend fun deleteAll()
}
