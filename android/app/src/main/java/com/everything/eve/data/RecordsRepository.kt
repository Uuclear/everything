package com.everything.eve.data

import com.everything.eve.api.BatchRequest
import com.everything.eve.api.RemoteRecord
import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

/**
 * 离线优先资料库：本地 Room 为唯一读来源；写入先落本地（dirty），
 * 再由 SyncWorker 推送并增量拉取。
 *
 * 模块兼容（阶段 1 → 阶段 2）：
 *  - 新笔记写 module='pass'/type='note'（与 Web、docs/module-schemas.md 对齐）；
 *  - 历史 module='note'/type='secure_note' 继续可读，AAD 用其自身 module 解；
 *  - login/card/identity 记录只同步保存，不在笔记 UI 暴露（Android 本期不做其完整 UI）。
 */
class RecordsRepository(
    private val dao: RecordDao,
    private val auth: AuthManager,
) {
    private val api get() = com.everything.eve.ServiceLocator.api

    fun observeNotes(): Flow<List<RecordEntity>> = dao.observeNotes()

    /** 最近一次成功同步的毫秒时间戳流（顶栏“上次同步 HH:mm”）。 */
    fun observeLastSuccessfulSync(): Flow<Long?> =
        dao.observeSyncState(SyncStateEntity.KEY_LAST_SUCCESSFUL_SYNC)
            .map { it?.toLongOrNull() }

    /** 新建一条加密安全笔记（pass/note）。返回记录 id。 */
    suspend fun createNote(title: String, body: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val plaintext = JSONObject()
            .put("title", title)
            .put("body", body)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plaintext, id, MODULE_PASS, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = id,
                    module = MODULE_PASS,
                    type = TYPE_NOTE,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    dirty = true,
                ),
            ),
        )
        return id
    }

    /** 本地解密（MK 仅内存）；新旧模块的 AAD 都按记录自身 module 计算，天然兼容。 */
    fun decryptNote(entity: RecordEntity): Pair<String, String> {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() } ?: error("资料库未解锁")
        val plain = CryptoEnvelope.openRecord(
            mk,
            CryptoEnvelope.unb64(entity.ciphertext),
            entity.id,
            entity.module,
            entity.version,
        )
        val obj = JSONObject(String(plain, Charsets.UTF_8))
        return obj.optString("title") to obj.optString("body")
    }

    /** WorkManager 调用：推脏数据 + 增量拉取；成功后记录同步时间戳。 */
    suspend fun sync() {
        if (!auth.isLoggedIn) return

        val dirty = dao.dirtyRecords()
        if (dirty.isNotEmpty()) {
            val remote = dirty.map {
                RemoteRecord(
                    id = it.id,
                    module = it.module,
                    type = it.type,
                    ciphertext = it.ciphertext,
                    version = it.version,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deleted = it.deleted,
                )
            }
            // 单批 ≤1000 与服务端约束保持一致
            remote.chunked(1000).forEach { chunk ->
                val result = api.pushRecords(BatchRequest(chunk))
                if (result.applied + result.skipped == chunk.size) {
                    // 用服务端权威时间覆盖本地 updatedAt：拉取游标 maxUpdatedAt 由此只反映
                    // 服务端时钟，本地设备时钟偏快也不会导致漏拉（FU-1）。
                    dao.markClean(chunk.map { it.id }, result.serverTime)
                }
            }
        }

        var since = dao.maxUpdatedAt()
        // 首次全量拉取（since=0）；之后按 updated_at 游标翻页，未知 module/type 也原样入库。
        while (true) {
            val resp = api.listRecords(since, 500)
            if (resp.records.isEmpty()) break
            dao.upsertAll(
                resp.records.map { r ->
                    RecordEntity(
                        id = r.id,
                        module = r.module,
                        type = r.type,
                        ciphertext = r.ciphertext,
                        version = r.version,
                        createdAt = r.createdAt,
                        updatedAt = r.updatedAt,
                        deleted = r.deleted,
                        dirty = false,
                    )
                },
            )
            since = resp.records.maxOf { it.updatedAt }
            if (!resp.hasMore) break
        }

        dao.putSyncState(
            SyncStateEntity(
                SyncStateEntity.KEY_LAST_SUCCESSFUL_SYNC,
                System.currentTimeMillis().toString(),
            ),
        )
    }

    // ========== 阶段 4b：日程/日历模块（spec FR-3 / FR-4）==========
    // event 作为一条 records 记录写入：module="event"、type="event"，
    // AAD 沿用 "eve:v1:record:{id}"（与既有 records 逐字节一致）；
    // 明文载荷是 EventRule JSON（spec FR-1 字段表）。
    // 同步由 SyncWorker 既有周期触发；EventsRepository 仅调本类三方法做
    // 明文 → 密文 → records 表入库 → 标 dirty；不触碰加密原语。
    //
    // 复用 4a RecordsRepository 既有链路：与 createNote / sync() 同款调用——
    // 同样的 CryptoEnvelope.sealRecord 参数、同样的 dao.upsertAll 写库、同样的
    // dirty=true 标记；**不新造** envelope / seal / open 路径。

    /** event 模块常量：与 module-schemas.md 第 8 章 / Web types.ts 字段表一致。 */
    private val moduleEvent = "event"
    private val typeEvent = "event"

    /**
     * 把一条 EventRule 明文密封为 records 条目（module=event/type=event），并标 dirty。
     *
     * 同 id 已存在则按 records REPLACE 覆盖（updatedAt 取当前本地时钟；SyncWorker
     * 推送成功后会被服务端权威时间覆盖，FU-1）。
     *
     * @return 写入的 record id（即 rule.id，与传入一致）。
     * @throws IllegalStateException MK 未解锁。
     */
    suspend fun upsertEventRule(ruleId: String, plaintextJson: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val now = System.currentTimeMillis()
        val plain = plaintextJson.toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plain, ruleId, moduleEvent, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = ruleId,
                    module = moduleEvent,
                    type = typeEvent,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    dirty = true,
                ),
            ),
        )
        return ruleId
    }

    /**
     * 删除一条 event 记录：以 tombstone 形式覆盖 records 行（deleted=true，
     * ciphertext 置空字符串，避免服务端再次推送失败；与既有 records 通道
     * 沿用 4a RecordsRepository 同款协议）。
     */
    suspend fun deleteEventRule(ruleId: String) {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        // 读取既有 records 行的 createdAt（如有），保证 tombstone 不丢失原始创建时刻
        val existing = dao.getById(ruleId)
        val createdAt = existing?.createdAt ?: System.currentTimeMillis()
        val now = System.currentTimeMillis()
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = ruleId,
                    module = moduleEvent,
                    type = typeEvent,
                    ciphertext = "", // tombstone 不带密文，服务端按 deleted=true 清扫
                    version = 1,
                    createdAt = createdAt,
                    updatedAt = now,
                    deleted = true,
                    dirty = true,
                ),
            ),
        )
    }

    /**
     * 解密一条 event 记录密文回明文 JSON（SyncWorker pull 后入库 Room 用）。
     *
     * @return 明文 JSON 字符串（与 upsertEventRule 的 plaintextJson 同格式）。
     * @throws IllegalStateException MK 未解锁。
     * @throws javax.crypto.AEADBadTagException 密文/AAD 不匹配（被改或非 event 模块）。
     */
    fun decryptEventRule(entity: RecordEntity): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val plain = CryptoEnvelope.openRecord(
            mk,
            CryptoEnvelope.unb64(entity.ciphertext),
            entity.id,
            entity.module,
            entity.version,
        )
        return String(plain, Charsets.UTF_8)
    }

    /**
     * 同步 pull 后服务端下行 event 条目（已密文）；直接落本地 records 表
     * dirty=false（沿用 4a RecordsRepository.sync() 同款 upsertAll 模式）。
     */
    suspend fun ingestRemoteEvent(
        id: String,
        ciphertext: String,
        createdAt: Long,
        updatedAt: Long,
    ) {
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = id,
                    module = moduleEvent,
                    type = typeEvent,
                    ciphertext = ciphertext,
                    version = 1,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    deleted = false,
                    dirty = false,
                ),
            ),
        )
    }

    // =============================================================================
    // 阶段 5：财务模块（spec FR-7 / FR-8 / FR-9；TR-4.6 补做挂载点）。
    // =============================================================================
    // finance 作为一条 records 记录写入：module="finance"、type="account"/"card"/"tx"，
    // AAD 沿用 "eve:v1:record:{id}:finance:"（由 CryptoEnvelope.recordAAD 内置 + BE(uint64 version)），
    // 与既有 pass/event 链路**逐字节一致**；明文载荷为对应财务实体的 JSON。
    //
    // 设计纪律：
    //  1) **不新造 envelope 路径**：完全复用 CryptoEnvelope.sealRecord / openRecord；
    //  2) **dirty 标记**：本方法写完后 records 行 dirty=true，等待 RecordsRepository.sync()
    //     周期推送；推送成功后由 markClean 翻 false（与 4a NotesRepository 4b EventsRepository
    //     既有规则一致）；
    //  3) **零知识纪律**：name / last4 / amount / note / holder 等明文载荷**不**打印日志、
    //     **不**写 SharedPreferences；写 Records 行 ciphertext 加密字段、由 SyncWorker 上行。
    //  4) **type 子类型**：account / card / tx 用同一个 records module=finance 通道，
    //     type 字段区分类型；AAD 与 version 通道口径完全一致，下行解密按 type 反序列化即可。
    //  5) **decryptFinanceRecord 抛异常**：解密失败（密文被改 / AAD 不匹配 / 类型非 finance）
    //     直接抛 javax.crypto.AEADBadTagException，由调用方（FinanceRepository.pullAndDecrypt）
    //     自行决定是否降级；不复用 4a NotesRepository 的静默吞掉（与 4b EventsRepository 一致）。

    /** finance 模块常量：与 Web types.ts FINANCE_MODULE = 'finance' 严格一致。 */
    private val moduleFinance = "finance"

    /** finance 子类型：账户。 */
    private val typeFinanceAccount = "account"

    /** finance 子类型：银行卡。 */
    private val typeFinanceCard = "card"

    /** finance 子类型：流水（transaction）。 */
    private val typeFinanceTx = "tx"

    /**
     * 把一条 FinanceAccount 明文密封为 records 条目（module=finance/type=account），
     * 并标 dirty。
     *
     * 与 4b [upsertEventRule] 同款链路：sealRecord → dao.upsertAll → dirty=true。
     * 同 id 已存在则按 records REPLACE 覆盖；updatedAt 取本地时钟（推送后由
     * RecordsRepository.sync() 标记 clean 时用服务端权威时间覆盖）。
     *
     * @param accountId 账户 UUID（即 entity.id，与 FinanceAccountEntity.id 对齐）。
     * @param plaintextJson 账户明文载荷 JSON（与 finance/types.ts 对齐的 snake_case 字段）。
     * @return 写入的 record id（即 accountId）。
     * @throws IllegalStateException MK 未解锁。
     */
    suspend fun upsertFinanceAccount(accountId: String, plaintextJson: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val now = System.currentTimeMillis()
        val plain = plaintextJson.toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plain, accountId, moduleFinance, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = accountId,
                    module = moduleFinance,
                    type = typeFinanceAccount,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    dirty = true,
                ),
            ),
        )
        return accountId
    }

    /**
     * 把一条 FinanceCard 明文密封为 records 条目（module=finance/type=card），
     * 并标 dirty。
     *
     * **零知识纪律（spec NFR-1 / Luhn 校验）**：plaintextJson **必须只含 last4**，
     * 完整 PAN 由编辑器在调用前丢弃，不进 Room / 不进 ciphertext 载荷；
     * 本方法**不**额外拦截完整卡号（编辑器契约）。
     *
     * @param cardId 卡片 UUID。
     * @param plaintextJson 卡片明文载荷 JSON（仅 last4；不含完整 PAN）。
     * @return 写入的 record id（即 cardId）。
     * @throws IllegalStateException MK 未解锁。
     */
    suspend fun upsertFinanceCard(cardId: String, plaintextJson: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val now = System.currentTimeMillis()
        val plain = plaintextJson.toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plain, cardId, moduleFinance, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = cardId,
                    module = moduleFinance,
                    type = typeFinanceCard,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    dirty = true,
                ),
            ),
        )
        return cardId
    }

    /**
     * 把一条 FinanceTx 明文密封为 records 条目（module=finance/type=tx），
     * 并标 dirty。
     *
     * 关联账户/卡删除后历史流水保留 account_id / card_id 引用；本方法**不**做
     * 关联校验（编辑器契约）。
     *
     * @param txId 流水 UUID。
     * @param plaintextJson 流水明文载荷 JSON（含 amount / occurred_at / currency / note 等）。
     * @return 写入的 record id（即 txId）。
     * @throws IllegalStateException MK 未解锁。
     */
    suspend fun upsertFinanceTx(txId: String, plaintextJson: String): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val now = System.currentTimeMillis()
        val plain = plaintextJson.toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(mk, plain, txId, moduleFinance, 1)
        dao.upsertAll(
            listOf(
                RecordEntity(
                    id = txId,
                    module = moduleFinance,
                    type = typeFinanceTx,
                    ciphertext = CryptoEnvelope.b64(sealed),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    deleted = false,
                    dirty = true,
                ),
            ),
        )
        return txId
    }

    /**
     * 解密一条 finance 记录密文回明文 JSON（FinanceRepository.pullAndDecrypt 入库用）。
     *
     * 与 4b [decryptEventRule] 同款口径：解密失败（AAD 不匹配 / 模块非 finance /
     * 密文被改）抛 javax.crypto.AEADBadTagException，由 FinanceRepository 决定
     * 是否降级。**不**静默吞掉，避免脏数据入库（与 4b 纪律一致）。
     *
     * @param entity 已落 records 表的 entity（ciphertext / module / version 来自下行）。
     * @return 明文 JSON 字符串（与 upsertFinance* 的 plaintextJson 同格式）。
     * @throws IllegalStateException MK 未解锁。
     * @throws javax.crypto.AEADBadTagException 密文/AAD 不匹配。
     */
    fun decryptFinanceRecord(entity: RecordEntity): String {
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: error("资料库未解锁")
        val plain = CryptoEnvelope.openRecord(
            mk,
            CryptoEnvelope.unb64(entity.ciphertext),
            entity.id,
            entity.module,
            entity.version,
        )
        return String(plain, Charsets.UTF_8)
    }

    companion object {
        const val MODULE_PASS = "pass"
        const val TYPE_NOTE = "note"

        // ---- 阶段 4b：日程/日历模块挂载点（spec FR-3 / FR-4） ----
        // event 模块沿用既有 records 通道，module/type 双键约定与 place/pass 一致；
        // AAD 沿用 "eve:v1:record:{id}"，**不新造** envelope 参数；
        // 详见 [com.everything.eve.data.event.EventsRepository] 加密链路说明。

        // ---- 阶段 5：财务模块挂载点（TR-4.6 / TR-11.2） ----
        // finance 模块 module=finance/type=account|card|tx；AAD 沿用既有 records
        // 通道（详情见 [com.everything.eve.data.finance.FinanceRepository]）。
    }

    /**
     * 取本地 records 表 updatedAt 大于 sinceMs 的所有行（含 dirty 本地新写 + 远端下行）。
     *
     * 阶段 5 / TR-11.2 CollectorWorker 专用：先调此方法取出 records 列表，
     * 然后按 module 过滤后交给各模块 Repository 做解密与入库（finance / event 同款模式）。
     *
     * @param sinceMs 毫秒游标；sinceMs<=0 即全量。
     * @return 命中行列表（按 updatedAt 升序）。
     */
    suspend fun listRecordsAfter(sinceMs: Long): List<RecordEntity> {
        val cursor = if (sinceMs <= 0) 0L else sinceMs
        return dao.getUpdatedAfter(cursor)
    }
}
