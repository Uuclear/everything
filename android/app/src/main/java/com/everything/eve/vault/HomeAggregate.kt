// P3 本地聚合：搜索与 7 日窗口（纯函数；明文不出日志）
package com.everything.eve.vault

import com.everything.eve.data.identity.IdentityEntity
import com.everything.eve.data.item.ItemEntity
import java.time.LocalDate

const val HOME_HORIZON_DAYS = 7

data class GlobalSearchHit(
    val module: String,
    val id: String,
    val title: String,
)

/** 证件名 + 物品名本地搜索（不含证号/卡号）。 */
fun globalSearchLocal(
    query: String,
    identities: List<IdentityEntity>,
    items: List<ItemEntity>,
): List<GlobalSearchHit> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val out = mutableListOf<GlobalSearchHit>()
    for (id in identities) {
        val hay = "${id.title}\n${id.name ?: ""}".lowercase()
        if (hay.contains(q)) {
            out += GlobalSearchHit("identity", id.id, id.title)
        }
    }
    for (item in items) {
        if (item.name.lowercase().contains(q)) {
            out += GlobalSearchHit("item", item.id, item.name)
        }
    }
    return out
}

data class IdentityExpiryRow(
    val entity: IdentityEntity,
    val days: Int,
)

fun identitiesExpiringWithinDays(
    identities: List<IdentityEntity>,
    withinDays: Int,
    today: LocalDate = LocalDate.now(),
): List<IdentityExpiryRow> {
    return identities
        .mapNotNull { e ->
            val days = expiryDays(e.expiresOn ?: "", today) ?: return@mapNotNull null
            if (days < 0 || days > withinDays) return@mapNotNull null
            IdentityExpiryRow(e, days)
        }
        .sortedBy { it.days }
}
