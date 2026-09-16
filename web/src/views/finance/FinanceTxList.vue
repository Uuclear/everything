<script setup lang="ts">
// ============================================================================
// 流水列表视图（stage5-finance / Task 9 / TR-9.5）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.5
// 路径: web/src/views/finance/FinanceTxList.vue
// 作用: 渲染流水列表 —— 按日期分组（YYYY-MM-DD 本地日历）;每条流水一个
//       NThing —— amount（千分位 + ¥）+ kind + category chip + 备注;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listTxs;
//   2. 分组 —— 按本地日历日 (YYYY-MM-DD) 分组, 同日内的流水合并到一个
//      NCollapse / 自定义分组容器中;
//   3. 分类 Chip —— 渲染为 NTag, 不显示完整分类字符串长度;
//   4. 零知识纪律:
//      - 金额数字外露（千分位 + ¥, 不显示小数点精度）;
//      - 备注仅在本视图渲染;不进入通知/日志;
//      - 不渲染具体日期数字 —— 分组标签展示"YYYY-MM-DD 形式"(用户主动查看
//        财务详情时才外露,与通知文案不渲染具体日期的纪律分开);
//   5. 不修改 financeStore —— 仅消费。
//
// 关联:
//   - tasks.md TR-9.5
// ============================================================================

import { computed } from 'vue'
import { NEmpty, NTag, NList, NListItem, NThing } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceTx } from '../../finance/types'

const store = useFinanceStore()

// ========== 派生 ==========
const txs = computed(() => store.listTxs)

// ========== 本地日历日分组 ==========
// 与 aggregator.yearMonthOf 同口径 —— CST 本地日历分量。
function dayKey(ts: number): string {
  const TZ_OFFSET_MIN = -new Date(1780000000000).getTimezoneOffset()
  const shifted = ts + TZ_OFFSET_MIN * 60_000
  const d = new Date(shifted)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${dd}`
}

interface Group {
  dayKey: string
  items: FinanceTx[]
}

const groups = computed<Group[]>(() => {
  const map = new Map<string, FinanceTx[]>()
  for (const tx of txs.value) {
    const k = dayKey(tx.occurred_at)
    if (!map.has(k)) map.set(k, [])
    map.get(k)!.push(tx)
  }
  // 按 dayKey 降序排列（最新在前）
  return Array.from(map.entries())
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .map(([k, v]) => ({ dayKey: k, items: v }))
})

// ========== 工具 ==========
// 金额格式化 —— decimal-as-string → ¥ + 千分位 + 收入/支出符号;
function formatAmount(tx: FinanceTx): string {
  const n = Number(tx.amount)
  if (!Number.isFinite(n)) return '¥0'
  const yuan = Math.floor(n).toLocaleString('zh-CN')
  if (tx.kind === 'income') return '+¥' + yuan
  if (tx.kind === 'expense') return '-¥' + yuan
  return '¥' + yuan
}

const KIND_LABEL: Record<string, string> = {
  income: '收入',
  expense: '支出',
  transfer: '转账',
}
const KIND_TYPE: Record<string, 'success' | 'error' | 'info'> = {
  income: 'success',
  expense: 'error',
  transfer: 'info',
}
function kindLabel(k: string): string {
  return KIND_LABEL[k] ?? k
}
function kindType(k: string): 'success' | 'error' | 'info' {
  return KIND_TYPE[k] ?? 'info'
}

// ========== 跳转 ==========
function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/tx'
    return
  }
  window.location.hash = '#/finance/editor/tx/' + id
}
</script>

<template>
  <div class="tx-list">
    <n-empty
      v-if="txs.length === 0"
      description="还没有流水,点击右上角新建"
    />

    <div v-else class="groups">
      <div v-for="g in groups" :key="g.dayKey" class="group">
        <div class="day-head">{{ g.dayKey }}</div>
        <n-list hoverable clickable bordered>
          <n-list-item
            v-for="tx in g.items"
            :key="tx.id"
            @click="gotoEditor(tx.id)"
          >
            <n-thing>
              <template #header>
                <n-tag size="tiny" :bordered="false" :type="kindType(tx.kind)">
                  {{ kindLabel(tx.kind) }}
                </n-tag>
                <span class="category">{{ tx.category }}</span>
              </template>
              <template #header-extra>
                <span class="amount" :class="tx.kind">{{ formatAmount(tx) }}</span>
              </template>
              <template #description>
                <span class="sub">{{ tx.note || '—' }}</span>
              </template>
            </n-thing>
          </n-list-item>
        </n-list>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tx-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.day-head {
  font-size: 13px;
  color: #374151;
  font-weight: 600;
  margin-bottom: 4px;
  letter-spacing: 0.5px;
}
.category {
  margin-left: 6px;
  font-weight: 500;
}
.amount {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 0.5px;
  font-size: 14px;
}
.amount.income {
  color: #15803d;
}
.amount.expense {
  color: #b91c1c;
}
.amount.transfer {
  color: #1d4ed8;
}
.sub {
  font-size: 12px;
  color: #6b7280;
}
</style>
