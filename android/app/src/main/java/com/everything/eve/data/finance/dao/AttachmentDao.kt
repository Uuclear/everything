package com.everything.eve.data.finance.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.everything.eve.data.finance.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * 附件 DAO（阶段 5 v2 / TR-3.1）。
 *
 * 表 `finance_attachment` 是本地 Room 缓存密文表——
 *  1) UI 实时观察附件列表（Compose AttachmentList 订阅 Flow，按
 *     `recordId` 过滤归属）；
 *  2) 同步协议的对账参考（与 records 通道外发密文联动 dirty 标记）；
 *  3) 增量同步上行使用 [getUpdatedAfter]（T11 同步集成时落地）。
 *
 * 字段映射要点（与 AttachmentEntity 一一对应）：
 *  - `record_id`：父记录主键；按 `recordId` 过滤归属；
 *  - `updated_at`：本地增量同步游标基础；
 *  - `dirty`：本地脏标记；与服务端 LWW 下行后由 SyncWorker 写入 `dirty=0`；
 *  - `deleted`：软删除墓碑；UI 列表过滤 `deleted = 0`。
 *
 * dirty 协议：upsert 时由调用方（FinanceRepository）显式标记 dirty；
 * 服务端 LWW 下行后由 SyncWorker 写入 `dirty=0`。
 */
@Dao
interface AttachmentDao {

    /**
     * 写入一条附件（按 id REPLACE；同 id 重复调用即覆盖）；
     * createdAt / updatedAt 由调用方在 Repository 层维护。
     *
     * @param entity 待写入的附件实体。
     */
    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    /**
     * 批量写入附件（按 id REPLACE；同步对账 / 批量回填场景使用）。
     *
     * @param entities 待写入的附件实体列表。
     */
    @Upsert
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    /**
     * 按 id 单查（同步对账、删除前置校验、明文解码前置读取用）。
     * 过滤墓碑：`deleted = 0`。
     *
     * @param id 附件 UUID。
     * @return 命中的附件实体；不存在或已删除则 null。
     */
    @Query("SELECT * FROM finance_attachment WHERE id = :id AND deleted = 0")
    suspend fun getById(id: String): AttachmentEntity?

    /**
     * 实时观察全表（按 `updated_at` 降序；UI 订阅此 Flow 自动刷新）；
     * 过滤墓碑 `deleted = 0`。
     */
    @Query("SELECT * FROM finance_attachment WHERE deleted = 0 ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<AttachmentEntity>>

    /**
     * 按父记录 id 实时观察该记录下的所有附件（按 `updated_at` 降序）；
     * 过滤墓碑 `deleted = 0`。
     *
     * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
     */
    @Query(
        "SELECT * FROM finance_attachment " +
            "WHERE record_id = :recordId AND deleted = 0 " +
            "ORDER BY updated_at DESC",
    )
    fun observeByRecordId(recordId: String): Flow<List<AttachmentEntity>>

    /**
     * 取所有 dirty 附件（理论上仅作观测与诊断；真正的密文推送走 records 表
     * dirty 字段——`RecordsRepository.dirtyRecords()`）。`dirty=1` 行应与
     * records 表 dirty=1 AND module='finance' AND type='attachment' 行
     * 一一对应。
     */
    @Query("SELECT * FROM finance_attachment WHERE dirty = 1")
    suspend fun dirtyList(): List<AttachmentEntity>

    /**
     * 增量上行用：取 `updated_at > sinceMs` 的所有附件（含 deleted=1 墓碑）；
     * 服务端按 LWW 协议对账。
     *
     * @param sinceMs 增量游标（毫秒）；≤0 表示全量（sinceMs=0）。
     * @return 本地待同步附件列表（含 dirty=0 但 updated_at 推进的行）。
     */
    @Query("SELECT * FROM finance_attachment WHERE updated_at > :sinceMs")
    suspend fun getUpdatedAfter(sinceMs: Long): List<AttachmentEntity>

    /**
     * 标记单条 dirty 状态（不修改 updated_at 与其他字段；
     * 服务端 LWW 下行成功后由 SyncWorker 显式调用 dirty=0）。
     *
     * @param id 附件 UUID。
     * @param dirty 目标 dirty 值（0/1）。
     */
    @Query("UPDATE finance_attachment SET dirty = :dirty WHERE id = :id")
    suspend fun markDirty(id: String, dirty: Int)

    /**
     * 软删除墓碑（设置 `deleted=1` 且同时置 `dirty=1` 以驱动同步上行；
     * **不**真删；records 通道负责密文侧清退）。
     *
     * @param id 附件 UUID。
     * @param now 当前时间戳（毫秒；写入 updated_at 驱动增量游标推进）。
     */
    @Query(
        "UPDATE finance_attachment SET deleted = 1, dirty = 1, updated_at = :now " +
            "WHERE id = :id",
    )
    suspend fun markDeleted(id: String, now: Long)

    /**
     * 真删（按 id 物理删除；仅测试与未来"重置资料库"流程使用；生产不调用）。
     *
     * @param id 附件 UUID。
     */
    @Query("DELETE FROM finance_attachment WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全表（仅测试用；生产不调用）。
     */
    @Query("DELETE FROM finance_attachment")
    suspend fun deleteAll()
}