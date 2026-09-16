// ============================================================================
// Luhn 模 10 校验 —— 财务模块 Web 端纯函数实现
// ============================================================================
//
// 任务: stage5-finance / Task 2 / TR-2.1
// 路径: web/src/finance/luhn.ts
// 作用: 在客户端对用户录入的银行卡/信用卡卡号做 Luhn 校验,
//       校验通过后才会提取后四位入库; 完整卡号仅在校验瞬间驻留内存,
//       不写入任何持久化层 / 日志 / 网络（零知识纪律）。
//
// 设计要点:
//   1. 纯函数 —— 无副作用, 不调用 crypto / 网络 / 数据库;
//   2. 入参为 string —— 避免 JS Number 在长卡号上的精度丢失;
//   3. 自动 trim 空格 / 去除常见分隔符（空格 / 连字符）;
//   4. 边界情况: 空串 / 非数字残留 / 长度 < 2 → 全部返回 false;
//   5. 与 Android 端 Luhn.kt 行为逐字段一致（三端契约, 见 docs/finance.md）。
//
// 关联:
//   - tasks.md TR-2.1（Web Luhn.ts 纯函数实现）
//   - tasks.md TR-2.2（共享 fixture luhn-cases.json）
//   - tasks.md TR-3.1（Android Luhn.kt 镜像, 三端哈希一致）
//   - docs/finance.md §"Luhn 校验"（算法描述 + 零知识纪律）
// ============================================================================

/**
 * Luhn 校验算法常量 —— 卡号仅在「去分隔符后纯数字字符」状态下
 * 才参与模 10 累加; 其它任意字符残留都直接判非法。
 */

/**
 * 用户录入卡号时常见的可见分隔符 —— Luhn 计算前必须剥离。
 * 注意: 只剥离空白与连字符, 其它符号（例如点 / 斜杠）按非法处理。
 */
const SEPARATOR_REGEX = /[\s-]+/g

/**
 * 卡号纯数字字符正则 —— 仅在剥离分隔符后用此正则做最终合法性判定。
 */
const DIGITS_ONLY_REGEX = /^\d+$/

/**
 * 银行卡号合法长度区间（按 ISO/IEC 7812 行业惯例）。
 *
 * - 最小 13 位: 部分老式 Maestro / 早期 Visa 13 位卡;
 * - 最大 19 位: UnionPay / Maestro 长卡号上限;
 * - 小于 13 或大于 19 一律视为非法输入, 直接返回 false。
 */
const MIN_PAN_LENGTH = 13
const MAX_PAN_LENGTH = 19

/**
 * 对银行卡号做 Luhn 模 10 校验（纯函数）。
 *
 * 行为契约:
 *   - 输入允许包含空格 / 连字符（自动剥离）;
 *   - 输入为空 / 非字符串 / 长度越界 / 含其它字符 → false;
 *   - 算法采用自右向左「奇偶位 ×2 后求个位数字之和」的标准实现;
 *   - 永不抛错, 仅返回 boolean。
 *
 * @param pan 银行卡号原文（仅校验, 不入库）
 * @returns 是否通过 Luhn 校验
 */
export function luhnValidate(pan: string): boolean {
  // ========== 1. 基础类型与空值守卫 ==========
  // 非字符串直接判非法 —— 防御性兜底, 正常调用方不会传入非字符串。
  if (typeof pan !== 'string') {
    return false
  }
  // 空串 / 纯空白 → 立即 false, 避免后续逻辑误判。
  if (pan.length === 0) {
    return false
  }

  // ========== 2. 剥离常见分隔符（空格 / 连字符） ==========
  // 用临时变量承载规范化后的串, 不修改入参本身（pure 语义）。
  const normalized = pan.replace(SEPARATOR_REGEX, '')

  // ========== 3. 长度与纯数字合法性校验 ==========
  if (normalized.length < MIN_PAN_LENGTH || normalized.length > MAX_PAN_LENGTH) {
    return false
  }
  if (!DIGITS_ONLY_REGEX.test(normalized)) {
    return false
  }

  // ========== 4. Luhn 模 10 累加 ==========
  // 自右向左, 偶数位（0-index 自右数）做 ×2 处理; 乘积 ≥10 时
  // 取个位数字之和（即 (n * 2) - 9, 等价写法更直观）。
  let sum = 0
  // 倒序遍历, 索引 i 是从右侧开始的位数（0 = 校验位本身）。
  for (let i = 0; i < normalized.length; i++) {
    // charCodeAt(48) === '0', 借助 charCode 直接转数字省一次 parseInt。
    const digit = normalized.charCodeAt(normalized.length - 1 - i) - 48
    if (i % 2 === 1) {
      // 偶数位（自右数 i=1, 3, 5...）需要 ×2。
      const doubled = digit * 2
      sum += doubled > 9 ? doubled - 9 : doubled
    } else {
      // 奇数位（自右数 i=0, 2, 4...）保持原值。
      sum += digit
    }
  }

  // ========== 5. 模 10 判定 ==========
  return sum % 10 === 0
}

/**
 * 从完整卡号提取后四位数字字符串。
 *
 * 行为契约:
 *   - 仅在 Luhn 校验通过时返回后四位, 否则返回 null;
 *   - 返回值不包含分隔符, 纯 4 位数字;
 *   - 不修改入参, 不持久化（仅在调用方显式写入存储时才入库）。
 *
 * @param pan 银行卡号原文（仅提取瞬间驻留内存）
 * @returns 后四位数字字符串; Luhn 未通过 / 输入非法时返回 null
 */
export function extractLast4(pan: string): string | null {
  // Luhn 校验是后四位入库的看门人 —— 未通过一律不放行,
  // 防止脏数据污染 Room / IndexedDB 持久层。
  if (!luhnValidate(pan)) {
    return null
  }
  // 剥离分隔符后取末 4 位; 不再做长度校验, 因为 luhnValidate 已保证
  // 长度 ≥ MIN_PAN_LENGTH (13), 末 4 位必然存在。
  const normalized = pan.replace(SEPARATOR_REGEX, '')
  return normalized.slice(-4)
}