// ============================================================================
// 离线汇率本地缓存表实体（stage5-finance-v2 / B5 / FR-V2-C.2、FR-V2-C.3）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/entity/FinanceRateEntity.kt
//
// 职责：
//   表 `finance_rate` 是离线汇率包（RateTable）在本地 Room 的**按货币对拆行**
//   冗余缓存 —— 一个汇率包（effective_ts）对应 N 行（每个 "FROM/TO" 一对一行），
//   供 latest() / observeLatestTable() 快速拼回 RateTable，驱动看板多币种折算。
//
// 双写纪律（与 AttachmentEntity 同风格，但密文口径不同）：
//   - records 通道：一个生效时刻整包一条密文行（id="rate@${effective_ts}"，
//     type=FinanceModule.TYPE_RATE），**密文以 records 通道为唯一真理源**；
//   - 本地表：每个货币对一行，encrypted_payload 列在**本地导入**路径存空串占位
//     （upsertFinanceV2 只回 id 不回密文，不在端侧二次封包制造双份 nonce），
//     仅在 **pull 下行**路径写服务端密文（RecordEntity.ciphertext）。
//
// 幂等键：
//   - 行主键 id = "${base}/${quote}@${effective_ts}"（如 "USD/CNY@1735689600000"），
//     同一生效时刻重复导入走 Room @Upsert 的 REPLACE 覆盖，行数不翻倍；
//   - 包记录 id = "rate@${effective_ts}"，records 通道同键覆盖。
//
// 索引：
//   - (currency_base, currency_quote)：按货币对查历史汇率；
//   - (effective_ts)：取最新生效时刻（MAX(effective_ts)）；
//   - (dirty)：同步推送对账。
//
// 关联：
//   - android/.../data/finance/dao/FinanceRateDao.kt
//   - android/.../data/finance/RateTableRepository.kt
//   - android/.../finance/RateTable.kt（RateTable / RateTables 纯函数）
// ============================================================================

package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 离线汇率本地缓存表（stage5-finance-v2 / B5）。
 *
 * @param id 确定性主键 "${currencyBase}/${currencyQuote}@${effectiveTs}"。
 * @param currencyBase 源货币三字母代码（如 USD）。
 * @param currencyQuote 目标货币三字母代码（如 CNY）。
 * @param rate 汇率（1 单位 base 可兑换多少 quote；恒 > 0，由 RateTables.parse 保证）。
 * @param effectiveTs 汇率包生效时刻（Unix 毫秒）。
 * @param encryptedPayload 整包密文（base64，与 records 通道同形态）；本地导入路径
 *   存空串占位（密文以 records 通道为准），pull 下行路径写真实密文。
 * @param schemaVersion 本表 schema 版本号；本期固定 1。
 * @param module 模块标识，固定 "finance"。
 * @param createdAt 创建时刻（Unix 毫秒；下行取 records 行 createdAt）。
 * @param updatedAt 最近写入时刻（增量对账用）。
 * @param dirty 本地脏标记（Int；1 待推送对账，0 已干净）。
 * @param deleted 软删除墓碑（Int；本期汇率包无删除入口，保留与附件表同风格）。
 */
@Entity(
    tableName = "finance_rate",
    indices = [
        Index(
            value = ["currency_base", "currency_quote"],
            name = "idx_finance_rate_pair",
        ),
        Index(value = ["effective_ts"], name = "idx_finance_rate_effective_ts"),
        Index(value = ["dirty"], name = "idx_finance_rate_dirty"),
    ],
)
data class FinanceRateEntity(
    /** 确定性主键 "${base}/${quote}@${effectiveTs}"；同键重复写 REPLACE 覆盖。 */
    @PrimaryKey val id: String,

    /** 源货币三字母代码（ISO 4217 粗校验，如 "USD"）。 */
    @ColumnInfo(name = "currency_base") val currencyBase: String,

    /** 目标货币三字母代码（如 "CNY"）。 */
    @ColumnInfo(name = "currency_quote") val currencyQuote: String,

    /** 汇率（有限正数；解析层已校验）。 */
    val rate: Double,

    /** 汇率包生效时刻（Unix 毫秒）。 */
    @ColumnInfo(name = "effective_ts") val effectiveTs: Long,

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
