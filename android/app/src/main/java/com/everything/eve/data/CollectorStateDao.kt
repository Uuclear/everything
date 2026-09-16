package com.everything.eve.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 采集器状态 DAO：游标读写与运行结果落库。 */
@Dao
interface CollectorStateDao {

    /** 读取某类采集的持久化状态；未运行过返回 null（视为首轮全量）。 */
    @Query("SELECT * FROM collector_state WHERE kind = :kind")
    suspend fun get(kind: String): CollectorStateEntity?

    /** 推进游标 / 写入运行结果（REPLACE 语义，kind 为主键）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: CollectorStateEntity)

    /** UI 观测全部类别的采集状态。 */
    @Query("SELECT * FROM collector_state")
    fun observeAll(): Flow<List<CollectorStateEntity>>
}
