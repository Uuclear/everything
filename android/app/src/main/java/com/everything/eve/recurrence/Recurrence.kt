/*
 * 阶段 4b — 日程事件展开纯函数（tasks.md Task 3 / TR-3.1）。
 *
 * 设计目标（与 spec FR-1 / FR-2 / FR-11 一致；权威口径：spec / tasks.md /
 * docs/module-schemas.md 第 8 章）：
 *   1. **纯函数**：不依赖 Android Framework；不读除运行时本地时区外的可变状态；
 *      唯一副作用是返回值（Occurrence 数组）。可在 JVM 单测中直接跑。
 *   2. **跨端镜像**：算法骨架与 Web 端 `web/src/events/expand.ts`（Task 2）一一
 *      对齐；fixture `__fixtures__/cases.json` 是字节级共享的真理源。
 *   3. **本地时区语义**：所有日期语义按 `tz_mode=local`（spec FR-2）；
 *      运行时本地时区偏移由本模块顶部 `TZ_OFFSET_MIN` 一次性读取，
 *      既保持 spec 签名 `expand(rule, window)` 不变，又避免把 4a `month.ts`
 *      的"显式 tzOffsetMin"参数污染本模块。
 *   4. **算法分层**：内部小工具（localDayKey、addLocalDays、tryAddLocalMonths、
 *      tryAddLocalYears、nthWeekdayDayInMonth、isoWeekdayOf、weekdayOrder）
 *      只做纯函数本地日历推理；主 `expand` 仅做组合与裁剪，可读性优先。
 *   5. **红线条目**：本模块产出的 occurrence 仅驻留内存；调用方不得持久化
 *      明文到 SharedPreferences/数据库/日志（与 4a month.ts 红线一致）。
 *
 * 注意：
 *   - 字段命名（除 Kotlin 内部小工具参数）为与 Web 端 JSON 字段逐字段对齐，
 *     故意保留 snake_case：`start_ts`、`end_ts`、`tz_mode`、`all_day`、
 *     `instance_id`、`rule_id`、`original_start_ts`、`byweekday` 等。这与
 *     Web expand.ts 一致——Web 端 `expand.test.ts` 的 JSON 比对逐字段名
 *     直接断言。Compose UI/Repository 上层若要做 Kotlin 习惯命名映射，
 *     在 DataStore/Repository 层做，本模块坚持"跨端字节级镜像"。
 *
 * 文件位置变更说明：
 *   - 原位置：`android/app/src/test/java/com/everything/eve/recurrence/Recurrence.kt`（仅 test 可见）
 *   - 新位置：`android/app/src/main/java/com/everything/eve/recurrence/Recurrence.kt`（main 可见）
 *   - 迁移原因：Task 5（TR-5.1/5.2）主代码 `ReminderScheduler` 需 import 本模块
 *     计算 nextTrigger；test/java 下的 .kt 文件对 main/java 不可见（Gradle source
 *     set 隔离）。原 TR-3.1 注释承诺"4c 实施 RRULE 全档与编辑器联动时一并迁移"，
 *     本任务硬约束提前到 4b 阶段执行（属于"受控扩展"范围）。
 *   - 兼容性：`test/.../RecurrenceTest.kt` 仍可正常引用本文件（test source set
 *     可见 main source set；不需修改测试）。
 */

package com.everything.eve.recurrence

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

// =============================================================================
// 类型（与 web/src/events/expand.ts 字段名/语义逐字段对齐）
// =============================================================================

/** RRULE 频率枚举（spec FR-2 B 档四档）。 */
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/** ISO 周内 weekday 枚举（周一开头，MO..SU；与 Web 端 Weekday 一致）。 */
enum class Weekday(val isoIndex: Int) {
    MO(0), TU(1), WE(2), TH(3), FR(4), SA(5), SU(6)
}

/** RRULE 终止条件（spec FR-2 / 8.3.1 三类）。 */
sealed class RRuleEnd {
    /** 永不终止（实际由窗口外推停）。 */
    object Never : RRuleEnd()
    /** 日期终止：本地日历日 `until`（含该日最后一次展开）。 */
    data class Date(val until: String) : RRuleEnd()
    /**
     * 计数终止：累计 emitted 数（含 emitted 数 ≥ count 即停）。
     *
     * 权威口径（spec FR-2 / tasks.md Task 2）：count 累计展开次数**不含**已被
     * exdate 跳过的实例。即 N 次 emitted 后即停，跳过 exdate 的实例不"消耗"
     * count 配额（与 instance_id 的 n 编号口径一致：跳过 exdate 不递增 n）。
     */
    data class Count(val count: Int) : RRuleEnd()
}

/**
 * RRULE 重复规则（spec FR-2 简化子集）：
 *   - freq 四档之一；
 *   - interval ≥ 1（默认 1）；
 *   - byweekday 仅 WEEKLY/MONTHLY 有效：WEEKLY 多元素，MONTHLY 单元素
 *     （spec 明示 MONTHLY 不支持 MO+TU 同时）；
 *   - end 三类：never / date.until（本地日历日）/ count。
 */
data class RRule(
    val freq: Frequency,
    val interval: Int,
    val byweekday: List<Weekday> = emptyList(),
    val end: RRuleEnd,
)

/**
 * 事件规则（spec FR-1 字段表）。
 *
 * 字段名严格保持 snake_case 以与 Web JSON / `module="event"` 落 records
 * 后的明文序列化逐字段一致；Kotlin 字段顺序遵循 spec FR-1 表列。
 */
data class EventRule(
    val id: String,
    val title: String,
    /** Unix 毫秒（UTC ms）；all_day=true 时为该日 00:00 本地时刻对应 ms。 */
    val start_ts: Long,
    val end_ts: Long,
    val all_day: Boolean,
    /** 本期固定 "local"（字段保留以便未来扩展，UI 不暴露）。 */
    val tz_mode: String,
    val location_text: String? = null,
    val note: String? = null,
    /** 8 色板预设（spec FR-1）：blue/green/red/amber/violet/pink/cyan/slate。 */
    val color: String,
    /** 提前分钟数组；0=开始时刻；≤3 个（类型层面不强制上限，由表单校验把关）。 */
    val reminders: List<Int>,
    val rrule: RRule? = null,
    /** 本地日历日 YYYY-MM-DD；与 start_ts 同口径匹配。 */
    val exdates: List<String> = emptyList(),
)

/** 单次事件展开结果（spec FR-11 Occurrence 字段表）。 */
data class Occurrence(
    /** `<rule.id>:<rule.start_ts>#<n>`；n 从 0 起，跳过 exdate 后递增。 */
    val instance_id: String,
    val rule_id: String,
    /** 实例起始 Unix 毫秒（UTC ms）。 */
    val start_ts: Long,
    /** 实例结束 Unix 毫秒（end_ts - start_ts 保持规则时长）。 */
    val end_ts: Long,
    val all_day: Boolean,
    val color: String,
    val title: String,
    /** 与 RFC 5545 recurrence-id 对齐：原始（未漂移）起始 ts。 */
    val original_start_ts: Long,
)

/** 时间窗口（毫秒；from 含、to 不含）。 */
data class TimeWindow(val from: Long, val to: Long)

// =============================================================================
// 本地时区常量（运行时一次性读取，与 web/src/events/expand.ts TZ_OFFSET_MIN 对齐）
// =============================================================================

/**
 * 运行时本地时区相对 UTC 的偏移（分钟；如 UTC+8 为 480）。
 *
 * 实现：取一个固定时刻（选 1780000000000 ≈ 2026-06-28）读取 ZoneId.systemDefault()
 * 偏移，规避历史 tz 偏移变化带来的歧义。
 *
 * 注意：
 *   - 此变量在 JVM 类加载时初始化；JVM 单测中需在类加载**前**通过
 *     `System.setProperty("user.timezone", "Asia/Shanghai")` +
 *     `TimeZone.setDefault(...)` 锁定到 CST 锚定（与 Web `expand.test.ts`
 *     顶部 p() 工具同口径）。fixture ts 注释明示"构造方式：CST = UTC+8"。
 *   - 若运行环境非 CST，本模块将自动按运行时偏移计算本地日历日，与 fixture
 *     不一致——这是 spec 跨端共享 fixture 的硬约束。
 */
val TZ_OFFSET_MIN: Int = run {
    val zone = ZoneId.systemDefault()
    val offsetSeconds = zone.rules.getOffset(Instant.ofEpochMilli(1780000000000L)).totalSeconds
    offsetSeconds / 60
}

/** 把分钟偏移转毫秒。 */
private const val MIN_TO_MS: Long = 60_000L

/** 一天毫秒数。 */
private const val DAY_MS: Long = 86_400_000L

/**
 * 把"无时区" ts+offset ms 拆成本地日历分量（与 Web expand.ts localPartsOf 完全等价）。
 *
 * 关键陷阱：JS `new Date(ms).getUTCFullYear()` 把 ms **当成** UTC ms 然后读 UTC
 * 字段——这是 JS Date 的"无时区 Date"语义。Kotlin java.time 没有直接的对应：
 * `Instant.ofEpochMilli(ms).atZone(Asia/Shanghai)` 会按 Asia/Shanghai 解释，
 * 与 JS 行为不同。正确做法：先把 ms 视为 UTC 时刻，用 UTC Calendar 字段读出
 * y/m/d/h/mi——这正是 Web 的语义。
 *
 * 例：CST 2026-01-01 09:00 = 1767229200000（UTC 01:00），
 *      ts + offset = 1767258000000；当作 UTC 读 → 2026-01-01 09:00 ✅
 */
private fun localPartsOfAsUtc(ts: Long): LocalDateTime {
    val shifted = ts + TZ_OFFSET_MIN * MIN_TO_MS
    return Instant.ofEpochMilli(shifted).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
}

// =============================================================================
// 本地日历日工具
// =============================================================================

/**
 * Unix 毫秒 → 本地日历日键 "YYYY-MM-DD"。
 *
 * 算法：先按运行时 tz 偏移加 → 再按偏移后时刻取 ISO 日历日。与 web expand.ts
 * 中 localDayKey 完全等价（JS Date → Kotlin java.time 适配）。
 */
fun localDayKey(ts: Long): String {
    val shifted = ts + TZ_OFFSET_MIN * MIN_TO_MS
    // 用 UTC 字段读"无时区"Date 的 ISO 日历日——与 web `toISOString().slice(0,10)` 同源。
    return Instant.ofEpochMilli(shifted).atOffset(java.time.ZoneOffset.UTC)
        .toLocalDate()
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}

/** 由 (y, m, d, h, mi) 本地分量重构 Unix 毫秒（用于按本地日历推进）。 */
private fun utcMsOfLocal(y: Int, m: Int, d: Int, h: Int, mi: Int): Long {
    // 注：Java java.time.YearMonth 会自动处理月越界（m=13 → 次年 1 月）；
    // 日字段越界（如 2/30）也会进位到 3/2——这里需要"该月无该日则跳过"
    // 语义，故下方 tryAddLocalMonths 单独做了日子裁剪。
    val shifted = LocalDateTime.of(y, m, d, h, mi)
        .toInstant(java.time.ZoneOffset.UTC)
        .toEpochMilli()
    return shifted - TZ_OFFSET_MIN * MIN_TO_MS
}

/** 一个月内的天数（1-31）。 */
private fun daysInMonth(y: Int, m: Int): Int {
    // 取 y-m-01 与 y-m+1-01 的偏移天数（java.time 天然处理跨年）。
    val first = LocalDateTime.of(y, m, 1, 0, 0)
        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    val nextMonth = if (m == 12) LocalDateTime.of(y + 1, 1, 1, 0, 0)
        else LocalDateTime.of(y, m + 1, 1, 0, 0)
    val next = nextMonth.toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    return ((next - first) / DAY_MS).toInt()
}

/** 0=Sun..6=Sat（Date.getDay）→ ISO 0=Mon..6=Sun。 */
private fun isoWeekdayOf(ts: Long): Int {
    val shifted = ts + TZ_OFFSET_MIN * MIN_TO_MS
    val dt = Instant.ofEpochMilli(shifted).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
    // DayOfWeek.MONDAY=1..SUNDAY=7；映射到 ISO 0=Mon..6=Sun。
    return dt.dayOfWeek.value - 1
}

/** Weekday → ISO 0=Mon..6=Sun 顺序。 */
private fun weekdayOrder(w: Weekday): Int = w.isoIndex

// =============================================================================
// 推进工具（按本地日历日/周/月/年推进；DST 不补 23/25 小时校正）
// =============================================================================

/**
 * 加 days 个本地日。
 *
 * 算法：拆本地分量 → 在日上加 days → 重构 ms；跨 DST 切换日时本函数始终
 * 保持"本地时刻"不变（h/mi 不变），UTC ms 变化但 localDayKey 输出与本地
 * 语义对齐——即 spec FR-2 "不补 23/25 小时"语义。
 *
 * 实现：先转到本地午间 12:00 加天数（避开 DST 边界 + 月末边界歧义），
 * 再回填 h/mi。所有"无时区"Calendar 字段都用 UTC 解读（与 web 同源）。
 */
private fun addLocalDays(ts: Long, days: Int): Long {
    val dt = localPartsOfAsUtc(ts)
    // 用中午 12:00 锚点：避开 23/25 小时切换与月初月末歧义。
    val noonShiftedMs = LocalDateTime.of(dt.year, dt.monthValue, dt.dayOfMonth, 12, 0)
        .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    val shiftedMs = noonShiftedMs + days * DAY_MS
    val shiftedLocal = Instant.ofEpochMilli(shiftedMs)
        .atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
    return utcMsOfLocal(
        shiftedLocal.year, shiftedLocal.monthValue, shiftedLocal.dayOfMonth,
        dt.hour, dt.minute,
    )
}

/**
 * 加 months 个本地月。日字段越界（start_ts 本地日 > 目标月天数）则跳过
 * 该月（spec FR-2 MONTHLY "该月无该日则跳过"）；返回 null 由调用方决定
 * 是否继续迭代。
 */
private fun tryAddLocalMonths(ts: Long, months: Int): Long? {
    val p = localPartsOfAsUtc(ts)
    // java.time.YearMonth 自动进位 m + months。
    val target = LocalDate.of(p.year, p.monthValue, 1).plusMonths(months.toLong())
    val dim = target.lengthOfMonth()
    if (p.dayOfMonth > dim) return null // 该月无该日 → 跳过
    return utcMsOfLocal(target.year, target.monthValue, p.dayOfMonth, p.hour, p.minute)
}

/**
 * 加 years 个本地年。2/29 在非闰年跳过（spec FR-2 YEARLY）。
 */
private fun tryAddLocalYears(ts: Long, years: Int): Long? {
    val p = localPartsOfAsUtc(ts)
    val target = LocalDate.of(p.year, p.monthValue, p.dayOfMonth).plusYears(years.toLong())
    // 2 月 29 仅闰年存在；其它规则原样推进。
    if (p.monthValue == 2 && p.dayOfMonth == 29) {
        val dim = target.lengthOfMonth()
        if (dim < 29) return null
    }
    return utcMsOfLocal(target.year, target.monthValue, p.dayOfMonth, p.hour, p.minute)
}

/**
 * 在 (y, m) 内取第 n 个指定 weekday 的日期（day 字段）；
 * n 从 1 起；该月无第 n 个则返回 null（spec FR-2 MONTHLY 单 weekday）。
 *
 * 注：本函数仅返回日期 1..31；上层调用方负责用 rule.start_ts 的 h/mi 重构 ms。
 */
private fun nthWeekdayDayInMonth(y: Int, m: Int, w: Weekday, n: Int): Int? {
    if (n < 1) return null
    val firstMs = utcMsOfLocal(y, m, 1, 0, 0)
    val firstIsoW = isoWeekdayOf(firstMs)
    val targetIsoW = weekdayOrder(w)
    val delta = ((targetIsoW - firstIsoW) + 7) % 7
    val day = 1 + delta + (n - 1) * 7
    val dim = daysInMonth(y, m)
    return if (day <= dim) day else null
}

// =============================================================================
// 主展开函数（与 web/src/events/expand.ts expand() 算法一一对应）
// =============================================================================

/**
 * 展开事件规则为指定窗口内的所有 Occurrence。
 *
 * 算法骨架（与 web expand.ts 完全一致）：
 *   1. 单次事件（rrule=null 或视为空）：判断 [start_ts, end_ts) 与窗口交集；
 *      交集非空返回 1 个 Occurrence，否则空。
 *   2. 重复事件：从 start_ts 出发按 freq/interval 生成候选时刻，应用：
 *      a) 窗口裁剪：start_ts < window.from 或 start_ts ≥ window.to 丢；
 *      b) exdate 应用：候选本地日历日命中 exdates 列表即丢（n 不递增，
 *         也不计入 count 终止累计——spec FR-2 / tasks.md Task 2 权威口径）；
 *      c) end.kind=count：累计 emitted（不含 exdate 跳过）达 count 即停；
 *      d) end.kind=date：候选本地日历日 > until 即停。
 *   3. WEEKLY byweekday 多元素：每周内按 MO..SU 顺序展开多个候选；
 *      interval=N 时隔 N 周。
 *   4. MONTHLY byweekday 单元素：按 rule.start_ts 推算"该月第 N 个 weekday"，
 *      该月无则跳过该月。
 *   5. DST：所有推进工具均按本地日历分量重构（h/mi 不变），自然不补 23/25。
 */
fun expand(rule: EventRule, window: TimeWindow): List<Occurrence> {
    val out = mutableListOf<Occurrence>()
    val durationMs = rule.end_ts - rule.start_ts

    // 计数与 n：
    //   - emitted：已写出实例数；同时作为 instance_id 的 n 编号基（n 从 0 起递增）。
    //   - skipped：被 exdate 跳过的候选数（仅作统计/日志，不计入 count 终止）。
    // 权威口径（spec FR-2 / tasks.md Task 2）：end.kind=count 时，终止累计仅看
    // emitted，不含 skipped；即 N 次 emitted 后即停，跳过 exdate 的实例不"消耗"
    // count 配额。
    var emitted = 0
    var skipped = 0

    // ---------- 单次事件分支 ----------
    // rrule=null 或空对象（freq 缺失或 interval≤0）都视为单次（spec FR-2 +
    // 任务 TR-2.1 第 23 用例 `empty-rrule-treated-as-null`）。
    if (rule.rrule == null || isEmptyRRule(rule.rrule)) {
        if (intersectsWindow(rule.start_ts, rule.end_ts, window)) {
            out.add(makeOccurrence(rule, rule.start_ts, rule.start_ts + durationMs, emitted))
        }
        return out
    }

    // ---------- 重复事件分支 ----------
    val rr = rule.rrule
    val exdateSet: Set<String> = rule.exdates.toHashSet()
    // 候选迭代上限保护（防止死循环；单用户量级 N 年远低于此上限）。
    val maxIterations = 100_000
    var iter = 0

    // 通用迭代器：以 step 间隔推进产生候选 ts；每个 ts 在窗口内再判 exdate。
    // 使用局部 fun 而非 lambda 赋值：Kotlin lambda 标签 + 同名 val 在赋值
    // 上下文会产生"Unresolved label"歧义；局部 fun 天然支持 return。
    fun produce(candidate: Long) {
        iter++
        if (iter > maxIterations) return
        // 窗口裁剪（from 含、to 不含）。
        if (candidate < window.from || candidate >= window.to) {
            // 窗口外不计入任何计数（spec 仅就"已展开候选"判定 count/date）。
            return
        }
        // count 终止：emitted（不含 skipped）≥ count 即停。
        // 权威口径：spec FR-2 / tasks.md Task 2——count 累计展开次数**不含**已被
        // exdate 跳过的实例（与 instance_id 的 n 编号口径一致：跳过 exdate 不递增 n，
        // 也不"消耗"count 配额）。
        if (rr.end is RRuleEnd.Count && emitted >= rr.end.count) {
            iter = maxIterations // 触发外层 while 终止
            return
        }
        // date 终止：本地日历日 > until 即停。
        if (rr.end is RRuleEnd.Date) {
            if (localDayKey(candidate) > rr.end.until) {
                iter = maxIterations
                return
            }
        }
        // exdate 命中：n 不递增，count 也不计入——只更新 skipped 统计供观测。
        if (exdateSet.contains(localDayKey(candidate))) {
            skipped++
            return
        }
        out.add(makeOccurrence(rule, candidate, candidate + durationMs, emitted))
        emitted++
    }

    when (rr.freq) {
        Frequency.DAILY -> {
            // 从 rule.start_ts 起，按 interval*1 天推进；窗口外的候选不计入任何计数。
            // 终止条件：date/count 在 produce 内部已置 iter=maxIterations；
            // never 模式下，候选超过 window.to 即可提前结束。
            var cursor = rule.start_ts
            while (iter < maxIterations) {
                produce(cursor)
                if (iter >= maxIterations) break
                val next = addLocalDays(cursor, rr.interval)
                // never + 超出窗口上界 → 下一候选必然更晚，提前停。
                if (rr.end is RRuleEnd.Never && next >= window.to) break
                cursor = next
            }
        }
        Frequency.WEEKLY -> {
            // byweekday 决定一周内哪些天出候选；interval 决定隔几周。
            val wds: List<Weekday> =
                if (!rr.byweekday.isNullOrEmpty()) rr.byweekday.sortedBy { weekdayOrder(it) }
                else listOf(weekdayFromIso(isoWeekdayOf(rule.start_ts)))
            // 计算规则起点所在 ISO 周的第一天（周一）的本地 00:00 对应 ms：
            // 用本地分量回溯到本周一（同 h/mi）。月/日减法可能产生负值
            // （如 1/4 周日 → -2），通过 LocalDate.plusDays 自动进位到上月。
            val startDt = localPartsOfAsUtc(rule.start_ts)
            val startIsoW = isoWeekdayOf(rule.start_ts)
            val mondayDate = LocalDate.of(startDt.year, startDt.monthValue, startDt.dayOfMonth)
                .plusDays((-startIsoW).toLong())
            val weekStartUtc = utcMsOfLocal(
                mondayDate.year, mondayDate.monthValue, mondayDate.dayOfMonth,
                startDt.hour, startDt.minute,
            )
            var weekCursor = weekStartUtc
            while (iter < maxIterations) {
                for (w in wds) {
                    val off = weekdayOrder(w)
                    produce(addLocalDays(weekCursor, off))
                    if (iter >= maxIterations) break
                }
                if (iter >= maxIterations) break
                val nextWeek = addLocalDays(weekCursor, rr.interval * 7)
                // never 模式：下一周首日超出窗口上界 → 提前停。
                if (rr.end is RRuleEnd.Never && nextWeek >= window.to) break
                weekCursor = nextWeek
            }
        }
        Frequency.MONTHLY -> {
            if (!rr.byweekday.isNullOrEmpty()) {
                // MONTHLY + byweekday（单元素）：取"该月第 N 个 weekday"，N 由
                // rule.start_ts 推算。
                val w = rr.byweekday[0]
                val startDt = localPartsOfAsUtc(rule.start_ts)
                // 求 rule.start_ts 是其所在月份的"第几个"该 weekday。
                val n = nthOfWeekdayInMonth(
                    startDt.year, startDt.monthValue, w, startDt.dayOfMonth,
                )
                var monthOffset = 0
                while (iter < maxIterations) {
                    // 该月目标日（若存在）：先按 +monthOffset 月定位到 y/m，再求第 n 个 weekday。
                    val baseLocal = LocalDate.of(startDt.year, startDt.monthValue, 1)
                        .plusMonths(monthOffset.toLong())
                    val bpYear = baseLocal.year
                    val bpMonth = baseLocal.monthValue
                    val day = nthWeekdayDayInMonth(bpYear, bpMonth, w, n)
                    if (day != null) {
                        val cand = utcMsOfLocal(bpYear, bpMonth, day, startDt.hour, startDt.minute)
                        produce(cand)
                        if (iter >= maxIterations) break
                        // never 模式：超过窗口上界即停。
                        if (rr.end is RRuleEnd.Never && cand >= window.to) break
                    }
                    monthOffset += rr.interval
                }
            } else {
                // MONTHLY 不带 byweekday：start_ts 的本地日 → 后续月同 day；该月无则跳。
                var monthOffset = 0
                while (iter < maxIterations) {
                    val cand = tryAddLocalMonths(rule.start_ts, monthOffset)
                    if (cand != null) produce(cand)
                    if (iter >= maxIterations) break
                    // never 模式：超过窗口上界即停。
                    if (rr.end is RRuleEnd.Never && cand != null && cand >= window.to) break
                    monthOffset += rr.interval
                }
            }
        }
        Frequency.YEARLY -> {
            // spec FR-2 明示 byweekday 仅 WEEKLY/MONTHLY 有效；YEARLY + byweekday
            // 在 schema 校验阶段应被前端拒绝，本函数保守兜底：忽略 byweekday，
            // 按 start_ts 本地月/日展开；2/29 仅闰年存在。
            var yearOffset = 0
            while (iter < maxIterations) {
                val cand = tryAddLocalYears(rule.start_ts, yearOffset)
                if (cand != null) produce(cand)
                if (iter >= maxIterations) break
                if (rr.end is RRuleEnd.Never && cand != null && cand >= window.to) break
                yearOffset += rr.interval
            }
        }
    }

    // 排序（防御性：理论上产生顺序即升序，但 DST 边界 + 多 branch 合并时显式
    // 排序确保 toEqual 与 fixture 严格对齐）。
    out.sortBy { it.start_ts }
    return out
}

// =============================================================================
// 内部小工具
// =============================================================================

/** 区间 [aStart, aEnd) 与窗口 [from, to) 是否有交集。 */
private fun intersectsWindow(aStart: Long, aEnd: Long, w: TimeWindow): Boolean {
    return aEnd > w.from && aStart < w.to
}

/** 构造一个 Occurrence（字段映射集中便于维护）。 */
private fun makeOccurrence(
    rule: EventRule,
    startTs: Long,
    endTs: Long,
    n: Int,
): Occurrence {
    return Occurrence(
        instance_id = "${rule.id}:${rule.start_ts}#$n",
        rule_id = rule.id,
        start_ts = startTs,
        end_ts = endTs,
        all_day = rule.all_day,
        color = rule.color,
        title = rule.title,
        original_start_ts = rule.start_ts,
    )
}

/** rrule 是否为"空"（interval≤0）；视为单次。 */
private fun isEmptyRRule(rr: RRule): Boolean {
    return rr.interval < 1
}

/** ISO weekday index（0=Mon..6=Sun）→ Weekday 枚举（用于 WEEKLY 无 byweekday 默认）。 */
private fun weekdayFromIso(idx: Int): Weekday {
    val arr = Weekday.values()
    return arr[((idx % 7) + 7) % 7]
}

/**
 * 求 (y, m) 月内 day 日是该月第几个指定 weekday。
 * 例：2026-03-31 是该月第 5 个 Tuesday → (2026, 3, TU, 31) → 5。
 */
private fun nthOfWeekdayInMonth(y: Int, m: Int, w: Weekday, day: Int): Int {
    val firstMs = utcMsOfLocal(y, m, 1, 0, 0)
    val firstIsoW = isoWeekdayOf(firstMs)
    val targetIsoW = weekdayOrder(w)
    // 月内首个目标 weekday 的"日"。
    val firstTargetDay = 1 + ((targetIsoW - firstIsoW + 7) % 7)
    return ((day - firstTargetDay) / 7) + 1
}