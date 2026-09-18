// ============================================================================
// 离线汇率本地缓存 DAO（stage5-finance-v2 / B5 / FR-V2-C.2）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/dao/FinanceRateDao.kt
//
// 表 `finance_rate` 的访问面（与 AttachmentDao 同风格）：
//   1) upsert / upsertAll：按确定性 id REPLACE（同生效时刻重复导入幂等）；
//   2) latestEffectiveTs + listByEffectiveTs：两步取最新一组汇率行；
//   3) observeLatest：Room Flow，UI 随导入 / 下行自动刷新；
//   4) dirtyList / markDirty / markDeleted / markClean：同步对账。
// ============================================================================

package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.everything.eve.data.finance.entity.FinanceRateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 离线汇率本地缓存 DAO（B5）。
 */
@Dao
interface FinanceRateDao {

    /** 写入一行汇率（按 id REPLACE；createdAt / updatedAt 由 Repository 维护）。 */
    @Upsert
    suspend fun upsert(entity: FinanceRateEntity)

    /** 批量写入汇率（整包导入 / pull 下行场景；按 id REPLACE）。 */
    @Upsert
    suspend fun upsertAll(entities: List<FinanceRateEntity>)

    /** 按 id 单查；过滤墓碑 deleted=0。 */
    @Query("SELECT * FROM finance_rate WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): FinanceRateEntity?

    /**
     * 最新生效时刻（未删除行中的 MAX(effective_ts)）；空表 / 全墓碑返回 null。
     */
    @Query("SELECT MAX(effective_ts) FROM finance_rate WHERE deleted = 0")
    suspend fun latestEffectiveTs(): Long?

    /** 取某生效时刻的全部汇率行（未删除）；Repository 两步法拼 RateTable 用。 */
    @Query("SELECT * FROM finance_rate WHERE effective_ts = :ts AND deleted = 0")
    suspend fun listByEffectiveTs(ts: Long): List<FinanceRateEntity>

    /**
     * 实时观察全部汇率行（按 effective_ts 降序）；Repository 在 Flow 上 map
     * 取首组（最新 effective_ts）拼 [com.everything.eve.finance.RateTable]。
     */
    @Query("SELECT * FROM finance_rate WHERE deleted = 0 ORDER BY effective_ts DESC")
    fun observeLatest(): Flow<List<FinanceRateEntity>>

    /** 取所有 dirty=1 行（pushChanges 对账用）。 */
    @Query("SELECT * FROM finance_rate WHERE dirty = 1")
    suspend fun dirtyList(): List<FinanceRateEntity>

    /** 标记单条 dirty 状态（与 AttachmentDao.markDirty 同款签名）。 */
    @Query("UPDATE finance_rate SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Int)

    /** 软删除墓碑（deleted=1 + dirty=1 + 推进 updated_at）。 */
    @Query(
        "UPDATE finance_rate SET deleted = 1, dirty = 1, updated_at = :now " +
            "WHERE id = :id",
    )
    suspend fun markDeleted(id: String, now: Long)

    /**
     * 批量翻干净并以服务端权威时间覆盖 updated_at（与 RecordDao.markClean 同语义；
     * 预留 CollectorWorker 服务端对账成功后调用）。
     */
    @Query("UPDATE finance_rate SET dirty = 0, updated_at = :serverTime WHERE id IN (:ids)")
    suspend fun markClean(ids: List<String>, serverTime: Long)

    /** 清空全表（仅测试用；生产不调用）。 */
    @Query("DELETE FROM finance_rate")
    suspend fun deleteAll()
}
