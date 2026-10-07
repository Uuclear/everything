import { describe, expect, it } from 'vitest'
import type { DecryptedRecord, IdentityData } from '../../types/vault'
import type { Item } from '../../items/types'
import type { FinanceCard, FinanceTx } from '../../finance/types'
import {
  financeCardRemindersWithinDays,
  globalSearch,
  identitiesExpiringWithinDays,
  occurrencesOnLocalDay,
} from '../aggregate'
import { localDayKey, type Occurrence } from '../../events/expand'

function identityRecord(id: string, title: string, expiresOn: string): DecryptedRecord {
  const data: IdentityData = {
    title,
    kind: 'generic',
    expires_on: expiresOn,
  }
  return {
    id,
    module: 'identity',
    type: 'generic',
    version: 1,
    createdAt: 0,
    updatedAt: 0,
    deleted: false,
    data,
  }
}

describe('vault/aggregate', () => {
  it('globalSearch 仅匹配允许字段，不含证号', () => {
    const identities = [
      identityRecord('a', '护照', '2030-01-01'),
    ]
    identities[0].data = { ...identities[0].data as IdentityData, name: '张三', number: 'SECRET123' }
    const hits = globalSearch('张三', {
      identities,
      items: [],
      financeTxs: [],
      passRecords: [],
    })
    expect(hits).toHaveLength(1)
    expect(hits[0].module).toBe('identity')
    const noHit = globalSearch('secret123', {
      identities,
      items: [],
      financeTxs: [],
      passRecords: [],
    })
    expect(noHit).toHaveLength(0)
  })

  it('identitiesExpiringWithinDays 边界含当天、排除已过期', () => {
    const now = new Date('2026-06-15T12:00:00')
    const rows = identitiesExpiringWithinDays(
      [
        identityRecord('expired', '旧证', '2026-06-14'),
        identityRecord('today', '今日', '2026-06-15'),
        identityRecord('week', '一周', '2026-06-22'),
        identityRecord('far', '远期', '2026-08-01'),
      ],
      7,
      now,
    )
    expect(rows.map((r) => r.record.id)).toEqual(['today', 'week'])
    expect(rows[0].days).toBe(0)
  })

  it('occurrencesOnLocalDay 按本地日过滤', () => {
    const occs: Occurrence[] = [
      {
        instance_id: '1',
        rule_id: 'r1',
        start_ts: new Date('2026-06-15T10:00:00').getTime(),
        end_ts: new Date('2026-06-15T11:00:00').getTime(),
        all_day: false,
        color: 'blue',
        title: 'A',
        original_start_ts: 0,
      },
      {
        instance_id: '2',
        rule_id: 'r2',
        start_ts: new Date('2026-06-16T10:00:00').getTime(),
        end_ts: new Date('2026-06-16T11:00:00').getTime(),
        all_day: false,
        color: 'green',
        title: 'B',
        original_start_ts: 0,
      },
    ]
    const key = localDayKey(occs[0].start_ts)
    const filtered = occurrencesOnLocalDay(occs, key)
    expect(filtered).toHaveLength(1)
    expect(filtered[0].title).toBe('A')
  })

  it('financeCardRemindersWithinDays 跳过归档卡', () => {
    const now = Date.UTC(2026, 5, 1, 8, 0, 0)
    const baseCard = {
      schema_version: 1 as const,
      issuer: '测试行',
      last4: '4242',
      currency: 'CNY' as const,
      credit_limit: '10000',
      color: 'blue' as const,
      include_in_net_assets: true,
      billing_day: 5,
      due_day: 3,
      created_at: 0,
      updated_at: 0,
    }
    const cards: FinanceCard[] = [
      { id: 'c1', name: '主卡', kind: 'credit', archived: true, ...baseCard },
      { id: 'c2', name: '副卡', kind: 'credit', archived: false, ...baseCard, last4: '1111' },
    ]
    const rows = financeCardRemindersWithinDays(cards, 30, now)
    expect(rows.every((r) => r.id !== 'c1')).toBe(true)
  })

  it('globalSearch 匹配财务流水备注', () => {
    const txs: FinanceTx[] = [
      {
        id: 'tx1',
        schema_version: 1,
        kind: 'expense',
        amount: '10.00',
        category: 'food',
        account_id: 'a',
        occurred_at: 0,
        note: '超市买菜',
        color: 'blue',
        created_at: 0,
        updated_at: 0,
      },
    ]
    const hits = globalSearch('超市', {
      identities: [],
      items: [] as Item[],
      financeTxs: txs,
      passRecords: [],
    })
    expect(hits[0].module).toBe('finance_tx')
  })
})
