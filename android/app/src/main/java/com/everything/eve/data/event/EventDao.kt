package com.everything.eve.data.event

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 事件 DAO（阶段 4b Task 4 / TR-4.1）。
 *
 * 表 `event` 是明文缓存——满足：
 *  1) UI 实时观察（Compose CalendarScreen / MonthGrid / WeekGrid 订阅 Flow）；
 *  2) 提醒调度取窗口内事件（ReminderScheduler.nextTrigger 在 `queryWindow` 上
 *     跑 Recurrence.expand 找下一触发点，Task 5）；
 *  3) 同步协议的对账参考（SyncWorker pull 后对比远端 records → 本地 EventEntity
 *     upsert；不直接走 DAO，由 EventsRepository 编排）。
 *
 * 字段映射要点（与 EventEntity 一一对应）：
 *  - `rrule_json`：可为 null；null 表示单次事件。
 *  - `reminders_json` / `exdates_json`：永远非 null；空列表以 `"[]"` 持久化。
 *  - `tz_mode`：默认 `"local"`，由 `@ColumnInfo(defaultValue = "local")` 兜底。
 *  - `dirty`：本地脏标记；与 records 表 dirty 同步更新。
 *
 * dirty 协议：upsert 时由调用方（EventsRepository）显式标记 dirty；
 * 服务端 LWW 下行后由 SyncWorker 写入 `dirty=false`。
 */
@Dao
interface EventDao {

    /**
     * 写入一条事件（按 id REPLACE；同 id 重复调用即覆盖；
     * createdAt/updatedAt 由调用方在 Repository 层维护）。
     *
     * @return 影响行数（Room 不直接返回；本方法作为占位签名）。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EventEntity)

    /** 按 id 单查（ReminderReceiver 收到闹钟后拉取事件详情用，Task 5）。 */
    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    /**
     * 实时观察全表（按 `start_ts` 升序）。
     * Compose UI 顶层订阅此 Flow，UI 自动刷新。
     */
    @Query("SELECT * FROM event ORDER BY start_ts ASC")
    fun observeAll(): Flow<List<EventEntity>>

    /**
     * 窗口查询（ReminderScheduler 拉取"接下来 N 小时窗口"事件以计算下一触发点）。
     *
     * 边界：`start_ts >= from AND start_ts < to`（半开区间，避免重复事件与窗口
     * 边界同日时跨实例重叠）。
     */
    @Query(
        "SELECT * FROM event WHERE start_ts >= :from AND start_ts < :to " +
            "ORDER BY start_ts ASC",
    )
    suspend fun queryWindow(from: Long, to: Long): List<EventEntity>

    /**
     * 取所有 dirty 事件（理论上仅作观测与诊断；真正的密文推送走 records 表 dirty
     * 字段——`RecordsRepository.dirtyRecords()`）。`dirty=1` 行应与 records 表
     * dirty=1 AND module='event' AND type='event' 行一一对应。
     */
    @Query("SELECT * FROM event WHERE dirty = 1")
    suspend fun dirtyList(): List<EventEntity>

    /**
     * 删除一条事件（按 id；RecordsRepository.delete 走 records 通道，本表同步删除）。
     */
    @Query("DELETE FROM event WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 清空全表（仅测试与未来"重置资料库"流程使用；生产不调用）。
     */
    @Query("DELETE FROM event")
    suspend fun deleteAll()
}