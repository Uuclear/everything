<script setup lang="ts">
// ============================================================================
// 保单列表视图（stage5-finance-v2 / TR-1.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.4（4 个 v2 子类型列表之一）
// 路径: web/src/views/finance/FinancePolicyList.vue
// 作用: 渲染保单列表 —— 按 expiry_ts 升序（即将到期在前）;
//       卡片式;支持搜索 (name / provider);空态 + 新建按钮;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listPolicies（store 内已按到期时间排序）
//   2. 搜索:按 name / provider 模糊匹配;
//   3. 卡片:显示 name / provider / 保单号末 4 / 保费 / 保额 / 到期天数;
//   4. 点击卡片 → /finance/editor/policy/:id;
//   5. 零知识纪律 —— 保单号只显示末 4 位;保费 / 保额 只显示整数元;
//
// 关联:
//   - tasks.md TR-1.4
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NInput, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinancePolicy } from '../../finance/types'

const store = useFinanceStore()

const keyword = ref('')

const filtered = computed<FinancePolicy[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return store.listPolicies
  return store.listPolicies.filter(
    (p) => p.name.toLowerCase().includes(k) || p.provider.toLowerCase().includes(k),
  )
})

function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/policy'
    return
  }
  window.location.hash = '#/finance/editor/policy/' + id
}

function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

/** 保单号末 4 位（不可逆截断，避免完整号外泄）。 */
function last4(pn: string): string {
  if (!pn) return '****'
  return pn.length <= 4 ? pn : '****' + pn.slice(-4)
}

const CYCLE_LABEL: Record<string, string> = {
  monthly: '月付',
  quarterly: '季付',
  yearly: '年付',
  single: '一次性',
}
</script>

<template>
  <div class="pol-list">
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索保单名称 / 保险公司"
        clearable
        size="small"
      />
    </div>

    <n-empty
      v-if="filtered.length === 0"
      :description="keyword ? '没有匹配的保单' : '还没有保单, 点击右上角新建'"
    />

    <div v-else class="grid">
      <n-card
        v-for="pol in filtered"
        :key="pol.id"
        hoverable
        class="card"
        :class="{ inactive: !pol.active }"
        @click="gotoEditor(pol.id)"
      >
        <template #header>
          <span class="title">{{ pol.name }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" type="info">
            {{ CYCLE_LABEL[pol.billing_cycle] ?? pol.billing_cycle }}
          </n-tag>
        </template>
        <div class="provider">{{ pol.provider }} · {{ last4(pol.policy_number) }}</div>
        <div class="amount">保费 {{ formatAmount(pol.premium_minor) }} / 保额 {{ formatAmount(pol.coverage_minor) }}</div>
        <div class="sub">
          <span>{{ pol.currency }}</span>
          <span class="dot">·</span>
          <span>到期 {{ daysUntil(pol.expiry_ts) >= 0 ? daysUntil(pol.expiry_ts) + ' 天后' : '已过期 ' + (-daysUntil(pol.expiry_ts)) + ' 天' }}</span>
        </div>
      </n-card>
    </div>
  </div>
</template>

<style scoped>
.pol-list {
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
  font-size: 16px;
  font-weight: 600;
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