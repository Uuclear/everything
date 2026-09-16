package com.everything.eve.data.event

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 事件明文表（阶段 4b，v5 迁移新增）。
 *
 * 字段表与 [docs/module-schemas.md] 第 8 章 / [web/src/events/types.ts] `EventRule`
 * 严格一致——命名、单位、枚举完全相同；JSON 列表/对象以 TEXT 序列化入列。
 *
 * Room 列名沿用 spec 字段命名（snake_case），与 Web/服务端 records 一致；
 * 索引 `(start_ts)` 服务窗口查询（spec FR-11 展开窗口裁剪），
 * `(dirty)` 服务 sync 周期推送（仅拉取本地 dirty 条目入密文）。
 *
 * 零知识红线（spec FR-4 / NFR-1）：本表只存设备本地 Vault DB 容器内明文（Room
 * 加密由阶段 0 已落地容器承载），不进 SharedPreferences / 日志 / 通知文案 /
 * 异常消息；事件→实例展示所需 title/color/reminders 全靠本表 + 内存展开。
 */
@Entity(
    tableName = "event",
    // (start_ts)：事件按时间窗口渲染（月/周视图）+ reminder 调度下一触发点计算。
    // (dirty)：SyncWorker 周期推送时筛选本地待同步条目（理论上事件表 dirty 与
    //           records 表 dirty 通过同 repo 接口写入保持一致；此处冗余便于观测）。
    indices = [
        Index(value = ["start_ts"]),
        Index(value = ["dirty"]),
    ],
)
data class EventEntity(
    /** 事件 id（UUID v4）；同时作为 records 主键与服务端投递主键。 */
    @PrimaryKey val id: String,

    /** 事件标题（≤200 字符；表单校验非空）。 */
    val title: String,

    /** 事件本地起始时刻，Unix 毫秒；与 `tz_mode=local` 联动按本地日历日换算。 */
    val start_ts: Long,

    /** 事件结束时刻，Unix 毫秒；`all_day=true` 时为结束日 00:00 本地时刻。 */
    val end_ts: Long,

    /** 是否全天事件；true 时 UI 仅展示日期选择。 */
    val all_day: Boolean,

    /**
     * 时区模式；本期固定 `"local"`（设备系统时区语义）；
     * 字段保留以便未来扩展为独立时区（`tz_mode="tz"`），UI 本期不暴露。
     */
    @ColumnInfo(defaultValue = "local") val tz_mode: String,

    /** 纯文本地点描述（可选；不关联 place 模块）。 */
    val location_text: String?,

    /** 纯文本备注（可选）。 */
    val note: String?,

    /** 8 色板预设：blue/green/red/amber/violet/pink/cyan/slate。 */
    val color: String,

    /**
     * 提前分钟数组的 JSON 字符串；非 null；UI/编辑器强校验 ≤3 项 + 档位合法；
     * 例：`"[0,15]"`、`"[]"`、`"[1440]"`。
     */
    val reminders_json: String,

    /**
     * 重复规则 JSON 对象字符串；可为 null 表示单次事件；
     * 非 null 时为 B 档简化子集（freq/interval/byweekday/end 四字段必出现），
     * 例：`"{\"freq\":\"WEEKLY\",\"interval\":1,\"byweekday\":[\"MO\"],\"end\":{\"kind\":\"never\"}}"`。
     */
    val rrule_json: String?,

    /**
     * 例外日期数组的 JSON 字符串；非 null；按本地日历日匹配（`YYYY-MM-DD`）；
     * 例：`"[]"`、`"[\"2026-01-01\"]"`。
     */
    @ColumnInfo(defaultValue = "[]") val exdates_json: String,

    /**
     * 本地脏标记（与 records 表 dirty 联动）；true 时 SyncWorker 下次推送会带上
     * 对应 records 条目密文上行（records 通道复用 4a RecordsRepository.upsert）。
     */
    val dirty: Boolean,

    /** 本地最近一次写入时间戳（毫秒）；用于观测/排序，不参与同步协议。 */
    val updated_ts: Long,
)