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

    /** 按主键单查（采集引擎变化检测用）。 */
    @Query("SELECT * FROM records WHERE id = :id")
    suspend fun getById(id: String): RecordEntity?

    /**
     * 笔记列表（阶段 2 模块兼容）：
     *  - 历史 module='note'（阶段 1 Android 写入，type=secure_note）；
     *  - 规范 module='pass' AND type='note'（Web/Android 阶段 2 起写入）。
     * login/card/identity 记录不在此暴露，但仍完整同步保存在本表。
     */
    @Query(
        """
        SELECT * FROM records
        WHERE deleted = 0 AND (module = 'note' OR (module = 'pass' AND type = 'note'))
        ORDER BY updatedAt DESC
        """,
    )
    fun observeNotes(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE dirty = 1")
    suspend fun dirtyRecords(): List<RecordEntity>

    /**
     * 按 module + type 双键取全部**未删除**记录（stage5-finance-v2 B4：
     * FinanceViewModel.hydrateV2 与 ReminderScheduler.rebuildChain 的 v2 三类
     * 提醒扫描共用）。
     *
     * 墓碑行（deleted=1）天然排除；调用方逐条解密 + 容错，单条解析失败跳过。
     *
     * @param module records.module（finance 固定传 "finance"）。
     * @param type records.type（subscription / policy / loan / contract）。
     * @return 命中行（不保证顺序；v2 单用户量级为小集合）。
     */
    @Query("SELECT * FROM records WHERE module = :module AND type = :type AND deleted = 0")
    suspend fun getActiveByModuleType(module: String, type: String): List<RecordEntity>

    /** 按 module 统计本地已采集条数（采集页状态行展示用，deleted 也计入存量）。 */
    @Query("SELECT COUNT(*) FROM records WHERE module = :module")
    fun countByModule(module: String): Flow<Int>

    @Query("SELECT COALESCE(MAX(updatedAt), 0) FROM records")
    suspend fun maxUpdatedAt(): Long

    /**
     * 取 updatedAt 大于 sinceMs 的所有 records 行（含已删除墓碑；包含 dirty=true 本地新写）。
     *
     * 用途：阶段 5 / TR-11.2 CollectorWorker 拉取 finance 模块时，用此方法取
     * 待解密 records 子集，无需修改既有 sync() 流程；过滤 module 在调用方进行。
     *
     * @param sinceMs 毫秒游标；sinceMs=0 即全量。
     * @return 命中行按 updatedAt 升序（便于稳定分页；同步小数据集一般不超 500 行）。
     */
    @Query("SELECT * FROM records WHERE updatedAt > :sinceMs ORDER BY updatedAt ASC")
    suspend fun getUpdatedAfter(sinceMs: Long): List<RecordEntity>

    // FU-1：清 dirty 的同时把 updatedAt 覆盖为服务端权威时间，
    // 防止本地时钟偏差（尤其偏未来）通过 maxUpdatedAt 污染增量拉取游标。
    @Query("UPDATE records SET dirty = 0, updatedAt = :serverTime WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>, serverTime: Long)

    // ---- sync_state（v2 新增）----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(state: SyncStateEntity)

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun getSyncState(key: String): String?

    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    fun observeSyncState(key: String): Flow<String?>
}
