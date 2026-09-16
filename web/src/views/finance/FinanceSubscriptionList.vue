<script setup lang="ts">
// ============================================================================
// 订阅列表视图（stage5-finance-v2 / TR-1.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.4（4 个 v2 子类型列表之一）
// 路径: web/src/views/finance/FinanceSubscriptionList.vue
// 作用: 渲染订阅列表 —— 卡片式;按 next_renewal_ts 升序;
//       支持搜索 (name / provider);空态 + 新建按钮;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listSubscriptions（store 内已按到期时间排序）
//   2. 搜索:按 name / provider 模糊匹配;
//   3. 卡片:显示 name / provider / 金额(¥ + 千分位整数) / currency / 下次扣费
//      距今天数;
//   4. 点击卡片 → 跳转到 /finance/editor/subscription/:id;
//   5. 零知识纪律 —— amount_minor 只显示整数元;保单号/账号等敏感字段不外泄;
//   6. 不修改 store —— 仅消费。
//
// 关联:
//   - tasks.md TR-1.4
//   - web/src/stores/finance.ts
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NInput, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceSubscription } from '../../finance/types'

const store = useFinanceStore()

// ========== 搜索 ==========
const keyword = ref('')

const filtered = computed<FinanceSubscription[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return store.listSubscriptions
  return store.listSubscriptions.filter(
    (s) => s.name.toLowerCase().includes(k) || s.provider.toLowerCase().includes(k),
  )
})

// ========== 跳转 ==========
function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/subscription'
    return
  }
  window.location.hash = '#/finance/editor/subscription/' + id
}

// ========== 工具 ==========
function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

/** 距下次扣费剩余天数（负数 = 已过期）。 */
function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

const CATEGORY_LABEL: Record<string, string> = {
  entertainment: '娱乐',
  productivity: '效率',
  utility: '生活缴费',
  other: '其他',
}

const CYCLE_LABEL: Record<string, string> = {
  monthly: '月付',
  quarterly: '季付',
  yearly: '年付',
  custom_days: '自定义',
}
</script>

<template>
  <div class="sub-list">
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索订阅名称 / 服务商"
        clearable
        size="small"
      />
    </div>

    <n-empty
      v-if="filtered.length === 0"
      :description="keyword ? '没有匹配的订阅' : '还没有订阅, 点击右上角新建'"
    />

    <div v-else class="grid">
      <n-card
        v-for="sub in filtered"
        :key="sub.id"
        hoverable
        class="card"
        :class="{ inactive: !sub.active }"
        @click="gotoEditor(sub.id)"
      >
        <template #header>
          <span class="title">{{ sub.name }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" type="info">
            {{ CATEGORY_LABEL[sub.category] ?? sub.category }}
          </n-tag>
        </template>
        <div class="provider">{{ sub.provider }}</div>
        <div class="amount">{{ formatAmount(sub.amount_minor) }} / {{ CYCLE_LABEL[sub.billing_cycle] ?? sub.billing_cycle }}</div>
        <div class="sub">
          <span>{{ sub.currency }}</span>
          <span class="dot">·</span>
          <span>下次扣费 {{ daysUntil(sub.next_renewal_ts) >= 0 ? daysUntil(sub.next_renewal_ts) + ' 天后' : '已过期 ' + (-daysUntil(sub.next_renewal_ts)) + ' 天' }}</span>
        </div>
      </n-card>
    </div>
  </div>
</template>

<style scoped>
.sub-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  max-width: 360px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
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
.card.inactive {
  opacity: 0.6;
}
.title {
  font-weight: 600;
  margin-right: 6px;
}
.provider {
  font-size: 13px;
  color: #374151;
  margin: 4px 0;
}
.amount {
  font-size: 18px;
  font-weight: 700;
  margin: 4px 0;
}
.sub {
  font-size: 12px;
  color: #6b7280;
}
.dot {
  margin: 0 6px;
}
</style>