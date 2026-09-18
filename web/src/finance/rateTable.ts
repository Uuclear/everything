// ============================================================================
// RateTable 纯函数 —— 财务模块 Web 端离线汇率包解析与多币种折算（stage5-finance-v2 / B5 / FR-V2-C.2、FR-V2-C.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / B5 批次（多币种汇率纯函数层）
// 路径: web/src/finance/rateTable.ts
// 作用: 解析 spec FR-V2-C.2 定义的加密离线汇率包（解密后的明文 JSON），并按
//       "分"（minor = 最小货币单位）BigInt 口径完成跨币种折算；纯函数，不依赖
//       Network / Database / DOM / localStorage —— 入参 -> 返回值，Vitest 在
//       node 环境直接跑。
//
// 汇率包格式（spec FR-V2-C.2 真理源）:
//   {
//     "version": 1,
//     "effective_ts": 1735689600000,
//     "rates": {
//       "USD/CNY": 7.25,
//       "EUR/CNY": 7.85,
//       "JPY/CNY": 0.048,
//       "HKD/CNY": 0.93
//     }
//   }
//
// 双端锁定规则（与 Android RateTable.kt 逐行为锁定 —— 同一代理双端实现）:
//   1. 金额全程 minor bigint（分）出入；乘除阶段临时转 Number：业务量级
//      （千亿元 = 1e13 分, 乘 10 以内汇率后 ≈ 1e14）仍小于 Number.MAX_SAFE_INTEGER
//      （2^53 − 1 ≈ 9.007e15），精度安全；超出该量级不在 v2 业务范围内, 不额外防御；
//   2. 舍入规则双端统一为"绝对值先取整、再恢复符号":
//      - Web 对绝对值 Math.round（x.5 向更大的整数走）, 随后恢复负号；
//      - Android 对绝对值 BigDecimal setScale(0, HALF_UP)。
//      非负数上二者等价；差异只在负数（Android HALF_UP 远离零, 朴素
//      Math.round 向正无穷, 如 Math.round(-14.5) === -14）—— 绝对值规则下
//      差异被消除: −2 分 × 7.25 = −14.5, 双端均得 −15；
//   3. 正反向: 优先命中 rates["FROM/TO"]（乘法）, 其次 rates["TO/FROM"]
//      （除法, 用反向汇率除回）, 都缺失则 convertMinor 返回 null,
//      convertMinorOrIdentity 退回原值（aggregator 缺汇率时的面值 1:1 降级口径）。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-C.2（汇率包格式真理源）
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-C.3（资产看板多币种折算）
//   - android/app/src/main/java/com/everything/eve/finance/RateTable.kt（Android 镜像）
//   - web/src/finance/aggregator.ts（唯一消费方, toTarget 私有封装）
// ============================================================================

/**
 * 离线汇率表（汇率包解密后的内存形态）。
 *
 * @property effectiveTs 汇率包生效时刻（Unix 毫秒；对应 spec 包字段 effective_ts）
 * @property rates 汇率映射；key 形如 "USD/CNY"，value = 1 单位 from 可兑换多少 to
 */
export interface RateTable {
  effectiveTs: number
  rates: Record<string, number>
}

/** 汇率键正则：3 个大写字母 / 3 个大写字母，如 "USD/CNY"。 */
const PAIR_RE = /^[A-Z]{3}\/[A-Z]{3}$/

/** ISO 4217 三字母代码粗校验正则。 */
const CODE_RE = /^[A-Z]{3}$/

// =============================================================================
// parseRateTable —— spec FR-V2-C.2 汇率包解析
// =============================================================================

/**
 * 解析 spec FR-V2-C.2 离线汇率包明文 JSON。
 *
 * 容错与校验策略：
 *   - version 缺失按 1 接受；显式给出且不为 1 则拒绝（前向兼容守门）；
 *   - effective_ts 必须存在、为有限整数且 >= 0，否则抛 Error；
 *   - rates 必须为对象且至少 1 条；
 *   - 每个 key 必须匹配 ^[A-Z]{3}/[A-Z]{3}$ 且 from ≠ to；
 *   - 每个 value 必须为有限数且 > 0（NaN / Infinity / 字符串 / null 一律拒绝）。
 *
 * @param json 汇率包明文 JSON 字符串
 * @returns 解析后的 RateTable
 * @throws Error 任一校验不通过（message 为中文人类可读说明）
 */
export function parseRateTable(json: string): RateTable {
  // 根节点：JSON 语法错误 / 非对象 / 数组 / null 一律拒绝。
  let root: unknown
  try {
    root = JSON.parse(json)
  } catch {
    throw new Error('汇率包解析失败：根节点不是合法 JSON 对象')
  }
  if (root === null || typeof root !== 'object' || Array.isArray(root)) {
    throw new Error('汇率包解析失败：根节点必须为 JSON 对象')
  }
  const rootObj = root as Record<string, unknown>

  // ========== 1. version：缺失按 1 接受，显式异值拒绝 ==========
  if (Object.prototype.hasOwnProperty.call(rootObj, 'version') && rootObj.version !== null) {
    if (typeof rootObj.version !== 'number' || rootObj.version !== 1) {
      throw new Error(
        `汇率包 version 不受支持：${String(rootObj.version)}（当前仅支持版本 1）`,
      )
    }
  }

  // ========== 2. effective_ts：必填、有限整数、>= 0 ==========
  const eff = rootObj.effective_ts
  if (
    typeof eff !== 'number' ||
    !Number.isFinite(eff) ||
    eff < 0 ||
    !Number.isInteger(eff)
  ) {
    throw new Error('汇率包 effective_ts 缺失或非法（必须为 >= 0 的 Unix 毫秒整数）')
  }

  // ========== 3. rates：必填对象、至少 1 条 ==========
  const ratesRaw = rootObj.rates
  if (ratesRaw === null || typeof ratesRaw !== 'object' || Array.isArray(ratesRaw)) {
    throw new Error('汇率包 rates 缺失或非法（必须为对象）')
  }
  const ratesSource = ratesRaw as Record<string, unknown>
  const keys = Object.keys(ratesSource)
  if (keys.length === 0) {
    throw new Error('汇率包 rates 至少需要 1 条汇率')
  }

  // ========== 4. 逐条校验 key 与 value ==========
  const rates: Record<string, number> = {}
  for (const key of keys) {
    if (!PAIR_RE.test(key)) {
      throw new Error(`汇率包汇率键非法：${key}（必须形如 USD/CNY 的三字母代码对）`)
    }
    const from = key.slice(0, 3)
    const to = key.slice(4, 7)
    if (from === to) {
      throw new Error(`汇率包汇率键非法：${key}（源货币与目标货币不能相同）`)
    }
    const value = ratesSource[key]
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
      throw new Error(
        `汇率包 ${key} 的汇率非法：必须为有限正数（实际 = ${String(value)}）`,
      )
    }
    rates[key] = value
  }

  return { effectiveTs: eff, rates }
}

// =============================================================================
// convertMinor / convertMinorOrIdentity —— minor 整数折算
// =============================================================================

/**
 * 按汇率表把 minor 金额从 from 折算到 to。
 *
 * 解析顺序：
 *   1. from / to 任一不是合法三字母代码 → 返回 null；
 *   2. from == to（合法自交叉）→ 原值返回（与汇率表内容无关，空表也成立）；
 *   3. rates["FROM/TO"] 命中 → 金额绝对值 × 汇率，Math.round 取整后恢复符号；
 *   4. rates["TO/FROM"] 命中（反向）→ 金额绝对值 ÷ 反向汇率，同口径取整；
 *   5. 双向均缺失 → null。
 *
 * @param amountMinor 源币种 minor 金额（分；允许负数，如负债）
 * @param from 源币种 ISO 4217 三字母代码
 * @param to 目标币种 ISO 4217 三字母代码
 * @param table 已解析的汇率表
 * @returns 目标币种 minor 金额；无法折算时返回 null
 */
export function convertMinor(
  amountMinor: bigint,
  from: string,
  to: string,
  table: RateTable,
): bigint | null {
  // 非法币种代码不做任何折算尝试。
  if (!CODE_RE.test(from) || !CODE_RE.test(to)) return null
  // 自交叉恒等 —— 不读表，空汇率表也成立。
  if (from === to) return amountMinor

  // 双端舍入锁定：绝对值参与乘除取整，最后恢复符号（见文件头注释 2）。
  const negative = amountMinor < 0n
  const absMinor = negative ? -amountMinor : amountMinor
  const absNum = Number(absMinor)

  let rounded: number
  const forward = table.rates[`${from}/${to}`]
  if (forward !== undefined) {
    rounded = Math.round(absNum * forward)
  } else {
    const reverse = table.rates[`${to}/${from}`]
    if (reverse === undefined) return null
    rounded = Math.round(absNum / reverse)
  }

  const result = BigInt(rounded)
  return negative ? -result : result
}

/**
 * convertMinor 的面值降级版本：无法折算（缺汇率 / 非法代码对）时原样返回金额。
 *
 * 业务语义：aggregator 在汇率表缺失某币种时按 1:1 面值口径计入看板，保证总量
 * 不因离线汇率包不全而丢条目（spec FR-V2-C.3 降级约定）。
 */
export function convertMinorOrIdentity(
  amountMinor: bigint,
  from: string,
  to: string,
  table: RateTable,
): bigint {
  return convertMinor(amountMinor, from, to, table) ?? amountMinor
}
