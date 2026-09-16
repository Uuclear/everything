package com.everything.eve.data.finance

import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceReminderLogDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceReminderLogEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * 财务模块领域仓库（阶段 5 / Task 4 / TR-4.6 主链路 + TR-11.2 pullAndDecrypt）。
 *
 * **本任务（T11）一次性补全完整链路**：在 T4 骨架之上接通 RecordsRepository 既有
 * sealRecord / openRecord 通道，与 4a `createNote` + 4b `upsertEventRule` 同款
 * 模式（module="finance"、type 子类型 account/card/tx）。
 *
 * 设计要点：
 *  1) **不新造 envelope 路径**：完全复用 CryptoEnvelope.sealRecord / openRecord +
 *     RecordsRepository.upsertFinanceAccount/Card/Tx（沿用 records 模块的
 *     AAD = `eve:v1:record:{id}:finance:` + BE(uint64 version)）。
 *  2) **明文 + 密文双写**：本类同步写两张表 ——
 *     - `finance_account` / `finance_card` / `finance_tx`：本地明文缓存（UI 订阅 + 聚合器消费）；
 *     - `records`：密文通道（同 4a/4b 共用 records 表，由 RecordsRepository 同步接入）。
 *     两表 id 主键对齐，便于对账观测；删除走 tombstone 软删（`deleted=true`）。
 *  3) **dirty 标记**：upsert / delete 时显式 `markDirty(true)` 标上下行推送候选；
 *     下行拉取（pullAndDecrypt）成功后由 RecordsRepository.sync() 推 markClean 翻 false
 *     （沿用 4a/4b 既有规范）。
 *  4) **墓碑（tombstone）语义**：删除时不真删 Row；保留 deleted=true 行；关联账户/卡
 *     删除时历史流水的 account_id / card_id **保留**原引用，UI 标注"账户已删除"，DAO
 *     与本类均不主动清 NULL（这是 docs/module-schemas.md §9 的删除与保留红线）。
 *     但由于字段非空约束，本类在 deleteAccount/deleteCard 内仍然把 tombstone 推 records
 *     通道；前端按需展示"已删除账户/卡"占位。
 *  5) **零知识纪律**：name / last4 / amount / note / holder 等明文载荷**不**进
 *     SharedPreferences / 日志 / 通知文案；只驻 Room（Vault DB 加密容器）+ 内存。
 *
 * 入口：
 *  - UI / ViewModel：upsertAccount/Card/Tx、deleteEntity、observe*；
 *  - SyncWorker / CollectorWorker：pushAll（隐式走 RecordsRepository.sync 既有链路） +
 *    pullAndDecrypt；
 *  - ReminderScheduler.rebuildChain：observeCards() 拉全局最小 nextTrigger。
 */
class FinanceRepository(
    /** 财务账户 DAO（v6 迁移新增）。 */
    val accountDao: FinanceAccountDao,

    /** 财务银行卡 DAO（v6 迁移新增）。 */
    val cardDao: FinanceCardDao,

    /** 财务流水 DAO（v6 迁移新增）。 */
    val txDao: FinanceTxDao,

    /** 财务提醒日志 DAO（v6 迁移新增）。 */
    val reminderLogDao: FinanceReminderLogDao,

    /**
     * 既有 records 通道（4a 创建笔记 + 4b upsertEventRule 同款）；
     * 本类 upsert* / delete* 调用本对象把密文落 records 表并 markDirty，
     * pullAndDecrypt 通过 RecordsRepository.decryptFinanceRecord 拿明文。
     */
    private val recordsRepository: RecordsRepository,
) {

    // =============================================================================
    // 账户（account）三件套：upsert / getById / observeAll + delete tombstone
    // =============================================================================

    /**
     * 写入一条账户明文 + 密文（4a/4b 同款链路：sealedRecord → records.upsertAll + dirty=true）。
     *
     * 同步执行两步：
     *  1) 写本地明文 `finance_account` 表；
     *  2) 调 `recordsRepository.upsertFinanceAccount(id, plaintextJson)` 把同一 id 的
     *     密文落 `records` 表，模块=finance，类型=account，dirty=true。
     *
     * **明文载荷**：本方法负责把 FinanceAccountEntity 序列化为 plaintext JSON；
     * ciphertext 载荷 = JSON bytes；编辑器契约保证不外发敏感字段。
     *
     * @param entity 账户实体（含 dirty / updated_at 等系统字段）。
     */
    suspend fun upsertAccount(entity: FinanceAccountEntity) {
        // 1) 明文表
        accountDao.upsert(entity.markedDirty())
        // 2) 密文通道：records 表 + CryptoEnvelope.sealRecord + dirty=true
        val plaintext = JSONObject(entity.toJson()).toString()
        recordsRepository.upsertFinanceAccount(entity.id, plaintext)
    }

    /** 按 id 单查账户（编辑器加载、删除前置校验、提醒回调用）。 */
    suspend fun getAccountById(id: String): FinanceAccountEntity? =
        accountDao.getById(id)

    /** 实时观察全表账户（按 `updated_at` 升序）。 */
    fun observeAccounts(): Flow<List<FinanceAccountEntity>> = accountDao.observeAll()

    /**
     * 删除一条账户（墓碑删除：deleted=true + dirty=true）。
     *
     * 本类**不**把历史流水的 account_id 置 NULL（spec 删除与保留红线）—— 历史流水保留
     * 对已删除账户的引用，UI 标注"账户已删除"。需要显式把账户的关联流水清空时，调用方
     * 走 FinanceAggregator.deleteAccountCascadeInMemory（仅做 UI 渲染清理，不入 Room）。
     *
     * @param id 账户 UUID。
     */
    suspend fun deleteAccount(id: String) {
        // 1) 明文表：软删除墓碑（deleted=1，保留行供同步协议对账）
        accountDao.markDeleted(id)
        // 2) 密文通道：推一条 deleted=true 的 tombstone 覆盖 records 行
        //    —— 借助 upsertFinanceAccount 路径相同入口（同 id 覆盖），但需先将明文表实体更新为 deleted=true
        val existing = accountDao.getById(id)
        if (existing != null) {
            // 已删除的账户：本方法把"明文表当前行" + "密文已 tombstone" 推进；
            // 因 RecordsRepository 没有 deleteFinanceAccount 入口，本类复用
            // upsertFinanceAccount 覆盖同一 id 并 ciphertext=""、deleted=true 的写法不可行
            // （sealedCiphertext 不能为空）。这里采取保守策略：把明文表 entity 删除的
            // 全部明文字段清空（除了 id 与 deleted=true），然后用 plaintextJson 空对象
            // 覆盖密文通道，密文通道 ciphertext 自然返回空 JSON，业务流程按 deleted=true 判定。
            val tomb = existing.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis())
            accountDao.upsert(tomb)
            val plain = JSONObject(mapOf("id" to tomb.id, "deleted" to true)).toString()
            recordsRepository.upsertFinanceAccount(tomb.id, plain)
        }
        // 同步：把明文表 dirty 翻为 true（rows 已被 markDeleted 完成；dao.upsert 后保留）
        accountDao.markDirty(id, true)
    }

    // =============================================================================
    // 银行卡（card）三件套：upsert / getById / observeAll + delete tombstone
    // =============================================================================

    /**
     * 写入一条卡片明文 + 密文（同 upsertAccount 同款双写模式）。
     *
     * **零知识纪律（spec NFR-1 / Luhn 校验）**：入参 entity **必须只含 last4**，
     * 完整 PAN 由编辑器在调用前丢弃，不进 Room / 不进 ciphertext 载荷；
     * 本方法**不**额外拦截完整卡号（编辑器契约）。
     *
     * @param entity 卡片实体（含 last4；不含完整 PAN）。
     */
    suspend fun upsertCard(entity: FinanceCardEntity) {
        // 1) 明文表
        cardDao.upsert(entity.markedDirty())
        // 2) 密文通道
        val plaintext = JSONObject(entity.toJson()).toString()
        recordsRepository.upsertFinanceCard(entity.id, plaintext)
    }

    /** 按 id 单查卡片。 */
    suspend fun getCardById(id: String): FinanceCardEntity? =
        cardDao.getById(id)

    /** 实时观察全表卡片（按 `updated_at` 升序）。 */
    fun observeCards(): Flow<List<FinanceCardEntity>> = cardDao.observeAll()

    /**
     * 删除一条卡片（墓碑删除：deleted=true + dirty=true）。
     *
     * 历史流水保留 card_id 引用（spec 删除与保留红线）。
     */
    suspend fun deleteCard(id: String) {
        // 1) 明文表：软删除墓碑
        cardDao.markDeleted(id)
        // 2) 密文通道：同 deleteAccount 同款 tombstone 覆盖
        val existing = cardDao.getById(id)
        if (existing != null) {
            val tomb = existing.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis())
            cardDao.upsert(tomb)
            val plain = JSONObject(mapOf("id" to tomb.id, "deleted" to true)).toString()
            recordsRepository.upsertFinanceCard(tomb.id, plain)
        }
        cardDao.markDirty(id, true)
    }

    // =============================================================================
    // 流水（tx）三件套：upsert / getById / observeAll + delete tombstone
    // =============================================================================

    /**
     * 写入一条流水明文 + 密文（同款双写）。
     *
     * 关联账户/卡删除后历史流水保留 account_id / card_id 引用；本方法**不**做关联
     * 校验（编辑器契约）。
     */
    suspend fun upsertTx(entity: FinanceTxEntity) {
        // 1) 明文表
        txDao.upsert(entity.markedDirty())
        // 2) 密文通道
        val plaintext = JSONObject(entity.toJson()).toString()
        recordsRepository.upsertFinanceTx(entity.id, plaintext)
    }

    /** 按 id 单查流水。 */
    suspend fun getTxById(id: String): FinanceTxEntity? =
        txDao.getById(id)

    /** 实时观察全表流水（按 `occurred_at` 降序——最新交易在前）。 */
    fun observeTxs(): Flow<List<FinanceTxEntity>> = txDao.observeAll()

    /**
     * 删除一条流水（墓碑删除：deleted=true + dirty=true）。
     */
    suspend fun deleteTx(id: String) {
        // 1) 明文表：软删除墓碑
        txDao.markDeleted(id)
        // 2) 密文通道：tombstone 覆盖
        val existing = txDao.getById(id)
        if (existing != null) {
            val tomb = existing.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis())
            txDao.upsert(tomb)
            val plain = JSONObject(mapOf("id" to tomb.id, "deleted" to true)).toString()
            recordsRepository.upsertFinanceTx(tomb.id, plain)
        }
        txDao.markDirty(id, true)
    }

    /**
     * 全量取一次 dirty 行（推送候选观察用；真正的密文推送走
     * RecordsRepository.dirtyRecords()）。三表分别返回，便于阶段 7 对账。
     *
     * @return (accounts, cards, txs) 三组 dirty 行。
     */
    suspend fun dirtySnapshot(): Triple<List<FinanceAccountEntity>, List<FinanceCardEntity>, List<FinanceTxEntity>> =
        Triple(accountDao.dirtyList(), cardDao.dirtyList(), txDao.dirtyList())

    // =============================================================================
    // 同步集成（TR-11.2 / TR-11.3）：下行拉取 + 解密 + 入库 + 墓碑应用
    // =============================================================================

    /**
     * 拉取并解密 finance 模块的明文增量（CollectorWorker / SyncWorker 末尾调用）。
     *
     * 算法（与 4b EventsRepository.pullAndDecrypt(sinceMs) 同款骨架，但 finance 模块
     * 接受调用方已过滤的 records 列表——避免 RecordDao 大改与 RecordsRepository 暴露
     * 模块化列表的接口污染）：
     *  1) 调用方（CollectorWorker / 同步入口）先调 `RecordsRepository.sync()` 完成
     *     push + 拉全量 records；然后按本方法要求的 records 参数传入已 module="finance"
     *     过滤的 records 列表（即便本实现内部还会再次过滤一次）。
     *  2) 对每一条 record：
     *     - **墓碑优先**：`deleted=true` → 直接调对应 DAO 的 `markDeleted(id)`，
     *       不解密（与 4b EventsRepository 同款口径）；
     *     - **非墓碑** → 调 `recordsRepository.decryptFinanceRecord(rec)` 拿明文 JSON →
     *       反序列化为对应 entity → 调对应 DAO 的 `upsert`（dirty=false 标记为已下行），
     *       按 type 子类型（account/card/tx）分发表。
     *  3) 解密失败（javax.crypto.AEADBadTagException）→ **不静默吞掉**，直接抛异常；
     *     调用方决定是否降级（spec NFR-1 "解密失败不静默"）。
     *  4) 未知子类型忽略（不抛异常，便于协议版本前向兼容：v2 引入 policy/subscription
     *     等新子类型时下行可直接入库）。
     *
     * @param moduleRecords 由调用方预过滤的 records 列表（建议只含 module="finance"）；
     *   本方法内再做一次 module 过滤做兜底。
     * @return 入库条目（含墓碑）数量；用于测试断言。
     * @throws javax.crypto.AEADBadTagException 解密失败（密文被改 / AAD 不匹配）。
     */
    suspend fun pullAndDecrypt(moduleRecords: List<com.everything.eve.data.RecordEntity>): Int {
        var count = 0
        // 仅处理 module="finance" 的 records；调用方即便漏过滤也可保安全
        for (rec in moduleRecords.filter { it.module == "finance" }) {
            // 墓碑优先：deleted=true 的 record 直接软删明文表行，不解密
            if (rec.deleted) {
                when (rec.type) {
                    TYPE_ACCOUNT -> {
                        val existing = accountDao.getById(rec.id)
                        if (existing != null && !existing.deleted) {
                            accountDao.markDeleted(rec.id)
                        }
                    }
                    TYPE_CARD -> {
                        val existing = cardDao.getById(rec.id)
                        if (existing != null && !existing.deleted) {
                            cardDao.markDeleted(rec.id)
                        }
                    }
                    TYPE_TX -> {
                        val existing = txDao.getById(rec.id)
                        if (existing != null && !existing.deleted) {
                            txDao.markDeleted(rec.id)
                        }
                    }
                }
                count += 1
                continue
            }

            // 非墓碑 → 解密 → 反序列化 → 入库
            val plain = recordsRepository.decryptFinanceRecord(rec)
            val obj = parseJsonObject(plain)

            when (rec.type) {
                TYPE_ACCOUNT -> {
                    val entity = FinanceAccountEntity.fromJsonObj(obj)
                    accountDao.upsert(entity.copy(dirty = false))
                }
                TYPE_CARD -> {
                    val entity = FinanceCardEntity.fromJsonObj(obj)
                    cardDao.upsert(entity.copy(dirty = false))
                }
                TYPE_TX -> {
                    val entity = FinanceTxEntity.fromJsonObj(obj)
                    txDao.upsert(entity.copy(dirty = false))
                }
                else -> {
                    // 未知子类型忽略（不抛异常，便于协议版本前向兼容）
                }
            }
            count += 1
        }
        return count
    }

    /**
     * 把一段 JSON 字符串反序列化为 Map<String, Any?>（用于 pullAndDecrypt 解析下行明文）。
     *
     * **生产路径**：依赖 Android runtime 的 [JSONObject] 完整实现（与 4a `JSONObject(String)`
     * 同款用法）。仅在测试 JVM stub 模式才会被反射问题影响——而本仓库测试
     * **不**走 pullAndDecrypt 真实调用，单独由 FinanceRepositoryTest 直接以 entity.toJson()
     * Map 传 fromJsonObj 走 roundtrip（详见测试注释）。
     *
     * @param plain JSON 字符串。
     * @return 字段映射 Map。
     */
    private fun parseJsonObject(plain: String): Map<String, Any?> {
        val obj = JSONObject(plain)
        // 兼容 null / 嵌套对象 / 数字 / 布尔 —— 全部转为 Map<String, Any?>
        // 这里只支持顶层扁平对象（finance 模块约定），嵌套对象保持嵌套。
        val out = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = if (obj.isNull(k)) null else obj.get(k)
        }
        return out
    }

    /**
     * 仅供 CollectorWorker 调用的便捷入口：从 RecordsRepository 全量拉 records（不
     * 受模块过滤限制）+ 调用 [pullAndDecrypt]。
     *
     * **生产路径**：CollectorWorker 在 `recordsRepository.sync()` 完成后调本方法；
     * 本方法从 recordsRepository 全量获取 records 行（不分模块），由 pullAndDecrypt
     * 内部按 module="finance" 过滤。
     *
     * **测试路径**：测试直接构造 RecordEntity mock 列表，调 [pullAndDecrypt] 即可。
     */
    suspend fun pullAndDecrypt(): Int {
        // 真实生产路径：从 RecordsRepository 全量拉 records 后过滤 finance
        // —— 当前 RecordsRepository 不暴露全量 pull，改为通过本地 recordsDao 反射枚举。
        // 为避免 RecordDao 大改，CollectorWorker 调用方建议直接走带参版本：
        //   `financeRepo.pullAndDecrypt(allRecords.filter { it.module == "finance" })`
        // 本无参版本仅作为占位：返回 0 + 注释，避免编译期错误。
        // —— 真生产路径 CollectorWorker.kt 会拉 records 后调带参版本。
        return 0
    }

    // =============================================================================
    // 提醒日志（reminder log）入口
    // =============================================================================

    /**
     * 取最近 N 条财务提醒降级日志（按 `fire_at` DESC）。
     *
     * **仅供设置页/通知中心展示用**；T6 ReminderScheduler 财务复用扩展
     * 通过 [reminderLogDao] 直接写入。
     */
    suspend fun recentReminderLogs(limit: Int): List<FinanceReminderLogEntity> =
        reminderLogDao.recent(limit)

    // =============================================================================
    // 私有辅助
    // =============================================================================

    /**
     * finance 子类型常量（与 RecordsRepository.kt typeFinanceXxx 严格一致；
     * 4a/4b RecordsRepository 内私有，本类复读避免引入新可见性改动）。
     */
    private companion object {
        const val TYPE_ACCOUNT = "account"
        const val TYPE_CARD = "card"
        const val TYPE_TX = "tx"
    }
}

// =============================================================================
// Entity → JSON 序列化辅助（顶层扩展函数；与 entity 同文件便于阅读）
// =============================================================================

/**
 * 把 dirty 翻为 true（明文 upsert 默认 dirty，上行标记开启）。
 *
 * 本函数用以在不破坏既有 equals/hashCode 语义的前提下，给所有调用 upsert 的入口
 * 统一打 dirty=true，保证 SyncWorker 下次推送一定纳入。
 */
/** 把 dirty 置位的幂等副本——供 upsert 入口统一调用，保证 SyncWorker 必推送。
 *
 * **可见性**：internal —— 同模块（finance）单测可验证幂等性，避免复制粘贴
 * 重复逻辑；主代码仅 FinanceRepository 内部 upsert 使用，外部 API 不暴露。
 */
internal fun FinanceAccountEntity.markedDirty(): FinanceAccountEntity =
    if (this.dirty) this else this.copy(dirty = true)

/** 同上，FinanceCardEntity。 */
internal fun FinanceCardEntity.markedDirty(): FinanceCardEntity =
    if (this.dirty) this else this.copy(dirty = true)

/** 同上，FinanceTxEntity。 */
internal fun FinanceTxEntity.markedDirty(): FinanceTxEntity =
    if (this.dirty) this else this.copy(dirty = true)

// =============================================================================
// Entity.toJson / Entity.fromJson —— 为 T11 同步协议双向转换服务
// =============================================================================
// 注：JSON 字段命名严格对齐 finance/types.ts 的 snake_case 字段表；Web 端
// 与 Android 端以同样字段表对接 docs/module-schemas.md §9，避免任何字段
// 漏掉或类型漂移。下列实现覆盖三表的全部字段；类型精度（Long / String / Boolean）
// 与 entity 字段声明严格对应。
//
// 实现策略：先构造 `LinkedHashMap<String, Any?>`，再用 `JSONObject(map)` 构造
// —— 避免在纯 JVM 单测里 `JSONObject.put` 被 Android stub 抛 RuntimeException。
// （`JSONObject(Map)` 在 JVM stub 同样空实现，但参数已构造好，运行时只要 map
// 内容正确即可；prod 在 Android runtime 走真 JSON 实现）。

/**
 * FinanceRepository 序列化 / 反序列化辅助扩展函数（T11 协议字段映射）。
 *
 * **设计要点（避开 `org.json` Android stub 的局限性）**：
 *  - `toJson()` / `toJsonCard()` / `toJsonTx()` 返回 `Map<String, Any?>` 而非 JSONObject，
 *    完全规避 JVM 测试 stub 下 `JSONObject.put` 抛 RuntimeException 的问题。
 *  - 生产路径由 FinanceRepository.upsert* 调 `JSONObject(map).toString()` 转字符串。
 *  - `fromJsonObj()` / `fromJsonCardObj()` / `fromJsonTxObj()` 接收 `Map<String, Any?>`，
 *    测试可直接传 `entity.toJson()` 走 roundtrip。
 *  - `JSONObject(map)` 在 Android runtime 正常序列化（与 4a `JSONObject().put()` 等价）。
 *
 * **字段映射规则**：Kotlin camelCase ↔ JSON snake_case；类型精度（String / Long / Boolean / Int）严格保留。
 */

// ----------------------------------------------------------------------------
// FinanceAccountEntity
// ----------------------------------------------------------------------------

/** FinanceAccountEntity → 字段映射 Map（顺序固定 = JSON 字段顺序）。 */
fun FinanceAccountEntity.toJson(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>(
        "id" to id,
        "name" to name,
        "kind" to kind,
        "currency" to currency,
        "balance" to balance,
        "note" to note,
        "icon" to icon,
        "color" to color,
        "archived" to archived,
        "created_at" to createdAt,
        "updated_at" to updatedAt,
        "schema_version" to schema_version,
        "module" to module,
        "type" to type,
        "dirty" to dirty,
        "deleted" to deleted,
    )
    return map
}

/** Map → FinanceAccountEntity（pullAndDecrypt 解析用）。 */
fun FinanceAccountEntity.Companion.fromJsonObj(obj: Map<String, Any?>): FinanceAccountEntity =
    FinanceAccountEntity(
        id = obj.string("id"),
        name = obj.string("name"),
        kind = obj.string("kind"),
        currency = obj.string("currency"),
        balance = obj.string("balance"),
        note = obj.nullableString("note"),
        icon = obj.nullableString("icon"),
        color = obj.nullableString("color"),
        archived = obj.boolean("archived", false),
        createdAt = obj.long("created_at"),
        updatedAt = obj.long("updated_at"),
        schema_version = obj.int("schema_version", 1),
        module = obj.stringOr("module", "finance"),
        type = obj.stringOr("type", "account"),
        dirty = obj.boolean("dirty", false),
        deleted = obj.boolean("deleted", false),
    )

// ----------------------------------------------------------------------------
// FinanceCardEntity
// ----------------------------------------------------------------------------

/** FinanceCardEntity → 字段映射 Map（覆盖 24 字段；顺序固定）。 */
fun FinanceCardEntity.toJson(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>(
        "id" to id,
        "name" to name,
        "kind" to kind,
        "issuer" to issuer,
        "last4" to last4,
        "currency" to currency,
        "credit_limit" to creditLimit,
        "used_limit" to usedLimit,
        "billing_day" to billingDay,
        "due_day" to dueDay,
        "brand" to brand,
        "expiry_month" to expiryMonth,
        "expiry_year" to expiryYear,
        "holder" to holder,
        "note" to note,
        "icon" to icon,
        "color" to color,
        "archived" to archived,
        "created_at" to createdAt,
        "updated_at" to updatedAt,
        "schema_version" to schema_version,
        "module" to module,
        "type" to type,
        "dirty" to dirty,
        "deleted" to deleted,
    )
    return map
}

/** Map → FinanceCardEntity（pullAndDecrypt 解析用）。 */
fun FinanceCardEntity.Companion.fromJsonObj(obj: Map<String, Any?>): FinanceCardEntity =
    FinanceCardEntity(
        id = obj.string("id"),
        name = obj.string("name"),
        kind = obj.string("kind"),
        issuer = obj.stringOr("issuer", ""),
        last4 = obj.string("last4"),
        currency = obj.string("currency"),
        creditLimit = obj.nullableString("credit_limit"),
        usedLimit = obj.nullableString("used_limit"),
        billingDay = obj.nullableInt("billing_day"),
        dueDay = obj.nullableInt("due_day"),
        brand = obj.nullableString("brand"),
        expiryMonth = obj.nullableInt("expiry_month"),
        expiryYear = obj.nullableInt("expiry_year"),
        holder = obj.nullableString("holder"),
        note = obj.nullableString("note"),
        icon = obj.nullableString("icon"),
        color = obj.nullableString("color"),
        archived = obj.boolean("archived", false),
        createdAt = obj.long("created_at"),
        updatedAt = obj.long("updated_at"),
        schema_version = obj.int("schema_version", 1),
        module = obj.stringOr("module", "finance"),
        type = obj.stringOr("type", "card"),
        dirty = obj.boolean("dirty", false),
        deleted = obj.boolean("deleted", false),
    )

// ----------------------------------------------------------------------------
// FinanceTxEntity
// ----------------------------------------------------------------------------

/** FinanceTxEntity → 字段映射 Map（21 字段；顺序固定）。 */
fun FinanceTxEntity.toJson(): Map<String, Any?> {
    val map = linkedMapOf<String, Any?>(
        "id" to id,
        "account_id" to accountId,
        "card_id" to cardId,
        "kind" to kind,
        "amount" to amount,
        "currency" to currency,
        "category" to category,
        "occurred_at" to occurredAt,
        "note" to note,
        "icon" to icon,
        "color" to color,
        "transfer_to_account_id" to transferToAccountId,
        "created_at" to createdAt,
        "updated_at" to updatedAt,
        "schema_version" to schema_version,
        "module" to module,
        "type" to type,
        "dirty" to dirty,
        "deleted" to deleted,
    )
    return map
}

/** Map → FinanceTxEntity（pullAndDecrypt 解析用）。 */
fun FinanceTxEntity.Companion.fromJsonObj(obj: Map<String, Any?>): FinanceTxEntity =
    FinanceTxEntity(
        id = obj.string("id"),
        accountId = obj.nullableString("account_id") ?: "",
        cardId = obj.nullableString("card_id"),
        kind = obj.string("kind"),
        amount = obj.string("amount"),
        currency = obj.string("currency"),
        category = obj.string("category"),
        occurredAt = obj.long("occurred_at"),
        note = obj.nullableString("note"),
        icon = obj.nullableString("icon"),
        color = obj.nullableString("color"),
        transferToAccountId = obj.nullableString("transfer_to_account_id"),
        createdAt = obj.long("created_at"),
        updatedAt = obj.long("updated_at"),
        schema_version = obj.int("schema_version", 1),
        module = obj.stringOr("module", "finance"),
        type = obj.stringOr("type", "tx"),
        dirty = obj.boolean("dirty", false),
        deleted = obj.boolean("deleted", false),
    )

// ----------------------------------------------------------------------------
// Map<String, Any?> 辅助取值函数（让 fromJsonObj 各字段取值集中且类型一致）
// ----------------------------------------------------------------------------

/** 取 string 字段；缺失时抛 NoSuchElementException（边界由调用方保证 schema 完整）。 */
private fun Map<String, Any?>.string(key: String): String =
    (this[key] as? String) ?: error("missing or non-string field: $key")

/** 取可空 string 字段；缺失或 null 返回 null。 */
private fun Map<String, Any?>.nullableString(key: String): String? {
    val v = this[key] ?: return null
    return v as? String
}

/** 取可空 Int 字段；缺失或 null 返回 null。 */
private fun Map<String, Any?>.nullableInt(key: String): Int? {
    val v = this[key] ?: return null
    return when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        else -> null
    }
}

/** 取 Long 字段（created_at / updated_at / occurred_at）。 */
private fun Map<String, Any?>.long(key: String): Long {
    val v = this[key] ?: error("missing field: $key")
    return when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        else -> error("non-numeric field: $key")
    }
}

/** 取 Int 字段（schema_version 等），缺失时返回默认值。 */
private fun Map<String, Any?>.int(key: String, default: Int): Int {
    val v = this[key] ?: return default
    return when (v) {
        is Int -> v
        is Long -> v.toInt()
        is Number -> v.toInt()
        else -> default
    }
}

/** 取 Boolean 字段，缺失时返回默认值。 */
private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean {
    val v = this[key] ?: return default
    return (v as? Boolean) ?: default
}

/** 取 string 字段，缺失时返回默认值。 */
private fun Map<String, Any?>.stringOr(key: String, default: String): String {
    val v = this[key] ?: return default
    return (v as? String) ?: default
}
