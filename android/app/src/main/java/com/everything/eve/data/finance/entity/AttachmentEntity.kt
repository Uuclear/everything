package com.everything.eve.data.finance.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 财务附件本地缓存表（阶段 5 v2 / TR-3.1）。
 *
 * 表 `finance_attachment` 是 Vault DB 容器内的**本地密文缓存**——
 * 承担 policy / contract 等父记录（FinanceModule 中 v2 子类型占位）所引用的
 * 附件（合同扫描件、保单 PDF、票据照片等）在本机的加密态持久化。
 *
 * 重要——**双密文区分**：
 *  - 本表 `encrypted_payload` 字段存的是**本地 Room 缓存密文**（用 vault
 *    masterKey 在本地再封一层 envelope 的内容，attachment 块的明文解码只
 *    在调用方 FinanceRepository 处发生）；
 *  - 与之并行，服务端 records 通道外发的 ciphertext 是另一份密文（policy /
 *    contract 类型对应的 records envelope 服务端投递密文），二者**不是同
 *    一个密文**：一个是本地缓存，一个是外发通道。
 *
 * 字段口径（snake_case → camelCase）与 [MIGRATION_6_7][com.everything.eve.data.EveDatabase.Companion.MIGRATION_6_7]
 * 一一对应；SQLite 列名遵循 spec 命名。
 *
 * 索引说明：
 *  - `(record_id)`：按父记录（policy/contract）筛选附件；附件属于谁的索引；
 *  - `(updated_at)`：增量同步游标（与既有财务表同语义）；
 *  - `(dirty)`：同步推送对账（理论上与 records 表 dirty 联动）。
 *
 * 零知识红线（spec NFR-1 / FR-4）：
 *  - `mime` / `size` / `sha256` 仅用于本地校验与 UI 展示，**不**进
 *    SharedPreferences / 日志 / 通知文案；
 *  - `encrypted_payload` 严禁被反序列化进日志（写日志仅记 id / size / sha256
 *    摘要，不打印字节）。
 */
@Entity(
    tableName = "finance_attachment",
    // 索引：(record_id) 按父记录筛选附件；
    //       (updated_at) 增量同步游标；
    //       (dirty) 同步推送对账。
    indices = [
        Index(value = ["record_id"], name = "idx_finance_attachment_record_id"),
        Index(value = ["updated_at"], name = "idx_finance_attachment_updated_at"),
        Index(value = ["dirty"], name = "idx_finance_attachment_dirty"),
    ],
)
data class AttachmentEntity(
    /** 附件 UUID（与 records 通道外发密文 id 同空间；本表 id 是 envelope 主键）。 */
    @PrimaryKey val id: String,

    /**
     * 父记录 id（policy / contract 等 v2 子类型记录的主键）；
     * UI / 同步层用此字段把附件挂回到对应记录下。
     */
    @ColumnInfo(name = "record_id") val recordId: String,

    /**
     * MIME 类型（application/pdf / image/jpeg / image/png 等）；
     * 严格白名单，超出白名单由编辑器在调用 DAO 前拒绝。
     */
    val mime: String,

    /** 字节数（明文态体积；与 sha256 校验口径一一对应）。 */
    val size: Long,

    /**
     * 明文态 SHA-256 hex 字符串（64 字符）；
     * 本地校验与去重用，外发时不带此字段（仅 records 通道摘要）。
     */
    val sha256: String,

    /**
     * 加密后载荷（attachments 块的内容，本地 Room 缓存密文）。
     *
     * 该字段存的是 vault masterKey envelope 内的附件密文；与服务端 records
     * 通道外发的 ciphertext 是不同的密文。
     *
     * 写入前由 FinanceRepository 在调用方完成 envelope 封装；本字段仅承载
     * 密文字节，绝不写日志或通知。
     */
    @ColumnInfo(name = "encrypted_payload") val encryptedPayload: ByteArray,

    /**
     * 本表 schema 版本号；本期固定 1。
     * 与 records 通道 schemaVersion 概念一致：v2 扩展子类型或附件元数据
     * （例如多页 PDF 页数）时此处升 2。
     */
    @ColumnInfo(name = "schema_version", defaultValue = "1") val schemaVersion: Int = 1,

    /** 模块标识，固定 `"finance"`（与 records 表 module 字段一致）。 */
    @ColumnInfo(defaultValue = "finance") val module: String = "finance",

    /** 创建时刻（Unix 毫秒；本地时钟）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,

    /** 最近一次写入时间戳（毫秒；用于增量同步推送与本地排序）。 */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,

    /**
     * 本地脏标记（与 records 表 dirty 联动）；
     * 1 表示 SyncWorker 下次推送会带上对应 records 条目密文上行；
     * 0 表示已与服务端对账干净。
     */
    @ColumnInfo(defaultValue = "1") val dirty: Int = 1,

    /**
     * 软删除墓碑标记：1 表示该附件已删除但保留行用于同步推送；
     * UI 列表过滤 `deleted = 0`。
     */
    @ColumnInfo(defaultValue = "0") val deleted: Int = 0,
) {
    /**
     * 覆写 equals / hashCode——
     * ByteArray 默认走引用相等，作为 Room @PrimaryKey 主键实体若不覆写，
     * data class 自带的 equals 会把 `encryptedPayload` 当作引用比较，
     * 导致 Room upsert/observeAll 行为与预期不一致。
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AttachmentEntity) return false
        if (id != other.id) return false
        if (recordId != other.recordId) return false
        if (mime != other.mime) return false
        if (size != other.size) return false
        if (sha256 != other.sha256) return false
        if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        if (schemaVersion != other.schemaVersion) return false
        if (module != other.module) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (dirty != other.dirty) return false
        if (deleted != other.deleted) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + recordId.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + encryptedPayload.contentHashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + module.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + dirty
        result = 31 * result + deleted
        return result
    }
}