package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 财务账户明文表（阶段 5 / Task 4 / TR-4.1）。
 *
 * 表 `finance_account` 是 Vault DB 容器内的**明文缓存**——
 * 1) UI 实时观察账户余额（Compose AccountList / FinanceDashboard 订阅 Flow）；
 * 2) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行；
 *    本表 dirty 标记仅供本地观测与服务端权威时间对账，密文通道复用
 *    RecordsRepository.upsertFinanceAccount 既有链路）。
 *
 * 字段与 [docs/module-schemas.md] 第 9 章 / [docs/schemas/finance.schema.json]
 * 中 FinanceAccount `$defs` 严格一致：枚举值、单位、可空性一一对应。
 * Room 列名沿用 spec 字段命名（snake_case），与 Web/服务端 records 通道一致。
 *
 * 索引说明：
 *  - `(updated_at)`：增量同步推送时筛选本地待同步条目；
 *  - `(dirty)`：与 records 表 dirty 联动，便于本地对账与观测。
 *
 * 零知识红线（spec NFR-1 / FR-4）：本表只存设备本地 Vault DB 容器内明文
 * （Room 加密由阶段 0 已落地容器承载）；不进 SharedPreferences / 日志 /
 * 通知文案；account 信息仅展示不外发。
 */
@Entity(
    tableName = "finance_account",
    // 索引：(updated_at) 服务增量同步游标；
    //       (dirty) 服务同步推送对账（理论上与 records 表 dirty 联动）。
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["dirty"]),
    ],
)
data class FinanceAccountEntity(
    /** 账户 id（UUID v4）；同时作为 records 主键与服务端投递主键。 */
    @PrimaryKey val id: String,

    /** 账户名称（≤100 字符；表单校验非空）。 */
    val name: String,

    /**
     * 账户类型枚举：
     *  - `cash`：现金；
     *  - `deposit`：储蓄卡/活期存款；
     *  - `stock`：投资账户（基金/股票）；
     *  - `wallet`：第三方钱包（支付宝/微信等）；
     *  - `other`：其他。
     * 严格五选一；写入前由编辑器校验。
     */
    val kind: String,

    /** ISO 4217 货币代码（默认 CNY；本期仅 CNY，v2 扩展多币种）。 */
    val currency: String,

    /**
     * 当前余额（decimal-as-string，正负皆可；负数表示透支）；
     * 不用 DOUBLE/REAL —— 精度优先按字符串承载，避免浮点误差。
     * 示例：`"1234.56"`、`"-100.00"`、`"0"`。
     */
    val balance: String,

    /** 纯文本备注（可选）。 */
    val note: String?,

    /** 图标 key（可选；与色板枚举联动）。 */
    val icon: String?,

    /** 调色板 key（blue/green/red/amber/violet/pink/cyan/slate 等）。 */
    val color: String?,

    /**
     * 归档标记：true 时列表置底显示并标注"已归档"，
     * 但**不**从净资产聚合中过滤（filter 规则在 FinanceAggregator 处）。
     */
    val archived: Boolean,

    /** 创建时刻（Unix 毫秒；本地时钟，服务端覆盖后由 records 通道反映）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** 最近一次写入时间戳（毫秒；用于增量同步推送与本地排序）。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /**
     * 本表 schema 版本号；本期固定 1。
     * 与 records 通道 schemaVersion 概念一致：v2 扩展子类型时此处升 2。
     */
    @ColumnInfo(defaultValue = "1") val schema_version: Int = 1,

    /** 模块标识，固定 `"finance"`（与 records 表 module 字段一致）。 */
    @ColumnInfo(defaultValue = "finance") val module: String = "finance",

    /** 子类型标识，固定 `"account"`（account/card/tx + v2 占位）。 */
    @ColumnInfo(defaultValue = "account") val type: String = "account",

    /**
     * 本地脏标记（与 records 表 dirty 联动）；
     * true 时 SyncWorker 下次推送会带上对应 records 条目密文上行。
     */
    val dirty: Boolean,

    /**
     * 软删除墓碑标记：true 表示该条目已删除但保留行用于同步推送；
     * UI 列表过滤 `deleted = 0`。
     */
    val deleted: Boolean,
) {
    /** 阶段 5 / TR-11.2 配套：companion 用于挂载顶层扩展 fromJsonObj。 */
    companion object
}