package com.everything.eve.collector.location.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 轨迹密文块上行队列表（v4 迁移新增，阶段 4a）。
 *
 * 只存 XChaCha20-Poly1305 信封密文（AAD 绑块 id），绝无任何明文坐标。
 * 块 id 由 BlockPacker.blockId 确定性派生（"{deviceId}:{startTs}:{endTs}"），
 * 全链路幂等锚点：本表 INSERT OR IGNORE 去重（[LocationDao.upsertIgnore]），
 * 服务端按同 id 幂等 upsert。
 *
 * 诊断列只存计数（[pointCount] / [attempts]），last_error 类信息一律枚举原因
 * （NFR-1），严禁写入坐标或其他明文内容。
 */
@Entity(tableName = "location_outbox")
data class LocationOutboxEntity(
    /** 幂等块 id："{deviceId}:{startTs}:{endTs}"（UTC 毫秒）。 */
    @PrimaryKey
    @ColumnInfo(name = "block_id") val blockId: String,
    /** 块首点时刻（UTC 毫秒；服务端月表归属按该值 UTC 月份）。 */
    @ColumnInfo(name = "start_ts") val startTs: Long,
    /** 块末点时刻（UTC 毫秒）。 */
    @ColumnInfo(name = "end_ts") val endTs: Long,
    /** 块内点数（≤ LocationParams.MAX_POINTS_PER_BLOCK）。 */
    @ColumnInfo(name = "point_count") val pointCount: Int,
    /** 轨迹块明文 JSON 的 XChaCha20-Poly1305 信封密文（nonce||cipher）。 */
    val cipher: ByteArray,
    /** 入队本地毫秒时间戳（pending 按此升序，先入队先传）。 */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 上行失败次数（attempts ≥8 的坏块记 give_up 留队待排查，不删不丢）。 */
    @ColumnInfo(name = "attempts", defaultValue = "0") val attempts: Int = 0,
) {
    // data class 默认 equals/hashCode 对 ByteArray 走引用比较，
    // 这里按内容比较，保证测试断言与日志判重行为符合直觉（不含明文，仅密文）。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocationOutboxEntity) return false
        return blockId == other.blockId &&
            startTs == other.startTs &&
            endTs == other.endTs &&
            pointCount == other.pointCount &&
            cipher.contentEquals(other.cipher) &&
            createdAt == other.createdAt &&
            attempts == other.attempts
    }

    override fun hashCode(): Int {
        var result = blockId.hashCode()
        result = 31 * result + startTs.hashCode()
        result = 31 * result + endTs.hashCode()
        result = 31 * result + pointCount
        result = 31 * result + cipher.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + attempts
        return result
    }
}
