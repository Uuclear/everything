<script setup lang="ts">
// ============================================================================
// 应收/应付借款列表视图（stage5-finance-v2 / TR-1.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.4（4 个 v2 子类型列表之一）
// 路径: web/src/views/finance/FinanceLoanList.vue
// 作用: 渲染借款列表 —— 按 due_ts 升序;支持搜索 (counterparty);
//       显示本金 / 已还 / 状态 / 距到期天数;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listLoans（store 内已按到期时间排序）
//   2. 搜索:按 counterparty 模糊匹配;
//   3. 卡片:对手方 / 方向 / 本金 / 已还 / 状态 / 到期天数;
//   4. 点击卡片 → /finance/editor/loan/:id;
//   5. 零知识纪律 —— 对手方不外泄到通知文案（仅本视图显示）;
//      本金 / 已还 只显示整数元;
//
// 关联:
//   - tasks.md TR-1.4
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NInput, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceLoan } from '../../finance/types'

const store = useFinanceStore()

const keyword = ref('')

const filtered = computed<FinanceLoan[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return store.listLoans
  return store.listLoans.filter((l) => l.counterparty.toLowerCase().includes(k))
})

function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/loan'
    return
  }
  window.location.hash = '#/finance/editor/loan/' + id
}

function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
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
</script>

<template>
  <div class="loan-list">
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索对手方"
        clearable
        size="small"
      />
    </div>

    <n-empty
      v-if="filtered.length === 0"
      :description="keyword ? '没有匹配的借款' : '还没有借款, 点击右上角新建'"
    />

    <div v-else class="grid">
      <n-card
        v-for="loan in filtered"
        :key="loan.id"
        hoverable
        class="card"
        @click="gotoEditor(loan.id)"
      >
        <template #header>
          <span class="title">{{ loan.counterparty }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" :type="loan.direction === 'lent' ? 'success' : 'warning'">
            {{ loan.direction === 'lent' ? '借出' : '借入' }}
          </n-tag>
          <n-tag size="tiny" :bordered="false" :type="STATUS_TYPE[loan.status] ?? 'default'" style="margin-left:6px">
            {{ STATUS_LABEL[loan.status] ?? loan.status }}
          </n-tag>
        </template>
        <div class="amount">本金 {{ formatAmount(loan.principal_minor) }}</div>
        <div class="paid">已还 {{ formatAmount(loan.paid_minor) }}</div>
        <div class="sub">
          <span>{{ loan.currency }}</span>
          <span class="dot">·</span>
          <span>到期 {{ daysUntil(loan.due_ts) >= 0 ? daysUntil(loan.due_ts) + ' 天后' : '已逾期 ' + (-daysUntil(loan.due_ts)) + ' 天' }}</span>
        </div>
      </n-card>
    </div>
  </div>
</template>

<style scoped>
.loan-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  max-width: 360px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}
.card {
  cursor: pointer;
  border-radius: 10px;
  transition: transform 0.12s;
}
.card:hover {
  transform: translateY(-2px);
}
.title {
  font-weight: 600;
  margin-right: 6px;
}
.amount {
  font-size: 18px;
  font-weight: 700;
  margin: 4px 0 2px;
}
.paid {
  font-size: 14px;
  color: #374151;
}
.sub {
  font-size: 12px;
  color: #6b7280;
  margin-top: 4px;
}
.dot {
  margin: 0 6px;
}
</style>