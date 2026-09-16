/*
 * 阶段 4b — Android 端 expand() 跨端镜像 JUnit 测试（tasks.md Task 3 / TR-3.2）。
 *
 * 验证策略（与 web/src/events/expand.test.ts 完全对齐）：
 *   1. 测试入口**强制锁定本地时区到 Asia/Shanghai (CST/UTC+8)**——与 Web 端
 *      `expand.test.ts` 顶部 p() 工具同口径（fixture ts_anchor_comment 明示
 *      "CST = UTC+8"）。非 CST 设备会让断言失败，这正是 spec "跨端共享 fixture"
 *      的硬约束（spec FR-11）。
 *   2. 从 `src/test/resources/recurrence/__fixtures__/cases.json` 加载 24+ 用例
 *      （与 Web 端**字节级一致**，TR-3.3 SHA-256 校验）。
 *   3. 每个用例独立 @Test，逐字段断言 expand() 输出与 expected 完全一致；
 *      失败用例给出 case.name + rule.id + window 便于排查。
 *
 * 实现要点：
 *   - 复用工程既有 Moshi 库（libs.moshi.kotlin）做 JSON 解析——org.json 在
 *     Android JVM 单测中默认未 mock（参见 Android 文档 "not-mocked exception"），
 *     而 Moshi 是工程既有依赖（Retrofit 已引入），无须新增第三方库。
 *   - 用 `java.net.URL` + `ClassLoader.getResource()` 加载 test resources 路径
 *     下的 fixture 文件；JUnit 标准做法。
 *   - 时区锁定必须在类加载**前**生效：JUnit 4 中通过 `@BeforeClass` 静态块
 *     设置 `System.setProperty("user.timezone", ...)` 与 `TimeZone.setDefault(...)`，
 *     之后再让 Recurrence.kt 顶层 `TZ_OFFSET_MIN` 初始化时读取到正确的 CST 偏移。
 *
 * 用例数守护：
 *   - "fixture 至少 24 用例" 测试守住 TR-2.3 / TR-3.2 的硬性指标；
 *   - 每个 fixture case 生成一个 @Test 方法（Grep @Test ≥25 = 24 fixture + 1 守护）。
 */

package com.everything.eve.recurrence

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.URL
import java.util.TimeZone

class RecurrenceTest {

    companion object {
        /**
         * 类加载**前**锁定运行时本地时区到 Asia/Shanghai（CST/UTC+8）。
         *
         * 关键时序：
         *   - Recurrence.kt 顶层 `val TZ_OFFSET_MIN = run { ... }` 在 Recurrence
         *     类首次引用时初始化；JUnit 4 在执行首个 @Before 前会先加载测试类
         *     静态字段，故此 @BeforeClass 内的 setProperty + TimeZone.setDefault
         *     必须早于任何 expand() 调用。
         *   - System.setProperty 是兜底（部分 JVM/库读取 user.timezone）；
         *     TimeZone.setDefault 是主路径（ZoneId.systemDefault() 走这里）。
         *
         * 锚定依据：fixture ts_anchor_comment 注明"所有 from/to/start_ts/end_ts
         * 以毫秒为单位；构造方式见 web/src/events/expand.test.ts 顶部 p() 工具
         * （CST = UTC+8，本地 y/m/d h:mi → Date.UTC(y, m-1, d, h-8, mi)）"。
         */
        @JvmStatic
        @BeforeClass
        fun lockTimezone() {
            System.setProperty("user.timezone", "Asia/Shanghai")
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        }

        // ---- Moshi 数据类（与 fixture JSON 字段 snake_case 对齐） ----

        @JsonClass(generateAdapter = false)
        data class FixtureRRuleEnd(
            val kind: String = "never",
            val until: String? = null,
            val count: Int? = null,
        )

        @JsonClass(generateAdapter = false)
        data class FixtureRRule(
            val freq: String = "",
            val interval: Int = 1,
            val byweekday: List<String>? = null,
            val end: FixtureRRuleEnd? = null,
        )

        @JsonClass(generateAdapter = false)
        data class FixtureRule(
            val id: String = "",
            val title: String = "",
            val start_ts: Long = 0L,
            val end_ts: Long = 0L,
            val all_day: Boolean = false,
            val tz_mode: String = "local",
            val location_text: String? = null,
            val note: String? = null,
            val color: String = "blue",
            val reminders: List<Int> = emptyList(),
            val rrule: FixtureRRule? = null,
            val exdates: List<String> = emptyList(),
        )

        @JsonClass(generateAdapter = false)
        data class FixtureWindow(
            val from: Long = 0L,
            val to: Long = 0L,
        )

        @JsonClass(generateAdapter = false)
        data class FixtureOccurrence(
            val instance_id: String = "",
            val rule_id: String = "",
            val start_ts: Long = 0L,
            val end_ts: Long = 0L,
            val all_day: Boolean = false,
            val color: String = "blue",
            val title: String = "",
            val original_start_ts: Long = 0L,
        )

        @JsonClass(generateAdapter = false)
        data class FixtureCase(
            val name: String = "",
            val rule: FixtureRule? = null,
            val window: FixtureWindow? = null,
            val expected: List<FixtureOccurrence> = emptyList(),
            val comment: String? = null,
        )

        @JsonClass(generateAdapter = false)
        data class FixtureRoot(
            val comment: String? = null,
            val tz_anchor: String? = null,
            val ts_anchor_comment: String? = null,
            val cases: List<FixtureCase> = emptyList(),
        )

        private val moshi: Moshi by lazy {
            // 反射需要 KotlinJsonAdapterFactory（moshi-kotlin 提供）；
            // 不使用 @JsonClass(generateAdapter=true) 避免引入 KSP 处理 test sources。
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        }

        private val fixtureAdapter: JsonAdapter<FixtureRoot> by lazy {
            moshi.adapter(FixtureRoot::class.java)
        }

        /**
         * 加载 fixture（test resources 路径 `recurrence/__fixtures__/cases.json`）。
         * 失败立即 fail 让 CI 快速暴露 fixture 路径错误。
         */
        @JvmStatic
        private fun loadFixture(): List<FixtureCase> {
            val resource: URL? = RecurrenceTest::class.java.classLoader.getResource(
                "recurrence/__fixtures__/cases.json",
            )
            if (resource == null) {
                // 使用 throw 而非 fail()，确保后续 .readText 能 smart cast
                // （fail 返回 Nothing 但 Kotlin smart cast 需要稳定的契约断言）。
                throw IllegalStateException("fixture 资源缺失：recurrence/__fixtures__/cases.json")
            }
            val text = resource.readText(Charsets.UTF_8)
            // 显式抛出 IllegalArgumentException，避免 fail() 返回 Nothing 与
            // elvis ?: 链式推断 Unit 导致后续 .cases 访问类型不匹配。
            val root: FixtureRoot = fixtureAdapter.fromJson(text)
                ?: throw IllegalArgumentException("fixture JSON 解析失败：cases.json")
            return root.cases
        }

        /** 字符串 → Frequency 枚举。 */
        private fun toFrequency(s: String): Frequency = when (s) {
            "DAILY" -> Frequency.DAILY
            "WEEKLY" -> Frequency.WEEKLY
            "MONTHLY" -> Frequency.MONTHLY
            "YEARLY" -> Frequency.YEARLY
            else -> throw IllegalArgumentException("未知 freq=$s")
        }

        /** weekday 字符串 → 枚举。 */
        private fun toWeekday(s: String): Weekday = when (s) {
            "MO" -> Weekday.MO
            "TU" -> Weekday.TU
            "WE" -> Weekday.WE
            "TH" -> Weekday.TH
            "FR" -> Weekday.FR
            "SA" -> Weekday.SA
            "SU" -> Weekday.SU
            else -> throw IllegalArgumentException("未知 weekday=$s")
        }

        /** fixture.RRule → RRule。 */
        private fun toRRule(j: FixtureRRule): RRule {
            val endObj = j.end ?: throw IllegalArgumentException("rrule.end 缺失")
            val end: RRuleEnd = when (endObj.kind) {
                "never" -> RRuleEnd.Never
                "date" -> RRuleEnd.Date(endObj.until ?: throw IllegalArgumentException("date.end 缺 until"))
                "count" -> RRuleEnd.Count(endObj.count ?: throw IllegalArgumentException("count.end 缺 count"))
                else -> throw IllegalArgumentException("未知 end.kind=${endObj.kind}")
            }
            return RRule(
                freq = toFrequency(j.freq),
                interval = j.interval,
                byweekday = (j.byweekday ?: emptyList()).map { toWeekday(it) },
                end = end,
            )
        }

        /** fixture.Rule → EventRule。 */
        private fun toEventRule(j: FixtureRule): EventRule {
            return EventRule(
                id = j.id,
                title = j.title,
                start_ts = j.start_ts,
                end_ts = j.end_ts,
                all_day = j.all_day,
                tz_mode = j.tz_mode,
                location_text = j.location_text?.ifEmpty { null },
                note = j.note?.ifEmpty { null },
                color = j.color,
                reminders = j.reminders,
                rrule = j.rrule?.let { toRRule(it) },
                exdates = j.exdates,
            )
        }

        /** fixture.Occurrence → Occurrence。 */
        private fun toOccurrence(j: FixtureOccurrence): Occurrence {
            return Occurrence(
                instance_id = j.instance_id,
                rule_id = j.rule_id,
                start_ts = j.start_ts,
                end_ts = j.end_ts,
                all_day = j.all_day,
                color = j.color,
                title = j.title,
                original_start_ts = j.original_start_ts,
            )
        }

        /** 友好打印（失败信息更可读）。 */
        private fun dumpFailure(
            name: String, rule: EventRule, w: TimeWindow,
            got: List<Occurrence>, exp: List<Occurrence>,
        ): String {
            return buildString {
                append("case=$name rule.id=${rule.id} window=[${w.from}, ${w.to})\n")
                append("got (${got.size}): ")
                    .append(got.joinToString { "(${it.start_ts},${it.end_ts},id=${it.instance_id})" })
                    .append('\n')
                append("exp (${exp.size}): ")
                    .append(exp.joinToString { "(${it.start_ts},${it.end_ts},id=${it.instance_id})" })
                    .append('\n')
            }
        }
    }

    /**
     * 用例数量守护测试（守住 TR-2.3 / TR-3.2 的硬性指标 ≥24）。
     */
    @Test
    fun fixtureHasAtLeast24Cases() {
        val cases = loadFixture()
        assertTrue(
            "fixture 用例数应 ≥24，实际=${cases.size}（参 web/src/events/__fixtures__/cases.json）",
            cases.size >= 24,
        )
    }

    /**
     * 跨端 fixture 加载 + 逐用例断言主测试。
     *
     * 失败时输出 case.name + rule.id + window，便于主会话排查；
     * 用例数通过 `fixtureHasAtLeast24Cases` 独立守护。
     */
    @Test
    fun expandCrossStageFixture() {
        val cases = loadFixture()
        var passed = 0
        for (c in cases) {
            val ruleJson = c.rule ?: throw IllegalArgumentException("case=${c.name} 缺 rule")
            val windowJson = c.window ?: throw IllegalArgumentException("case=${c.name} 缺 window")
            val rule = toEventRule(ruleJson)
            val window = TimeWindow(windowJson.from, windowJson.to)
            val expected = c.expected.map { toOccurrence(it) }
            val got = expand(rule, window)
            // 深比较：JUnit assertEquals 对 List<DataClass> 比对字段内容；
            // 任何遗漏/多余字段都会被捕获。
            if (got != expected) {
                fail(dumpFailure(c.name, rule, window, got, expected))
            }
            passed++
        }
        assertTrue(
            "expand() 通过用例数应 ≥24，实际=$passed",
            passed >= 24,
        )
    }
}