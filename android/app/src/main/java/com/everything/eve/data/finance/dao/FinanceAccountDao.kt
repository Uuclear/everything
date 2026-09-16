package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import kotlinx.coroutines.flow.Flow

/**
 * 账户 DAO（阶段 5 / Task 4 / TR-4.1）。
 *
 * 表 `finance_account` 是明文缓存——满足：
 *  1) UI 实时观察（Compose AccountList / FinanceDashboard 订阅 Flow）；
 *  2) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行；
 *     本 DAO 不直接对接密文通道，仅供本地数据访问）；
 *  3) 增量同步上行使用 [getUpdatedAfter]（T11 同步集成时落地）。
 *
 * 字段映射要点（与 FinanceAccountEntity 一一对应）：
 *  - `updated_at`：本地脏标记基础；增量同步以 `updated_at > since` 筛选；
 *  - `dirty`：本地脏标记；与服务端 LWW 下行后由 SyncWorker 写入 `dirty=false`；
 *  - `deleted`：软删除墓碑；UI 列表过滤 `deleted = 0`。
 *
 * dirty 协议：upsert 时由调用方（FinanceRepository）显式标记 dirty；
 * 服务端 LWW 下行后由 SyncWorker 写入 `dirty=false`。
 */
@Dao
interface FinanceAccountDao {

    /**
     * 写入一条账户（按 id REPLACE；同 id 重复调用即覆盖；
     * createdAt/updatedAt 由调用方在 Repository 层维护）。
     *
     * @param entity 待写入的账户实体。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FinanceAccountEntity)

    /**
     * 按 id 单查（同步对账、编辑器加载、删除前置校验用）。
     *
     * @param id 账户 UUID。
     * @return 命中的账户实体；不存在则 null。
     */
    @Query("SELECT * FROM finance_account WHERE id = :id")
    suspend fun getById(id: String): FinanceAccountEntity?

    /**
     * 实时观察全表（按 `updated_at` 升序；UI 订阅此 Flow 自动刷新）。
     */
    @Query("SELECT * FROM finance_account ORDER BY updated_at ASC")
    fun observeAll(): Flow<List<FinanceAccountEntity>>

    /**
     * 取所有 dirty 账户（理论上仅作观测与诊断；真正的密文推送走 records 表 dirty
     * 字段——`RecordsRepository.dirtyRecords()`）。`dirty=1` 行应与 records 表
     * dirty=1 AND module='finance' AND type='account' 行一一对应。
     */
    @Query("SELECT * FROM finance_account WHERE dirty = 1")
    suspend fun dirtyList(): List<FinanceAccountEntity>

    /**
     * 取所有未删除账户（按 `updated_at` 升序）。
     * UI 列表默认查询此方法以过滤墓碑。
     */
    @Query("SELECT * FROM finance_account WHERE deleted = 0 ORDER BY updated_at ASC")
    fun observeActive(): Flow<List<FinanceAccountEntity>>

    /**
     * 增量上行用：取 `updated_at >= timestampMs` 的所有账户（含 deleted=1 墓碑）；
     * 服务端按 LWW 协议对账。
     *
     * @param timestampMs 增量游标（毫秒）；≤0 表示全量（since=0）。
     * @return 本地待同步账户列表（含 dirty=0 但 updated_at 推进的行）。
     */
    @Query("SELECT * FROM finance_account WHERE updated_at >= :timestampMs")
    suspend fun getUpdatedAfter(timestampMs: Long): List<FinanceAccountEntity>

    /**
     * 标记单条 dirty 状态（不修改 updated_at 与其他字段；
     * 服务端 LWW 下行成功后由 SyncWorker 显式调用 dirty=false）。
     *
     * @param id 账户 UUID。
     * @param dirty 目标 dirty 值。
     */
    @Query("UPDATE finance_account SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Boolean)

    /**
     * 软删除墓碑（设置 `deleted=1`，**不**真删；records 通道负责密文侧清退）。
     * 该方法**不**改 dirty，由调用方（FinanceRepository）决定是否同时标 dirty。
     *
     * @param id 账户 UUID。
     */
    @Query("UPDATE finance_account SET deleted = 1 WHERE id = :id")
    suspend fun markDeleted(id: String)

    /**
     * 真删（按 id 物理删除；仅测试与未来"重置资料库"流程使用；生产不调用）。
     *
     * @param id 账户 UUID。
     */
    @Query("DELETE FROM finance_account WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全表（仅测试用；生产不调用）。
     */
    @Query("DELETE FROM finance_account")
    suspend fun deleteAll()
}