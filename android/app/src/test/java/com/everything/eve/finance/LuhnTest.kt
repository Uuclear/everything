// ============================================================================
// Luhn 纯函数单元测试（stage5-finance / Task 3 / TR-3.2）
// ============================================================================
//
// 验证目标（≥6 用例, 覆盖 TR-3.2 Pass Condition + 全部边界）:
//   1. fixture 数据驱动 —— 共享 luhn-cases.json（9 条用例, ≥6 硬性指标）;
//   2. 关键正例显式断言（Visa / Mastercard / UnionPay 三类卡组织）;
//   3. 边界与负例显式断言（空串 / 字母 / 长度越界 / 纯分隔符）;
//   4. extractLast4 联动断言（Luhn 通过时返回末 4 位, 否则返回 null）;
//   5. 零知识纪律断言（pure 语义 + 幂等性 + 入参不被修改）。
//
// 共享 fixture（__fixtures__/luhn-cases.json）由三端共同加载, SHA-256 必须
// 字节级一致 —— 见 tasks.md TR-3.3; 本测试仅读加载, 不修改 fixture 内容。
//
// 零知识纪律:
//   - 测试卡号均为业界公开示例号（Visa/Mastercard/UnionPay 测试卡号段）,
//     非真实持卡人卡号;
//   - 不在断言失败消息中打印完整卡号（避免泄漏到 CI 日志）;
//   - 不向 Room / DataStore / 网络写入任何数据。
//
// 关联:
//   - android/.../finance/Luhn.kt（被测目标, 与 Web luhn.ts 镜像）
//   - android/.../finance/__fixtures__/luhn-cases.json（共享 fixture）
//   - web/src/finance/__fixtures__/luhn-cases.json（Web 镜像, 三端字节级一致）
// ============================================================================

package com.everything.eve.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Luhn 纯函数 JUnit 4 单元测试 —— 与 Web `luhn.spec.ts` 行为对齐。
 *
 * 用例数守护:
 *   - fixture 至少 6 条用例（TR-3.2 硬性指标）, 当前 fixture 9 条;
 *   - 关键正例 + 边界负例 + extractLast4 联动 + 零知识纪律共 ~22 条 @Test。
 */
class LuhnTest {

    // ============================================================================
    // 共享 fixture 加载 —— 复用工程既有 Moshi 库（libs.moshi.kotlin 已引入）
    // ============================================================================

    /**
     * fixture 单条用例结构 —— 与 Web `LuhnCase` 字段命名对齐。
     *
     * 字段命名刻意保持极简（input + expected）以便三端共用一套加载器,
     * 避免 name/description 等冗余字段造成跨语言映射噪音。
     */
    private data class LuhnCase(
        val input: String,
        val expected: Boolean,
    )

    private val cases: List<LuhnCase> by lazy { loadFixture() }

    /**
     * 从 classpath 或文件系统加载共享 fixture。
     *
     * 加载策略（双路径兜底）:
     *   1. **首选 classpath**: 路径 `finance/__fixtures__/luhn-cases.json`
     *      （与 Web 端镜像命名风格一致; 若有 `src/test/resources` 副本）;
     *   2. **兜底文件系统**: 相对当前工作目录解析 T2 子代理放置的物理路径
     *      `android/app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json`
     *      （gradle test 工作目录默认 = 模块根, 即 `android/`）。
     *
     * 为什么不直接用 classpath 包路径?
     *   - T2 子代理把 fixture 放在 `test/java/.../finance/__fixtures__/` 下,
     *     该路径不会被 Android Gradle 默认识别为 test resources 根（默认
     *     仅 `test/resources/` 是资源根, `test/java/` 仅编译 .kt/.java）;
     *     因此 `getResource("com/everything/.../luhn-cases.json")` 必然返回
     *     null。本测试采用文件系统兜底, **不修改 fixture 内容/不创建副本**。
     *
     * 为什么不复制 fixture 到 `test/resources/`?
     *   - 任务硬约束"不创建额外文件", 且复制两份会导致 SHA-256 漂移检测
     *     失去意义; 故保留物理单一份, 仅在测试层做绝对路径读取。
     */
    private fun loadFixture(): List<LuhnCase> {
        // ========== 策略 1: classpath（优先, 若未来 fixture 移至 resources/）==========
        val classpathPath = "finance/__fixtures__/luhn-cases.json"
        val cpResource = LuhnTest::class.java.classLoader?.getResource(classpathPath)
        val text = if (cpResource != null) {
            cpResource.readText(Charsets.UTF_8)
        } else {
            // ========== 策略 2: 文件系统兜底 ==========
            // Gradle Test Executor 默认工作目录 = android/app/, 故此相对路径。
            // 候选路径列表: 兼容 Gradle 在不同目录执行测试时的 cwd 差异。
            val candidates = listOf(
                // Gradle 默认工作目录 = app/（android/app/）
                "src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json",
                // 模块根（android/）兜底
                "android/app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json",
                // 仓库根目录兜底
                "app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json",
                // 绝对路径兜底（Gradle -p 或脚本化调用时 cwd 不固定）
                "d:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json",
                "D:/github/everything/everything/android/app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json",
                "D:\\github\\everything\\everything\\android\\app\\src\\test\\java\\com\\everything\\eve\\finance\\__fixtures__\\luhn-cases.json",
            )
            val file = candidates
                .map { java.io.File(it) }
                .firstOrNull { it.exists() }
                ?: throw IllegalStateException(
                    "fixture 资源缺失: 尝试路径 = [$classpathPath classpath, ${candidates.joinToString()}]"
                )
            file.readText(Charsets.UTF_8)
        }
        // 直接手动解析 —— fixture schema 极简（List<Map<String, Any>>）,
        // 不引入额外数据类, 与 Web 端 it.each 风格保持一致。
        // 使用正则提取每条用例的 input / expected 字段。
        val parsed = parseLuhnCases(text)
        if (parsed.isEmpty()) {
            throw IllegalStateException("fixture 解析为空: luhn-cases.json")
        }
        return parsed
    }

    /**
     * 极简 JSON 解析 —— 仅服务于本测试 fixture（结构稳定）。
     *
     * 使用工程已有依赖 `org.json` 不可用（JVM 单测 not-mocked）, 此处手写
     * 正则解析。fixture schema 为 `[{input, expected}, ...]`, 字段顺序稳定。
     */
    private fun parseLuhnCases(json: String): List<LuhnCase> {
        val result = mutableListOf<LuhnCase>()
        // 匹配单条对象 { "input": "...", "expected": true|false }。
        // 简单状态机 —— 配对花括号, 提取 input 与 expected。
        var i = 0
        while (i < json.length) {
            // 找到下一个 '{' 起始。
            val start = json.indexOf('{', i)
            if (start < 0) break
            val end = json.indexOf('}', start)
            if (end < 0) break
            val obj = json.substring(start + 1, end)
            // 提取 input 字段值（字符串）。
            val input = extractStringField(obj, "input") ?: ""
            // 提取 expected 字段值（布尔）。
            val expected = extractBoolField(obj, "expected")
            result.add(LuhnCase(input, expected))
            i = end + 1
        }
        return result
    }

    /**
     * 从 JSON 对象片段中提取字符串字段值（如 `"input": "4111..."`）。
     */
    private fun extractStringField(obj: String, field: String): String? {
        val regex = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(obj)?.groupValues?.get(1)
    }

    /**
     * 从 JSON 对象片段中提取布尔字段值（如 `"expected": true`）。
     */
    private fun extractBoolField(obj: String, field: String): Boolean {
        val regex = Regex("\"$field\"\\s*:\\s*(true|false)")
        return regex.find(obj)?.groupValues?.get(1) == "true"
    }

    // ============================================================================
    // 1. fixture 加载驱动 —— 从 JSON 数据驱动全部用例（≥6 硬性指标）
    // ============================================================================

    /**
     * 守护测试 —— fixture 用例数 ≥6（TR-3.2 硬性指标）。
     *
     * 当前 fixture 包含 9 条用例, 任何对 fixture 的裁剪若导致 < 6 条
     * 该测试会失败, 提醒维护者恢复。
     */
    @Test
    fun fixture_hasAtLeastSixCases() {
        assertTrue(
            "fixture 用例数应不少于 6 条, 实际 = ${cases.size}",
            cases.size >= 6
        )
    }

    /**
     * fixture 数据驱动 —— 每条用例生成一个 @Test。
     *
     * 通过 JUnit 4 @Test 模板（forEach + 手动命名）展开, 避免引入 JUnit
     * Parameterized 等价 API 的额外学习成本（与 Web 端 `it.each` 风格一致）。
     *
     * 用例名仅显示 input 摘要（>8 字符截断为前缀 + 长度）, 避免完整卡号
     * 进入测试报告 → CI 日志 → 触发零知识纪律告警。
     */
    @Test
    fun fixture_driven_allCasesPass() {
        // 一次性跑完所有用例, 失败时给出 index + 输入摘要 + 期望值。
        for ((index, tc) in cases.withIndex()) {
            val actual = Luhn.luhnValidate(tc.input)
            assertEquals(
                "fixture[$index] 输入=${summarizeForLog(tc.input)} 期望=${tc.expected}",
                tc.expected,
                actual
            )
        }
    }

    // ============================================================================
    // 2. 关键正例显式断言（覆盖三类卡组织 + 两种分隔符兼容）
    // ============================================================================

    /**
     * Visa 测试卡号 4111111111111111 通过 Luhn 校验。
     */
    @Test
    fun visa_4111111111111111_passes() {
        assertTrue(Luhn.luhnValidate("4111111111111111"))
    }

    /**
     * Mastercard 测试卡号 5555555555554444 通过 Luhn 校验。
     */
    @Test
    fun mastercard_5555555555554444_passes() {
        assertTrue(Luhn.luhnValidate("5555555555554444"))
    }

    /**
     * UnionPay 测试卡号 6212345678901232 通过 Luhn 校验。
     */
    @Test
    fun unionpay_6212345678901232_passes() {
        assertTrue(Luhn.luhnValidate("6212345678901232"))
    }

    /**
     * Visa 卡号带空格（4 位空格分隔）通过校验 —— 分隔符剥离兼容。
     */
    @Test
    fun visa_withSpaces_passes() {
        assertTrue(Luhn.luhnValidate("4111 1111 1111 1111"))
    }

    /**
     * Visa 卡号带连字符通过校验 —— 分隔符剥离兼容。
     */
    @Test
    fun visa_withHyphens_passes() {
        assertTrue(Luhn.luhnValidate("4111-1111-1111-1111"))
    }

    // ============================================================================
    // 3. 边界 / 负例显式断言（确保不抛错, 仅返回 boolean）
    // ============================================================================

    /**
     * 校验位错（末位 4111...1112）返回 false —— 标准 Luhn 负例。
     */
    @Test
    fun badCheckDigit_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("4111111111111112"))
    }

    /**
     * 空串返回 false 而非抛错。
     */
    @Test
    fun emptyString_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate(""))
    }

    /**
     * 纯字母（无数字）返回 false。
     */
    @Test
    fun lettersOnly_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("abcd"))
    }

    /**
     * 长度 1 的单字符返回 false（小于行业最小 13）。
     */
    @Test
    fun singleDigit_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("1"))
    }

    /**
     * 长度 12（小于行业最小 13）返回 false。
     */
    @Test
    fun lengthBelowMinimum_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("411111111111"))
    }

    /**
     * 长度 20（大于行业最大 19）返回 false。
     */
    @Test
    fun lengthAboveMaximum_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("41111111111111111111"))
    }

    /**
     * 纯空格（仅分隔符）返回 false —— 剥离后为空串。
     */
    @Test
    fun onlySpaces_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("    "))
    }

    /**
     * 纯连字符（仅分隔符）返回 false —— 剥离后为空串。
     */
    @Test
    fun onlyHyphens_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("----"))
    }

    /**
     * 含字母的混合串返回 false（不抛错）。
     */
    @Test
    fun mixedLettersAndDigits_returnsFalse() {
        assertEquals(false, Luhn.luhnValidate("4111a1111111b1111"))
    }

    // ============================================================================
    // 4. extractLast4 联动断言（Luhn 是后四位入库的看门人）
    // ============================================================================

    /**
     * Visa 卡号末 4 位为 1111。
     */
    @Test
    fun extractLast4_visa_returns1111() {
        assertEquals("1111", Luhn.extractLast4("4111111111111111"))
    }

    /**
     * 带空格的卡号同样返回末 4 位 1111（剥离分隔符后取末位）。
     */
    @Test
    fun extractLast4_withSpaces_stripsAndReturns() {
        assertEquals("1111", Luhn.extractLast4("4111 1111 1111 1111"))
    }

    /**
     * 带连字符的卡号同样返回末 4 位 1111。
     */
    @Test
    fun extractLast4_withHyphens_stripsAndReturns() {
        assertEquals("1111", Luhn.extractLast4("4111-1111-1111-1111"))
    }

    /**
     * Mastercard 卡号末 4 位为 4444。
     */
    @Test
    fun extractLast4_mastercard_returns4444() {
        assertEquals("4444", Luhn.extractLast4("5555555555554444"))
    }

    /**
     * UnionPay 卡号末 4 位为 1232。
     */
    @Test
    fun extractLast4_unionpay_returns1232() {
        assertEquals("1232", Luhn.extractLast4("6212345678901232"))
    }

    /**
     * Luhn 未通过时返回 null（拒绝入库）。
     */
    @Test
    fun extractLast4_badCheckDigit_returnsNull() {
        assertNull(Luhn.extractLast4("4111111111111112"))
    }

    /**
     * 空串返回 null。
     */
    @Test
    fun extractLast4_empty_returnsNull() {
        assertNull(Luhn.extractLast4(""))
    }

    /**
     * 纯字母返回 null。
     */
    @Test
    fun extractLast4_lettersOnly_returnsNull() {
        assertNull(Luhn.extractLast4("abcd"))
    }

    // ============================================================================
    // 5. 零知识纪律断言（无副作用 / 无外部依赖）
    // ============================================================================

    /**
     * luhnValidate 不修改入参（pure 语义）。
     *
     * 注意: Kotlin String 是 immutable, 调用者持有的引用天然不会被修改;
     * 此断言用于在跨语言迁移 / 重构时把"pure 语义"显式锁住, 防止后续
     * 引入 `var` / 反射等破坏不可变性。
     */
    @Test
    fun luhnValidate_doesNotMutateInput() {
        val original = "4111 1111 1111 1111"
        Luhn.luhnValidate(original)
        assertEquals("4111 1111 1111 1111", original)
    }

    /**
     * extractLast4 不修改入参（pure 语义）。
     */
    @Test
    fun extractLast4_doesNotMutateInput() {
        val original = "4111-1111-1111-1111"
        Luhn.extractLast4(original)
        assertEquals("4111-1111-1111-1111", original)
    }

    /**
     * 纯函数无副作用: 相同输入多次调用结果一致（幂等性）。
     */
    @Test
    fun luhnValidate_isIdempotent() {
        val input = "4111111111111111"
        val a = Luhn.luhnValidate(input)
        val b = Luhn.luhnValidate(input)
        val c = Luhn.luhnValidate(input)
        assertEquals(a, b)
        assertEquals(b, c)
        assertTrue(a)
    }

    // ============================================================================
    // 私有工具方法 —— 日志摘要（避免完整卡号进入失败消息 → CI 日志）
    // ============================================================================

    /**
     * 把输入字符串裁剪为安全日志摘要（避免完整卡号进入失败消息）。
     *
     * 规则:
     *   - 长度 ≤ 8 字符: 原样返回;
     *   - 长度 > 8 字符: 返回「前 4 字符 + … + 长度标注」, 例如 "4111…(16 位)";
     *   - 这样既保留诊断信息（足以定位用例）, 又避免完整卡号泄漏到 CI 日志。
     */
    private fun summarizeForLog(input: String): String {
        if (input.length <= 8) return input
        return "${input.substring(0, 4)}…(${input.length} 位)"
    }

    /**
     * 防御性兜底 —— 任何测试不应走到这里（fail() 占位以避免 IDE 警告
     * "Test class should have at least one public test method"）。
     */
    @Test
    fun sanityCheck_companionWired() {
        // 该测试永远通过 —— 仅用于确认 @Test 注解 + 编译期 class 加载成功。
        if (cases.isEmpty()) {
            fail("fixture 加载异常: cases 应非空")
        }
    }
}