// ============================================================================
// 离线汇率包仓库（stage5-finance-v2 / B5 / FR-V2-C.2、FR-V2-C.3）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/RateTableRepository.kt
//
// 职责：
//   1) 导入汇率包明文 JSON：RateTables.parse 校验 → records 通道整包密封上行
//      （type=rate，一个生效时刻一条包记录）→ 本地 finance_rate 按货币对拆行缓存；
//   2) 拉取服务端下行 rate records → 解密 → parse → 按货币对 upsertAll 本地行
//      （真实密文落 encrypted_payload，dirty=0）；
//   3) pushChanges 对账：参照 AttachmentRepository.pushChanges 的实际做法 ——
//      从本地行重建 records 载荷确保包记录存在，再把本地 dirty 行翻干净；
//      records 行自身的服务端推送与 markClean 由既有 RecordsRepository.sync 承接；
//   4) latest() 两步查最新一组行拼 RateTable；observeLatestTable() 供 UI 实时订阅。
//
// 幂等设计：
//   - 包记录 id = "rate@${effectiveTs}"，records 通道同键 REPLACE 覆盖；
//   - 行 id = "${base}/${quote}@${effectiveTs}"，Room @Upsert 同键 REPLACE；
//   - 同生效时刻重复导入：行被覆盖而非追加，行数不翻倍，latest() 取到新值。
//
// 密文口径（重要）：
//   - 密文唯一真理源是 records 通道（type=rate）整包密文；
//   - RecordsRepository.upsertFinanceV2 只回 id 不回密文，本地导入路径**不**
//     二次封包（避免同明文产生双份 nonce 密文），encrypted_payload 存空串占位；
//   - pull 下行路径才把 RecordEntity.ciphertext（base64 整包密文）冗余到每行。
//
// 关联：
//   - android/.../finance/RateTable.kt（RateTable / RateTables.parse 纯函数）
//   - android/.../data/finance/dao/FinanceRateDao.kt
//   - android/.../data/RecordsRepository.kt（upsertFinanceV2 / decryptFinanceV2）
//   - android/.../data/finance/AttachmentRepository.kt（pushChanges 同骨架）
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.FinanceRateDao
import com.everything.eve.data.finance.entity.FinanceRateEntity
import com.everything.eve.finance.RateTable
import com.everything.eve.finance.RateTables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.TreeMap

/**
 * 离线汇率包仓库（B5 多币种折算的数据闭环）。
 *
 * @param rateDao 本地汇率行 DAO。
 * @param recordsRepository records 密文通道（复用 upsertFinanceV2 / decryptFinanceV2）。
 * @param auth 鉴权管理器（构造与 AttachmentRepository 对齐；密封 / 解密实际由
 *   [recordsRepository] 内部消费 masterKey，本类不直接读取）。
 */
class RateTableRepository(
    /** 本地 finance_rate 表 DAO（按货币对拆行缓存）。 */
    private val rateDao: FinanceRateDao,

    /** records 密文通道（type=rate 整包一条）。 */
    private val recordsRepository: RecordsRepository,

    /** 鉴权管理器（与 AttachmentRepository 构造对齐）。 */
    @Suppress("unused")
    private val auth: AuthManager,
) {

    // ==========================================================================
    // 导入（UI 选择汇率包 JSON → records 通道 + 本地拆行双写）
    // ==========================================================================

    /**
     * 导入一份汇率包明文 JSON。
     *
     * 算法：
     *  1) [RateTables.parse] 严格校验（失败原样返回 [Result.failure]，含中文原因）；
     *  2) 确定性包记录 id = "rate@${effectiveTs}"，调
     *     [RecordsRepository.upsertFinanceV2] 整包密封入 records 通道（dirty=true，
     *     由既有 SyncWorker 推送）；同生效时刻重复导入幂等覆盖；
     *  3) 按货币对拆行 upsertAll 本地表：行 id = "${pair}@${effectiveTs}"，
     *     encrypted_payload 存空串占位（密文以 records 通道为准，避免端侧二次封包
     *     制造双份 nonce；pull 下行路径才写真实密文），dirty=1；
     *  4) 返回解析后的 [RateTable]。
     *
     * @param plaintextJson 汇率包明文 JSON（spec FR-V2-C.2 契约）。
     * @return 成功携带 [RateTable]；失败携带 [IllegalArgumentException]（中文原因）。
     */
    suspend fun importPackage(plaintextJson: String): Result<RateTable> {
        // ---- 步骤 1：解析校验（非法 JSON / 缺字段 / 汇率非正全部在此拒绝）----
        val table = try {
            RateTables.parse(plaintextJson)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }

        // ---- 步骤 2：records 通道整包密封（同 effectiveTs 幂等覆盖）----
        val packageId = packageRecordId(table.effectiveTs)
        recordsRepository.upsertFinanceV2(FinanceModule.TYPE_RATE, packageId, plaintextJson)

        // ---- 步骤 3：本地按货币对拆行（密文留空，以 records 通道为准）----
        val now = System.currentTimeMillis()
        rateDao.upsertAll(table.toEntities(now, now, encryptedPayload = "", dirty = 1))

        return Result.success(table)
    }

    // ==========================================================================
    // 读取（latest 两步查 / Flow 实时观察）
    // ==========================================================================

    /**
     * 取最新生效时刻的汇率表。
     *
     * 两步：latestEffectiveTs → listByEffectiveTs → 按行的 base/quote 列拼
     * "FROM/TO" → rate 映射；空表 / 全墓碑返回 null。
     */
    suspend fun latest(): RateTable? {
        val ts = rateDao.latestEffectiveTs() ?: return null
        return rowsToTable(ts, rateDao.listByEffectiveTs(ts))
    }

    /**
     * 实时观察最新汇率表：DAO Flow 已按 effective_ts 降序，map 时取首组
     * （最大 effective_ts）的全部行拼表；空表发射 null。
     */
    fun observeLatestTable(): Flow<RateTable?> =
        rateDao.observeLatest().map { rows ->
            if (rows.isEmpty()) {
                null
            } else {
                // 已按 effective_ts DESC 排序，首行 ts 即最大值；同 ts 行成一组。
                val latestTs = rows.first().effectiveTs
                rowsToTable(latestTs, rows.filter { it.effectiveTs == latestTs })
            }
        }

    // ==========================================================================
    // 同步集成（CollectorWorker 尾部调用）
    // ==========================================================================

    /**
     * 拉取服务端下行 rate records → 解密 → 解析 → 按货币对入库。
     *
     * 算法（与 AttachmentRepository.pullAndDecrypt 同款骨架）：
     *  1) 兜底过滤 module=finance + type=rate；
     *  2) 墓碑（deleted=true）当前无删除语义，跳过（汇率包以新生效时刻覆盖，
     *     不做行级清扫；保留过滤位以便协议前向兼容）；
     *  3) [RecordsRepository.decryptFinanceV2] 解密 → [RateTables.parse] 解析；
     *     单包解密 / 解析失败 runCatching 跳过，不阻塞其余包（坏包不入库）；
     *  4) 按货币对 upsertAll：encrypted_payload=rec.ciphertext（真实整包 base64
     *     密文，仅下行路径有值），dirty=0（下行已对账干净），时间取 records 行；
     *  5) 同 effectiveTs 幂等 REPLACE；返回成功处理的包数。
     *
     * @param rateRecords 调用方预过滤的 records 列表（内部再按 module + type 兜底）。
     * @return 成功解密并入库的包数量。
     */
    suspend fun pullAndDecrypt(rateRecords: List<RecordEntity>): Int {
        var count = 0
        for (rec in rateRecords) {
            // 兜底过滤：只处理 finance + rate
            if (rec.module != FinanceModule.MODULE || rec.type != FinanceModule.TYPE_RATE) continue
            // 墓碑：本期汇率包无行级删除语义，跳过（新包以更大 effectiveTs 覆盖）。
            if (rec.deleted) continue

            // 单包失败（密钥未就绪 / 密文损坏 / 包格式非法）跳过，不阻塞其余包。
            val plain = runCatching { recordsRepository.decryptFinanceV2(rec) }.getOrNull()
                ?: continue
            val table = runCatching { RateTables.parse(plain) }.getOrNull()
                ?: continue

            rateDao.upsertAll(
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
     * 策略（读 AttachmentRepository.pushChanges 后对齐其实际做法：从本地行重建
     * records 载荷 → upsert 通道 → 本地 markDirty=0；records 行自身的服务端推送
     * 与服务端时间回写由既有 RecordsRepository.sync 承接，本方法不直连 API）：
     *  1) dirtyList 取 dirty=1 行，按 effectiveTs 分组；
     *  2) 每个 ts 用 listByEffectiveTs 取该时刻**全部**行（不能只用 dirty 子集，
     *     否则会推丢货币对），TreeMap 排序重建稳定 JSON 整包，best-effort
     *     upsertFinanceV2("rate", "rate@$ts", json) 兜底对账 —— 正常导入路径
     *     records 包已存在，此处为同内容覆盖；异常不阻断翻干净；
     *  3) 逐行 markDirty(id, 0)。
     *
     * @return 本次翻干净的本地行数量。
     */
    suspend fun pushChanges(): Int {
        val dirty = rateDao.dirtyList()
        if (dirty.isEmpty()) return 0

        // 按生效时刻分组，逐包确保 records 通道存在。
        val effectiveTsSet = dirty.map { it.effectiveTs }.distinct()
        for (ts in effectiveTsSet) {
            // 取该时刻全部行（含已干净行），保证重建包不缺货币对。
            val fullRows = rateDao.listByEffectiveTs(ts)
            val rowsForRebuild = fullRows.ifEmpty {
                dirty.filter { it.effectiveTs == ts }
            }
            if (rowsForRebuild.isEmpty()) continue
            val rebuiltJson = rebuildPackageJson(ts, rowsForRebuild)
            // best-effort：导入路径已写过原始包，此处仅兜底；失败不阻断本地对账。
            runCatching {
                recordsRepository.upsertFinanceV2(
                    FinanceModule.TYPE_RATE,
                    packageRecordId(ts),
                    rebuiltJson,
                )
            }
        }

        // 本地行翻干净（与 AttachmentRepository.pushChanges 逐行 markDirty 同款）。
        for (entity in dirty) {
            rateDao.markDirty(entity.id, 0)
        }
        return dirty.size
    }

    // ==========================================================================
    // 私有辅助
    // ==========================================================================

    /**
     * 把一组同 effectiveTs 的行拼回 [RateTable]；行组为空返回 null。
     * key 直接用行的 base/quote 列拼 "FROM/TO"（不依赖 id 字符串切分约定）。
     */
    private fun rowsToTable(effectiveTs: Long, rows: List<FinanceRateEntity>): RateTable? {
        if (rows.isEmpty()) return null
        // TreeMap 保证 key 排序稳定，与导入包的输出口径一致。
        val rates = TreeMap<String, Double>()
        for (row in rows) {
            rates["${row.currencyBase}/${row.currencyQuote}"] = row.rate
        }
        return RateTable(effectiveTs = effectiveTs, rates = rates)
    }

    /**
     * 把 [RateTable] 拆成本地行（一个货币对一行）。
     *
     * @param encryptedPayload 整包密文（本地导入空串占位；pull 下行写真实密文）。
     * @param dirty 本地脏标记（导入 1；下行 0）。
     */
    private fun RateTable.toEntities(
        createdAt: Long,
        updatedAt: Long,
        encryptedPayload: String,
        dirty: Int,
    ): List<FinanceRateEntity> = rates.map { (pair, rate) ->
        // pair 已由 RateTables.parse 正则保证形如 "USD/CNY"。
        val base = pair.substring(0, 3)
        val quote = pair.substring(4, 7)
        FinanceRateEntity(
            id = "$pair@$effectiveTs",
            currencyBase = base,
            currencyQuote = quote,
            rate = rate,
            effectiveTs = effectiveTs,
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
     * 从本地行重建汇率包明文 JSON（pushChanges 兜底用）。
     *
     * rates 用 [TreeMap] 按 key 字典序输出，保证同内容重建字节结构稳定；
     * 输出契约与 RateTables.parse 输入一致（version=1 / effective_ts / rates）。
     */
    private fun rebuildPackageJson(effectiveTs: Long, rows: List<FinanceRateEntity>): String {
        val sorted = TreeMap<String, Double>()
        for (row in rows) {
            sorted["${row.currencyBase}/${row.currencyQuote}"] = row.rate
        }
        val ratesObj = JSONObject()
        for ((pair, rate) in sorted) {
            ratesObj.put(pair, rate)
        }
        return JSONObject()
            .put("version", 1)
            .put("effective_ts", effectiveTs)
            .put("rates", ratesObj)
            .toString()
    }

    /** 包记录确定性 id："rate@${effectiveTs}"（records 通道幂等键）。 */
    private fun packageRecordId(effectiveTs: Long): String = "rate@$effectiveTs"
}
