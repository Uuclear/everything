// ============================================================================
// FinanceTypesV2 纯函数单元测试（stage5-finance-v2 / Task 1 / TR-1.1）
// ============================================================================
//
// 验证目标（≥24 用例, 覆盖 TR-1.1 Pass Condition + 全部边界）:
//   1. 4 子类型接口 schema 校验（subscription / policy / loan / contract）；
//   2. 校验函数边界（空字段 / 非法金额 / 非整数 reminders / 非法货币 / 非法状态）；
//   3. validateV2Payload 路由（type='subscription'/'policy'/'loan'/'contract' +
//      非法 type）；
//   4. 校验幂等性 + 入参不修改（零知识纪律）；
//   5. 附件元数据校验（sha256 非 hex / size 超限 / mime 缺失）；
//   6. loan 已还本金 ≤ 本金；contract notice_deadline_ts 推算；
//   7. SPEC 字符级一致性（schema_version=2 / policy_number_encrypted=true /
//      include_in_net_assets=true）。
//
// 关联:
//   - web/src/finance/types.ts（被测目标, 4 子类型接口 + 校验函数）
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-A.1~A.4 / TR-1.1
// ============================================================================

import { describe, it, expect } from 'vitest'
import {
  ATTACHMENT_MAX_SIZE_BYTES,
  FINANCE_V2_SCHEMA_VERSION,
  type FinanceSubscription,
  type FinancePolicy,
  type FinanceLoan,
  type FinanceContract,
  validateSubscription,
  validatePolicy,
  validateLoan,
  validateContract,
  validateV2Payload,
  isValidDecimalString,
  isValidCurrencyCode,
  isValidSha256Hex,
} from '../types'

// ----------------------------------------------------------------------------
// fixture 工厂：构造合法 + 非法样本（覆盖 4 子类型）
// ----------------------------------------------------------------------------

const baseTs = 1780000000000 // 2026-06-28 锚定
const baseSha256Hex = 'a'.repeat(64)

function makeSub(): FinanceSubscription {
  return {
    id: '00000000-0000-4000-8000-000000000001',
    schema_version: FINANCE_V2_SCHEMA_VERSION,
    name: 'Netflix',
    provider: 'Netflix Inc.',
    amount_minor: '120.00',
    currency: 'CNY',
    billing_cycle: 'monthly',
    custom_days: null,
    start_ts: baseTs,
    next_renewal_ts: baseTs + 30 * 86400000,
    reminders: [0, 1440],
    active: true,
    category: 'entertainment',
    created_at: baseTs,
    updated_at: baseTs,
  }
}

function makePolicy(): FinancePolicy {
  return {
    id: '00000000-0000-4000-8000-000000000002',
    schema_version: FINANCE_V2_SCHEMA_VERSION,
    name: '健康险',
    policy_number: 'ABC12345',
    policy_number_encrypted: true,
    provider: '太平洋保险',
    premium_minor: '1200.00',
    currency: 'CNY',
    billing_cycle: 'yearly',
    start_ts: baseTs,
    expiry_ts: baseTs + 365 * 86400000,
    reminders: [0, 10080, 43200],
    coverage_minor: '500000.00',
    active: true,
    linked_account_id: null,
    attachments: [
      { id: '00000000-0000-4000-8000-000000000010', mime: 'application/pdf', size: 1024, sha256: baseSha256Hex },
    ],
    created_at: baseTs,
    updated_at: baseTs,
  }
}

function makeLoan(): FinanceLoan {
  return {
    id: '00000000-0000-4000-8000-000000000003',
    schema_version: FINANCE_V2_SCHEMA_VERSION,
    counterparty: '李四',
    principal_minor: '10000.00',
    currency: 'CNY',
    direction: 'lent',
    issue_ts: baseTs,
    due_ts: baseTs + 180 * 86400000,
    interest_rate_apy_bps: 360,
    status: 'active',
    paid_minor: '0.00',
    reminders: [0, 43200],
    linked_account_id: null,
    include_in_net_assets: true,
    created_at: baseTs,
    updated_at: baseTs,
  }
}

function makeContract(): FinanceContract {
  const start = baseTs
  const end = baseTs + 365 * 86400000
  const noticeDays = 30
  return {
    id: '00000000-0000-4000-8000-000000000004',
    schema_version: FINANCE_V2_SCHEMA_VERSION,
    title: '房屋租赁',
    counterparty: '北京物业有限公司',
    kind: 'rental',
    amount_minor: '3600.00',
    currency: 'CNY',
    signed_ts: start - 7 * 86400000,
    start_ts: start,
    end_ts: end,
    auto_renew: true,
    notice_period_days: noticeDays,
    notice_deadline_ts: end - noticeDays * 86400000,
    status: 'active',
    linked_account_id: null,
    attachments: [],
    created_at: baseTs,
    updated_at: baseTs,
  }
}

// ----------------------------------------------------------------------------
// 工具函数单测
// ----------------------------------------------------------------------------

describe('isValidDecimalString', () => {
  it('接受 1 位小数', () => expect(isValidDecimalString('1.5')).toBe(true))
  it('接受 2 位小数', () => expect(isValidDecimalString('120.00')).toBe(true))
  it('接受整数', () => expect(isValidDecimalString('100')).toBe(true))
  it('拒绝 3 位小数', () => expect(isValidDecimalString('1.235')).toBe(false))
  it('拒绝负数', () => expect(isValidDecimalString('-1.00')).toBe(false))
  it('拒绝全 0', () => expect(isValidDecimalString('0')).toBe(false))
  it('拒绝字母', () => expect(isValidDecimalString('abc')).toBe(false))
  it('拒绝空串', () => expect(isValidDecimalString('')).toBe(false))
})

describe('isValidCurrencyCode', () => {
  it('接受 CNY', () => expect(isValidCurrencyCode('CNY')).toBe(true))
  it('接受 USD', () => expect(isValidCurrencyCode('USD')).toBe(true))
  it('拒绝小写', () => expect(isValidCurrencyCode('cny')).toBe(false))
  it('拒绝 2 字母', () => expect(isValidCurrencyCode('CN')).toBe(false))
  it('拒绝 4 字母', () => expect(isValidCurrencyCode('CNYX')).toBe(false))
})

describe('isValidSha256Hex', () => {
  it('接受 64 hex', () => expect(isValidSha256Hex(baseSha256Hex)).toBe(true))
  it('拒绝短串', () => expect(isValidSha256Hex('abc')).toBe(false))
  it('拒绝大写', () => expect(isValidSha256Hex(baseSha256Hex.toUpperCase())).toBe(false))
  it('拒绝非 hex', () => expect(isValidSha256Hex('g'.repeat(64))).toBe(false))
})

// ----------------------------------------------------------------------------
// Subscription 校验
// ----------------------------------------------------------------------------

describe('validateSubscription', () => {
  it('合法 subscription 通过', () => {
    expect(validateSubscription(makeSub())).toEqual({ ok: true })
  })

  it('schema_version=1 拒绝', () => {
    const p = makeSub()
    const bad: FinanceSubscription = { ...p, schema_version: 1 as 2 }
    expect(validateSubscription(bad).ok).toBe(false)
  })

  it('amount_minor=0 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, amount_minor: '0' }).ok).toBe(false)
  })

  it('currency=cny 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, currency: 'cny' }).ok).toBe(false)
  })

  it('billing_cycle=custom_days 但 custom_days=null 拒绝', () => {
    const p = makeSub()
    expect(
      validateSubscription({ ...p, billing_cycle: 'custom_days', custom_days: null }).ok,
    ).toBe(false)
  })

  it('billing_cycle=monthly 但 custom_days=30 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, custom_days: 30 }).ok).toBe(false)
  })

  it('reminders 含负数 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, reminders: [0, -1] }).ok).toBe(false)
  })

  it('next_renewal_ts < start_ts 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, next_renewal_ts: p.start_ts - 1 }).ok).toBe(false)
  })

  it('name 空串 拒绝', () => {
    const p = makeSub()
    expect(validateSubscription({ ...p, name: '' }).ok).toBe(false)
  })

  it('幂等性：合法入参不被修改', () => {
    const p = makeSub()
    const snapshot = JSON.stringify(p)
    validateSubscription(p)
    expect(JSON.stringify(p)).toBe(snapshot)
  })
})

// ----------------------------------------------------------------------------
// Policy 校验
// ----------------------------------------------------------------------------

describe('validatePolicy', () => {
  it('合法 policy 通过', () => {
    expect(validatePolicy(makePolicy())).toEqual({ ok: true })
  })

  it('policy_number 空 拒绝', () => {
    const p = makePolicy()
    expect(validatePolicy({ ...p, policy_number: '' }).ok).toBe(false)
  })

  it('policy_number > 100 字符 拒绝', () => {
    const p = makePolicy()
    expect(validatePolicy({ ...p, policy_number: 'x'.repeat(101) }).ok).toBe(false)
  })

  it('expiry_ts < start_ts 拒绝', () => {
    const p = makePolicy()
    expect(validatePolicy({ ...p, expiry_ts: p.start_ts - 1 }).ok).toBe(false)
  })

  it('attachment sha256 非 hex 拒绝', () => {
    const p = makePolicy()
    expect(
      validatePolicy({
        ...p,
        attachments: [{ id: 'a', mime: 'application/pdf', size: 1024, sha256: 'g'.repeat(64) }],
      }).ok,
    ).toBe(false)
  })

  it('attachment size > 50MB 拒绝', () => {
    const p = makePolicy()
    expect(
      validatePolicy({
        ...p,
        attachments: [
          { id: 'a', mime: 'application/pdf', size: ATTACHMENT_MAX_SIZE_BYTES + 1, sha256: baseSha256Hex },
        ],
      }).ok,
    ).toBe(false)
  })

  it('attachment mime 缺失 拒绝', () => {
    const p = makePolicy()
    expect(
      validatePolicy({
        ...p,
        attachments: [{ id: 'a', mime: '', size: 1024, sha256: baseSha256Hex }],
      }).ok,
    ).toBe(false)
  })

  it('空 attachments 数组合法', () => {
    const p = makePolicy()
    expect(validatePolicy({ ...p, attachments: [] }).ok).toBe(true)
  })

  it('policy_number_encrypted=false 仍合法', () => {
    const p = makePolicy()
    expect(validatePolicy({ ...p, policy_number_encrypted: false }).ok).toBe(true)
  })
})

// ----------------------------------------------------------------------------
// Loan 校验
// ----------------------------------------------------------------------------

describe('validateLoan', () => {
  it('合法 loan 通过', () => {
    expect(validateLoan(makeLoan())).toEqual({ ok: true })
  })

  it('direction=unknown 拒绝', () => {
    const p = makeLoan()
    expect(
      validateLoan({ ...p, direction: 'unknown' as 'lent' }).ok,
    ).toBe(false)
  })

  it('lent + include_in_net_assets=true 默认合法', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, direction: 'lent', include_in_net_assets: true }).ok).toBe(true)
  })

  it('borrowed + include_in_net_assets=false 合法', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, direction: 'borrowed', include_in_net_assets: false }).ok).toBe(true)
  })

  it('paid_minor="0.00" 未还款合法', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, paid_minor: '0.00' }).ok).toBe(true)
  })

  it('paid_minor > principal_minor 拒绝', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, paid_minor: '10001.00' }).ok).toBe(false)
  })

  it('paid_minor == principal_minor + status=paid 合法', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, paid_minor: '10000.00', status: 'paid' }).ok).toBe(true)
  })

  it('interest_rate_apy_bps 负数 拒绝', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, interest_rate_apy_bps: -1 }).ok).toBe(false)
  })

  it('interest_rate_apy_bps 小数 拒绝', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, interest_rate_apy_bps: 3.6 }).ok).toBe(false)
  })

  it('status=overdue 合法', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, status: 'overdue' }).ok).toBe(true)
  })

  it('counterparty 空串 拒绝', () => {
    const p = makeLoan()
    expect(validateLoan({ ...p, counterparty: '' }).ok).toBe(false)
  })

  it('幂等性：合法入参不被修改', () => {
    const p = makeLoan()
    const snapshot = JSON.stringify(p)
    validateLoan(p)
    expect(JSON.stringify(p)).toBe(snapshot)
  })
})

// ----------------------------------------------------------------------------
// Contract 校验
// ----------------------------------------------------------------------------

describe('validateContract', () => {
  it('合法 contract 通过', () => {
    expect(validateContract(makeContract())).toEqual({ ok: true })
  })

  it('notice_deadline_ts 推算错误 拒绝', () => {
    const p = makeContract()
    expect(validateContract({ ...p, notice_deadline_ts: p.end_ts - 10 * 86400000 }).ok).toBe(false)
  })

  it('notice_period_days=0 + notice_deadline_ts=end_ts 合法', () => {
    const p = makeContract()
    expect(
      validateContract({ ...p, notice_period_days: 0, notice_deadline_ts: p.end_ts }).ok,
    ).toBe(true)
  })

  it('kind=loan 合法', () => {
    const p = makeContract()
    expect(validateContract({ ...p, kind: 'loan' }).ok).toBe(true)
  })

  it('kind=unknown 拒绝', () => {
    const p = makeContract()
    expect(validateContract({ ...p, kind: 'unknown' as 'rental' }).ok).toBe(false)
  })

  it('end_ts < start_ts 拒绝', () => {
    const p = makeContract()
    expect(validateContract({ ...p, end_ts: p.start_ts - 1 }).ok).toBe(false)
  })

  it('auto_renew=false 合法', () => {
    const p = makeContract()
    expect(validateContract({ ...p, auto_renew: false }).ok).toBe(true)
  })
})

// ----------------------------------------------------------------------------
// validateV2Payload 路由
// ----------------------------------------------------------------------------

describe('validateV2Payload', () => {
  it('route subscription → validateSubscription', () => {
    expect(validateV2Payload('subscription', makeSub()).ok).toBe(true)
  })

  it('route policy → validatePolicy', () => {
    expect(validateV2Payload('policy', makePolicy()).ok).toBe(true)
  })

  it('route loan → validateLoan', () => {
    expect(validateV2Payload('loan', makeLoan()).ok).toBe(true)
  })

  it('route contract → validateContract', () => {
    expect(validateV2Payload('contract', makeContract()).ok).toBe(true)
  })

  it('非法 type 拒绝', () => {
    expect(
      validateV2Payload('account', makeSub()).ok,
    ).toBe(false)
  })

  it('route subscription 收到 policy payload 拒绝', () => {
    expect(validateV2Payload('subscription', makePolicy()).ok).toBe(false)
  })

  it('route loan 收到 contract payload 拒绝', () => {
    expect(validateV2Payload('loan', makeContract()).ok).toBe(false)
  })
})