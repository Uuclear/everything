package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.everything.eve.data.finance.entity.FinanceTxEntity
import kotlinx.coroutines.flow.Flow

/**
 * 流水 DAO（阶段 5 / Task 4 / TR-4.3）。
 *
 * 表 `finance_tx` 是明文缓存——满足：
 *  1) UI 实时观察（Compose TxList / FinanceDashboard 订阅 Flow）；
 *  2) 月报聚合（FinanceAggregator.monthlyReport 按 `occurred_at` 拉窗口）；
 *  3) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行）。
 *
 * 关联账户/卡删除策略（spec 删除与保留红线）：
 *  - 关联账户/卡被删除时，**历史流水保留** `account_id`/`card_id` 引用，
 *    UI 标注"账户已删除"/"卡已删除"；DAO 不做级联删除；
 *  - 编辑器隐藏交易双方校验失败时弹错，但**不**自动清理历史数据。
 *
 * dirty 协议：upsert 时由调用方（FinanceRepository）显式标记 dirty；
 * 服务端 LWW 下行后由 SyncWorker 写入 `dirty=false`。
 */
@Dao
interface FinanceTxDao {

    /**
     * 写入一条流水（按 id REPLACE；同 id 重复调用即覆盖；
     * createdAt/updatedAt 由调用方在 Repository 层维护）。
     *
     * @param entity 待写入的流水实体。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FinanceTxEntity)

    /**
     * 按 id 单查（同步对账、编辑器加载、详情页用）。
     *
     * @param id 流水 UUID。
     * @return 命中的流水实体；不存在则 null。
     */
    @Query("SELECT * FROM finance_tx WHERE id = :id")
    suspend fun getById(id: String): FinanceTxEntity?

    /**
     * 实时观察全表（按 `occurred_at` 降序——最新交易在前；
     * UI 列表订阅此 Flow 自动刷新）。
     */
    @Query("SELECT * FROM finance_tx ORDER BY occurred_at DESC")
    fun observeAll(): Flow<List<FinanceTxEntity>>

    /**
     * 窗口查询（FinanceAggregator.monthlyReport 按月拉窗口流水；
     * 边界：`occurred_at >= from AND occurred_at < to`，半开区间）。
     *
     * @param from 窗口起始（Unix 毫秒，含）。
     * @param to 窗口结束（Unix 毫秒，不含）。
     * @return 窗口内所有未删除流水（按 occurred_at 升序）。
     */
    @Query(
        "SELECT * FROM finance_tx " +
            "WHERE deleted = 0 AND occurred_at >= :from AND occurred_at < :to " +
            "ORDER BY occurred_at ASC",
    )
    suspend fun queryWindow(from: Long, to: Long): List<FinanceTxEntity>

    /**
     * 按账户过滤窗口流水（FinanceAggregator 联动账户余额用，
     * 及 UI 按账户筛选按钮）。
     *
     * @param accountId 账户 UUID。
     * @param from 窗口起始（Unix 毫秒，含）。
     * @param to 窗口结束（Unix 毫秒，不含）。
     * @return 该账户窗口内未删除流水（按 occurred_at 升序）。
     */
    @Query(
        "SELECT * FROM finance_tx " +
            "WHERE deleted = 0 AND account_id = :accountId " +
            "AND occurred_at >= :from AND occurred_at < :to " +
            "ORDER BY occurred_at ASC",
    )
    suspend fun queryByAccount(
        accountId: String,
        from: Long,
        to: Long,
    ): List<FinanceTxEntity>

    /**
     * 取所有 dirty 流水（理论上仅作观测与诊断；真正的密文推送走 records 表 dirty
     * 字段——`RecordsRepository.dirtyRecords()`）。`dirty=1` 行应与 records 表
     * dirty=1 AND module='finance' AND type='tx' 行一一对应。
     */
    @Query("SELECT * FROM finance_tx WHERE dirty = 1")
    suspend fun dirtyList(): List<FinanceTxEntity>

    /**
     * 增量上行用：取 `updated_at >= timestampMs` 的所有流水（含 deleted=1 墓碑）；
     * 服务端按 LWW 协议对账。
     *
     * @param timestampMs 增量游标（毫秒）；≤0 表示全量（since=0）。
     * @return 本地待同步流水列表（含 dirty=0 但 updated_at 推进的行）。
     */
    @Query("SELECT * FROM finance_tx WHERE updated_at >= :timestampMs")
    suspend fun getUpdatedAfter(timestampMs: Long): List<FinanceTxEntity>

    /**
     * 标记单条 dirty 状态（不修改 updated_at 与其他字段）。
     *
     * @param id 流水 UUID。
     * @param dirty 目标 dirty 值。
     */
    @Query("UPDATE finance_tx SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Boolean)

    /**
     * 软删除墓碑（设置 `deleted=1`，**不**真删；records 通道负责密文侧清退）。
     * 历史趋势/聚合不受单条墓碑影响（聚合按 `deleted = 0` 过滤）。
     *
     * @param id 流水 UUID。
     */
    @Query("UPDATE finance_tx SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    /**
     * 真删（按 id 物理删除；仅测试与未来"重置资料库"流程使用；生产不调用）。
     *
     * @param id 流水 UUID。
     */
    @Query("DELETE FROM finance_tx WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全表（仅测试用；生产不调用）。
     */
    @Query("DELETE FROM finance_tx")
    suspend fun deleteAll()
}