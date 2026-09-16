package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.everything.eve.data.finance.entity.FinanceCardEntity
import kotlinx.coroutines.flow.Flow

/**
 * 银行卡 DAO（阶段 5 / Task 4 / TR-4.2）。
 *
 * 表 `finance_card` 是明文缓存——满足：
 *  1) UI 实时观察（Compose CardList / FinanceDashboard 订阅 Flow）；
 *  2) 提醒调度取窗口内卡片（ReminderScheduler.rebuildChain 在 4b 事件 + 5 财务
 *     之间取全局最小 nextTrigger）；
 *  3) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行）。
 *
 * **零知识纪律（spec NFR-1 / Luhn 校验）：**
 *  - 卡号完整 PAN **永不入库**；本表只存 `last4`（已通过 Luhn 校验的末四位）；
 *  - DAO 接口不暴露完整卡号读取；`getById` 返回的实体不含 PAN。
 *
 * dirty 协议：upsert 时由调用方（FinanceRepository）显式标记 dirty；
 * 服务端 LWW 下行后由 SyncWorker 写入 `dirty=false`。
 */
@Dao
interface FinanceCardDao {

    /**
     * 写入一条卡片（按 id REPLACE；同 id 重复调用即覆盖；
     * createdAt/updatedAt 由调用方在 Repository 层维护）。
     *
     * @param entity 待写入的卡片实体（**仅含 last4**，不含完整 PAN）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FinanceCardEntity)

    /**
     * 按 id 单查（同步对账、编辑器加载、提醒回调拉详情用）。
     *
     * @param id 卡片 UUID。
     * @return 命中的卡片实体（仅 last4）；不存在则 null。
     */
    @Query("SELECT * FROM finance_card WHERE id = :id")
    suspend fun getById(id: String): FinanceCardEntity?

    /**
     * 实时观察全表（按 `updated_at` 升序；UI 订阅此 Flow 自动刷新）。
     */
    @Query("SELECT * FROM finance_card ORDER BY updated_at ASC")
    fun observeAll(): Flow<List<FinanceCardEntity>>

    /**
     * 取所有未删除未归档卡片（按 `updated_at` 升序）；
     * 提醒调度扫描此集合（归档卡跳过）。
     */
    @Query(
        "SELECT * FROM finance_card WHERE deleted = 0 AND archived = 0 " +
            "ORDER BY updated_at ASC",
    )
    fun observeActive(): Flow<List<FinanceCardEntity>>

    /**
     * 取所有 dirty 卡片（理论上仅作观测与诊断；真正的密文推送走 records 表 dirty
     * 字段——`RecordsRepository.dirtyRecords()`）。`dirty=1` 行应与 records 表
     * dirty=1 AND module='finance' AND type='card' 行一一对应。
     */
    @Query("SELECT * FROM finance_card WHERE dirty = 1")
    suspend fun dirtyList(): List<FinanceCardEntity>

    /**
     * 增量上行用：取 `updated_at >= timestampMs` 的所有卡片（含 deleted=1 墓碑）；
     * 服务端按 LWW 协议对账。
     *
     * @param timestampMs 增量游标（毫秒）；≤0 表示全量（since=0）。
     * @return 本地待同步卡片列表（含 dirty=0 但 updated_at 推进的行）。
     */
    @Query("SELECT * FROM finance_card WHERE updated_at >= :timestampMs")
    suspend fun getUpdatedAfter(timestampMs: Long): List<FinanceCardEntity>

    /**
     * 标记单条 dirty 状态（不修改 updated_at 与其他字段）。
     *
     * @param id 卡片 UUID。
     * @param dirty 目标 dirty 值。
     */
    @Query("UPDATE finance_card SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Boolean)

    /**
     * 软删除墓碑（设置 `deleted=1`，**不**真删；records 通道负责密文侧清退）。
     * 历史流水保留 `card_id` 引用，UI 标注"卡已删除"。
     *
     * @param id 卡片 UUID。
     */
    @Query("UPDATE finance_card SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    /**
     * 真删（按 id 物理删除；仅测试与未来"重置资料库"流程使用；生产不调用）。
     *
     * @param id 卡片 UUID。
     */
    @Query("DELETE FROM finance_card WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全表（仅测试用；生产不调用）。
     */
    @Query("DELETE FROM finance_card")
    suspend fun deleteAll()
}