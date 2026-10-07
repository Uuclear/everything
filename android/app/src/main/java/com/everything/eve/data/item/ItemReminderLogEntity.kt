package com.everything.eve.data.item

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 物品提醒/降级日志表（阶段 5 items，v11 迁移新增）。
 *
 * `kind` 枚举：`warranty_expiring` | `alarm_killed` | `notification_denied` |
 * `exact_denied`。仅存 item_id + occurrence_ts，不写 name/价格等明文。
 */
@Entity(tableName = "item_reminder_log")
data class ItemReminderLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val item_id: String,
    val occurrence_ts: Long,
    val kind: String,
    val created_ts: Long,
)
