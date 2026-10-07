package com.everything.eve.data.item

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 物品提醒降级日志 DAO（阶段 5 items Task 4 / TR-4.2）。
 */
@Dao
interface ItemReminderLogDao {

    @Insert
    suspend fun insert(entity: ItemReminderLogEntity): Long

    @Query(
        """
        INSERT INTO item_reminder_log (item_id, occurrence_ts, kind, created_ts)
        VALUES (:itemId, :occurrenceTs, :kind, :createdTs)
        """,
    )
    suspend fun insertRaw(itemId: String, occurrenceTs: Long, kind: String, createdTs: Long): Long

    @Query(
        "SELECT * FROM item_reminder_log ORDER BY created_ts DESC LIMIT :limit",
    )
    suspend fun recent(limit: Int): List<ItemReminderLogEntity>

    @Query("DELETE FROM item_reminder_log WHERE created_ts < :beforeTs")
    suspend fun purgeBefore(beforeTs: Long): Int
}
