// ============================================================================
// QuoteTable 纯函数 —— 手动行情包解析与价格查询
// （stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 8 批次（投资账户 + 手动行情）
// 路径: web/src/finance/quoteTable.ts
// 作用: 解析 spec FR-V2-D.2 定义的"行情 RSS / JSON 包"（HTTP GET 自托管端点，
//       解密后的明文 JSON），校验合法性，提供不可变内存形态以便 aggregator
//       聚合投资账户市值；纯函数，无副作用，不依赖 Network / Database / DOM /
//       localStorage —— 入参 -> 返回值，Vitest 在 node 环境直接跑。
//
// 行情包格式（spec FR-V2-D.2 真理源）：
//   {
//     "version": 1,
//     "ts": 1735689600000,
//     "base": "CNY",
//     "quotes": [
//       {"symbol": "AAPL", "price_minor": 18500, "currency": "USD", "ts": 1735689600000},
//       {"symbol": "0700.HK", "price_minor": 38000, "currency": "HKD", "ts": 1735689600000}
//     ]
//   }
//
// 双端锁定规则（与 Android android/.../finance/QuoteTable.kt 逐行为锁定 ——
// 同一代理双端实现，fixture 双向驱动守护）：
//   1. symbol 非空字符串，长度 1..32（与 HoldingLike 锁同口径，便于跨表匹配）；
//   2. price_minor 必须为非负整数（>= 0；价格可为零但不允许负，NaN / Infinity
//      / 小数 / 字符串 / null 一律拒绝）；
//   3. currency 必须是 3 个大写字母 ISO 4217 代码（与 RateTable / HoldingLike
//      同口径）；
//   4. ts 必须是整数毫秒（>= 0，不允许小数 / 负数 / NaN）；
//   5. 包顶层 ts：必填、整数毫秒、>= 0（行情包整体生效时刻，与单 quote ts 可不同）；
//   6. base：可选；显式给出时必须满足 CURRENCY_REGEX（用于跨币种聚合时声明基
//      准币，不强制一致，aggregator 会按 holdings 实际币种走 RateTable 折算）；
//   7. 同 symbol 多次出现：取数组最后一条覆盖（同 Android 端 last-write-wins
//      锁口径）。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-D.2（手动行情同步）
//   - android/.../finance/QuoteTable.kt（Android 镜像，同批由同一代理实现）
//   - web/src/finance/investmentAccountRecord.ts（投资账户持仓消费方，
//     同批由同一代理实现）
//   - web/src/finance/aggregator.ts#investmentMarketValue（投资账户市值聚合
//     消费方）
//   - web/src/finance/rateTable.ts（多币种折算消费方）
// ============================================================================

/**
 * 行情包单条报价。
 *
 * @property symbol 证券代码（与 HoldingLike.symbol 锁同口径，1..32 字符）
 * @property priceMinor 报价（minor = 分，非负整数；AAPL 185.00 USD → price_minor=18500）
 * @property currency 报价币种（ISO 4217 三字母大写代码）
 * @property ts 该条报价生效时刻（Unix 毫秒）
 */
export interface Quote {
  readonly symbol: string
  readonly priceMinor: number
  readonly currency: string
  readonly ts: number
}

/**
 * 手动行情包（解密后内存形态）。
 *
 * @property ts 行情包整体生效时刻（Unix 毫秒；与单 quote.ts 可不同）
 * @property quotes 报价映射（key = symbol；同 symbol 多次出现时取数组最后一条）
 * @property base 基准币（可选；null 表示包未声明基准，aggregator 按 holdings 实
 *   际币种折算）
 */
export interface QuoteTable {
  readonly ts: number
  readonly quotes: ReadonlyMap<string, Quote>
  readonly base?: string | null
}

// ============================================================================
// 常量区 —— 校验正则与边界（与 Android QuoteTable.kt 完全一致）
// ============================================================================

/** ISO 4217 三字母代码粗校验正则（与 RateTable / HoldingLike 同口径）。 */
const CURRENCY_REGEX = /^[A-Z]{3}$/

/** 证券代码长度边界（1..32 字符，与 HoldingLike 锁同口径）。 */
const SYMBOL_MIN_LEN = 1
const SYMBOL_MAX_LEN = 32

// ============================================================================
// 公开 API —— parseQuoteTable / priceMinorOf / encodeQuoteTable
// ============================================================================

/**
 * 解析 spec FR-V2-D.2 行情包明文 JSON。
 *
 * 容错与校验策略：
 *   - version 缺失按 1 接受；显式给出且不为 1 则拒绝（前向兼容守门）；
 *   - ts 必填、整数、>= 0；
 *   - quotes 必填数组、至少 1 条（空数组视为非法 —— 与 RateTable "至少 1 汇率"
 *     同口径，行情包不允许空载同步）；
 *   - base 可选；显式给出时必须满足 CURRENCY_REGEX；
 *   - 每条 quote.symbol / price_minor / currency / ts 全部按上文锁口径校验；
 *   - 同 symbol 多次出现：取数组最后一条覆盖（last-write-wins）。
 *
 * @param json 行情包明文 JSON 字符串
 * @returns 解析后的 QuoteTable
 * @throws Error 任一校验不通过（message 为中文人类可读说明）
 */
export function parseQuoteTable(json: string): QuoteTable {
  // 根节点：JSON 语法错误 / 非对象 / 数组 / null 一律拒绝。
  let root: unknown
  try {
    root = JSON.parse(json)
  } catch {
    throw new Error('行情包解析失败：根节点不是合法 JSON 对象')
  }
  if (root === null || typeof root !== 'object' || Array.isArray(root)) {
    throw new Error('行情包解析失败：根节点必须为 JSON 对象')
  }
  const rootObj = root as Record<string, unknown>

  // ========== 1. version：缺失按 1 接受，显式异值拒绝 ==========
  if (
    Object.prototype.hasOwnProperty.call(rootObj, 'version') &&
    rootObj.version !== null
  ) {
    if (typeof rootObj.version !== 'number' || rootObj.version !== 1) {
      throw new Error(
        `行情包 version 不受支持：${String(rootObj.version)}（当前仅支持版本 1）`,
      )
    }
  }

  // ========== 2. ts：必填、有限整数、>= 0 ==========
  if (!Object.prototype.hasOwnProperty.call(rootObj, 'ts') || rootObj.ts === null) {
    throw new Error('行情包 ts 缺失或非法（必须为 >= 0 的 Unix 毫秒整数）')
  }
  if (
    typeof rootObj.ts !== 'number' ||
    !Number.isFinite(rootObj.ts) ||
    rootObj.ts < 0 ||
    !Number.isInteger(rootObj.ts)
  ) {
    throw new Error('行情包 ts 缺失或非法（必须为 >= 0 的 Unix 毫秒整数）')
  }
  const ts = rootObj.ts

  // ========== 3. base：可选；显式给出时必须满足 CURRENCY_REGEX ==========
  let base: string | null = null
  if (Object.prototype.hasOwnProperty.call(rootObj, 'base') && rootObj.base !== null) {
    if (typeof rootObj.base !== 'string') {
      throw new Error('行情包 base 类型非法：必须是字符串')
    }
    const b = rootObj.base.trim()
    if (!CURRENCY_REGEX.test(b)) {
      throw new Error(
        `行情包 base 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = ${b}）`,
      )
    }
    base = b
  }

  // ========== 4. quotes：必填数组、至少 1 条 ==========
  const arrRaw = rootObj.quotes
  if (arrRaw === null || !Array.isArray(arrRaw)) {
    throw new Error('行情包 quotes 缺失或非法（必须为数组）')
  }
  if (arrRaw.length === 0) {
    throw new Error('行情包 quotes 至少需要 1 条报价')
  }

  // ========== 5. 逐条解析与校验 ==========
  // 同 symbol 多次出现：取数组最后一条覆盖（last-write-wins）。
  const quotes = new Map<string, Quote>()
  for (let i = 0; i < arrRaw.length; i++) {
    const itemRaw = arrRaw[i]
    if (itemRaw === null || typeof itemRaw !== 'object' || Array.isArray(itemRaw)) {
      throw new Error(`quotes[${i}] 维度非法：必须是 JSON 对象`)
    }
    const item = itemRaw as Record<string, unknown>

    // ---- 5.1 symbol ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'symbol') ||
      item.symbol === null
    ) {
      throw new Error(`quotes[${i}].symbol 缺失`)
    }
    if (typeof item.symbol !== 'string') {
      throw new Error(`quotes[${i}].symbol 类型非法：必须是字符串`)
    }
    const symbol = item.symbol.trim()
    if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) {
      throw new Error(
        `quotes[${i}].symbol 长度非法：必须在 ${SYMBOL_MIN_LEN}..${SYMBOL_MAX_LEN} 字符之间（实际 = ${symbol.length}）`,
      )
    }

    // ---- 5.2 price_minor ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'price_minor') ||
      item.price_minor === null
    ) {
      throw new Error(`quotes[${i}].price_minor 缺失`)
    }
    if (typeof item.price_minor !== 'number') {
      throw new Error(`quotes[${i}].price_minor 类型非法：必须是数字`)
    }
    const priceDouble = item.price_minor
    if (!Number.isFinite(priceDouble) || priceDouble < 0) {
      throw new Error(
        `quotes[${i}].price_minor 值非法：必须是非负有限数（实际 = ${String(item.price_minor)}）`,
      )
    }
    if (!Number.isInteger(priceDouble)) {
      throw new Error(`quotes[${i}].price_minor 必须为整数（不允许小数）`)
    }
    const priceMinor = priceDouble

    // ---- 5.3 currency ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'currency') ||
      item.currency === null
    ) {
      throw new Error(`quotes[${i}].currency 缺失`)
    }
    if (typeof item.currency !== 'string') {
      throw new Error(`quotes[${i}].currency 类型非法：必须是字符串`)
    }
    const currency = item.currency.trim()
    if (!CURRENCY_REGEX.test(currency)) {
      throw new Error(
        `quotes[${i}].currency 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = ${currency}）`,
      )
    }

    // ---- 5.4 ts ----
    if (!Object.prototype.hasOwnProperty.call(item, 'ts') || item.ts === null) {
      throw new Error(`quotes[${i}].ts 缺失`)
    }
    if (typeof item.ts !== 'number') {
      throw new Error(`quotes[${i}].ts 类型非法：必须是数字`)
    }
    const qTsDouble = item.ts
    if (!Number.isFinite(qTsDouble) || qTsDouble < 0) {
      throw new Error(
        `quotes[${i}].ts 值非法：必须是非负有限数（实际 = ${String(item.ts)}）`,
      )
    }
    if (!Number.isInteger(qTsDouble)) {
      throw new Error(`quotes[${i}].ts 必须为整数（不允许小数）`)
    }
    const qTs = qTsDouble

    quotes.set(symbol, { symbol, priceMinor, currency, ts: qTs })
  }

  return { ts, quotes, base }
}

/**
 * 查某 symbol 的报价（minor = 分）。
 *
 * 找不到 / symbol 为空 / symbol 超长时一律返回 null（不抛）。
 * aggregator 在缺价时按 0 计值（与缺汇率时面值 1:1 降级同口径，行为可预期）。
 *
 * @param symbol 证券代码（聚合时直接传入 HoldingLike.symbol）
 * @param table 已解析的行情包
 * @returns 该 symbol 的 price_minor；缺价时返回 null
 */
export function priceMinorOf(symbol: string, table: QuoteTable): number | null {
  if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) return null
  const q = table.quotes.get(symbol)
  return q ? q.priceMinor : null
}

/**
 * 将行情包编码回 JSON 段（用于本地导入或 records 通道密封前的明文拼装）。
 *
 * 编码格式与 spec FR-V2-D.2 严格对齐（同 symbol 多次出现按入参 Map 顺序输
 * 出；Map 已通过遍历顺序保持 last-write-wins 顺序）。
 *
 * @param table 待编码的行情包
 * @returns JSON 字符串（JSON.stringify 序列化，不含缩进）
 */
export function encodeQuoteTable(table: QuoteTable): string {
  const out: Record<string, unknown> = {
    version: 1,
    ts: table.ts,
  }
  if (table.base != null) {
    out.base = table.base
  }
  const arr: Array<Record<string, unknown>> = []
  for (const q of table.quotes.values()) {
    arr.push({
      symbol: q.symbol,
      price_minor: q.priceMinor,
      currency: q.currency,
      ts: q.ts,
    })
  }
  out.quotes = arr
  return JSON.stringify(out)
}
