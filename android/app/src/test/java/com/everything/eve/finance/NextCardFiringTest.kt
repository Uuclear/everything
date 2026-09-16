// ============================================================================
// NextCardFiring 纯函数单元测试（stage5-finance / Task 5 / TR-5.4）
// ============================================================================
//
// 验证目标（≥4 用例, 覆盖 TR-5.4 Pass Condition + 全部边界）：
//   1. fixture 数据驱动 —— 共享 nextCardFiring-cases.json（7 条用例, ≥4 硬性指标）；
//   2. 关键场景显式断言（账单日 T+0 09:00 / 还款日 T-1 09:00 / 跨月滚动）；
//   3. 边界与负例显式断言（归档卡 / 缺 billingDay / 缺 dueDay）；
//   4. upcomingTriggers 多卡批量取全局最小（与 4b rebuildChain 语义一致）；
//   5. zero-knowledge 纪律（pure 语义 + 幂等性 + 入参不被修改）。
//
// 共享 fixture（__fixtures__/nextCardFiring-cases.json）由三端共同加载, SHA-256 必须
// 字节级一致 —— 见 tasks.md TR-5.4; 本测试仅读加载, 不修改 fixture 内容。
//
// 零知识纪律：
//   - 测试卡号均为业界公开示例 last4（如 "1111" / "2222"）, 非真实持卡人卡号；
//   - 不在断言失败消息中打印完整卡号（fixture 仅含 last4, 已是安全摘要）；
//   - 不向 Room / DataStore / 网络写入任何数据。
//
// 关联:
//   - android/.../finance/NextCardFiring.kt（被测目标）
//   - android/.../finance/__fixtures__/nextCardFiring-cases.json（共享 fixture）
//   - web/src/finance/__fixtures__/nextCardFiring-cases.json（Web 镜像, 三端字节级一致）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test

/**
 * NextCardFiring 纯函数 JUnit 4 单元测试 —— 与 Web `nextCardFiring.spec.ts` 行为对齐。
 *
 * 用例数守护:
 *   - fixture 至少 4 条用例（TR-5.4 硬性指标）, 当前 fixture 7 条;
 *   - 关键场景 + 边界负例 + upcomingTriggers 联动 + 零知识纪律共 ~12 条 @Test。
 */
class NextCardFiringTest {

    // ============================================================================
    // 时区锁定（fixture CST 锚定；与 4b RecurrenceTest / FinanceAggregatorTest 同款）
    // ============================================================================

    companion object {
        /**
         * 在所有 @Test 执行前, 锁定 JVM 默认时区到 CST（UTC+8）。
         *
         * 原因：NextCardFiring.nextTrigger 使用 ZoneId.systemDefault() 拆本地日历分量；
         * fixture 期望值（如 "2026-01-15 09:00 CST"）按 UTC+8 计算, 必须在单测 class
         * 加载**前**完成时区锁定, 否则 java.time 在 CI 环境（CET/UTC）会拆出错误 y/m,
         * 导致 nextTrigger 用例失败。
         */
        @BeforeClass
        @JvmStatic
        fun lockTimezone() {
            val cst = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            java.util.TimeZone.setDefault(cst)
            System.setProperty("user.timezone", "Asia/Shanghai")
        }
    }

    // ============================================================================
    // 共享 fixture 加载 —— 复用 FinanceAggregatorTest 双路径兜底策略
    // ============================================================================

    /**
     * fixture 单条用例结构 —— 与 Web `NextCardFiringCase` 字段命名对齐。
     */
    private data class NextCardFiringCase(
        val name: String,
        val card: NextCardFiring.CardLike,
        val nowMs: Long,
        val expectedTriggerMs: Long?, // null 表示 nextTrigger 应返回 null
    )

    private val cases: List<NextCardFiringCase> by lazy { loadFixture() }

    /**
     * 从 classpath 或文件系统加载共享 fixture（与 FinanceAggregatorTest 同款策略）。
     */
    private fun loadFixture(): List<NextCardFiringCase> {
        val classpathPath = "finance/__fixtures__/nextCardFiring-cases.json"
        val cpResource = this::class.java.classLoader?.getResource(classpathPath)
        val text = if (cpResource != null) {
            cpResource.readText(Charsets.UTF_8)
        } else {
            val candidates = listOf(
                "src/test/java/com/everything/eve/finance/__fixtures__/nextCardFiring-cases.json",
                "android/app/src/test/java/com/everything/eve/finance/__fixtures__/nextCardFiring-cases.json",
                "app/src/test/java/com/everything/eve/finance/__fixtures__/nextCardFiring-cases.json",
                "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/nextCardFiring-cases.json",
                "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/nextCardFiring-cases.json",
                "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\nextCardFiring-cases.json",
            )
            val file = candidates
                .map { java.io.File(it) }
                .firstOrNull { it.exists() }
                ?: throw IllegalStateException(
                    "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
                )
            file.readText(Charsets.UTF_8)
        }
        return parseCases(text)
    }

    /**
     * 极简 JSON 解析 —— 仅服务于本测试 fixture。
     *
     * fixture 顶层: { "_anchor_now_ms": 数字, "cases": [{name, input: {card, nowMs}, expected: {triggerMs}}, ...] }
     * card 块含 id / kind / billingDay / dueDay / archived 字段（Int 可空 + Bool）。
     */
    private fun parseCases(json: String): List<NextCardFiringCase> {
        val result = mutableListOf<NextCardFiringCase>()
        // 提取 cases 数组内容。
        val casesStart = json.indexOf("\"cases\"")
        if (casesStart < 0) return emptyList()
        val arrayStart = json.indexOf('[', casesStart)
        if (arrayStart < 0) return emptyList()
        var depth = 0
        var arrayEnd = -1
        for (i in arrayStart until json.length) {
            when (json[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        arrayEnd = i
                        break
                    }
                }
            }
        }
        if (arrayEnd < 0) return emptyList()
        val casesBody = json.substring(arrayStart + 1, arrayEnd)

        // 拆出每个顶层对象 {...}。
        val topObjects = splitTopLevelObjects(casesBody)
        for (obj in topObjects) {
            val name = extractStringField(obj, "name") ?: continue
            val inputBlock = extractObjectField(obj, "input") ?: continue
            // card 块字段解析。
            val cardBlock = extractObjectField(inputBlock, "card") ?: continue
            val card = NextCardFiring.CardLike(
                id = extractStringField(cardBlock, "id") ?: "",
                kind = extractStringField(cardBlock, "kind") ?: "credit",
                billingDay = extractIntOrNullField(cardBlock, "billingDay"),
                dueDay = extractIntOrNullField(cardBlock, "dueDay"),
                archived = extractBoolField(cardBlock, "archived"),
            )
            val nowMs = extractLongField(inputBlock, "nowMs")
            // expected 块 triggerMs 字段（null 表示 nextTrigger 期望返回 null）。
            val expectedBlock = extractObjectField(obj, "expected")
            val expectedTriggerMs: Long? = if (expectedBlock == null) {
                null
            } else {
                extractLongOrNullField(expectedBlock, "triggerMs")
            }
            result.add(NextCardFiringCase(name, card, nowMs, expectedTriggerMs))
        }
        return result
    }

    /** 在 JSON 文本中找出所有顶层对象（不计嵌套的 {...}）的内容。 */
    private fun splitTopLevelObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            val start = body.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until body.length) {
                when (body[j]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = j
                            break
                        }
                    }
                }
            }
            if (end < 0) break
            out.add(body.substring(start + 1, end))
            i = end + 1
        }
        return out
    }

    private fun extractStringField(obj: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(obj)?.groupValues?.get(1)
    }

    private fun extractIntField(obj: String, field: String): Int {
        val regex = Regex("\"$field\"\\s*:\\s*(-?\\d+)")
        return regex.find(obj)?.groupValues?.get(1)?.toInt() ?: 0
    }

    private fun extractLongField(obj: String, field: String): Long {
        val regex = Regex("\"$field\"\\s*:\\s*(-?\\d+)")
        return regex.find(obj)?.groupValues?.get(1)?.toLong() ?: 0L
    }

    /**
     * 提取可空 Int 字段（"key": null | 数字）。
     *
     * 返回 null 表示 fixture 中字段为 null 显式标注；非 null 则按 Int 解析。
     */
    private fun extractIntOrNullField(obj: String, field: String): Int? {
        val nullRegex = Regex("\"$field\"\\s*:\\s*null")
        if (nullRegex.containsMatchIn(obj)) return null
        return extractIntField(obj, field)
    }

    /**
     * 提取可空 Long 字段（"key": null | 数字）。
     */
    private fun extractLongOrNullField(obj: String, field: String): Long? {
        val nullRegex = Regex("\"$field\"\\s*:\\s*null")
        if (nullRegex.containsMatchIn(obj)) return null
        return extractLongField(obj, field)
    }

    private fun extractBoolField(obj: String, field: String): Boolean {
        val regex = Regex("\"$field\"\\s*:\\s*(true|false)")
        return regex.find(obj)?.groupValues?.get(1) == "true"
    }

    /** 提取对象字段的原始 JSON 片段（"key": {...}）。 */
    private fun extractObjectField(obj: String, field: String): String? {
        val keyIdx = obj.indexOf("\"$field\"")
        if (keyIdx < 0) return null
        val colonIdx = obj.indexOf(':', keyIdx)
        if (colonIdx < 0) return null
        var i = colonIdx + 1
        while (i < obj.length && obj[i].isWhitespace()) i++
        if (i >= obj.length || obj[i] != '{') return null
        var depth = 0
        var end = -1
        for (j in i until obj.length) {
            when (obj[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
        }
        return if (end < 0) null else obj.substring(i + 1, end)
    }

    // ============================================================================
    // 1. fixture 加载驱动 —— 从 JSON 数据驱动全部用例（≥4 硬性指标）
    // ============================================================================

    /**
     * 守护测试 —— fixture 用例数 ≥4（TR-5.4 硬性指标）。
     */
    @Test
    fun fixture_hasAtLeastFourCases() {
        assertTrue(
            "fixture 用例数应不少于 4 条, 实际 = ${cases.size}",
            cases.size >= 4
        )
    }

    /**
     * fixture 数据驱动 —— 每条用例生成一个 @Test。
     *
     * 一次跑完所有用例, 失败时给出 case name + 实际 vs 期望 ms 摘要。
     */
    @Test
    fun fixture_driven_allCasesPass() {
        for (tc in cases) {
            val actual = NextCardFiring.nextTrigger(tc.card, tc.nowMs)
            // nextTrigger 可能为 null（早退守卫或未来无候选）。
            if (tc.expectedTriggerMs == null) {
                assertNull(
                    "case[${tc.name}] 期望返回 null, 实际 = $actual",
                    actual
                )
            } else {
                assertNotNull(
                    "case[${tc.name}] 期望非 null, 实际 = null",
                    actual
                )
                assertEquals(
                    "case[${tc.name}] nextTrigger 不一致",
                    tc.expectedTriggerMs, actual
                )
            }
        }
    }

    // ============================================================================
    // 2. 关键场景显式断言（覆盖账单日 / 还款日 / 跨月滚动）
    // ============================================================================

    /**
     * 关键锚点验证 —— CST 2026-01-01 09:00 的 Unix ms 应 = 1767229200000。
     *
     * 这是 fixture `_anchor_now_ms` 的权威值, 验证时区锁定 + 算法正确性。
     */
    @Test
    fun anchor_cst20260101_0900_isExpectedMs() {
        // CST 2026-01-01 09:00 = UTC 2026-01-01 01:00 = 1767229200000
        // 计算方法: LocalDateTime.of(2026, 1, 1, 9, 0).toInstant(UTC).toEpochMilli() - 480*60_000
        // = 1767229200000
        // 此处通过 nowMs 反向验证 nowMs 转 y/m/d 拆出来的本地分量是 2026-01-01。
        val testNowMs = 1767229200000L
        val card = NextCardFiring.CardLike(
            id = "c0", kind = "credit",
            billingDay = 15, dueDay = 25, archived = false,
        )
        // nextTrigger 应返回 1768438800000（CST 2026-01-15 09:00），与第一例 fixture 一致。
        assertEquals(1768438800000L, NextCardFiring.nextTrigger(card, testNowMs))
    }

    /**
     * 账单日 T+0 09:00 —— 关键正例直接断言（不依赖 fixture）。
     */
    @Test
    fun nextTrigger_statementDayAt0900() {
        // 账单日 15 日，未过 → 返回当月 15 日 09:00 CST = 1768438800000
        val card = NextCardFiring.CardLike(
            id = "c1", kind = "credit",
            billingDay = 15, dueDay = 25, archived = false,
        )
        assertEquals(1768438800000L, NextCardFiring.nextTrigger(card, 1767229200000L))
    }

    /**
     * 还款日 T-1 09:00 —— 账单日已过但还款日未过时, 返回还款日前一天 09:00。
     */
    @Test
    fun nextTrigger_paymentDueT1() {
        // billingDay=5, dueDay=25 → 还款日 = 30；T-1 = 29。
        // nowMs = CST 2026-01-09 17:00 = 1768000800000（账单日已过, 还款日未过）。
        val card = NextCardFiring.CardLike(
            id = "c2", kind = "credit",
            billingDay = 5, dueDay = 25, archived = false,
        )
        // 预期 CST 2026-01-29 09:00 = 1769648400000
        assertEquals(1769648400000L, NextCardFiring.nextTrigger(card, 1768000800000L))
    }

    /**
     * 跨月滚动 —— 当月账单日 + 还款日均已过, 返回下月账单日 09:00。
     */
    @Test
    fun nextTrigger_rollsToNextMonth() {
        // billingDay=3, dueDay=20 → 还款日 = 23；T-1 = 22。
        // nowMs = CST 2026-01-21 09:00 = 1769302800000（账单日/还款日均已过）。
        val card = NextCardFiring.CardLike(
            id = "c4", kind = "credit",
            billingDay = 3, dueDay = 20, archived = false,
        )
        // 预期下月账单日 CST 2026-02-03 09:00 = 1770080400000
        assertEquals(1770080400000L, NextCardFiring.nextTrigger(card, 1769302800000L))
    }

    // ============================================================================
    // 3. 边界 / 负例显式断言（归档 / 缺字段 → null）
    // ============================================================================

    /**
     * 归档卡 → 返回 null（不触发）。
     */
    @Test
    fun nextTrigger_archivedCard_returnsNull() {
        val card = NextCardFiring.CardLike(
            id = "c5", kind = "credit",
            billingDay = 15, dueDay = 25, archived = true,
        )
        assertNull(NextCardFiring.nextTrigger(card, 1767229200000L))
    }

    /**
     * 缺 billingDay → 返回 null。
     */
    @Test
    fun nextTrigger_missingBillingDay_returnsNull() {
        val card = NextCardFiring.CardLike(
            id = "c6", kind = "credit",
            billingDay = null, dueDay = 25, archived = false,
        )
        assertNull(NextCardFiring.nextTrigger(card, 1767229200000L))
    }

    /**
     * 缺 dueDay → 仅返回账单日触发, 还款日跳过。
     */
    @Test
    fun nextTrigger_missingDueDay_returnsStatementDayTrigger() {
        val card = NextCardFiring.CardLike(
            id = "c7", kind = "credit",
            billingDay = 15, dueDay = null, archived = false,
        )
        // 仍返回账单日触发 = 1768438800000
        assertEquals(1768438800000L, NextCardFiring.nextTrigger(card, 1767229200000L))
    }

    // ============================================================================
    // 4. upcomingTriggers 多卡批量（与 4b rebuildChain 取全局最小语义一致）
    // ============================================================================

    /**
     * upcomingTriggers —— 多张卡, 取全局最小未来触发 + 升序排列。
     */
    @Test
    fun upcomingTriggers_returnsGlobalMinimumSorted() {
        val cards = listOf(
            // 卡 1: 账单日 5 日, T+0 09:00 CST = 2026-01-05 09:00 = 1767574800000
            NextCardFiring.CardLike("c1", "credit", billingDay = 5, dueDay = 25, archived = false),
            // 卡 2: 账单日 15 日, T+0 09:00 CST = 2026-01-15 09:00 = 1768438800000
            NextCardFiring.CardLike("c2", "credit", billingDay = 15, dueDay = 25, archived = false),
            // 卡 3: 归档 → 跳过
            NextCardFiring.CardLike("c3", "credit", billingDay = 1, dueDay = 20, archived = true),
            // 卡 4: 缺 billingDay → 跳过
            NextCardFiring.CardLike("c4", "credit", billingDay = null, dueDay = 25, archived = false),
        )
        val triggers = NextCardFiring.upcomingTriggers(cards, 1767229200000L)
        // 期望升序: [c1 账单 5 日 = 1767574800000, c2 账单 15 日 = 1768438800000]
        assertEquals(2, triggers.size)
        assertEquals(1767574800000L, triggers[0])
        assertEquals(1768438800000L, triggers[1])
    }

    /**
     * upcomingTriggers —— limit 截断（默认 limit=5）。
     */
    @Test
    fun upcomingTriggers_respectsLimit() {
        val cards = listOf(
            NextCardFiring.CardLike("c1", "credit", billingDay = 1, dueDay = 20, archived = false),
            NextCardFiring.CardLike("c2", "credit", billingDay = 5, dueDay = 25, archived = false),
            NextCardFiring.CardLike("c3", "credit", billingDay = 15, dueDay = 25, archived = false),
            NextCardFiring.CardLike("c4", "credit", billingDay = 20, dueDay = 25, archived = false),
            NextCardFiring.CardLike("c5", "credit", billingDay = 25, dueDay = 25, archived = false),
        )
        val triggers = NextCardFiring.upcomingTriggers(cards, 1767229200000L, limit = 3)
        assertTrue("limit=3 应截断到 3 条", triggers.size <= 3)
        // 升序排列断言。
        for (i in 1 until triggers.size) {
            assertTrue(
                "upcomingTriggers 未按升序: triggers[$i]=${triggers[i]} < triggers[${i - 1}]=${triggers[i - 1]}",
                triggers[i] >= triggers[i - 1]
            )
        }
    }

    /**
     * upcomingTriggers —— 全空输入返回空列表, 不抛错。
     */
    @Test
    fun upcomingTriggers_emptyCards_returnsEmpty() {
        val triggers = NextCardFiring.upcomingTriggers(emptyList(), 1767229200000L)
        assertTrue("空输入应返回空列表", triggers.isEmpty())
    }

    // ============================================================================
    // 5. 零知识纪律（pure 语义 + 幂等性）
    // ============================================================================

    /**
     * nextTrigger 不修改入参（pure 语义）。
     */
    @Test
    fun nextTrigger_doesNotMutateInput() {
        val card = NextCardFiring.CardLike(
            id = "c1", kind = "credit",
            billingDay = 15, dueDay = 25, archived = false,
        )
        val originalBillingDay = card.billingDay
        val originalDueDay = card.dueDay
        val originalArchived = card.archived
        NextCardFiring.nextTrigger(card, 1767229200000L)
        assertEquals(originalBillingDay, card.billingDay)
        assertEquals(originalDueDay, card.dueDay)
        assertEquals(originalArchived, card.archived)
    }

    /**
     * 纯函数无副作用 —— 相同输入多次调用结果一致（幂等性）。
     */
    @Test
    fun nextTrigger_isIdempotent() {
        val card = NextCardFiring.CardLike(
            id = "c1", kind = "credit",
            billingDay = 15, dueDay = 25, archived = false,
        )
        val a = NextCardFiring.nextTrigger(card, 1767229200000L)
        val b = NextCardFiring.nextTrigger(card, 1767229200000L)
        val c = NextCardFiring.nextTrigger(card, 1767229200000L)
        assertEquals(a, b)
        assertEquals(b, c)
    }

    /**
     * 防御性兜底 —— fixture 非空校验（避免 IDE 警告 "Test class should have at
     * least one public test method"）。
     */
    @Test
    fun sanityCheck_casesNotEmpty() {
        if (cases.isEmpty()) {
            fail("fixture 加载异常: cases 应非空")
        }
        // 顺手验证第一例字段非 null（防御性）。
        assertNotNull(cases.first().name)
    }
}
