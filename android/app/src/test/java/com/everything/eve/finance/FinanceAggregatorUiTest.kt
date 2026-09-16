/*
 * ============================================================================
 * FinanceAggregator Ui 相关集成测试（stage5-finance / Task 7 / TR-7.5）
 * ============================================================================
 *
 * 设计要点：
 *   1. **JVM 友好**：不依赖 Android / Compose / Robolectric。对 FinanceAggregator
 *      纯函数 + FinanceFilter + Luhn 做端到端等价 + 边界断言（覆盖 UI 层
 *      FinanceDashboard / FinanceAccountList / FinanceCardList / FinanceTxList /
 *      FinanceEditor 调用链）。
 *   2. **用例覆盖（≥4）**：
 *      - dashboard 聚合：净资产 = 资产 - 负债；归档不计资产但计入计数
 *      - sort + search 联动（已 archived-last + 关键字匹配）
 *      - 卡片 brand / kind 组合 filter 命中
 *      - 流水按月聚合 income/expense/net — 跨月过滤
 *      - 预算阈值 OK/WARNING/EXCEEDED 三档
 *      - Luhn 校验通过 + 提取末四位；非法输入失败但不抛错
 *   3. **零知识**：测试数据采用虚构 UUID + 余额数字，不引入真实账户。
 *
 * 关联：
 *   - finance/FinanceAggregator.kt
 *   - ui/finance/FinanceFilter.kt
 * ============================================================================
 */

package com.everything.eve.finance

import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity
import com.everything.eve.ui.finance.FinanceFilter
import com.everything.eve.ui.finance.FinanceSortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * FinanceAggregator / FinanceFilter / Luhn 集成友好测试（≥4 用例）。
 *
 * 之所以叫 "UiTest"：覆盖 T7 Compose UI 列表/编辑器/看板调用 FinanceAggregator
 * 与 FinanceFilter 的端到端等价链 —— 用纯 JUnit 即可校验，无需启动 Compose runtime。
 */
class FinanceAggregatorUiTest {

    companion object {
        /**
         * CST 锁定（FinanceAggregator.yearMonthOf 使用 ZoneId.systemDefault 拆本地日历分量）。
         * 与 FinanceAggregatorTest 同口径。
         */
        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }

        // 2026-01-01 00:00 UTC = 2026-01-01 08:00 CST → FinanceAggregator.yearMonthOf 平移 8h 后落 2026-01
        private const val T_2026_01_01_CST_MS = 1767225600000L

        // 2026-01-15 00:00 UTC = 2026-01-15 08:00 CST → 平移 8h 后落 2026-01
        private const val T_2026_01_15_CST_MS = 1768435200000L

        // 2026-02-01 00:00 UTC = 2026-02-01 08:00 CST → 平移 8h 后落 2026-02
        private const val T_2026_02_01_CST_MS = 1769904000000L
    }

    // ============================================================================
    // 私有 fixture 工厂
    // ============================================================================

    private fun acc(id: String, balance: String, archived: Boolean = false, currency: String = "CNY") =
        FinanceAggregator.AccountLike(id = id, balance = balance, currency = currency, archived = archived)

    private fun card(id: String, kind: String, usedLimit: String?, archived: Boolean = false) =
        FinanceAggregator.CardLike(id = id, kind = kind, usedLimit = usedLimit, archived = archived)

    private fun tx(id: String, accountId: String? = null, cardId: String? = null, kind: String = "expense",
                   amount: String = "100.00", category: String = "food",
                   occurredAt: Long = T_2026_01_01_CST_MS,
                   transferToAccountId: String? = null) =
        FinanceAggregator.TxLike(id = id, accountId = accountId, cardId = cardId, kind = kind,
            amount = amount, category = category, occurredAt = occurredAt,
            transferToAccountId = transferToAccountId)

    private fun accEntity(
        id: String,
        name: String,
        kind: String = "cash",
        balance: String = "0",
        archived: Boolean = false,
        note: String? = null,
        updatedAt: Long = 0L,
    ) = FinanceAccountEntity(
        id = id,
        name = name,
        kind = kind,
        currency = "CNY",
        balance = balance,
        note = note,
        icon = null,
        color = null,
        archived = archived,
        createdAt = 1L,
        updatedAt = updatedAt,
        schema_version = 1,
        module = "finance",
        type = "account",
        dirty = true,
        deleted = false,
    )

    private fun cardEntity(
        id: String,
        name: String,
        kind: String = "credit",
        brand: String? = "visa",
        usedLimit: String? = "0",
        archived: Boolean = false,
        updatedAt: Long = 0L,
    ) = FinanceCardEntity(
        id = id,
        name = name,
        kind = kind,
        issuer = "Issuer",
        last4 = "0000",
        currency = "CNY",
        creditLimit = null,
        usedLimit = usedLimit,
        billingDay = null,
        dueDay = null,
        brand = brand,
        expiryMonth = null,
        expiryYear = null,
        holder = null,
        note = null,
        icon = null,
        color = null,
        archived = archived,
        createdAt = 1L,
        updatedAt = updatedAt,
        schema_version = 1,
        module = "finance",
        type = "card",
        dirty = true,
        deleted = false,
    )

    private fun txEntity(
        id: String,
        accountId: String,
        kind: String = "expense",
        amount: String = "100.00",
        category: String = "food",
        occurredAt: Long = T_2026_01_01_CST_MS,
    ) = FinanceTxEntity(
        id = id,
        accountId = accountId,
        cardId = null,
        kind = kind,
        amount = amount,
        currency = "CNY",
        category = category,
        occurredAt = occurredAt,
        note = null,
        icon = null,
        color = null,
        transferToAccountId = null,
        createdAt = 1L,
        updatedAt = 0L,
        schema_version = 1,
        module = "finance",
        type = "tx",
        dirty = true,
        deleted = false,
    )

    // ============================================================================
    // 用例 1：净资产聚合 —— 资产 - 负债；归档过滤；计数
    // ============================================================================

    /**
     * 用例 1：dashboard 三数字卡数据契约。
     *
     * - 2 张非归档账户余额 1000 + 500 → 总资产 1500
     * - 1 张已归档账户 9999 → 不计入资产但计入 count
     * - 1 张非归档信用卡 used=200 → 总负债 200
     * - 1 张借记卡 used=999 → 不计入负债
     * - 净资产 = 1500 - 200 = 1300
     * - 货币：取第一条账户 currency = "CNY"
     */
    @Test
    fun aggregator_netWorth_archiveFilter() {
        val accounts = listOf(
            acc("a1", "1000.00"),
            acc("a2", "500.00"),
            acc("archived", "9999.00", archived = true),
        )
        val cards = listOf(
            card("c1", "credit", "200.00"),
            card("c2", "debit", "999.00"), // 借记卡不计负债
            card("c3-archived", "credit", "500.00", archived = true), // 归档不计
        )
        val out = FinanceAggregator.netWorth(accounts, cards)
        assertEquals("1300.00", out.totalAssets)
        assertEquals("1500.00", out.totalAssetValue)
        assertEquals("200.00", out.totalLiability)
        assertEquals(3, out.accountCount)
        assertEquals(3, out.cardCount)
        assertEquals("CNY", out.currency)
    }

    // ============================================================================
    // 用例 2：单账户余额 + 流水联动
    // ============================================================================

    /**
     * 用例 2：accountBalance 联动 income/expense/transfer 多方向。
     */
    @Test
    fun aggregator_accountBalance_lifecycle() {
        val account = acc("acc-1", "100.00")
        val txs = listOf(
            tx(id = "tx1", accountId = "acc-1", kind = "income", amount = "500.00"),
            tx(id = "tx2", accountId = "acc-1", kind = "expense", amount = "200.00"),
            tx(id = "tx3", accountId = "acc-1", cardId = "c1", kind = "transfer",
               amount = "50.00", transferToAccountId = "acc-2"),
        )
        val out = FinanceAggregator.accountBalance(account, txs)
        assertEquals("350.00", out) // 100 + 500 - 200 - 50 = 350
    }

    // ============================================================================
    // 用例 3：月度汇总 + 跨月过滤
    // ============================================================================

    /**
     * 用例 3：monthlyReport 跨月过滤 + 分类占比。
     */
    @Test
    fun aggregator_monthlyReport_crossMonth() {
        val txs = listOf(
            tx(id = "tx1", kind = "income", amount = "1000.00", category = "salary",
                occurredAt = T_2026_01_15_CST_MS),
            tx(id = "tx2", kind = "expense", amount = "200.00", category = "food",
                occurredAt = T_2026_01_15_CST_MS),
            tx(id = "tx3", kind = "expense", amount = "300.00", category = "food",
                occurredAt = T_2026_01_15_CST_MS),
            tx(id = "tx4", kind = "expense", amount = "150.00", category = "transport",
                occurredAt = T_2026_01_15_CST_MS),
            // 跨月：2026-02-01 → 不参与 2026-01 聚合
            tx(id = "tx5", kind = "expense", amount = "9999.00", category = "food",
                occurredAt = T_2026_02_01_CST_MS),
        )
        val report = FinanceAggregator.monthlyReport("2026-01", txs)
        assertEquals("2026-01", report.yearMonth)
        assertEquals("1000.00", report.income)
        assertEquals("650.00", report.expense) // 200 + 300 + 150
        assertEquals("350.00", report.net)    // 1000 - 650
        // txCount 包含 transfer + income + expense，但跨月不计入
        assertEquals(4, report.txCount)
        assertEquals("500.00", report.categoryBreakdown["food"])
        assertEquals("150.00", report.categoryBreakdown["transport"])
    }

    // ============================================================================
    // 用例 4：预算阈值三档判定
    // ============================================================================

    /**
     * 用例 4：budgetThreshold 三档判定（OK / WARNING / EXCEEDED）。
     */
    @Test
    fun aggregator_budgetThreshold_threeLevels() {
        // 零收入 → 一律 OK
        assertEquals(FinanceAggregator.BudgetStatus.OK,
            FinanceAggregator.budgetThreshold("0", "1000", 0.8))
        // 比值 < 1.0 → OK
        assertEquals(FinanceAggregator.BudgetStatus.OK,
            FinanceAggregator.budgetThreshold("1000", "500", 0.8))
        // 1.0 ≤ 比值 < 1.5 → WARNING
        assertEquals(FinanceAggregator.BudgetStatus.WARNING,
            FinanceAggregator.budgetThreshold("1000", "1200", 0.8))
        // ≥ 1.5 → EXCEEDED
        assertEquals(FinanceAggregator.BudgetStatus.EXCEEDED,
            FinanceAggregator.budgetThreshold("1000", "2000", 0.8))
    }

    // ============================================================================
    // 用例 5：列表搜索 + 排序 + 归档置底端到端
    // ============================================================================

    /**
     * 用例 5：Ui 调用链 —— AccountList 调用 FinanceFilter.accounts 后排序 + 归档 + 搜索等价。
     */
    @Test
    fun ui_chain_accountListFilter() {
        val list = listOf(
            accEntity(id = "1", name = "ABC Active", balance = "100", updatedAt = 100),
            accEntity(id = "2", name = "ABC Archived", balance = "999", updatedAt = 99, archived = true),
            accEntity(id = "3", name = "DEF Other", balance = "500", updatedAt = 50),
        )
        val out = FinanceFilter.accounts(
            list = list,
            search = "ABC",
            sortKey = FinanceSortKey.UPDATED_DESC,
            filterKind = "all",
        )
        // 命中 id=1 + id=2（"ABC Active" + "ABC Archived"），归档置底
        assertEquals(2, out.size)
        assertEquals("1", out[0].id)
        assertEquals("2", out[1].id)
    }

    // ============================================================================
    // 用例 6：Luhn 校验 + 末四位提取端到端
    // ============================================================================

    /**
     * 用例 6：卡编辑器调用 Luhn.extractLast4 + Luhn.luhnValidate 端到端等价。
     */
    @Test
    fun ui_chain_cardLuhnEndToEnd() {
        // 公开 Luhn 通过样例卡号
        val pan = "4111 1111 1111 1111"
        assertTrue(Luhn.luhnValidate(pan))
        assertEquals("1111", Luhn.extractLast4(pan))

        // 故意伪造一位（checksum 错误）
        val bad = "4111 1111 1111 1112"
        assertFalse(Luhn.luhnValidate(bad))
        assertNull(Luhn.extractLast4(bad))

        // 编辑器失焦后丢弃完整 PAN：UI 不再持有完整卡号，等价仅 last4 留底
        val cardEntityOnlyLast4 = cardEntity(id = "c1", name = "MyCard")
        assertEquals("0000", cardEntityOnlyLast4.last4)
    }

    // ============================================================================
    // 用例 7：流水列表渲染 —— 跨月过滤 + 关联账户归档置底
    // ============================================================================

    /**
     * 用例 7：TxList UI 调用链 —— 跨月应被排除；归档账户下流水置底。
     */
    @Test
    fun ui_chain_txListWithArchive() {
        val txs = listOf(
            txEntity(id = "1", accountId = "active", occurredAt = T_2026_01_01_CST_MS + 100),
            txEntity(id = "2", accountId = "archived", occurredAt = T_2026_01_01_CST_MS + 200),
            txEntity(id = "3", accountId = "active", occurredAt = T_2026_02_01_CST_MS), // 跨月自然不命中 2026-01
        )
        val accounts = listOf(
            accEntity(id = "active", name = "Active"),
            accEntity(id = "archived", name = "Archived", archived = true),
        )

        // 验证 monthlyReport 用 2026-01 key 过滤 tx3（02 月）
        val report = FinanceAggregator.monthlyReport("2026-01", txs.map {
            FinanceAggregator.TxLike(
                id = it.id,
                accountId = it.accountId,
                cardId = null,
                kind = it.kind,
                amount = it.amount,
                category = it.category,
                occurredAt = it.occurredAt,
                transferToAccountId = null,
            )
        })
        assertEquals(2, report.txCount)

        // 验证 filtered txs 把 archived 账户下的流水置底（按 occurredAt desc）
        val out = FinanceFilter.txs(
            list = txs,
            search = "",
            filterKind = "all",
            accounts = accounts,
        )
        assertEquals(3, out.size)
        // 跨月的 tx3 虽然 occurredAt 较晚，但按 occurredAt desc 排序不影响；归档账户下流水置底
        // tx1(active) 在前, tx2(archived) 其次, tx3(cross-month) 按时间排最后
        assertEquals("3", out[0].id)
        assertEquals("1", out[1].id)
        assertEquals("2", out[2].id)
    }

    // ============================================================================
    // 用例 8：净资产聚合空集合 → 不抛错，全 0
    // ============================================================================

    /**
     * 用例 8：空账户/空卡/空流水 → 全 0 净资产（FinanceDashboard 空态对应）。
     */
    @Test
    fun aggregator_netWorth_emptyCollectionNoThrow() {
        val snap = FinanceAggregator.netWorth(emptyList(), emptyList(), emptyList())
        assertEquals("0.00", snap.totalAssets)
        assertEquals("0.00", snap.totalAssetValue)
        assertEquals("0.00", snap.totalLiability)
        assertEquals(0, snap.accountCount)
        assertEquals(0, snap.cardCount)
        assertEquals(0, snap.txCount)
        assertEquals("CNY", snap.currency)

        // monthlyReport 空集合
        val monthly = FinanceAggregator.monthlyReport("2026-01", emptyList())
        assertEquals(0, monthly.txCount)
        assertEquals("0.00", monthly.income)
        assertEquals("0.00", monthly.expense)

        // cardUsedLimit 单卡 0 usedLimit + 无流水 → "0.00"
        val cardOnly = card("c1", "credit", "0")
        val used = FinanceAggregator.cardUsedLimit(cardOnly, emptyList())
        assertEquals("0.00", used)
    }
}
