package com.everything.eve.collector.location.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 位置轨迹 DAO（阶段 4a，v4 迁移新增）。
 *
 *  - location_points：明文缓冲——封块即删（[deleteUpToTs]）、
 *    24h 过期强制清除（[deleteExpired]），是全库唯一明文坐标驻留点；
 *  - location_outbox：密文块上行队列——只存密文与计数/枚举，
 *    按块 id 幂等入队（[upsertIgnore]），上行成功后出队（[delete]）。
 */
@Dao
interface LocationDao {

    // ---- location_points：明文缓冲 ----

    /** 写入一个已通过 PointFilter 过滤的采样点，返回自增行 id。 */
    @Insert
    suspend fun insert(point: LocationPointEntity): Long

    /** 全部缓冲点按 ts 升序取出（封块输入即全量，Task 5 packPending 封块源）。 */
    @Query("SELECT * FROM location_points ORDER BY ts ASC")
    suspend fun oldestFirst(): List<LocationPointEntity>

    /**
     * 删除 ts ≤ [ts] 的已封块明文段（含边界：块覆盖的最大 ts 本身已入块，
     * 且封块输入为全量 oldestFirst，该 ts 之前的点必然全部已入块）。
     *
     * @return 删除行数（密封成功后明文清理计数）。
     */
    @Query("DELETE FROM location_points WHERE ts <= :ts")
    suspend fun deleteUpToTs(ts: Long): Int

    /**
     * 删除 ts 早于 [beforeTs] 的过期明文点（FR-4 红线：24 小时未加密封块强制清除，
     * 防 MK 长期不可用导致明文滞留；调用方传 now - LocationParams.POINT_EXPIRY_MS）。
     *
     * @return 删除行数（expiredDropped 计数）。
     */
    @Query("DELETE FROM location_points WHERE ts < :beforeTs")
    suspend fun deleteExpired(beforeTs: Long): Int

    /** 统计 ts ≥ [dayStartTs] 的采样点数（采集卡片"今日已采点数"状态行）。 */
    @Query("SELECT COUNT(*) FROM location_points WHERE ts >= :dayStartTs")
    suspend fun countSince(dayStartTs: Long): Int

    // ---- location_outbox：密文块上行队列 ----

    /**
     * 密文块入队（INSERT OR IGNORE，幂等锚点）。
     *
     * Room 的 INSERT 语句只允许返回 void/rowid，故底层用 @Insert(IGNORE)
     * （生成的 SQL 即 INSERT OR IGNORE），这里把 rowid 翻译为影响行数。
     *
     * @return 影响行数——1 表示新块入队；0 表示同块 id 已在队
     *         （同参数重复封块的幂等命中，调用方据此跳过重复上行）。
     */
    suspend fun upsertIgnore(entry: LocationOutboxEntity): Int =
        if (insertOrIgnore(entry) == -1L) 0 else 1

    /**
     * [upsertIgnore] 的底层插入：返回新行 rowid；块 id 冲突被 IGNORE 时返回 -1。
     * 新块 attempts 由实体缺省 0 写入（与列 DEFAULT 0 同语义）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entry: LocationOutboxEntity): Long

    /** 待上行块按入队时间升序全取（先入队先传）。 */
    @Query("SELECT * FROM location_outbox ORDER BY created_at ASC")
    suspend fun pending(): List<LocationOutboxEntity>

    /** 上行成功后按块 id 批量出队。 */
    @Query("DELETE FROM location_outbox WHERE block_id IN (:blockIds)")
    suspend fun delete(blockIds: List<String>): Int

    /** 待传块总数（采集卡片"待传块数"状态行）。 */
    @Query("SELECT COUNT(*) FROM location_outbox")
    suspend fun count(): Int

    /**
     * 上行失败批次的重试计数 +1（网络/5xx 与 4xx 留队均递增）。
     * attempts 达 [com.everything.eve.collector.location.LocationUploader.MAX_ATTEMPTS]
     * 的坏块由上行编排记 give_up 跳过（不删不丢），本列是唯一的坏块判据。
     *
     * @return 影响行数（观测用，调用方不强制消费）。
     */
    @Query("UPDATE location_outbox SET attempts = attempts + 1 WHERE block_id IN (:blockIds)")
    suspend fun incrementAttempts(blockIds: List<String>): Int

    // ---- 采集卡片状态行的实时观察（Room 失效表驱动，封块/上行后自动刷新）----

    /** 今日已采点数实时观察（与 FGS 常驻通知"今日 N 点"同 countSince 口径）。 */
    @Query("SELECT COUNT(*) FROM location_points WHERE ts >= :dayStartTs")
    fun observeCountSince(dayStartTs: Long): Flow<Int>

    /** 待传块数实时观察（上行成功删队后自动回落）。 */
    @Query("SELECT COUNT(*) FROM location_outbox")
    fun observeOutboxCount(): Flow<Int>
}
