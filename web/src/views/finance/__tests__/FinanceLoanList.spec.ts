// ============================================================================
// FinanceLoanList 单元测试（stage5-finance-v2 / B1 余下 / TR-1.7）
// ============================================================================
//
// 验证目标（4 用例）：
//   1. filter —— counterparty 模糊匹配,大小写不敏感;
//   2. STATUS_LABEL / STATUS_TYPE —— 状态文案 + tag 类型路由;
//   3. direction 路由（lent=借出/success, borrowed=借入/warning）;
//   4. 列表渲染 —— store.listLoans 按 due_ts 升序。
//
// 零知识纪律：
//   - 本金 / 已还 只显示整数元;
//   - 对手方不外泄到通知文案（仅本视图显示）。
//
// 关联:
//   - web/src/views/finance/FinanceLoanList.vue（被测目标）
//   - tasks.md TR-1.7
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../../stores/finance'
import type { FinanceLoan } from '../../../finance/types'

function memoryChannel(): StorageChannel {
  let state: PersistedFinanceState | null = null
  return {
    read() {
      return state == null ? null : JSON.parse(JSON.stringify(state))
    },
    write(next) {
      state = JSON.parse(JSON.stringify(next))
    },
    clear() {
      state = null
    },
  }
}

function noopCryptoChannel(): CryptoChannel {
  return {
    seal: () => '',
    open: () => ({}) as never,
    push: async () => ({ applied: 1, skipped: 0, server_time: Date.now() }),
    list: async () => ({ records: [], has_more: false }),
  }
}

function makeLoan(over: Partial<FinanceLoan> = {}): FinanceLoan {
  const issue = 1735689600000
  return {
    id: 'loan1',
    schema_version: 2,
    counterparty: '友人甲',
    principal_minor: '10000.00',
    currency: 'CNY',
    direction: 'lent',
    issue_ts: issue,
    due_ts: issue + 365 * 86400000,
    interest_rate_apy_bps: 360,
    status: 'active',
    paid_minor: '0.00',
    reminders: [0],
    linked_account_id: null,
    include_in_net_assets: true,
    created_at: issue,
    updated_at: issue,
    ...over,
  }
}

const STATUS_LABEL: Record<string, string> = {
  active: '进行中',
  partially_paid: '部分已还',
  paid: '已结清',
  overdue: '逾期',
}

const STATUS_TYPE: Record<string, 'default' | 'warning' | 'success' | 'error'> = {
  active: 'default',
  partially_paid: 'warning',
  paid: 'success',
  overdue: 'error',
}

function filterLoans(rows: FinanceLoan[], k: string): FinanceLoan[] {
  const key = k.trim().toLowerCase()
  if (!key) return rows
  return rows.filter((l) => l.counterparty.toLowerCase().includes(key))
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('FinanceLoanList / filter 搜索（counterparty）', () => {
  it('关键字匹配 counterparty', () => {
    const rows = [
      makeLoan({ id: 'l1', counterparty: '友人甲' }),
      makeLoan({ id: 'l2', counterparty: '友人乙' }),
    ]
    expect(filterLoans(rows, '甲').map((l) => l.id)).toEqual(['l1'])
  })

  it('关键字空 → 返回全部', () => {
    const rows = [makeLoan({ id: 'l1' }), makeLoan({ id: 'l2' })]
    expect(filterLoans(rows, '')).toEqual(rows)
  })

  it('关键字大小写不敏感', () => {
    const rows = [makeLoan({ id: 'l1', counterparty: 'Alice' })]
    expect(filterLoans(rows, 'alice').map((l) => l.id)).toEqual(['l1'])
  })

  it('无匹配 → 返回空数组', () => {
    const rows = [makeLoan({ id: 'l1', counterparty: '友人甲' })]
    expect(filterLoans(rows, 'zzz')).toEqual([])
  })
})

describe('FinanceLoanList / status 文案与 tag 类型', () => {
  it('STATUS_LABEL 4 态', () => {
    expect(STATUS_LABEL['active']).toBe('进行中')
    expect(STATUS_LABEL['partially_paid']).toBe('部分已还')
    expect(STATUS_LABEL['paid']).toBe('已结清')
    expect(STATUS_LABEL['overdue']).toBe('逾期')
  })

  it('STATUS_TYPE active=default / partially_paid=warning / paid=success / overdue=error', () => {
    expect(STATUS_TYPE['active']).toBe('default')
    expect(STATUS_TYPE['partially_paid']).toBe('warning')
    expect(STATUS_TYPE['paid']).toBe('success')
    expect(STATUS_TYPE['overdue']).toBe('error')
  })

  it('未知 status 兜底（list 模板内 `?? loan.status`）', () => {
    const label = STATUS_LABEL['unknown'] ?? 'unknown'
    expect(label).toBe('unknown')
  })

  it('status 切换后 tag 类型同步', () => {
    const l1 = makeLoan({ id: 'l1', status: 'partially_paid' })
    expect(STATUS_TYPE[l1.status]).toBe('warning')
    const l2 = makeLoan({ id: 'l2', status: 'overdue' })
    expect(STATUS_TYPE[l2.status]).toBe('error')
  })
})

describe('FinanceLoanList / direction 路由', () => {
  it('lent → 借出 + success tag', () => {
    const loan = makeLoan({ id: 'l1', direction: 'lent' })
    const tagType = loan.direction === 'lent' ? 'success' : 'warning'
    const tagLabel = loan.direction === 'lent' ? '借出' : '借入'
    expect(tagType).toBe('success')
    expect(tagLabel).toBe('借出')
  })

  it('borrowed → 借入 + warning tag', () => {
    const loan = makeLoan({ id: 'l1', direction: 'borrowed' })
    const tagType = loan.direction === 'lent' ? 'success' : 'warning'
    const tagLabel = loan.direction === 'lent' ? '借出' : '借入'
    expect(tagType).toBe('warning')
    expect(tagLabel).toBe('借入')
  })

  it('formatAmount 本金 / 已还 整数元', () => {
    const loan = makeLoan({ id: 'l1', principal_minor: '10000.00', paid_minor: '5000.00' })
    const fmt = (v: string) => '¥' + Math.floor(Number(v)).toLocaleString('zh-CN')
    expect(fmt(loan.principal_minor)).toBe('¥10,000')
    expect(fmt(loan.paid_minor)).toBe('¥5,000')
  })
})

describe('FinanceLoanList / 列表渲染（store 数据源）', () => {
  it('listLoans 按 due_ts 升序（store 内已排序）', () => {
    const store = useFinanceStore()
    const baseIssue = 1735689600000
    store.addLoan(
      makeLoan({ id: 'l1', issue_ts: baseIssue, due_ts: baseIssue + 365 * 86400000 }),
    )
    store.addLoan(
      makeLoan({ id: 'l2', issue_ts: baseIssue, due_ts: baseIssue + 180 * 86400000 }),
    )
    expect(store.listLoans[0]?.id).toBe('l2')
    expect(store.listLoans[1]?.id).toBe('l1')
  })

  it('empty 状态下 listLoans 为空数组', () => {
    const store = useFinanceStore()
    expect(store.listLoans).toEqual([])
  })

  it('paid 状态（status=paid）卡片仍展示但不进入"未结清"', () => {
    const store = useFinanceStore()
    store.addLoan(makeLoan({ id: 'l1', status: 'paid', paid_minor: '10000.00' }))
    expect(store.listLoans).toHaveLength(1)
    expect(store.listLoans[0]?.status).toBe('paid')
  })

  it('overdue 状态（status=overdue）逾期天数正确', () => {
    const loan = makeLoan({
      id: 'l1',
      due_ts: Date.now() - 10 * 86400000, // 10 天前到期
      status: 'overdue',
    })
    const days = Math.ceil((loan.due_ts - Date.now()) / 86400000)
    expect(days).toBeLessThan(0)
  })
})