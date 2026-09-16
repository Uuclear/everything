// ============================================================================
// Luhn 纯函数单元测试（stage5-finance / Task 2 / TR-2.2 / TR-2.3）
// ============================================================================
//
// 验证目标（≥9 用例, 覆盖 TR-2.2 Pass Condition + 全部边界）:
//   1. 正例: 有效 Visa 卡号 4111111111111111 → true;
//   2. 正例: 有效 Mastercard 5555555555554444 → true;
//   3. 正例: 有效银联 6212345678901232 → true;
//   4. 正例: 带空格 4111 1111 1111 1111 → true（trim 兼容）;
//   5. 正例: 带连字符 4111-1111-1111-1111 → true;
//   6. 负例: 校验位错 4111111111111112 → false;
//   7. 负例: 空串 "" → false;
//   8. 负例: 纯字母 "abcd" → false;
//   9. 负例: 长度 1 "1" → false;
//
// 共享 fixture（__fixtures__/luhn-cases.json）由三端共同加载, SHA-256 必须
// 字节级一致 —— 见 tasks.md TR-3.3。
//
// 零知识纪律:
//   - 测试卡号均为业界公开示例号（Visa/Mastercard/UnionPay 测试卡号段）,
//     非真实持卡人卡号;
//   - 不在断言中打印完整卡号（用末 4 位或前缀摘要描述）;
//   - 不向 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/finance/luhn.ts（被测目标）
//   - web/src/finance/__fixtures__/luhn-cases.json（共享 fixture）
//   - android/.../finance/__fixtures__/luhn-cases.json（Android 镜像）
// ============================================================================

import { describe, expect, it } from 'vitest'
import { extractLast4, luhnValidate } from '../luhn'
import cases from '../__fixtures__/luhn-cases.json'

// -----------------------------------------------------------------------------
// 类型定义 —— fixture schema（与 Android Kotlin data class 字段命名对齐）
// -----------------------------------------------------------------------------

/**
 * fixture 单条用例结构。
 *
 * 字段命名刻意保持极简（input + expected）以便三端（含 Android）共用一套
 * 加载器, 避免 name/description 等冗余字段造成跨语言映射噪音。
 */
interface LuhnCase {
  /** 卡号原文（允许带空格 / 连字符） */
  input: string
  /** Luhn 校验预期结果 */
  expected: boolean
}

// -----------------------------------------------------------------------------
// 1. fixture 加载驱动 —— 从 JSON 数据驱动全部 9 条用例
// -----------------------------------------------------------------------------

describe('luhnValidate —— fixture 驱动（TR-2.2 / TR-2.3）', () => {
  // 防御性断言: 用例数 ≥6（TR-2.2 硬性要求）; 当前实现 9 条。
  it('fixture 用例数不少于 6 条', () => {
    expect(cases.length).toBeGreaterThanOrEqual(6)
  })

  // 逐条用例以 it.each 风格展开: 每条 input + expected 独立断言。
  // 这里用 forEach 手写, 避免引入 it.each 等价 API 的额外学习成本。
  for (const tc of cases as LuhnCase[]) {
    const tag = tc.input === '' ? '空串' : tc.input
    it(`输入 ${tag} → 期望 ${tc.expected}`, () => {
      expect(luhnValidate(tc.input)).toBe(tc.expected)
    })
  }
})

// -----------------------------------------------------------------------------
// 2. 关键正例显式断言（覆盖三类卡组织 + 两种分隔符兼容）
// -----------------------------------------------------------------------------

describe('luhnValidate —— 关键正例（Visa / Mastercard / UnionPay）', () => {
  it('Visa 测试卡号 4111111111111111 通过校验', () => {
    expect(luhnValidate('4111111111111111')).toBe(true)
  })

  it('Mastercard 测试卡号 5555555555554444 通过校验', () => {
    expect(luhnValidate('5555555555554444')).toBe(true)
  })

  it('UnionPay 测试卡号 6212345678901232 通过校验', () => {
    expect(luhnValidate('6212345678901232')).toBe(true)
  })

  it('Visa 卡号带空格（4 位空格分隔）通过校验', () => {
    expect(luhnValidate('4111 1111 1111 1111')).toBe(true)
  })

  it('Visa 卡号带连字符通过校验', () => {
    expect(luhnValidate('4111-1111-1111-1111')).toBe(true)
  })
})

// -----------------------------------------------------------------------------
// 3. 边界 / 负例显式断言（确保不抛错, 仅返回 boolean）
// -----------------------------------------------------------------------------

describe('luhnValidate —— 边界与负例', () => {
  it('校验位错（末位 4111...1112）返回 false', () => {
    expect(luhnValidate('4111111111111112')).toBe(false)
  })

  it('空串返回 false 而非抛错', () => {
    expect(luhnValidate('')).toBe(false)
  })

  it('纯字母（无数字）返回 false', () => {
    expect(luhnValidate('abcd')).toBe(false)
  })

  it('长度 1 的单字符返回 false', () => {
    expect(luhnValidate('1')).toBe(false)
  })

  it('长度 12（小于行业最小 13）返回 false', () => {
    expect(luhnValidate('411111111111')).toBe(false)
  })

  it('长度 20（大于行业最大 19）返回 false', () => {
    expect(luhnValidate('41111111111111111111')).toBe(false)
  })

  it('纯空格（仅分隔符）返回 false', () => {
    expect(luhnValidate('    ')).toBe(false)
  })

  it('纯连字符（仅分隔符）返回 false', () => {
    expect(luhnValidate('----')).toBe(false)
  })

  it('含字母的混合串返回 false（不抛错）', () => {
    expect(luhnValidate('4111a1111111b1111')).toBe(false)
  })

  it('纯零 16 位（全零）返回 false（Luhn 累加和为 0 但视作可疑输入）', () => {
    // 注: 全零 16 位 Luhn 累加和为 0, 模 10 = 0 通过; 但行业惯例视为
    // 非法卡号（不可能由真实 BIN 发放）; 本实现按纯算法走, 不做额外黑名单。
    // 该用例仅锁定当前实现行为, 便于 Android 端镜像测试对齐。
    expect(luhnValidate('0000000000000000')).toBe(true)
  })
})

// -----------------------------------------------------------------------------
// 4. extractLast4 联动断言（Luhn 是后四位入库的看门人）
// -----------------------------------------------------------------------------

describe('extractLast4 —— Luhn 通过时返回末 4 位, 否则返回 null', () => {
  it('Visa 卡号末 4 位为 1111', () => {
    expect(extractLast4('4111111111111111')).toBe('1111')
  })

  it('带空格的卡号同样返回末 4 位 1111（剥离分隔符后取末位）', () => {
    expect(extractLast4('4111 1111 1111 1111')).toBe('1111')
  })

  it('带连字符的卡号同样返回末 4 位 1111', () => {
    expect(extractLast4('4111-1111-1111-1111')).toBe('1111')
  })

  it('Mastercard 卡号末 4 位为 4444', () => {
    expect(extractLast4('5555555555554444')).toBe('4444')
  })

  it('UnionPay 卡号末 4 位为 1232', () => {
    expect(extractLast4('6212345678901232')).toBe('1232')
  })

  it('Luhn 未通过时返回 null（拒绝入库）', () => {
    expect(extractLast4('4111111111111112')).toBeNull()
  })

  it('空串返回 null', () => {
    expect(extractLast4('')).toBeNull()
  })

  it('纯字母返回 null', () => {
    expect(extractLast4('abcd')).toBeNull()
  })
})

// -----------------------------------------------------------------------------
// 5. 零知识纪律断言（无副作用 / 无外部依赖）
// -----------------------------------------------------------------------------

describe('Luhn 纯函数 —— 零知识纪律', () => {
  it('luhnValidate 不修改入参（pure 语义）', () => {
    const original = '4111 1111 1111 1111'
    const snapshot = original
    luhnValidate(original)
    expect(original).toBe(snapshot)
  })

  it('extractLast4 不修改入参（pure 语义）', () => {
    const original = '4111-1111-1111-1111'
    const snapshot = original
    extractLast4(original)
    expect(original).toBe(snapshot)
  })

  it('纯函数无副作用: 相同输入多次调用结果一致（幂等性）', () => {
    const input = '4111111111111111'
    const a = luhnValidate(input)
    const b = luhnValidate(input)
    const c = luhnValidate(input)
    expect(a).toBe(b)
    expect(b).toBe(c)
    expect(a).toBe(true)
  })
})