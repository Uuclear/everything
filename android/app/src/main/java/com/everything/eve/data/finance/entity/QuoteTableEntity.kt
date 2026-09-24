// ============================================================================
// 投资账户手动行情本地缓存表实体（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/entity/QuoteTableEntity.kt
//
// 职责：
//   表 `finance_quote` 是手动行情包（QuoteTable）在本地 Room 的**按 symbol 拆行**
//   冗余缓存 —— 一个行情包（effective_ts = ts）对应 N 行（每个 symbol 一行），
//   供 latest() / observeLatestTable() 快速拼回 QuoteTable，驱动聚合与 UI。
//
// 双写纪律（与 FinanceRateEntity 同风格）：
//   - records 通道：一个生效时刻整包一条密文行（id="quote@${ts}"，
//     type="quote" 复用 FinanceModule.TYPE_QUOTE），**密文以 records 通道为唯一
//     真理源**；
//   - 本地表：每个 symbol 一行，encrypted_payload 列在**本地导入**路径存空串占位
//     （upsertFinanceV2 只回 id 不回密文，不在端侧二次封包制造双份 nonce），
//     仅在 **pull 下行**路径写服务端密文（RecordEntity.ciphertext）。
//
// 幂等键：
//   - 行主键 id = "${symbol}@${ts}"（如 "AAPL@$ts"），
//     同一生效时刻同 symbol 重复导入走 Room @Upsert 的 REPLACE 覆盖，行数不翻倍；
//   - 同 symbol 不同 ts → 不同主键（历史保留, 与 Web last-write-wins 不同）——
//     因为 Room 表按 ts 范围区分历史, QuoteTable 内存态只在 latest 拼包;
//   - 包记录 id = "quote@${ts}"，records 通道同键覆盖。
//
// 索引：
//   - (ts)：按 ts 取最新一组报价（latest / observeLatestTable）；
//   - (symbol)：按 symbol 查历史（预留 API, 本期未消费）；
//   - (dirty)：同步推送对账。
//
// 关联：
//   - android/.../data/finance/dao/QuoteTableDao.kt
//   - android/.../data/finance/QuoteTableRepository.kt
//   - android/.../finance/QuoteTable.kt（QuoteTable / QuoteTables 纯函数）
//   - android/.../finance/FinanceAggregator.kt#investmentMarketValue（消费方）
// ============================================================================

package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 投资账户手动行情本地缓存表（Task 8）。
 *
 * @param id 确定性主键 "${symbol}@${ts}"（如 "AAPL@1735689600000"）。
 * @param symbol 证券代码（与 Quote.symbol / HoldingLike.symbol 锁同口径, 1..32 字符）。
 * @param priceMinor 报价（minor = 分, 非负整数）。
 * @param currency 报价币种（ISO 4217 三字母大写代码）。
 * @param ts 该条报价生效时刻（Unix 毫秒；与包顶层 ts 一致, 便于按 ts 整包删除 / 拉取）。
 * @param encryptedPayload 整包密文（base64，与 records 通道同形态）；本地导入路径
 *   存空串占位（密文以 records 通道为准），pull 下行路径写真实密文。
 * @param schemaVersion 本表 schema 版本号；本期固定 1。
 * @param module 模块标识，固定 "finance"。
 * @param createdAt 创建时刻（Unix 毫秒；下行取 records 行 createdAt）。
 * @param updatedAt 最近写入时刻（增量对账用）。
 * @param dirty 本地脏标记（Int；1 待推送对账，0 已干净）。
 * @param deleted 软删除墓碑（Int；本期行情包无删除入口，保留与汇率表同风格）。
 */
@Entity(
    tableName = "finance_quote",
    indices = [
        Index(value = ["symbol"], name = "idx_finance_quote_symbol"),
        Index(value = ["ts"], name = "idx_finance_quote_ts"),
        Index(value = ["dirty"], name = "idx_finance_quote_dirty"),
    ],
)
data class QuoteTableEntity(
    /** 确定性主键 "${symbol}@${ts}"；同键重复写 REPLACE 覆盖。 */
    @PrimaryKey val id: String,

    /** 证券代码（与 Quote.symbol 锁同口径）。 */
    val symbol: String,

    /** 报价（minor = 分, 非负整数; 解析层已校验）。 */
    @ColumnInfo(name = "price_minor") val priceMinor: Long,

    /** 报价币种（ISO 4217 三字母大写代码）。 */
    val currency: String,

    /** 该条报价生效时刻（Unix 毫秒；与包顶层 ts 一致）。 */
    val ts: Long,

    /**
     * 整包密文（base64）：
     *  - 本地导入：空串占位（密文以 records 通道为准，避免端侧二次封包）；
     *  - pull 下行：写 RecordEntity.ciphertext 真实密文。
     */
    @ColumnInfo(name = "encrypted_payload") val encryptedPayload: String,

    /** 本表 schema 版本号；本期固定 1。 */
    @ColumnInfo(name = "schema_version", defaultValue = "1") val schemaVersion: Int = 1,

    /** 模块标识，固定 "finance"。 */
    @ColumnInfo(defaultValue = "finance") val module: String = "finance",

    /** 创建时刻（Unix 毫秒）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** 最近写入时间戳（毫秒）。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /** 本地脏标记：1 待对账，0 已干净。 */
    @ColumnInfo(defaultValue = "1") val dirty: Int = 1,

    /** 软删除墓碑：1 已删除（本期无删除入口，预留）。 */
    @ColumnInfo(defaultValue = "0") val deleted: Int = 0,
)