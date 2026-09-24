// ============================================================================
// 投资账户手动行情包仓库（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/QuoteTableRepository.kt
//
// 职责：
//   1) 导入行情包明文 JSON：QuoteTables.parse 校验 → records 通道整包密封上行
//      （type=quote，一个生效时刻一条包记录）→ 本地 finance_quote 按 symbol 拆行
//      缓存；
//   2) 拉取服务端下行 quote records → 解密 → 解析 → 按 symbol upsertAll 本地行
//      （真实密文落 encrypted_payload，dirty=0）；
//   3) pushChanges 对账：参照 RateTableRepository.pushChanges 的实际做法 ——
//      从本地行重建 records 载荷确保包记录存在，再把本地 dirty 行翻干净；
//      records 行自身的服务端推送与 markClean 由既有 RecordsRepository.sync 承接；
//   4) latest() 两步查最新一组行拼 QuoteTable；observeLatestTable() 供 UI 实时订阅。
//
// 幂等设计：
//   - 包记录 id = "quote@${ts}"，records 通道同键 REPLACE 覆盖；
//   - 行 id = "${symbol}@${ts}"，Room @Upsert 同键 REPLACE；
//   - 同生效时刻重复导入：行被覆盖而非追加，行数不翻倍，latest() 取到新值。
//
// 密文口径（重要，与 RateTableRepository 同风格）：
//   - 密文唯一真理源是 records 通道（type=quote）整包密文；
//   - RecordsRepository.upsertFinanceV2 只回 id 不回密文，本地导入路径**不**
//     二次封包（避免同明文产生双份 nonce 密文），encrypted_payload 存空串占位；
//   - pull 下行路径才把 RecordEntity.ciphertext（base64 整包密文）冗余到每行。
//
// 关联：
//   - android/.../finance/QuoteTable.kt（QuoteTable / QuoteTables.parse 纯函数）
//   - android/.../data/finance/dao/QuoteTableDao.kt
//   - android/.../data/RecordsRepository.kt（upsertFinanceV2 / decryptFinanceV2）
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.QuoteTableDao
import com.everything.eve.data.finance.entity.QuoteTableEntity
import com.everything.eve.finance.QuoteTable
import com.everything.eve.finance.QuoteTables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.TreeMap

/**
 * 投资账户手动行情包仓库（Task 8）。
 *
 * 与 [RateTableRepository] 同模板：导入 / pull / push / latest / observe 五件套。
 *
 * @param quoteDao 本地行情行 DAO。
 * @param recordsRepository records 密文通道（复用 upsertFinanceV2 / decryptFinanceV2）。
 * @param auth 鉴权管理器（密封 / 解密实际由 [recordsRepository] 内部消费 masterKey，
 *   本类不直接读取）。
 */
class QuoteTableRepository(
    /** 本地 finance_quote 表 DAO（按 symbol 拆行缓存）。 */
    private val quoteDao: QuoteTableDao,

    /** records 密文通道（type=quote 整包一条）。 */
    private val recordsRepository: RecordsRepository,

    /** 鉴权管理器（与 RateTableRepository 构造对齐）。 */
    @Suppress("unused")
    private val auth: AuthManager,
) {

    // ==========================================================================
    // 导入（UI 选择行情包 JSON → records 通道 + 本地拆行双写）
    // ==========================================================================

    /**
     * 导入一份行情包明文 JSON。
     *
     * 算法：
     *  1) [QuoteTables.parse] 严格校验（失败原样返回 [Result.failure]，含中文原因）；
     *  2) 确定性包记录 id = "quote@${ts}"，调
     *     [RecordsRepository.upsertFinanceV2] 整包密封入 records 通道（dirty=true，
     *     由既有 SyncWorker 推送）；同生效时刻重复导入幂等覆盖；
     *  3) 按 symbol 拆行 upsertAll 本地表：行 id = "${symbol}@${ts}"，
     *     encrypted_payload 存空串占位（密文以 records 通道为准，避免端侧二次封包
     *     制造双份 nonce；pull 下行路径才写真实密文），dirty=1；
     *  4) 返回解析后的 [QuoteTable]。
     *
     * @param plaintextJson 行情包明文 JSON（spec FR-V2-D.2 契约）。
     * @return 成功携带 [QuoteTable]；失败携带 [IllegalArgumentException]（中文原因）。
     */
    suspend fun importPackage(plaintextJson: String): Result<QuoteTable> {
        // ---- 步骤 1：解析校验（非法 JSON / 缺字段 / 价格非法全部在此拒绝）----
        val table = try {
            QuoteTables.parse(plaintextJson)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        // ---- 步骤 2：records 通道整包密封（同 ts 幂等覆盖）----
        val packageId = packageRecordId(table.ts)
        recordsRepository.upsertFinanceV2(FinanceModule.TYPE_QUOTE, packageId, plaintextJson)

        // ---- 步骤 3：本地按 symbol 拆行（密文留空，以 records 通道为准）----
        val now = System.currentTimeMillis()
        quoteDao.upsertAll(table.toEntities(now, now, encryptedPayload = "", dirty = 1))

        return Result.success(table)
    }

    // ==========================================================================
    // 读取（latest 两步查 / Flow 实时观察）
    // ==========================================================================

    /**
     * 取最新生效时刻的行情表。
     *
     * 两步：latestTs → listByTs → 按行的 symbol/currency/priceMinor/ts 列拼 Quote；
     * 空表 / 全墓碑返回 null。
     */
    suspend fun latest(): QuoteTable? {
        val ts = quoteDao.latestTs() ?: return null
        return rowsToTable(ts, quoteDao.listByTs(ts))
    }

    /**
     * 实时观察最新行情表：DAO Flow 已按 ts 降序，map 时取首组（最大 ts）的全部行
     * 拼 QuoteTable；空表发射 null。
     */
    fun observeLatestTable(): Flow<QuoteTable?> =
        quoteDao.observeLatest().map { rows ->
            if (rows.isEmpty()) {
                null
            } else {
                // 已按 ts DESC 排序，首行 ts 即最大值；同 ts 行成一组。
                val latestTs = rows.first().ts
                rowsToTable(latestTs, rows.filter { it.ts == latestTs })
            }
        }

    // ==========================================================================
    // 同步集成（CollectorWorker 尾部调用）
    // ==========================================================================

    /**
     * 拉取服务端下行 quote records → 解密 → 解析 → 按 symbol 入库。
     *
     * 算法（与 RateTableRepository.pullAndDecrypt 同款骨架）：
     *  1) 兜底过滤 module=finance + type=quote；
     *  2) 墓碑（deleted=true）当前无删除语义，跳过（行情包以新生效时刻覆盖，
     *     不做行级清扫；保留过滤位以便协议前向兼容）；
     *  3) [RecordsRepository.decryptFinanceV2] 解密 → [QuoteTables.parse] 解析；
     *     单包解密 / 解析失败 runCatching 跳过，不阻塞其余包（坏包不入库）；
     *  4) 按 symbol upsertAll：encrypted_payload=rec.ciphertext（真实整包 base64
     *     密文，仅下行路径有值），dirty=0（下行已对账干净），时间取 records 行；
     *  5) 同 ts 幂等 REPLACE；返回成功处理的包数。
     *
     * @param quoteRecords 调用方预过滤的 records 列表（内部再按 module + type 兜底）。
     * @return 成功解密并入库的包数量。
     */
    suspend fun pullAndDecrypt(quoteRecords: List<RecordEntity>): Int {
        var count = 0
        for (rec in quoteRecords) {
            // 兜底过滤：只处理 finance + quote
            if (rec.module != FinanceModule.MODULE || rec.type != FinanceModule.TYPE_QUOTE) continue
            // 墓碑：本期行情包无行级删除语义，跳过（新包以更大 ts 覆盖）。
            if (rec.deleted) continue

            // 单包失败（密钥未就绪 / 密文损坏 / 包格式非法）跳过，不阻塞其余包。
            val plain = runCatching { recordsRepository.decryptFinanceV2(rec) }.getOrNull()
                ?: continue
            val table = runCatching { QuoteTables.parse(plain) }.getOrNull()
                ?: continue

            quoteDao.upsertAll(
                table.toEntities(
                    createdAt = rec.createdAt,
                    updatedAt = rec.updatedAt,
                    // 下行路径：records 通道 base64 整包密文冗余到每行。
                    encryptedPayload = rec.ciphertext,
                    dirty = 0,
                ),
            )
            count += 1
        }
        return count
    }

    /**
     * 推送对账：确保每个含 dirty 行的生效时刻在 records 通道存在整包记录，
     * 然后把本地 dirty 行翻干净。
     *
     * 策略（与 RateTableRepository.pushChanges 同款）：
     *  1) dirtyList 取 dirty=1 行，按 ts 分组；
     *  2) 每个 ts 用 listByTs 取该时刻**全部**行（不能只用 dirty 子集，
     *     否则会推丢 symbol），TreeMap 排序重建稳定 JSON 整包，best-effort
     *     upsertFinanceV2("quote", "quote@$ts", json) 兜底对账 —— 正常导入路径
     *     records 包已存在，此处为同内容覆盖；异常不阻断翻干净；
     *  3) 逐行 markDirty(id, 0)。
     *
     * @return 本次翻干净的本地行数量。
     */
    suspend fun pushChanges(): Int {
        val dirty = quoteDao.dirtyList()
        if (dirty.isEmpty()) return 0

        // 按生效时刻分组，逐包确保 records 通道存在。
        val tsSet = dirty.map { it.ts }.distinct()
        for (ts in tsSet) {
            // 取该时刻全部行（含已干净行），保证重建包不缺 symbol。
            val fullRows = quoteDao.listByTs(ts)
            val rowsForRebuild = fullRows.ifEmpty {
                dirty.filter { it.ts == ts }
            }
            if (rowsForRebuild.isEmpty()) continue
            val rebuiltJson = rebuildPackageJson(ts, rowsForRebuild)
            // best-effort：导入路径已写过原始包，此处仅兜底；失败不阻断本地对账。
            runCatching {
                recordsRepository.upsertFinanceV2(
                    FinanceModule.TYPE_QUOTE,
                    packageRecordId(ts),
                    rebuiltJson,
                )
            }
        }

        // 本地行翻干净。
        for (entity in dirty) {
            quoteDao.markDirty(entity.id, 0)
        }
        return dirty.size
    }

    // ==========================================================================
    // 私有辅助
    // ==========================================================================

    /**
     * 把一组同 ts 的行拼回 [QuoteTable]；行组为空返回 null。
     *
     * quotes 使用 LinkedHashMap 保持 TreeMap 的字典序（与导入包输出口径一致），
     * base 从该 ts 行的首条记录推断（理论上整包 base 一致；取首条覆盖）。
     */
    private fun rowsToTable(ts: Long, rows: List<QuoteTableEntity>): QuoteTable? {
        if (rows.isEmpty()) return null
        // TreeMap 保证 key 排序稳定，与导入包的输出口径一致。
        val sorted = TreeMap<String, QuoteTableEntity>()
        for (row in rows) {
            sorted[row.symbol] = row
        }
        val quotes = LinkedHashMap<String, com.everything.eve.finance.Quote>(sorted.size)
        var baseInferred: String? = null
        for ((symbol, row) in sorted) {
            quotes[symbol] = com.everything.eve.finance.Quote(
                symbol = row.symbol,
                priceMinor = row.priceMinor,
                currency = row.currency,
                ts = row.ts,
            )
            // 推断 base：取首条行（非空即采纳；与 RateTable 同款宽松口径）。
            if (baseInferred == null) baseInferred = row.currency
        }
        // 行情包 base 在 Room 行无独立列，本表不强制声明基准币 → 一律为 null，
        // aggregator 走 RateTable 按 holdings 实际币种折算。
        return QuoteTable(ts = ts, quotes = quotes, base = null)
    }

    /**
     * 把 [QuoteTable] 拆成本地行（一个 symbol 一行）。
     *
     * @param encryptedPayload 整包密文（本地导入空串占位；pull 下行写真实密文）。
     * @param dirty 本地脏标记（导入 1；下行 0）。
     */
    private fun QuoteTable.toEntities(
        createdAt: Long,
        updatedAt: Long,
        encryptedPayload: String,
        dirty: Int,
    ): List<QuoteTableEntity> = quotes.map { (_, q) ->
        QuoteTableEntity(
            id = "${q.symbol}@$ts",
            symbol = q.symbol,
            priceMinor = q.priceMinor,
            currency = q.currency,
            ts = q.ts,
            encryptedPayload = encryptedPayload,
            schemaVersion = 1,
            module = FinanceModule.MODULE,
            createdAt = createdAt,
            updatedAt = updatedAt,
            dirty = dirty,
            deleted = 0,
        )
    }

    /**
     * 从本地行重建行情包明文 JSON（pushChanges 兜底用）。
     *
     * quotes 用 [TreeMap] 按 symbol 字典序输出，保证同内容重建字节结构稳定；
     * 输出契约与 QuoteTables.parse 输入一致（version=1 / ts / quotes）。
     */
    private fun rebuildPackageJson(ts: Long, rows: List<QuoteTableEntity>): String {
        val sorted = TreeMap<String, QuoteTableEntity>()
        for (row in rows) {
            sorted[row.symbol] = row
        }
        val arr = org.json.JSONArray()
        for ((_, row) in sorted) {
            val obj = JSONObject()
            obj.put("symbol", row.symbol)
            obj.put("price_minor", row.priceMinor)
            obj.put("currency", row.currency)
            obj.put("ts", row.ts)
            arr.put(obj)
        }
        return JSONObject()
            .put("version", 1)
            .put("ts", ts)
            .put("quotes", arr)
            .toString()
    }

    /** 包记录确定性 id："quote@${ts}"（records 通道幂等键）。 */
    private fun packageRecordId(ts: Long): String = "quote@$ts"
}