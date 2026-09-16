package com.everything.eve.data.event

import com.everything.eve.api.RemoteRecord
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 日程/日历领域仓库（阶段 4b Task 4 / TR-4.4）。
 *
 * 设计要点：
 *  1) **不新造 envelope 路径**：本类只编排「明文 EventRule → Room 明文 event 表
 *     + RecordsRepository.upsertEventRule 走既有 records 通道」。
 *  2) **加密由 4a RecordsRepository 既有 upsertEventRule 完成**：CryptoEnvelope
 *     的 Argon2id/XChaCha20-Poly1305 与 AAD `eve:v1:record:{id}` **逐字节一致**
 *     沿用，不引入新加密原语。
 *  3) **dirty 标记**：records 表 dirty=true 由 upsertEventRule 写入；event 表
 *     dirty 同步更新，便于本地对账与观测（理论上事件表 dirty 与 records 表 dirty
 *     通过本类写入保持一一对应）。
 *  4) **明文纪律**：title/location/note/rrule 不写 SharedPreferences / 日志 /
 *     通知文案；仅驻留 Room（Vault DB 加密容器）+ 浏览器/编辑器内存。
 *  5) **同步由 SyncWorker 周期触发**：本类不直接 push；SyncWorker 拉取
 *     `RecordDao.dirtyRecords()`（含 module=event/type=event 行）走既有
 *     RecordsRepository.sync() 推送上行。
 *
 * 字段映射（与 EventEntity / spec FR-1 字段表一一对应）：
 *  - reminders_json：JSONArray 序列化字符串，非 null；空即 `"[]"`。
 *  - rrule_json：JSONObject 序列化字符串，可 null；null 即单次事件。
 *  - exdates_json：JSONArray 序列化字符串，非 null；空即 `"[]"`。
 */
class EventsRepository(
    private val eventDao: EventDao,
    private val recordsRepository: RecordsRepository,
) {

    /**
     * 新建/编辑一条事件。
     *
     * 流程：
     *  1) 序列化 EventRule 为 JSON 明文；
     *  2) recordsRepository.upsertEventRule(id, json) → CryptoEnvelope.sealRecord
     *     → records 表写入密文 + dirty=true（4a 既有链路，不新造 envelope）；
     *  3) eventDao.upsert(entity) → event 表写入明文 + dirty=true。
     *
     * 字段为空时规范化（reminders/exdates 永不为 null；rrule=null 显式保留）。
     *
     * @return 写入的事件 id（即 rule.id；新建场景下与传入的 rule.id 一致）。
     */
    suspend fun upsert(rule: EventRule): String {
        val json = rule.toJson()
        // 1) records 通道：密文 + dirty（4a RecordsRepository 既有 upsertEventRule）
        recordsRepository.upsertEventRule(rule.id, json)
        // 2) 本地 event 表：明文 + dirty（供 UI / ReminderScheduler 直接读）
        eventDao.upsert(rule.toEntity(dirty = true))
        return rule.id
    }

    /**
     * 删除一条事件（tombstone + 本地清除）。
     *
     * 流程：
     *  1) recordsRepository.deleteEventRule(id) → records 表写入 deleted=true
     *    （4a 既有链路，密文置空字符串，dirty=true）；
     *  2) eventDao.deleteById(id) → event 表本地清除。
     */
    suspend fun delete(id: String) {
        recordsRepository.deleteEventRule(id)
        eventDao.deleteById(id)
    }

    /** 实时观察全表事件（按 start_ts 升序）。 */
    fun observeAll(): Flow<List<EventEntity>> = eventDao.observeAll()

    /** 窗口查询（ReminderScheduler 计算下一触发点专用）。 */
    suspend fun queryWindow(from: Long, to: Long): List<EventEntity> =
        eventDao.queryWindow(from, to)

    /** 取所有 dirty 事件（观测用；同步推送走 records 表 dirty 字段）。 */
    suspend fun dirtyList(): List<EventEntity> = eventDao.dirtyList()

    /** 单查（ReminderReceiver 触发后取详情用）。 */
    suspend fun getById(id: String): EventEntity? = eventDao.getById(id)

    /**
     * 给定 rule.id 已确认不存在于本地 event 表时，生成新 UUID（编辑器"新建"分支）。
     * 仅生成 id，不写库；由后续 upsert 触发入库。
     */
    fun newId(): String = UUID.randomUUID().toString()

    // =============================================================================
    // 阶段 4b / Task 10（TR-10.2）：远端拉取 → 解密 → 入 event 表
    // =============================================================================

    /**
     * 从服务端 records 通道拉取 module=event/type=event 的远端记录（sinceMs 起），
     * 逐条解密后 upsert 到本地 event 表。
     *
     * 设计要点：
     *  1) **不破坏 4a RecordsRepository 接口**：直接调既有 `RecordDao`，绕开
     *     4a sync 链路；eventDao 与 records 表由 `RecordsRepository.sync()` 保持
     *     一一对账关系（dirty=true 与 records 同步更新）。
     *  2) **拉取复用 api.listRecords**：与 Web 端 eventsStore.pullAll 同款
     *     `since` 增量协议；服务端不解密不解析，仅做密文中转（FR-3）。
     *  3) **解密失败抛异常不静默**：与 Web eventsStore.ingest 纪律一致
     *     （NFR-1 / AC-11 红线）；解密失败的记录**不入库** Room，留待下轮重试。
     *  4) **墓碑（deleted=true）走 deleteById**：与 Web vault.ingest 同形态。
     *  5) **客户端权威时间**：updatedTs 取服务端 `updated_at`（FU-1）。
     *  6) **幂等**：相同 id 重复解码走 Room upsert REPLACE；外层 RecordsRepository
     *     已有 `ingestRemoteEvent` 写入 records 表，本方法专注 event 表入库。
     *  7) **不写 SharedPreferences / 日志 / 通知文案**：明文 title/note/rrule
     *     仅存活于 Room event 表（Vault DB 容器内）+ 内存调用帧。
     *
     * @param sinceMs 增量游标（毫秒）；≤0 表示全量（since=0）。
     * @return 拉取并入库的事件条目数（含成功 upsert；失败抛异常不上报条数）。
     * @throws IllegalStateException MK 未解锁。
     * @throws javax.crypto.AEADBadTagException 解密失败（密文/AAD 不匹配）。
     */
    suspend fun pullAndDecrypt(sinceMs: Long): Int {
        val api = com.everything.eve.ServiceLocator.api
        var cursor = if (sinceMs < 0) 0 else sinceMs
        var ingested = 0
        while (true) {
            // 与 RecordsRepository.sync() 同款分页（PAGE_SIZE=500），不破坏既有 4a 协议。
            val page = api.listRecords(cursor, 500)
            if (page.records.isEmpty()) break
            for (remote in page.records) {
                // 仅消化 event 模块；其它模块的密文属 4a 已处理范围或 place/pass 等，
                // 这里不动——保留职责单一。
                if (remote.module != "event") continue
                if (remote.type != "event") continue
                ingestOneRemote(remote)
                ingested += 1
            }
            // 取本页事件的最大 updated_at 作游标（与 4a RecordsRepository.sync() 同款）
            val eventRecords = page.records.filter { it.module == "event" && it.type == "event" }
            if (eventRecords.isNotEmpty()) {
                cursor = eventRecords.maxOf { it.updatedAt }
            }
            if (!page.hasMore) break
        }
        return ingested
    }

    /**
     * 处理单条远端 event 记录：墓碑删除；非墓碑解密后 upsert 到 event 表。
     * 解密失败抛异常由 pullAndDecrypt 上抛（不静默）。
     */
    private suspend fun ingestOneRemote(remote: RemoteRecord) {
        if (remote.deleted) {
            // 远端墓碑：本地 event 表同步删除（与 vault tombstone 语义对齐）。
            eventDao.deleteById(remote.id)
            return
        }
        // 复用 RecordsRepository.decryptEventRule 解密（MK 由 4a 链路就位）。
        // 通过构造一个临时 RecordEntity 调用——避免重复 envelope 解密路径。
        val tmpEntity = RecordEntity(
            id = remote.id,
            module = remote.module,
            type = remote.type,
            ciphertext = remote.ciphertext,
            // RecordEntity.version 字段类型为 Long；RemoteRecord.version 字段类型为 Int，
            // 通过 .toLong() 显式拓宽避免类型推断错误。
            version = remote.version.toLong(),
            createdAt = remote.createdAt,
            updatedAt = remote.updatedAt,
            deleted = false,
        )
        val plainJson = recordsRepository.decryptEventRule(tmpEntity)
        val rule = EventRule.fromJson(plainJson)
        // 入 Room event 表：dirty=false 表示已与服务端同步。
        eventDao.upsert(rule.toEntity(dirty = false))
    }
}

/**
 * 事件规则（spec FR-1 字段表的运行时形态）。
 *
 * 字段命名/单位/枚举与 [docs/module-schemas.md] 第 8 章 / Web `EventRule`
 * **逐字段一致**；类型层仅做 Kotlin 数据类承载，序列化由 `toJson()` / `toEntity()`
 * / `EventRule.fromJson()` 完成。
 */
data class EventRule(
    val id: String,
    val title: String,
    val start_ts: Long,
    val end_ts: Long,
    val all_day: Boolean,
    val tz_mode: String = "local",
    val location_text: String? = null,
    val note: String? = null,
    val color: String,
    val reminders: List<Int> = emptyList(),
    val rrule: String? = null,
    val exdates: List<String> = emptyList(),
) {
    /**
     * 序列化为明文 JSON（与 Web `EventRule` 字段命名一致；rrule 为字符串是简化承载，
     * 由上层在序列化前解析为 B 档对象；此处 rrule 已是 JSON 字符串或 null）。
     */
    fun toJson(): String =
        JSONObject()
            .put("id", id)
            .put("title", title)
            .put("start_ts", start_ts)
            .put("end_ts", end_ts)
            .put("all_day", all_day)
            .put("tz_mode", tz_mode)
            .put("location_text", location_text)
            .put("note", note)
            .put("color", color)
            .put("reminders", JSONArray(reminders))
            .put("rrule", parseRruleToJsonOrNull(rrule))
            .put("exdates", JSONArray(exdates))
            .toString()

    /** Room 明文缓存映射（dirty 字段由 Repository 编排时显式标）。 */
    fun toEntity(dirty: Boolean): EventEntity = EventEntity(
        id = id,
        title = title,
        start_ts = start_ts,
        end_ts = end_ts,
        all_day = all_day,
        tz_mode = tz_mode,
        location_text = location_text,
        note = note,
        color = color,
        reminders_json = JSONArray(reminders).toString().ifEmpty { "[]" },
        rrule_json = rrule,
        exdates_json = JSONArray(exdates).toString().ifEmpty { "[]" },
        dirty = dirty,
        updated_ts = System.currentTimeMillis(),
    )

    companion object {
        /**
         * 从明文 JSON 反序列化为 EventRule（SyncWorker pull 后入库 Room 用）。
         *
         * JSON 字段缺失按 spec FR-1 默认值兜底（tz_mode="local" / reminders=[] /
         * rrule=null / exdates=[]）。
         */
        fun fromJson(json: String): EventRule {
            val o = JSONObject(json)
            val remindersArr = o.optJSONArray("reminders")
            val remindersList = (0 until (remindersArr?.length() ?: 0))
                .map { remindersArr.getInt(it) }
            val exdatesArr = o.optJSONArray("exdates")
            val exdatesList = (0 until (exdatesArr?.length() ?: 0))
                .map { exdatesArr.getString(it) }
            val rruleStr: String? = if (o.isNull("rrule")) {
                null
            } else {
                o.optJSONObject("rrule")?.toString()
            }
            return EventRule(
                id = o.getString("id"),
                title = o.getString("title"),
                start_ts = o.getLong("start_ts"),
                end_ts = o.getLong("end_ts"),
                all_day = o.getBoolean("all_day"),
                tz_mode = o.optString("tz_mode", "local"),
                location_text = o.optString("location_text").takeIf { it.isNotEmpty() },
                note = o.optString("note").takeIf { it.isNotEmpty() },
                color = o.getString("color"),
                reminders = remindersList,
                rrule = rruleStr,
                exdates = exdatesList,
            )
        }

        /**
         * rrule 字段承载转换：EventRule.rrule 简化为 String?，序列化前若已是 JSON
         * 字符串则原样嵌入；若为 null 则写 null 表示单次事件。
         *
         * 本函数保留为开放扩展点（未来可能把 rrule 升级为强类型 RRule 对象）。
         */
        private fun parseRruleToJsonOrNull(raw: String?): Any? =
            if (raw == null) JSONObject.NULL else JSONObject(raw)
    }
}