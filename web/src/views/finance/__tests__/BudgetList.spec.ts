// ============================================================================
// BudgetList 单元测试（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 验证目标（5 用例，挂载方式照 FinanceSubscriptionList.spec.ts）：
//   1. 空态：store.listBudgets 为空时走 NEmpty 分支（组件源内含 n-empty）；
//   2. 有预算：渲染 scope 中文口径 / 分类 / 阈值百分数 / 状态；
//   3. 零知识：组件展示文本不出现额度数字（amount_minor 1000.00），
//      且组件源码模板不引用 amount_minor 字段；
//   4. 新建入口：data-testid=budget-add-btn 存在且 store 加入预算后可渲染；
//   5. scope 四种值与启停状态映射完整。
//
// 测试策略：项目 vitest 环境为 node（无 jsdom / test-utils），与
// FinanceSubscriptionList.spec.ts 同款：组件内纯展示算法在测试内复制
// 一份锁定，数据源走真实 store（内存 StorageChannel + noop channel），
// 模板结构事实（NEmpty / testid / 不渲染金额）通过读 .vue 源断言。
//
// 关联: web/src/views/finance/BudgetList.vue
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
// 用 Vite 的 ?raw 导入读取组件源码做模板静态断言：
// vitest 原生支持该后缀，且 vite/client 已带类型声明，
// 避免引入 node:fs / node:url（项目未安装 @types/node，vue-tsc 会报错）。
import COMPONENT_SOURCE from '../BudgetList.vue?raw'
import { createPinia, setActivePinia } from 'pinia'
import {
  useFinanceStore,
  type StorageChannel,
  type CryptoChannel,
  type PersistedFinanceState,
} from '../../../stores/finance'
import type { FinanceBudget } from '../../../finance/types'

// ▌store 注入（照 FinanceSubscriptionList.spec.ts）

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

function makeBudget(over: Partial<FinanceBudget> = {}): FinanceBudget {
  return {
    id: 'bud1',
    schema_version: 2,
    scope: 'monthly',
    category: '餐饮',
    amount_minor: '1000.00',
    currency: 'CNY',
    start_ts: 1782619200000,
    end_ts: 1814155199999,
    warning_threshold_pct: 80,
    block_threshold_pct: 100,
    active: true,
    created_at: 1782619200000,
    updated_at: 1782619200000,
    ...over,
  }
}

// ▌与 BudgetList.vue 内同款的纯展示算法（改组件时两处需同步）

const SCOPE_LABEL: Record<FinanceBudget['scope'], string> = {
  monthly: '月度',
  weekly: '周度',
  yearly: '年度',
  custom: '自定义',
}

function categoryLabel(category: string): string {
  return category === 'all' ? '全部分类' : category
}

function thresholdText(b: FinanceBudget): string {
  return `预警 ${b.warning_threshold_pct}% / 拦截 ${b.block_threshold_pct}%`
}

/** 汇总一条预算在列表上会出现的全部中文文本（用于零知识断言）。 */
function renderedTexts(b: FinanceBudget): string[] {
  return [
    SCOPE_LABEL[b.scope],
    categoryLabel(b.category),
    thresholdText(b),
    b.active ? '启用' : '已停用',
  ]
}

beforeEach(() => {
  setActivePinia(createPinia())
  const store = useFinanceStore()
  store._setStorageForTest(memoryChannel())
  store._setChannelForTest(noopCryptoChannel())
})

describe('BudgetList / 空态', () => {
  it('store 无预算 → 列表为空且组件模板使用 NEmpty', () => {
    const store = useFinanceStore()
    expect(store.listBudgets).toEqual([])
    // 组件以 store.listBudgets.length === 0 作为 n-empty 渲染条件。
    expect(store.listBudgets.length === 0).toBe(true)
    expect(COMPONENT_SOURCE).toContain('n-empty')
  })
})

describe('BudgetList / 有预算渲染', () => {
  it('渲染 scope 中文 / 全部分类 / 阈值 / 启用状态', () => {
    const store = useFinanceStore()
    store.addBudget(makeBudget({ id: 'b1', category: 'all' }))
    expect(store.listBudgets).toHaveLength(1)
    const texts = renderedTexts(store.listBudgets[0] as FinanceBudget)
    expect(texts).toContain('月度')
    expect(texts).toContain('全部分类')
    expect(texts).toContain('预警 80% / 拦截 100%')
    expect(texts).toContain('启用')
  })

  it('零知识：展示文本不含额度数字，且源码不引用 amount_minor', () => {
    const store = useFinanceStore()
    // 额度取一个不会与阈值 80 / 100 撞值的数字，确保断言有效。
    store.addBudget(makeBudget({ id: 'b1', amount_minor: '1000.00' }))
    const joined = renderedTexts(store.listBudgets[0] as FinanceBudget).join(' ')
    expect(joined).not.toContain('1000')
    expect(joined).not.toContain('1000.00')
    // 组件任何位置都不得读取 / 插值预算额度字段。
    expect(COMPONENT_SOURCE).not.toContain('amount_minor')
  })

  it('停用预算展示“已停用”，scope 四值中文映射完整', () => {
    const off = makeBudget({ id: 'b2', active: false, scope: 'weekly' })
    expect(renderedTexts(off)).toContain('已停用')
    expect(renderedTexts(off)).toContain('周度')
    expect(SCOPE_LABEL.monthly).toBe('月度')
    expect(SCOPE_LABEL.weekly).toBe('周度')
    expect(SCOPE_LABEL.yearly).toBe('年度')
    expect(SCOPE_LABEL.custom).toBe('自定义')
  })
})

describe('BudgetList / 新建入口', () => {
  it('组件提供 data-testid=budget-add-btn 的新建预算按钮', () => {
    expect(COMPONENT_SOURCE).toContain('data-testid="budget-add-btn"')
    expect(COMPONENT_SOURCE).toContain('#/finance/editor/budget')
    // 加入预算后 store 数据源立刻可见（按钮跳转后的回路一致性）。
    const store = useFinanceStore()
    store.addBudget(makeBudget({ id: 'b3' }))
    expect(store.listBudgets.map((b) => b.id)).toEqual(['b3'])
  })
})
