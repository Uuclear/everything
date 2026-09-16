package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 财务交易流水明文表（阶段 5 / Task 4 / TR-4.3）。
 *
 * 表 `finance_tx` 是 Vault DB 容器内的**明文缓存**——
 * 1) UI 实时观察流水列表（Compose TxList / FinanceDashboard 订阅 Flow）；
 * 2) 同步协议的对账参考（FinanceRepository 走 records 通道加密后上行；
 *    本表 dirty 标记仅供本地观测与服务端权威时间对账）。
 *
 * 字段与 [docs/module-schemas.md] 第 9 章 / [docs/schemas/finance.schema.json]
 * 中 FinanceTx `$defs` 严格一致：枚举值、单位、可空性一一对应。
 *
 * 关联账户/卡删除策略（spec NFR-1 / 删除与保留红线）：
 *  - 关联账户/卡被删除时，**历史流水保留** `account_id=null` / `card_id=null`
 *    墓碑，不级联删除流水（流水是历史事实，账户/卡是当前快照）；
 *  - 编辑器隐藏交易双方校验失败时弹错，但**不**自动清理历史数据。
 *
 * 索引说明：
 *  - `(updated_at)` / `(dirty)`：增量同步推送时筛选本地待同步条目；
 *  - `(occurred_at)`：流水按发生时间排序/筛选/月报聚合；
 *  - `(account_id)`：按账户过滤流水的快速路径。
 */
@Entity(
    tableName = "finance_tx",
    // 索引：(updated_at)/(dirty) 服务增量同步；
    //       (occurred_at) 服务按时间排序与月报聚合；
    //       (account_id) 服务按账户过滤。
    indices = [
        Index(value = ["updated_at"]),
        Index(value = ["dirty"]),
        Index(value = ["occurred_at"]),
        Index(value = ["account_id"]),
    ],
)
data class FinanceTxEntity(
    /** 流水 id（UUID v4）；同时作为 records 主键与服务端投递主键。 */
    @PrimaryKey val id: String,

    /**
     * 关联账户 id（指向 [FinanceAccountEntity.id]，必填）；
     * 账户被删除后此字段保留但 UI 标注"账户已删除"。
     */
    @ColumnInfo(name = "account_id") val accountId: String,

    /**
     * 关联卡片 id（指向 [FinanceCardEntity.id]，可选）；
     * **仅信用卡交易时填写**，借记卡/现金/钱包交易留 null。
     */
    @ColumnInfo(name = "card_id") val cardId: String?,

    /**
     * 交易类型枚举：
     *  - `income`：收入（账户余额增加）；
     *  - `expense`：支出（账户余额减少）；
     *  - `transfer`：转账（双向：转出账户余额减 + 转入账户余额增，
     *    此时 `transferToAccountId` 必填且与 `accountId` 不同）。
     * 严格三选一。
     */
    val kind: String,

    /**
     * 交易金额（decimal-as-string，**正数**；语义由 `kind` 决定方向）；
     * 不用 DOUBLE/REAL —— 精度优先按字符串承载。
     */
    val amount: String,

    /** ISO 4217 货币代码（默认 CNY；本期仅 CNY，v2 扩展多币种）。 */
    val currency: String,

    /**
     * 分类 ID 或自由文本（如"food"/"transport"/"salary"/"rent"）；
     * 客户端提供默认分类下拉建议，**允许**用户自定义（spec FR-1.3）。
     */
    val category: String,

    /**
     * 交易发生时刻（Unix 毫秒；用户可选未来日期做预算记账）。
     */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,

    /** 纯文本备注（可选）。 */
    val note: String?,

    /** 图标 key（可选）。 */
    val icon: String?,

    /** 调色板 key（可选）。 */
    val color: String?,

    /**
     * 转入账户 id（**仅 kind="transfer" 时必填**）；
     * 必须与 `accountId` 不同；UI 编辑器强校验。
     */
    @ColumnInfo(name = "transfer_to_account_id") val transferToAccountId: String?,

    /** 创建时刻（Unix 毫秒；本地时钟）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** 最近一次写入时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /** 本表 schema 版本号；本期固定 1（v2 扩展时此处升 2）。 */
    @ColumnInfo(defaultValue = "1") val schema_version: Int = 1,

    /** 模块标识，固定 `"finance"`（与 records 表 module 字段一致）。 */
    @ColumnInfo(defaultValue = "finance") val module: String = "finance",

    /** 子类型标识，固定 `"tx"`。 */
    @ColumnInfo(defaultValue = "tx") val type: String = "tx",

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