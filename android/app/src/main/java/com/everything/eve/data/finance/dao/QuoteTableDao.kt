// ============================================================================
// 投资账户手动行情本地缓存 DAO（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/dao/QuoteTableDao.kt
//
// 表 `finance_quote` 的访问面（与 FinanceRateDao 同风格）：
//   1) upsert / upsertAll：按确定性 id REPLACE（同 ts 重复导入幂等）；
//   2) latestTs + listByTs：两步取最新一组报价行；
//   3) observeLatest：Room Flow，UI 随导入 / 下行自动刷新；
//   4) dirtyList / markDirty / markDeleted / markClean：同步对账。
// ============================================================================

package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.everything.eve.data.finance.entity.QuoteTableEntity
import kotlinx.coroutines.flow.Flow

/**
 * 投资账户手动行情本地缓存 DAO（Task 8）。
 */
@Dao
interface QuoteTableDao {

    /** 写入一行行情（按 id REPLACE；createdAt / updatedAt 由 Repository 维护）。 */
    @Upsert
    suspend fun upsert(entity: QuoteTableEntity)

    /** 批量写入行情（整包导入 / pull 下行场景；按 id REPLACE）。 */
    @Upsert
    suspend fun upsertAll(entities: List<QuoteTableEntity>)

    /** 按 id 单查；过滤墓碑 deleted=0。 */
    @Query("SELECT * FROM finance_quote WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): QuoteTableEntity?

    /**
     * 最新生效时刻（未删除行中的 MAX(ts)）；空表 / 全墓碑返回 null。
     */
    @Query("SELECT MAX(ts) FROM finance_quote WHERE deleted = 0")
    suspend fun latestTs(): Long?

    /** 取某 ts 的全部报价行（未删除）；Repository 两步法拼 QuoteTable 用。 */
    @Query("SELECT * FROM finance_quote WHERE ts = :ts AND deleted = 0")
    suspend fun listByTs(ts: Long): List<QuoteTableEntity>

    /**
     * 实时观察全部报价行（按 ts 降序）；Repository 在 Flow 上 map
     * 取首组（最新 ts）拼 [com.everything.eve.finance.QuoteTable]。
     */
    @Query("SELECT * FROM finance_quote WHERE deleted = 0 ORDER BY ts DESC")
    fun observeLatest(): Flow<List<QuoteTableEntity>>

    /** 取所有 dirty=1 行（pushChanges 对账用）。 */
    @Query("SELECT * FROM finance_quote WHERE dirty = 1")
    suspend fun dirtyList(): List<QuoteTableEntity>

    /** 标记单条 dirty 状态（与 FinanceRateDao.markDirty 同款签名）。 */
    @Query("UPDATE finance_quote SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Int)

    /** 软删除墓碑（deleted=1 + dirty=1 + 推进 updated_at）。 */
    @Query(
        "UPDATE finance_quote SET deleted = 1, dirty = 1, updated_at = :now " +
            "WHERE id = :id",
    )
    suspend fun markDeleted(id: String, now: Long)

    /**
     * 批量翻干净并以服务端权威时间覆盖 updated_at（与 RecordDao.markClean 同语义；
     * 预留 CollectorWorker 服务端对账成功后调用）。
     */
    @Query("UPDATE finance_quote SET dirty = 0, updated_at = :serverTime WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>, serverTime: Long)

    /** 清空全表（仅测试用；生产不调用）。 */
    @Query("DELETE FROM finance_quote")
    suspend fun deleteAll()
}