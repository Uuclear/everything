/*
 * ============================================================================
 * FinanceFilter —— 财务列表过滤 / 排序 / 搜索 纯函数（stage5-finance / Task 7 / TR-7.3）
 * ============================================================================
 *
 * 设计要点：
 *   1. **纯函数**：仅依赖入参（entity 列表 + 搜索关键字 + 排序键 + 过滤类型）；
 *      不读 ViewModel.state，避免 JUnit 单测被迫启动 AndroidViewModel / Robolectric。
 *   2. **可测**：FinanceViewModelTest 直接调用本 object 验证 search/sort/filter
 *      + archived-last 规则的字节级一致（无副作用，幂等）。
 *   3. **零知识**：金额字段在 UI 层做 mask；本函数输出原始 balance / usedLimit
 *      decimal-as-string，不在本函数做 mask（NFR-1 在 Composable 层落实）。
 *
 * 关联：
 *   - tasks.md TR-7.3
 *   - ui/finance/FinanceViewModel.filteredAccounts / filteredCards / filteredTxs
 * ============================================================================
 */

package com.everything.eve.ui.finance

import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity

/**
 * 财务列表过滤 / 排序 / 搜索纯函数（与 ViewModel 内置调用字节级对齐）。
 */
object FinanceFilter {

    /**
     * 应用 search + kind filter + sort + archived-last 后的账户列表。
     */
    fun accounts(
        list: List<FinanceAccountEntity>,
        search: String,
        sortKey: FinanceSortKey,
        filterKind: String,
    ): List<FinanceAccountEntity> {
        val q = search.trim().lowercase()
        val base = list.filter { acc ->
            val matchKind = filterKind == "all" || acc.kind == filterKind
            val matchText = q.isEmpty() ||
                acc.name.lowercase().contains(q) ||
                (acc.note?.lowercase()?.contains(q) == true)
            matchKind && matchText
        }
        val sorted = when (sortKey) {
            FinanceSortKey.UPDATED_DESC -> base.sortedByDescending { it.updatedAt }
            FinanceSortKey.UPDATED_ASC -> base.sortedBy { it.updatedAt }
            FinanceSortKey.BALANCE_ASC -> base.sortedBy { parseDecimalCents(it.balance) }
            FinanceSortKey.BALANCE_DESC -> base.sortedByDescending { parseDecimalCents(it.balance) }
        }
        // archived=true 置底：true.toInt()=1 → 排在 false(=0) 之后
        return sorted.sortedBy { if (it.archived) 1 else 0 }
    }

    /**
     * 应用 search + kind/brand filter + sort + archived-last 后的卡片列表。
     */
    fun cards(
        list: List<FinanceCardEntity>,
        search: String,
        sortKey: FinanceSortKey,
        filterKind: String,
    ): List<FinanceCardEntity> {
        val q = search.trim().lowercase()
        val base = list.filter { card ->
            val matchKind = filterKind == "all" || card.kind == filterKind || card.brand == filterKind
            val matchText = q.isEmpty() ||
                card.name.lowercase().contains(q) ||
                card.issuer.lowercase().contains(q) ||
                (card.holder?.lowercase()?.contains(q) == true) ||
                (card.note?.lowercase()?.contains(q) == true)
            matchKind && matchText
        }
        val sorted = when (sortKey) {
            FinanceSortKey.UPDATED_DESC -> base.sortedByDescending { it.updatedAt }
            FinanceSortKey.UPDATED_ASC -> base.sortedBy { it.updatedAt }
            FinanceSortKey.BALANCE_ASC -> base.sortedBy { parseDecimalCents(it.usedLimit ?: "0") }
            FinanceSortKey.BALANCE_DESC -> base.sortedByDescending { parseDecimalCents(it.usedLimit ?: "0") }
        }
        // archived=true 置底：true.toInt()=1 → 排在 false(=0) 之后
        return sorted.sortedBy { if (it.archived) 1 else 0 }
    }

    /**
     * 应用 search + kind filter + occurredAt-desc + 关联账户归档置底 后的流水列表。
     */
    fun txs(
        list: List<FinanceTxEntity>,
        search: String,
        filterKind: String,
        accounts: List<FinanceAccountEntity> = emptyList(),
    ): List<FinanceTxEntity> {
        val q = search.trim().lowercase()
        val base = list.filter { tx ->
            val matchKind = filterKind == "all" || tx.kind == filterKind
            val matchText = q.isEmpty() ||
                tx.category.lowercase().contains(q) ||
                (tx.note?.lowercase()?.contains(q) == true)
            matchKind && matchText
        }
        val desc = base.sortedByDescending { it.occurredAt }
        val archivedIds = accounts.filter { it.archived }.map { it.id }.toSet()
        return desc.sortedBy { tx -> tx.accountId in archivedIds }
    }

    /**
     * decimal-as-string → cents (Long)，非法/空串 → 0L。
     * 与 FinanceViewModel.balanceCents 同口径。
     */
    internal fun parseDecimalCents(s: String): Long {
        if (s.isBlank()) return 0L
        var negative = false
        var seenDot = false
        var whole = 0L
        var frac = 0L
        var fracDigits = 0
        for (c in s.trim()) {
            when {
                c == '-' && whole == 0L && !seenDot && !negative -> negative = true
                c == '+' && whole == 0L && !seenDot && !negative -> Unit
                c == '.' && !seenDot -> seenDot = true
                c in '0'..'9' -> {
                    val digit = c.code - '0'.code
                    if (seenDot) {
                        if (fracDigits < 2) {
                            frac = frac * 10L + digit.toLong()
                            fracDigits++
                        }
                    } else {
                        whole = whole * 10L + digit.toLong()
                    }
                }
                else -> return 0L
            }
        }
        while (fracDigits < 2) {
            frac *= 10L
            fracDigits++
        }
        var cents = whole * 100L + frac
        if (negative) cents = -cents
        return cents
    }
}
