// ============================================================================
// InvestmentAccountRecord 纯函数 —— 投资账户 holdings 解析与校验
// （stage5-finance-v2 / Task 8 / FR-V2-D.1）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 8 批次（投资账户 + 手动行情）
// 路径: web/src/finance/investmentAccountRecord.ts
// 作用: 解析 spec FR-V2-D.1 定义的 holdings JSON 字段（账户 envelope 解密后
//       的明文 JSON 段），校验合法性，提供 holdings 列表的不可变内存形态
//       以便 aggregator 聚合与 UI 渲染；纯函数，无副作用，不依赖 Network /
//       Database / DOM / localStorage —— 入参 -> 返回值，Vitest 在 node 环
//       境直接跑。
//
// holdings JSON 段格式（spec FR-V2-D.1 真理源）：
//   {
//     "holdings": [
//       {"symbol": "AAPL", "shares": 10, "cost_basis_minor": 150000, "currency": "USD"},
//       {"symbol": "0700.HK", "shares": 100, "cost_basis_minor": 380000, "currency": "HKD"}
//     ]
//   }
//
// 双端锁定规则（与 Android android/.../finance/InvestmentAccountRecord.kt 逐
// 行为锁定 —— 同一代理双端实现，fixture 双向驱动守护）：
//   1. shares 必须是有限正数（NaN / Infinity / 负数 / 0 一律拒绝）；
//   2. cost_basis_minor 必须为非负整数（>= 0，不允许小数，不允许负数）
//      —— 用 number 承载，业务量级（亿级份额 × 千倍市盈率 = 万亿）安全；
//   3. currency 必须是 3 个大写字母 ISO 4217 代码（与 RateTable CODE_RE 同口径）；
//   4. symbol 是非空字符串，长度 1..32（股票代码 + 港股后缀 / 美股盘前盘后标识）；
//   5. 整段 holdings 缺失按"空持仓"接受（合法账户可以没有持股）；
//   6. 同一 symbol 在 holdings 数组中可出现多次（不同批次建仓）—— 不做去重，
//      aggregator 求和时按序累加，与 Android 端按位置求和完全一致。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-D.1（投资账户子类型）
//   - android/.../finance/InvestmentAccountRecord.kt（Android 镜像，同批由同
//     一代理实现）
//   - web/src/finance/quoteTable.ts（行情聚合输入，同批由同一代理实现）
//   - web/src/finance/aggregator.ts#investmentMarketValue（投资账户市值聚合
//     消费方）
// ============================================================================

/**
 * 投资账户单笔持仓（holdings 数组元素）。
 *
 * @property symbol 证券代码（AAPL / 0700.HK / 600519.SH 等，长度 1..32）
 * @property shares 持仓份额（有限正数；NaN / Infinity / 负数 / 0 一律拒）
 * @property costBasisMinor 建仓成本（minor = 分，非负整数；跨币种按各持仓货币计）
 * @property currency 持仓货币 ISO 4217 三字母大写代码
 */
export interface HoldingLike {
  readonly symbol: string
  readonly shares: number
  readonly costBasisMinor: number
  readonly currency: string
}

/**
 * 投资账户记录形态（聚合函数入参 DTO，解耦 store / 持久层）。
 *
 * 设计意图：与持久层实体（planned：Investments / store 视图态）解耦，避免
 * Vitest 单测被迫构造完整持久层对象；聚合函数只关心 id + kind + currency +
 * holdings + archived 五元组。
 *
 * @property id 账户 id
 * @property kind 账户类型，严格 = "stock"（与现行账户枚举一致；spec 范例写
 *   investment 系勘误，见 Completion Evidence）
 * @property currency 账户主货币（持仓货币可能与之不同，多币种聚合由
 *   RateTable 折算，holdings 价值聚合由 QuoteTable + RateTable 双折算）
 * @property holdings 持仓列表（不可变，可为空）
 * @property archived 归档标记（true 时聚合跳过，与其它账户同口径）
 */
export interface InvestmentAccountRecord {
  readonly id: string
  readonly kind: string
  readonly currency: string
  readonly holdings: readonly HoldingLike[]
  readonly archived: boolean
}

// ============================================================================
// 常量区 —— 校验正则与边界（与 Android InvestmentAccountRecord.kt 完全一致）
// ============================================================================

/** 投资账户类型枚举：仅 "stock" 合法（与现行账户枚举对齐）。 */
export const KIND_STOCK = 'stock'

/** ISO 4217 三字母代码粗校验正则（与 RateTable CODE_RE 同口径）。 */
const CURRENCY_REGEX = /^[A-Z]{3}$/

/** 证券代码长度边界（1..32 字符）。 */
const SYMBOL_MIN_LEN = 1
const SYMBOL_MAX_LEN = 32

// ============================================================================
// 公开 API —— parseHoldings / encodeHoldings / build
// ============================================================================

/**
 * 解析 spec FR-V2-D.1 holdings JSON 段。
 *
 * 容错与校验策略：
 *   - holdings 缺失或为 null → 返回空数组（合法空仓）；
 *   - holdings 非数组 → 拒绝；
 *   - 元素非对象 → 拒绝；
 *   - 元素内任一字段缺失 / 类型不符 → 拒绝（中文人类可读 message）；
 *   - 同 symbol 多次出现 → 不去重，按序保留（与 Android 端位置求和锁口径）。
 *
 * @param json 账户明文 JSON（含 holdings 段；全段或仅 holdings 子段均可）
 * @returns 不可变 holdings 列表（空 = 无持仓）
 * @throws Error 任一校验不通过（message 为中文人类可读说明）
 */
export function parseHoldings(json: string): readonly HoldingLike[] {
  // 根节点：JSON 语法错误 / 非对象 / 数组 / null 一律拒绝。
  let root: unknown
  try {
    root = JSON.parse(json)
  } catch {
    throw new Error('账户 JSON 解析失败：根节点不是合法 JSON 对象')
  }
  if (root === null || typeof root !== 'object' || Array.isArray(root)) {
    throw new Error('账户 JSON 解析失败：根节点必须为 JSON 对象')
  }
  const rootObj = root as Record<string, unknown>

  // ========== 1. holdings 段可缺失 → 空仓 ==========
  if (
    !Object.prototype.hasOwnProperty.call(rootObj, 'holdings') ||
    rootObj.holdings === null
  ) {
    return []
  }
  const arrRaw = rootObj.holdings
  if (!Array.isArray(arrRaw)) {
    throw new Error(
      'holdings 段非法：必须是 JSON 数组（缺失 / null / 非数组一律视为非法）',
    )
  }

  // ========== 2. 逐元素解析与校验 ==========
  const result: HoldingLike[] = []
  for (let i = 0; i < arrRaw.length; i++) {
    const itemRaw = arrRaw[i]
    if (itemRaw === null || typeof itemRaw !== 'object' || Array.isArray(itemRaw)) {
      throw new Error(`holdings[${i}] 维度非法：必须是 JSON 对象`)
    }
    const item = itemRaw as Record<string, unknown>

    // ---- 2.1 symbol ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'symbol') ||
      item.symbol === null
    ) {
      throw new Error(`holdings[${i}].symbol 缺失`)
    }
    if (typeof item.symbol !== 'string') {
      throw new Error(`holdings[${i}].symbol 类型非法：必须是字符串`)
    }
    const symbol = item.symbol.trim()
    if (symbol.length < SYMBOL_MIN_LEN || symbol.length > SYMBOL_MAX_LEN) {
      throw new Error(
        `holdings[${i}].symbol 长度非法：必须在 ${SYMBOL_MIN_LEN}..${SYMBOL_MAX_LEN} 字符之间（实际 = ${symbol.length}）`,
      )
    }

    // ---- 2.2 shares ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'shares') ||
      item.shares === null
    ) {
      throw new Error(`holdings[${i}].shares 缺失`)
    }
    if (typeof item.shares !== 'number') {
      throw new Error(
        `holdings[${i}].shares 类型非法：必须是数字（不允许字符串 / null / 布尔）`,
      )
    }
    const shares = item.shares
    if (!Number.isFinite(shares) || shares <= 0) {
      throw new Error(
        `holdings[${i}].shares 值非法：必须是有限正数（实际 = ${String(item.shares)}）`,
      )
    }

    // ---- 2.3 cost_basis_minor ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'cost_basis_minor') ||
      item.cost_basis_minor === null
    ) {
      throw new Error(`holdings[${i}].cost_basis_minor 缺失`)
    }
    if (typeof item.cost_basis_minor !== 'number') {
      throw new Error(`holdings[${i}].cost_basis_minor 类型非法：必须是数字`)
    }
    const costBasis = item.cost_basis_minor
    if (!Number.isFinite(costBasis) || costBasis < 0) {
      throw new Error(
        `holdings[${i}].cost_basis_minor 值非法：必须是非负有限数（实际 = ${String(item.cost_basis_minor)}）`,
      )
    }
    if (!Number.isInteger(costBasis)) {
      throw new Error(`holdings[${i}].cost_basis_minor 必须为整数（不允许小数）`)
    }
    // 数值安全：业务量级（亿级 = 1e8）远小于 Number.MAX_SAFE_INTEGER
    // （2^53 − 1 ≈ 9.007e15），此处不额外防御。

    // ---- 2.4 currency ----
    if (
      !Object.prototype.hasOwnProperty.call(item, 'currency') ||
      item.currency === null
    ) {
      throw new Error(`holdings[${i}].currency 缺失`)
    }
    if (typeof item.currency !== 'string') {
      throw new Error(`holdings[${i}].currency 类型非法：必须是字符串`)
    }
    const currency = item.currency.trim()
    if (!CURRENCY_REGEX.test(currency)) {
      throw new Error(
        `holdings[${i}].currency 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = ${currency}）`,
      )
    }

    result.push({
      symbol,
      shares,
      costBasisMinor: costBasis,
      currency,
    })
  }

  return result
}

/**
 * 将 holdings 列表编码回 JSON 段（用于本地导入或 records 通道密封前的明文拼装）。
 *
 * 编码格式与 spec FR-V2-D.1 严格对齐：
 *   {
 *     "holdings": [
 *       {"symbol": "...", "shares": ..., "cost_basis_minor": ..., "currency": "..."}
 *     ]
 *   }
 *
 * 空仓列表也输出 holdings=[]（不省略键，与 parseHoldings 缺失分支兼容）。
 *
 * @param holdings 待编码的持仓列表（空 = 空仓）
 * @returns JSON 字符串（JSON.stringify 序列化，不含缩进）
 */
export function encodeHoldings(holdings: readonly HoldingLike[]): string {
  const root = {
    holdings: holdings.map((h) => ({
      symbol: h.symbol,
      shares: h.shares,
      cost_basis_minor: h.costBasisMinor,
      currency: h.currency,
    })),
  }
  return JSON.stringify(root)
}

/**
 * 构造一个投资账户记录（聚合函数入参 DTO 工厂）。
 *
 * 校验口径：
 *   - kind 必须为 "stock"（其它枚举值不属于投资账户范围，一律拒）；
 *   - currency 必须是 3 个大写字母 ISO 4217 代码；
 *   - 列表可空（合法空仓），不做 holdings 字段深校验（解析校验在
 *     [parseHoldings] 处完成，本工厂仅做账户壳校验）。
 *
 * @throws Error kind / currency / id 非法
 */
export function build(
  id: string,
  kind: string,
  currency: string,
  holdings: readonly HoldingLike[],
  archived: boolean,
): InvestmentAccountRecord {
  if (kind !== KIND_STOCK) {
    throw new Error(
      `投资账户 kind 非法：必须是 "${KIND_STOCK}"（实际 = ${kind}）`,
    )
  }
  if (!CURRENCY_REGEX.test(currency)) {
    throw new Error(
      `投资账户 currency 非法：必须是 3 个大写字母 ISO 4217 代码（实际 = ${currency}）`,
    )
  }
  if (!id || id.trim().length === 0) {
    throw new Error('投资账户 id 不能为空')
  }
  return {
    id,
    kind,
    currency,
    holdings: holdings.slice(),
    archived,
  }
}
