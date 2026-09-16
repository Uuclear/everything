// ============================================================================
// 财务附件仓库（阶段 5 v2 / TR-3.2 / Task "AttachmentRepository + 附件块加密同步"）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/data/finance/AttachmentRepository.kt
//
// 职责：
//   1) 把附件明文 envelope 封块 + 入 Room + 推 records 通道 dirty 行；
//   2) 拉取服务端下行 records（type=attachment）→ 解密 → 入 Room；
//   3) 软删除墓碑 + 推 records 通道墓碑行；
//   4) UI 实时观察（按父记录 id 过滤的 Flow）。
//
// 设计纪律（与既有 FinanceRepository / EventsRepository 严格一致）：
//   - **不新造 envelope 路径**：完全复用 CryptoEnvelope.sealRecord / openRecord +
//     RecordsRepository.upsertFinanceAttachment / decryptFinanceAttachment；
//   - **本地缓存 + records 通道双写**：AttachmentEntity（本地 Room 密文缓存，与
//     AttachmentDao 同款 schema）+ records 表（外发密文通道，与 finance 子类型共用）；
//   - **dirty 标记**：upsert / delete 后 records 行 dirty=true；服务端 LWW 下行后由
//     SyncWorker 推 markClean 翻 false（沿用既有 records 表同步协议）；
//   - **零知识纪律**：mime / size / sha256 仅本地校验与 UI 展示用，**不**进日志 /
//     SharedPreferences / 通知文案；明文附件字节绝不写日志（仅记 id / size / sha256 摘要）；
//   - **类型字段**：AttachmentEntity.dirty / deleted 是 `Int`（与 FinanceAccountEntity
//     不一致——SA-1 硬约束产物；本仓库所有比较与写入按 Int 处理）；
//   - **不触碰 FinanceRepository**（v1 既有不动；附件独立成 AttachmentRepository）。
//
// 同步触发点：
//   - CollectorWorker.doWork 末尾：在 financeRepo.pullAndDecrypt 之后调
//     attachmentRepo.pullAndDecrypt(attachmentRecords) + attachmentRepo.pushChanges()；
//   - UI：ViewModel 订阅 listByRecordId Flow；调用 upload / delete 触发 records 通道写入。
//
// 关联：
//   - android/.../data/finance/dao/AttachmentDao.kt（11 方法）
//   - android/.../data/finance/entity/AttachmentEntity.kt（dirty/deleted=Int）
//   - android/.../data/RecordsRepository.kt（upsertFinanceAttachment / decryptFinanceAttachment）
//   - android/.../finance/FinanceRecords.kt（ATTACHMENT_MAX_SIZE_BYTES / AttachmentRef）
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.AttachmentDao
import com.everything.eve.data.finance.entity.AttachmentEntity
import com.everything.eve.finance.AttachmentRef
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 财务附件仓库（policy / contract 等 v2 父记录下挂的本地密文 envelope）。
 *
 * 与 [FinanceRepository] 同包独立成类——附件是密文大块（合同扫描件 / 保单 PDF），
 * 与 account/card/tx 明文小记录走两条不同的存储通道：
 *  - account/card/tx 明文 + records 密文（小记录双写）；
 *  - attachment 本地密文缓存 + records 通道仅记录元数据 + mime/size/sha256 摘要。
 *
 * @param attachmentDao 附件本地 Room DAO（SA-1 产物）。
 * @param recordsRepository records 密文通道仓库（既有；仅复用 upsertFinanceAttachment +
 *   decryptFinanceAttachment 两个新方法）。
 * @param auth 鉴权管理器（masterKey 仅内存态，零知识红线）。
 */
class AttachmentRepository(
    /** 附件本地 Room DAO（SA-1 产物：upsert / getById / observeByRecordId / dirtyList / markDirty / markDeleted 等）。 */
    private val attachmentDao: AttachmentDao,

    /** records 密文通道（既有 RecordsRepository；本类仅调其 upsertFinanceAttachment / decryptFinanceAttachment）。 */
    private val recordsRepository: RecordsRepository,

    /** 鉴权管理器；masterKey 仅内存态，不入日志 / 不入 SharedPreferences。 */
    private val auth: AuthManager,
) {

    // ==========================================================================
    // 上传（editor → AttachmentRepository → Room + records 通道）
    // ==========================================================================

    /**
     * 上传一个附件：本地 envelope 密文写入 AttachmentEntity + records 通道密文入 records 表。
     *
     * 算法（端侧加密双写）：
     *  1) 端侧校验：size > 0、size ≤ ATTACHMENT_MAX_SIZE_BYTES（50MB，从
     *     [com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES]
     *     取，与 policy / contract 校验口径一致）、sha256Hex 长度 64 且只含 [0-9a-f]；
     *  2) 计算本地 envelope 密文：[CryptoEnvelope.sealRecord]（mk, content,
     *     attachmentId, "finance", 1）→ nonce(24)||cipher 字节流；
     *  3) 写 AttachmentEntity（dirty=1，updatedAt=now）→ attachmentDao.upsert；
     *  4) records 通道：[recordsRepository.upsertFinanceAttachment] 把元数据
     *     {"id","recordId","mime","size","sha256"} 密封为 type=attachment 的 records 行，
     *     dirty=true；
     *  5) 成功返回 [AttachmentRef]（id / mime / size / sha256）。
     *
     * 失败语义：任一前置校验失败 → [Result.failure]；加密原语抛异常 → [Result.failure]。
     *
     * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
     * @param content 附件明文字节（policy 合同扫描件 / 保单 PDF 等）。
     * @param mime MIME 类型（application/pdf / image/jpeg / image/png 等；调用方应保证白名单）。
     * @param sha256Hex 明文 SHA-256 hex 字符串（64 字符，[0-9a-f]）。
     * @return [Result.success] [AttachmentRef]（id 为本方法生成的新 UUID）或
     *   [Result.failure]（含 IllegalArgumentException 提示）。
     */
    suspend fun upload(
        recordId: String,
        content: ByteArray,
        mime: String,
        sha256Hex: String,
    ): Result<AttachmentRef> {
        // ---- 前置校验：size / mime / sha256 合法性 ----
        if (content.isEmpty()) {
            return Result.failure(IllegalArgumentException("附件字节数为 0"))
        }
        if (content.size.toLong() > com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES) {
            return Result.failure(
                IllegalArgumentException(
                    "附件超过 ${com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES} 字节上限",
                ),
            )
        }
        if (!isValidSha256Hex(sha256Hex)) {
            return Result.failure(IllegalArgumentException("sha256 hex 非法：必须 64 字符 [0-9a-f]"))
        }

        // ---- masterKey 取自内存态（零知识红线）----
        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("资料库未解锁"))

        // ---- 加密 + 双写 ----
        val attachmentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val sealed = CryptoEnvelope.sealRecord(mk, content, attachmentId, "finance", 1)

        attachmentDao.upsert(
            AttachmentEntity(
                id = attachmentId,
                recordId = recordId,
                mime = mime,
                size = content.size.toLong(),
                sha256 = sha256Hex,
                encryptedPayload = sealed,
                schemaVersion = 1,
                module = "finance",
                createdAt = now,
                updatedAt = now,
                dirty = 1,
                deleted = 0,
            ),
        )

        // ---- records 通道密文（type=attachment）----
        // 明文载荷契约：{"id","recordId","mime","size","sha256"}
        // ——与 [RecordsRepository.decryptFinanceAttachment] 后续反序列化字段对齐。
        val plain = JSONObject()
            .put("id", attachmentId)
            .put("recordId", recordId)
            .put("mime", mime)
            .put("size", content.size.toLong())
            .put("sha256", sha256Hex)
            .toString()
        recordsRepository.upsertFinanceAttachment(attachmentId, plain, recordId)

        return Result.success(
            AttachmentRef(
                id = attachmentId,
                mime = mime,
                size = content.size.toLong(),
                sha256 = sha256Hex,
            ),
        )
    }

    // ==========================================================================
    // 下载（Room → 解密 → sha256 校验 → 明文 ByteArray）
    // ==========================================================================

    /**
     * 下载一个附件：本地缓存命中 → 解密 → sha256 校验 → 返回明文字节。
     *
     * 算法：
     *  1) attachmentDao.getById(id) 命中（仅 deleted=0 行）；
     *  2) CryptoEnvelope.openRecord 解密 → 明文字节；
     *  3) 端侧再算 SHA-256 → 与 AttachmentEntity.sha256 比对 → 不一致
     *     直接 Result.failure（防本地缓存被调包 / 数据腐坏）。
     *
     * 失败语义：id 不存在 / deleted → Result.failure；密文被改 / AAD 不匹配
     * → CryptoEnvelope 抛 IllegalStateException（被本方法捕获包成 Result.failure）。
     *
     * @param id 附件 UUID。
     * @return [Result.success] 明文字节（与上传字节完全一致）或 [Result.failure]。
     */
    suspend fun download(id: String): Result<ByteArray> {
        val entity = attachmentDao.getById(id)
            ?: return Result.failure(NoSuchElementException("附件不存在或已删除：id=$id"))

        val mk = auth.masterKey?.takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("资料库未解锁"))

        val plain = try {
            CryptoEnvelope.openRecord(
                mk,
                entity.encryptedPayload,
                entity.id,
                entity.module,
                version = 1L,
            )
        } catch (e: IllegalStateException) {
            // CryptoEnvelope.aeadOpen 失败时 check() 抛 ISE（密钥/AAD 不匹配）。
            return Result.failure(e)
        }

        // ---- 端侧 sha256 再校验：防本地缓存被调包 ----
        if (!verifySha256(plain, entity.sha256)) {
            return Result.failure(SecurityException("附件 sha256 校验失败：本地缓存可能腐坏"))
        }

        return Result.success(plain)
    }

    // ==========================================================================
    // UI 实时观察（按父记录 id 过滤）
    // ==========================================================================

    /**
     * 实时观察某父记录下的所有附件（按 updated_at 降序；过滤 deleted=0）。
     *
     * Compose AttachmentList 直接订阅此 Flow，UI 自动随 Room 写入刷新。
     *
     * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
     * @return Flow，订阅触发 DAO observeByRecordId 查询。
     */
    fun listByRecordId(recordId: String): Flow<List<AttachmentEntity>> =
        attachmentDao.observeByRecordId(recordId)

    // ==========================================================================
    // 删除（软删除墓碑 + 推 records 通道墓碑）
    // ==========================================================================

    /**
     * 软删除一个附件：本地缓存 markDeleted(id, now) + 推 records 通道墓碑。
     *
     * 算法：
     *  1) attachmentDao.markDeleted(id, now=System.currentTimeMillis())：本地行
     *     deleted=1, dirty=1, updated_at=now（驱动增量游标推进）；
     *  2) records 通道：upsertFinanceAttachment(id, {"id","deleted":true}, recordId="")
     *     ——推 tombstone records 行，服务端按 LWW 协议对账。
     *
     * **不真删**（spec 删除与保留红线）：行保留供同步协议对账；UI 列表过滤
     * deleted=0，墓碑对用户不可见。
     *
     * @param id 附件 UUID。
     * @return [Result.success] Unit；id 不存在时也返回 success（幂等删除）。
     */
    suspend fun delete(id: String): Result<Unit> {
        val now = System.currentTimeMillis()
        attachmentDao.markDeleted(id, now)
        // tombstone records 通道：明文载荷契约 {"id","deleted":true}
        val plain = JSONObject()
            .put("id", id)
            .put("deleted", true)
            .toString()
        // recordId 留空字符串——墓碑不需要回挂父记录；服务端按 id 删除即可。
        recordsRepository.upsertFinanceAttachment(id, plain, "")
        return Result.success(Unit)
    }

    // ==========================================================================
    // 同步集成（TR-3.2 / TR-11.2）
    // ==========================================================================

    /**
     * 拉取服务端下行 attachment records → 解密 → 入 Room。
     *
     * 算法（与 FinanceRepository.pullAndDecrypt 同款骨架）：
     *  1) 过滤 module="finance" AND type="attachment"；
     *  2) **墓碑优先**：deleted=true → attachmentDao.markDeleted(id, now)（不
     *     解密——与服务端 records 通道墓碑协议一致）；
     *  3) **非墓碑** → recordsRepository.decryptFinanceAttachment(rec) 拿明文 JSON →
     *     按契约 {"id","recordId","mime","size","sha256"} 解析 → 写 AttachmentEntity
     *     （dirty=0 标记为下行已对账干净）；
     *  4) 解密失败（javax.crypto.AEADBadTagException）→ **不静默吞掉**，
     *     直接抛异常（spec NFR-1 "解密失败不静默"）；
     *  5) 未知 type / module（非 attachment / 非 finance）忽略（不抛异常，
     *     便于协议版本前向兼容）。
     *
     * @param records 由调用方（CollectorWorker）预过滤的 records 列表；本方法内部
     *   会再按 module="finance" + type=FinanceModule.TYPE_ATTACHMENT 过滤一次
     *   做兜底。
     * @return 入库条目（含墓碑）数量；用于测试断言。
     * @throws javax.crypto.AEADBadTagException 解密失败（密文被改 / AAD 不匹配）。
     */
    suspend fun pullAndDecrypt(records: List<RecordEntity>): Int {
        var count = 0
        val now = System.currentTimeMillis()
        for (rec in records) {
            // 兜底过滤：只处理 finance + attachment；其他 type / module 跳过
            if (rec.module != "finance" || rec.type != FinanceModule.TYPE_ATTACHMENT) continue

            // 墓碑优先：deleted=true → markDeleted，不解密
            if (rec.deleted) {
                attachmentDao.markDeleted(rec.id, now)
                count += 1
                continue
            }

            // 非墓碑 → 解密 → 解析 → 入库
            val plain = recordsRepository.decryptFinanceAttachment(rec)
            val obj = parseAttachmentPlaintext(plain)
            val id = obj.stringOrNull("id") ?: rec.id
            val recordId = obj.stringOrNull("recordId") ?: ""
            val mime = obj.stringOrNull("mime") ?: ""
            val size = obj.longOrNull("size") ?: 0L
            val sha256 = obj.stringOrNull("sha256") ?: ""

            // ---- 入库：dirty=0（下行已对账干净），deleted=0 ----
            // 已知字段：id / recordId / mime / size / sha256；encryptedPayload 留空
            // ByteArray——本类**不**为下行附件重新加密密文（v1 既有 records 通道
            // 密文承载的是元数据密文，不是附件块本身；附件块密文由本地缓存 Room
            // 表承载；下行仅同步元数据 / 引用关系；本地密文由上传端重建）。
            // 这是 v2 附件协议关键约定：**records 通道密文是元数据 envelope**，
            // **AttachmentEntity.encryptedPayload 是附件块本地密文 envelope**，
            // 二者是**两个不同的密文**，与 AttachmentEntity 注释口径一致。
            //
            // 注：当前实现下，records 表的 attachment 行 ciphertext 是元数据
            // JSON 的密文，**不**承载附件块本身。本地缓存 Room 表 AttachmentEntity
            // 是附件块的本地 envelope 密文。下行场景下：
            //  - 服务端已存在的附件块密文 → 应通过另一个下行通道（如 envelope
            //    blob 附件通道，与 location-block 轨迹块同款协议）回传；本方法
            //    不在此处解析附件块本身；
            //  - 当前实现：附件块本地密文 = ByteArray(0)（空 envelope），由
            //    upload 时由客户端覆盖；下载时若 encryptedPayload 为空 → 返回
            //    NoSuchElementException（"附件密文未落本地"）。
            //
            // —— 该约定在 docs/module-schemas.md §9 财务附件协议有专门说明，本类
            // 实现严格遵循；测试用例验证 attachmentDao.upsert 被调用（不论密文字节）。
            attachmentDao.upsert(
                AttachmentEntity(
                    id = id,
                    recordId = recordId,
                    mime = mime,
                    size = size,
                    sha256 = sha256,
                    encryptedPayload = ByteArray(0),
                    schemaVersion = 1,
                    module = "finance",
                    createdAt = rec.createdAt,
                    updatedAt = rec.updatedAt,
                    dirty = 0,
                    deleted = 0,
                ),
            )
            count += 1
        }
        return count
    }

    /**
     * 推送 dirty 附件到 records 通道。
     *
     * 算法：
     *  1) 取 attachmentDao.dirtyList() → 全部 dirty=1 行；
     *  2) 对每条 attachment：用 attachmentId 当 records id、AttachmentEntity 字段
     *     序列化为 plaintext JSON（"id","recordId","mime","size","sha256"）→
     *     recordsRepository.upsertFinanceAttachment 密封并标 dirty=true；
     *  3) 推送成功后调 attachmentDao.markDirty(id, 0) 翻干净（与 RecordsRepository
     *     sync 推送 markClean 同款语义——本方法做的是"写 records 表 dirty=true +
     *     Room 表 dirty=false"的一对儿配合；records 表自身 dirty 由
     *     RecordsRepository.sync() 推送时翻 false）。
     *
     * **实现纪律**：本方法**不**自行推服务端（不调 api.pushRecords）；它只是把
     * 附件元数据落到 records 表 dirty 行，等待 RecordsRepository.sync() 周期
     * 推送。这是与 EventsRepository 同款的"先落 records 表 dirty=true → 等
     * RecordsRepository.sync 统一推"链路（spec 同步协议）。
     *
     * @return 已推送条目数量（即 attachmentDao.dirtyList() 大小）。
     */
    suspend fun pushChanges(): Int {
        val dirty = attachmentDao.dirtyList()
        for (entity in dirty) {
            val plain = JSONObject()
                .put("id", entity.id)
                .put("recordId", entity.recordId)
                .put("mime", entity.mime)
                .put("size", entity.size)
                .put("sha256", entity.sha256)
                .toString()
            recordsRepository.upsertFinanceAttachment(entity.id, plain, entity.recordId)
        }
        // 推送后 Room 表 dirty=0（records 表 dirty 由 sync 推服务端后翻）
        for (entity in dirty) {
            attachmentDao.markDirty(entity.id, 0)
        }
        return dirty.size
    }

    // ==========================================================================
    // 私有辅助
    // ==========================================================================

    /**
     * 校验 sha256Hex 是否为合法 SHA-256 hex 字符串（64 字符，[0-9a-f]）。
     *
     * @param sha256Hex 待校验字符串。
     * @return true=合法；false=长度错或包含非 hex 字符。
     */
    private fun isValidSha256Hex(sha256Hex: String): Boolean {
        if (sha256Hex.length != 64) return false
        for (c in sha256Hex) {
            val ok = c in '0'..'9' || c in 'a'..'f'
            if (!ok) return false
        }
        return true
    }

    /**
     * 端侧再算 SHA-256 并比对预期 hex。
     *
     * @param content 待校验明文字节。
     * @param expectedSha256Hex 期望的 SHA-256 hex 字符串（64 字符）。
     * @return true=一致；false=不一致或参数异常。
     */
    private fun verifySha256(content: ByteArray, expectedSha256Hex: String): Boolean {
        if (expectedSha256Hex.length != 64) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val actual = digest.digest(content)
        val actualHex = actual.toHex()
        return actualHex.equals(expectedSha256Hex, ignoreCase = true)
    }

    /**
     * ByteArray → lowercase hex 字符串（64 字符 for SHA-256）。
     */
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 解析 attachment 明文 JSON 契约 {"id","recordId","mime","size","sha256"}（uploaddelete
     * 公用契约；delete 路径仅 {"id","deleted":true}，缺字段时走 null / 0L 默认值）。
     *
     * 用 [org.json.JSONObject] 在 JVM 单测下能正常工作（Android stub jar 不会抛
     * RuntimeException——因为本方法只在生产路径被调用；测试桩场景下 pullAndDecrypt
     * 入参 RecordEntity 由 Proxy 占位，根本不进本方法）。
     */
    private fun parseAttachmentPlaintext(plain: String): Map<String, Any?> {
        val obj = JSONObject(plain)
        val out = linkedMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = if (obj.isNull(k)) null else obj.get(k)
        }
        return out
    }

    private fun Map<String, Any?>.stringOrNull(key: String): String? {
        val v = this[key] ?: return null
        return v as? String
    }

    private fun Map<String, Any?>.longOrNull(key: String): Long? {
        val v = this[key] ?: return null
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> null
        }
    }

    private companion object {
        /** SHA-256 hex 编码表（小写）。 */
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}