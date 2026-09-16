package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 银行卡/信用卡明文表（阶段 5 / Task 4 / TR-4.2）。
 *
 * 表 `finance_card` 是 Vault DB 容器内的**明文缓存**——
 * 1) UI 实时观察卡片列表（Compose CardList / FinanceDashboard 订阅 Flow）；
 * 2) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行；
 *    本表 dirty 标记仅供本地观测与服务端权威时间对账）。
 *
 * **零知识关键纪律（spec NFR-1 / Luhn 校验）：**
 *  - 卡号完整 PAN **不入库** Room、不进 SharedPreferences / 日志 / 通知文案；
 *  - 仅 `last4`（已通过 Luhn 校验的末四位）入 Room；
 *  - Luhn 校验由编辑器在客户端调用 [com.everything.eve.data.finance.Luhn]
 *    （T3 实施）；服务端零知识，从不解密亦不接触卡号明文。
 *
 * 字段与 [docs/module-schemas.md] 第 9 章 / [docs/schemas/finance.schema.json]
 * 中 FinanceCard `$defs` 严格一致：枚举值、单位、可空性一一对应。
 *
 * 索引说明：
 *  - `(updated_at)`：增量同步推送时筛选本地待同步条目；
 *  - `(dirty)`：与 records 表 dirty 联动，便于本地对账与观测。
 *
 * 信用卡专用字段（`kind="credit"`）：
 *  - `credit_limit` / `used_limit`：decimal-as-string，可选；
 *  - `billing_day` / `due_day`：1-31 的账单日/还款日，可选；
 *  - `brand` / `expiry_month` / `expiry_year`：发卡品牌与有效期，可选。
 */
@Entity(
    tableName = "finance_card",
    // 索引：(updated_at) 服务增量同步游标；
    //       (dirty) 服务同步推送对账。
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["dirty"]),
    ],
)
data class FinanceCardEntity(
    /** 卡 id（UUID v4）；同时作为 records 主键与服务端投递主键。 */
    @PrimaryKey val id: String,

    /** 卡名称（≤100 字符；如"招行信用卡主卡"）；表单校验非空。 */
    val name: String,

    /**
     * 卡类型枚举：
     *  - `debit`：借记卡（储蓄卡）；
     *  - `credit`：信用卡（含准贷记卡）。
     * 严格二选一；写入前由编辑器校验。
     */
    val kind: String,

    /** 发卡行名称（如"招商银行"、"工商银行"）；表单校验非空。 */
    val issuer: String,

    /**
     * 卡号末四位（**已通过 Luhn 校验**，由 [Luhn.extractLast4] 提取）；
     * 完整 PAN 永不入库。
     */
    val last4: String,

    /** ISO 4217 货币代码（默认 CNY；本期仅 CNY，v2 扩展多币种）。 */
    val currency: String,

    /** 信用额度（decimal-as-string；**仅信用卡**有效，kind="credit" 时填写）。 */
    @ColumnInfo(name = "credit_limit") val creditLimit: String?,

    /** 已用额度（decimal-as-string；**仅信用卡**有效，kind="credit" 时填写）。 */
    @ColumnInfo(name = "used_limit") val usedLimit: String?,

    /** 账单日（1-31；**仅信用卡**有效，kind="credit" 时填写）。 */
    @ColumnInfo(name = "billing_day") val billingDay: Int?,

    /** 还款日（1-31；**仅信用卡**有效，kind="credit" 时填写）。 */
    @ColumnInfo(name = "due_day") val dueDay: Int?,

    /**
     * 卡组织枚举：
     *  - `visa` | `mastercard` | `unionpay` | `amex` | `jcb` | `discover` | `other`。
     * 可空；按卡面 logo 自动识别后填写。
     */
    val brand: String?,

    /** 有效期月份（1-12；可空）。 */
    @ColumnInfo(name = "expiry_month") val expiryMonth: Int?,

    /** 有效期年份（4 位数字；可空）。 */
    @ColumnInfo(name = "expiry_year") val expiryYear: Int?,

    /** 持卡人姓名（可选；不强制采集）。 */
    val holder: String?,

    /** 纯文本备注（可选）。 */
    val note: String?,

    /** 图标 key（可选）。 */
    val icon: String?,

    /** 调色板 key（blue/green/red/amber/violet/pink/cyan/slate 等）。 */
    val color: String?,

    /**
     * 归档标记：true 时列表置底显示并标注"已归档"；
     * 提醒调度跳过归档卡（[NextCardFiring] / ReminderScheduler）。
     */
    val archived: Boolean,

    /** 创建时刻（Unix 毫秒；本地时钟）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** 最近一次写入时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /** 本表 schema 版本号；本期固定 1（v2 扩展时此处升 2）。 */
    @ColumnInfo(defaultValue = "1") val schema_version: Int = 1,

    /** 模块标识，固定 `"finance"`（与 records 表 module 字段一致）。 */
    @ColumnInfo(defaultValue = "finance") val module: String = "finance",

    /** 子类型标识，固定 `"card"`。 */
    @ColumnInfo(defaultValue = "card") val type: String = "card",

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