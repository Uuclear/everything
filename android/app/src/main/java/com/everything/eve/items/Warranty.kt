// ============================================================================
// 物品模块 —— 保修到期与提醒触发纯函数（stage5-items / T3 / TR-3.1）
// ============================================================================
//
// 与 web/src/items/warranty.ts 字节级语义对齐；不依赖 Room / UI / 网络。
// 时间戳均为 Unix 毫秒；天数 × MS_PER_DAY 推算（与 Web 同款漂移接受度）。
// ============================================================================

package com.everything.eve.items

import java.net.URI

/** 一天的毫秒数（与 spec FR-1 / Web MS_PER_DAY 一致） */
const val MS_PER_DAY: Long = 86_400_000L

/** 保修到期前提醒档位（天）—— FR-6 / ReminderScheduler 共用 */
val WARRANTY_REMINDER_DAYS: IntArray = intArrayOf(30, 7, 1)

private const val MAX_TAGS = 8
private const val MAX_TAG_LEN = 24

/** nextItemTrigger 所需最小字段集（对齐 web ItemTriggerLike） */
data class ItemTriggerLike(
    val warranty_duration_days: Int,
    val warranty_until_ts: Long,
)

/**
 * 计算保修截止时刻：purchase_date + duration_days × 一天毫秒。
 * duration_days=0 时返回 purchaseDateTs（表示无保修期，仅基准日）。
 */
fun warrantyUntilTs(purchaseDateTs: Long, durationDays: Int): Long {
    val days = maxOf(0, durationDays)
    return purchaseDateTs + days.toLong() * MS_PER_DAY
}

/**
 * 计算物品下一次本地闹钟触发时刻。
 * 取 warranty_until_ts 前 30 / 7 / 1 天三档中仍大于 now 的最小值；
 * warranty_duration_days=0 或全部档位已过则返回 null。
 */
fun nextItemTrigger(item: ItemTriggerLike, now: Long): Long? {
    if (item.warranty_duration_days <= 0) return null
    val until = item.warranty_until_ts
    if (until <= now) return null

    var best: Long? = null
    for (d in WARRANTY_REMINDER_DAYS) {
        val trigger = until - d.toLong() * MS_PER_DAY
        if (trigger > now && (best == null || trigger < best)) {
            best = trigger
        }
    }
    return best
}

/**
 * 二级标签归一化：去重（大小写不敏感）、转小写、单项截断 24 字符、最多 8 个。
 */
fun normalizeTags(rawTags: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>(MAX_TAGS)
    for (raw in rawTags) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val clipped = if (trimmed.length > MAX_TAG_LEN) trimmed.substring(0, MAX_TAG_LEN) else trimmed
        val key = clipped.lowercase()
        if (!seen.add(key)) continue
        out.add(key)
        if (out.size >= MAX_TAGS) break
    }
    return out
}

/**
 * 发票外部链接校验：必须 https:// 前缀且为可解析 URL。
 */
fun isValidReceiptUrl(url: String): Boolean {
    val s = url.trim()
    if (!s.startsWith("https://")) return false
    return try {
        val u = URI(s).toURL()
        u.protocol == "https" && u.host.isNotEmpty()
    } catch (_: Exception) {
        false
    }
}
