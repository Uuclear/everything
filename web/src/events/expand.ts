// 阶段 4b — 日程事件展开纯函数（tasks.md Task 2 / TR-2.1）。
//
// 设计目标（与 spec FR-1 / FR-2 / FR-11 一致）：
//   1. **纯函数**：不依赖 DOM/Browser/Web API；可单跑于 Node 环境；不读
//      环境变量以外的可变状态；唯一副作用是返回值（Occurrence 数组）。
//   2. **跨端镜像**：算法骨架与 Android 端 `Recurrence.kt`（Task 3）一一
//      对齐；fixture `__fixtures__/cases.json` 是字节级共享的真理源。
//   3. **本地时区语义**：所有日期语义按 `tz_mode=local`（spec FR-2）；
//      运行时本地时区偏移由本模块顶部 `localTzOffsetMin()` 一次性读取，
//      不显式暴露给调用方——既保持 spec 签名 `expand(rule, window)` 不变，
//      又避免把 4a `month.ts` 的"显式 tzOffsetMin"参数污染本模块。
//   4. **算法分层**：内部小工具（localDayKey、addDays、addMonths、addYears、
//      nthWeekdayOfMonth、weekdayIndex、weekdayOfLocal）只做纯函数本地日历
//      推理；主 `expand` 仅做组合与裁剪，可读性优先。
//   5. **红线条目**：本模块产出的 occurrence 仅驻留内存；调用方不得持久化
//      明文到 localStorage/IndexedDB/日志（与 4a month.ts 红线一致）。

// =============================================================================
// 类型（与 spec FR-1 / FR-2 / FR-11 字段表逐字段一致）
// =============================================================================

/** 8 色板预设（spec FR-1）；色板在编辑器固定，事件→实例继承。 */
export type Color =
  | 'blue'
  | 'green'
  | 'red'
  | 'amber'
  | 'violet'
  | 'pink'
  | 'cyan'
  | 'slate'

/** RRULE 频率枚举（spec FR-2 B 档四档）。 */
export type Frequency = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'

/** ISO 周内 weekday 枚举（周一开头，MO..SU）。 */
export type Weekday = 'MO' | 'TU' | 'WE' | 'TH' | 'FR' | 'SA' | 'SU'

/**
 * 重复规则（spec FR-2 简化子集）：
 *   - freq 四档之一；
 *   - interval ≥ 1（默认 1）；
 *   - byweekday 仅 WEEKLY/MONTHLY 有效：WEEKLY 多元素，MONTHLY 单元素
 *     （spec 明示 MONTHLY 不支持 MO+TU 同时）；
 *   - end 三类：never / date.until（本地日历日）/ count。
 */
export interface RRule {
  freq: Frequency
  interval: number
  byweekday?: Weekday[]
  end:
    | { kind: 'never' }
    | { kind: 'date'; until: string /* YYYY-MM-DD 本地日历日 */ }
    | { kind: 'count'; count: number /* 不含已被 exdate 跳过的实例 */ }
}

/** 事件规则（spec FR-1 字段表）。 */
export interface EventRule {
  id: string
  title: string
  /** Unix 毫秒（UTC ms）；all_day=true 时为该日 00:00 本地时刻对应 ms。 */
  start_ts: number
  end_ts: number
  all_day: boolean
  /** 本期固定 'local'（字段保留以便未来扩展，UI 不暴露）。 */
  tz_mode: 'local'
  location_text?: string | null
  note?: string | null
  color: Color
  /** 提前分钟数组；0=开始时刻；≤3 个（类型层面不强制上限，由表单校验把关）。 */
  reminders: number[]
  rrule: RRule | null
  /** 本地日历日 YYYY-MM-DD；与 start_ts 同口径匹配。 */
  exdates: string[]
}

/** 单次事件展开结果（spec FR-11 Occurrence 字段表）。 */
export interface Occurrence {
  /** `<rule.id>:<rule.start_ts>#<n>`；n 从 0 起，跳过 exdate 后递增。 */
  instance_id: string
  rule_id: string
  /** 实例起始 Unix 毫秒（UTC ms）。 */
  start_ts: number
  /** 实例结束 Unix 毫秒（end_ts - start_ts 保持规则时长）。 */
  end_ts: number
  all_day: boolean
  color: Color
  title: string
  /** 与 RFC 5545 recurrence-id 对齐：原始（未漂移）起始 ts。 */
  original_start_ts: number
}

/** 时间窗口（毫秒；from 含、to 不含）。 */
export interface TimeWindow {
  from: number
  to: number
}

// =============================================================================
// 本地日历日工具（运行时本地时区一次性读取后即固定）
// =============================================================================

/**
 * 取一次运行时本地时区相对 UTC 的偏移（分钟；如 +08:00 为 480）。
 * 单次读取缓存：算法内多次取会因测试中改 Date.now 等触发偏差，
 * 这里直接冻结为模块常量。
 *
 * 实现：getTimezoneOffset 返回"本地比 UTC 慢多少分钟"，取负即 spec 约定偏移。
 */
const TZ_OFFSET_MIN: number = -new Date(1780000000000).getTimezoneOffset()
// 选 1780000000000（2026-06-28 附近）规避历史 tz 偏移变化带来的歧义。

/**
 * Unix 毫秒 → 本地日历日键 "YYYY-MM-DD"。
 * 算法：先按运行时 tz 偏移加 → 再对偏移后时刻取 ISO 日历日。
 * 与 4a `core/days.ts` 的 `localDayKey` 思路一致；本模块独立以避免引入
 * locations/core 依赖。
 */
export function localDayKey(ts: number): string {
  return new Date(ts + TZ_OFFSET_MIN * 60_000).toISOString().slice(0, 10)
}

/** 把 Unix 毫秒拆成运行时本地日历分量（与 localDayKey 同口径）。 */
interface LocalParts {
  y: number
  m: number // 1-12
  d: number // 1-31
  h: number // 0-23
  mi: number // 0-59
}

export function localPartsOf(ts: number): LocalParts {
  // 加偏移后用 UTC 字段读本地分量（与 4a month.ts calendarCells 同思路）。
  const dt = new Date(ts + TZ_OFFSET_MIN * 60_000)
  return {
    y: dt.getUTCFullYear(),
    m: dt.getUTCMonth() + 1,
    d: dt.getUTCDate(),
    h: dt.getUTCHours(),
    mi: dt.getUTCMinutes(),
  }
}

/** 由 (y, m, d, h, mi) 本地分量重构 Unix 毫秒（用于按本地日历推进）。 */
function utcMsOfLocal(y: number, m: number, d: number, h: number, mi: number): number {
  // Date.UTC 自动处理越界进位（month=13 → 次年 1 月）；日字段越界（如 2/30）
  // 也自动进位到 3/2——这里需要"该月无该日则跳过"语义，故下方 addMonths
  // 单独做了日子裁剪。
  return Date.UTC(y, m - 1, d, h, mi) - TZ_OFFSET_MIN * 60_000
}

/** 一个月内的天数（1-31）。 */
function daysInMonth(y: number, m: number): number {
  // 月初下个月初相差的毫秒数 / 一天 ms，即得当月天数。
  return Math.round((Date.UTC(y, m, 1) - Date.UTC(y, m - 1, 1)) / 86_400_000)
}

/** Weekday → ISO 0=周一..6=周日（spec 用 MO..SU；getDay 用 0=周日）。 */
const WEEKDAY_INDEX: Record<Weekday, number> = {
  MO: 0,
  TU: 1,
  WE: 2,
  TH: 3,
  FR: 4,
  SA: 5,
  SU: 6,
}

/** 0=周日..6=周六 → ISO 0=周一..6=周日。 */
function isoWeekdayOf(ts: number): number {
  // JS getDay：0=Sun..6=Sat；转为 0=Mon..6=Sun。
  return (new Date(ts + TZ_OFFSET_MIN * 60_000).getUTCDay() + 6) % 7
}

/** ISO 周内 weekday 顺序：MO=0..SU=6 → 用于 WEEKLY byweekday 升序展开。 */
function weekdayOrder(w: Weekday): number {
  return WEEKDAY_INDEX[w]
}

// =============================================================================
// 推进工具（按本地日历日/周/月/年推进；DST 不补 23/25 小时校正）
// =============================================================================

/**
 * 加 days 个本地日。算法：拆本地分量 → 在日上加 days → 重构 ms；
 * 跨 DST 切换日时本函数始终保持"本地时刻"不变（h/m 不变），UTC ms 变化但
 * localDayKey 输出与本地语义对齐——即 spec FR-2 "不补 23/25 小时"语义。
 */
function addLocalDays(ts: number, days: number): number {
  const p = localPartsOf(ts)
  // 用中间锚点避开月末边界歧义：先转到本地午间 12:00 加天数，再回填 h/mi。
  const noonUtc = Date.UTC(p.y, p.m - 1, p.d, 12, 0) - TZ_OFFSET_MIN * 60_000
  const shifted = noonUtc + days * 86_400_000
  const sp = localPartsOf(shifted)
  return utcMsOfLocal(sp.y, sp.m, sp.d, p.h, p.mi)
}

/**
 * 加 months 个本地月。日字段越界（start_ts 本地日 > 目标月天数）则跳过
 * 该月（spec FR-2 MONTHLY "该月无该日则跳过"）；调用方负责循环直到找到
 * 下一个有效月。
 */
function tryAddLocalMonths(ts: number, months: number): number | null {
  const p = localPartsOf(ts)
  // Date.UTC(month - 1 + months) 自动进位；再裁剪日字段。
  const baseUtc = Date.UTC(p.y, p.m - 1 + months, 1, p.h, p.mi) -
    TZ_OFFSET_MIN * 60_000
  const bp = localPartsOf(baseUtc)
  const dim = daysInMonth(bp.y, bp.m)
  if (p.d > dim) return null // 该月无该日 → 跳过
  return utcMsOfLocal(bp.y, bp.m, p.d, p.h, p.mi)
}

/**
 * 加 years 个本地年。2/29 在非闰年跳过（spec FR-2 YEARLY）。
 */
function tryAddLocalYears(ts: number, years: number): number | null {
  const p = localPartsOf(ts)
  const targetY = p.y + years
  // 2 月 29 仅闰年存在；其它规则原样推进。
  if (p.m === 2 && p.d === 29) {
    const dim = daysInMonth(targetY, 2)
    if (dim < 29) return null
  }
  return utcMsOfLocal(targetY, p.m, p.d, p.h, p.mi)
}

/**
 * 在 (y, m) 内取第 n 个指定 weekday 的日期（day 字段）；
 * n 从 1 起；该月无第 n 个则返回 null（spec FR-2 MONTHLY 单 weekday）。
 * 注：本函数仅返回日期 1..31；上层调用方负责用 rule.start_ts 的 h/mi 重构 ms。
 */
function nthWeekdayDayInMonth(
  y: number,
  m: number,
  w: Weekday,
  n: number,
): number | null {
  if (n < 1) return null
  const firstMs = utcMsOfLocal(y, m, 1, 0, 0)
  const firstIsoW = isoWeekdayOf(firstMs)
  const targetIsoW = WEEKDAY_INDEX[w]
  let delta = (targetIsoW - firstIsoW + 7) % 7
  const day = 1 + delta + (n - 1) * 7
  const dim = daysInMonth(y, m)
  return day <= dim ? day : null
}

// =============================================================================
// 主展开函数
// =============================================================================

/**
 * 展开事件规则为指定窗口内的所有 Occurrence。
 *
 * 算法骨架：
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
export function expand(rule: EventRule, window: TimeWindow): Occurrence[] {
  const out: Occurrence[] = []

  // all_day 持续时长（end_ts - start_ts），后续实例原样继承。
  const durationMs = rule.end_ts - rule.start_ts

  // 计数与 n：
  //   - emitted：已写出实例数；同时作为 instance_id 的 n 编号基（n 从 0 起递增）。
  //   - skipped：被 exdate 跳过的候选数（仅作统计/日志，不计入 count 终止）。
  // 权威口径（spec FR-2 / tasks.md Task 2）：end.kind=count 时，终止累计仅看
  // emitted，不含 skipped；即 N 次 emitted 后即停，跳过 exdate 的实例不"消耗"
  // count 配额。
  let emitted = 0
  let skipped = 0

  // ---------- 单次事件分支 ----------
  // rrule=null 或空对象都视为单次（spec FR-2 + 任务 TR-2.1 第 23 用例）。
  if (rule.rrule === null || isEmptyRRule(rule.rrule)) {
    if (intersectsWindow(rule.start_ts, rule.end_ts, window)) {
      out.push(makeOccurrence(rule, rule.start_ts, rule.start_ts + durationMs, emitted))
    }
    return out
  }

  // ---------- 重复事件分支 ----------
  const rr = rule.rrule
  // exdate 集合：本地日历日键 → Set 加速命中判断。
  const exdateSet = new Set(rule.exdates)
  // 候选迭代上限保护（防止死循环；单用户量级 N 年远低于此上限）。
  const MAX_ITERATIONS = 100_000
  let iter = 0

  // 通用迭代器：以 step 间隔推进产生候选 ts；每个 ts 在窗口内再判 exdate。
  const produce = (candidate: number): void => {
    iter++
    if (iter > MAX_ITERATIONS) return
    // 窗口裁剪（from 含、to 不含）。
    if (candidate < window.from || candidate >= window.to) {
      // 窗口外不计入任何计数（spec 仅就"已展开候选"判定 count/date）。
      return
    }
    // count 终止：emitted（不含 skipped）≥ count 即停。
    // 权威口径：spec FR-2 / tasks.md Task 2——count 累计展开次数**不含**已被
    // exdate 跳过的实例（与 instance_id 的 n 编号口径一致：跳过 exdate 不递增 n，
    // 也不"消耗"count 配额）。
    if (rr.end.kind === 'count' && emitted >= rr.end.count) {
      // 已达 count；不再推进。
      iter = MAX_ITERATIONS // 触发外层 while 终止
      return
    }
    // date 终止：本地日历日 > until 即停。
    if (rr.end.kind === 'date') {
      if (localDayKey(candidate) > rr.end.until) {
        iter = MAX_ITERATIONS
        return
      }
    }
    // exdate 命中：n 不递增，count 也不计入——只更新 skipped 统计供观测。
    if (exdateSet.has(localDayKey(candidate))) {
      skipped++
      return
    }
    out.push(makeOccurrence(rule, candidate, candidate + durationMs, emitted))
    emitted++
  }

  if (rr.freq === 'DAILY') {
    // 从 rule.start_ts 起，按 interval*1 天推进；窗口外的候选不计入任何计数。
    // 终止条件：date/count 在 produce 内部已置 iter=MAX_ITERATIONS；
    // never 模式下，候选超过 window.to 即可提前结束。
    let cursor = rule.start_ts
    while (iter < MAX_ITERATIONS) {
      produce(cursor)
      if (iter >= MAX_ITERATIONS) break
      const next = addLocalDays(cursor, rr.interval)
      // never + 超出窗口上界 → 下一候选必然更晚，提前停。
      if (rr.end.kind === 'never' && next >= window.to) break
      cursor = next
    }
  } else if (rr.freq === 'WEEKLY') {
    // byweekday 决定一周内哪些天出候选；interval 决定隔几周。
    const wds = rr.byweekday && rr.byweekday.length > 0
      ? [...rr.byweekday].sort((a, b) => weekdayOrder(a) - weekdayOrder(b))
      : [weekdayFromIso(isoWeekdayOf(rule.start_ts))]
    // 计算规则起点所在 ISO 周的第一天（周一）的本地 00:00 对应 ms：
    // 用本地分量回溯到本周一（同 h/mi）。
    const startParts = localPartsOf(rule.start_ts)
    const startIsoW = isoWeekdayOf(rule.start_ts)
    const weekStartUtc = utcMsOfLocal(
      startParts.y,
      startParts.m,
      startParts.d - startIsoW, // 回溯到本周一
      startParts.h,
      startParts.mi,
    )
    let weekCursor = weekStartUtc
    while (iter < MAX_ITERATIONS) {
      for (const w of wds) {
        const off = weekdayOrder(w)
        produce(addLocalDays(weekCursor, off))
        if (iter >= MAX_ITERATIONS) break
      }
      if (iter >= MAX_ITERATIONS) break
      const nextWeek = addLocalDays(weekCursor, rr.interval * 7)
      // never 模式：下一周首日超出窗口上界 → 提前停。
      if (rr.end.kind === 'never' && nextWeek >= window.to) break
      weekCursor = nextWeek
    }
  } else if (rr.freq === 'MONTHLY') {
    if (rr.byweekday && rr.byweekday.length > 0) {
      // MONTHLY + byweekday（单元素）：取"该月第 N 个 weekday"，N 由
      // rule.start_ts 推算。
      const w = rr.byweekday[0]
      const startParts = localPartsOf(rule.start_ts)
      // 求 rule.start_ts 是其所在月份的"第几个"该 weekday。
      const n = nthOfWeekdayInMonth(
        startParts.y,
        startParts.m,
        w,
        startParts.d,
      )
      let monthOffset = 0
      while (iter < MAX_ITERATIONS) {
        // 该月目标日（若存在）：先按 +monthOffset 月定位到 y/m，再求第 n 个 weekday。
        const baseUtc = Date.UTC(startParts.y, startParts.m - 1 + monthOffset, 1) -
          TZ_OFFSET_MIN * 60_000
        const bp = localPartsOf(baseUtc)
        const day = nthWeekdayDayInMonth(bp.y, bp.m, w, n)
        if (day !== null) {
          const cand = utcMsOfLocal(bp.y, bp.m, day, startParts.h, startParts.mi)
          produce(cand)
          if (iter >= MAX_ITERATIONS) break
          // never 模式：超过窗口上界即停。
          if (rr.end.kind === 'never' && cand >= window.to) break
        }
        monthOffset += rr.interval
      }
    } else {
      // MONTHLY 不带 byweekday：start_ts 的本地日 → 后续月同 day；该月无则跳。
      let monthOffset = 0
      while (iter < MAX_ITERATIONS) {
        const cand = tryAddLocalMonths(rule.start_ts, monthOffset)
        if (cand !== null) produce(cand)
        if (iter >= MAX_ITERATIONS) break
        // never 模式：超过窗口上界即停。
        if (rr.end.kind === 'never' && cand !== null && cand >= window.to) break
        monthOffset += rr.interval
      }
    }
  } else if (rr.freq === 'YEARLY') {
    // spec FR-2 明示 byweekday 仅 WEEKLY/MONTHLY 有效；YEARLY + byweekday
    // 在 schema 校验阶段应被前端拒绝，本函数保守兜底：忽略 byweekday，
    // 按 start_ts 本地月/日展开；2/29 仅闰年存在。
    let yearOffset = 0
    while (iter < MAX_ITERATIONS) {
      const cand = tryAddLocalYears(rule.start_ts, yearOffset)
      if (cand !== null) produce(cand)
      if (iter >= MAX_ITERATIONS) break
      if (rr.end.kind === 'never' && cand !== null && cand >= window.to) break
      yearOffset += rr.interval
    }
  }

  // 排序（防御性：理论上产生顺序即升序，但 DST 边界 + 多 branch 合并时显式
  // 排序确保 toEqual 与 fixture 严格对齐）。
  out.sort((a, b) => a.start_ts - b.start_ts)
  return out
}

// =============================================================================
// 内部小工具
// =============================================================================

/** 区间 [aStart, aEnd) 与窗口 [from, to) 是否有交集。 */
function intersectsWindow(aStart: number, aEnd: number, w: TimeWindow): boolean {
  return aEnd > w.from && aStart < w.to
}

/** 构造一个 Occurrence（字段映射集中便于维护）。 */
function makeOccurrence(
  rule: EventRule,
  startTs: number,
  endTs: number,
  n: number,
): Occurrence {
  return {
    instance_id: `${rule.id}:${rule.start_ts}#${n}`,
    rule_id: rule.id,
    start_ts: startTs,
    end_ts: endTs,
    all_day: rule.all_day,
    color: rule.color,
    title: rule.title,
    original_start_ts: rule.start_ts,
  }
}

/** rrule 是否为"空"（freq 缺失或 interval≤0）；视为单次。 */
function isEmptyRRule(rr: RRule): boolean {
  if (!rr.freq) return true
  if (typeof rr.interval !== 'number' || rr.interval < 1) return true
  return false
}

/** ISO weekday index（0=Mon..6=Sun）→ Weekday 枚举（用于 WEEKLY 无 byweekday 默认）。 */
function weekdayFromIso(idx: number): Weekday {
  const arr: Weekday[] = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU']
  return arr[((idx % 7) + 7) % 7]
}

/**
 * 求 (y, m) 月内 day 日是该月第几个指定 weekday。
 * 例：2026-03-31 是该月第 5 个 Tuesday → (2026, 3, 'TU', 31) → 5。
 */
function nthOfWeekdayInMonth(y: number, m: number, w: Weekday, day: number): number {
  const firstMs = utcMsOfLocal(y, m, 1, 0, 0)
  const firstIsoW = isoWeekdayOf(firstMs)
  const targetIsoW = WEEKDAY_INDEX[w]
  // 月内首个目标 weekday 的"日"。
  const firstTargetDay = 1 + ((targetIsoW - firstIsoW + 7) % 7)
  return Math.floor((day - firstTargetDay) / 7) + 1
}
