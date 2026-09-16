<script setup lang="ts">
// ============================================================================
// 合同列表视图（stage5-finance-v2 / TR-1.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.4（4 个 v2 子类型列表之一）
// 路径: web/src/views/finance/FinanceContractList.vue
// 作用: 渲染合同列表 —— 按 end_ts 升序（即将结束在前）;
//       支持搜索 (title / counterparty);显示种类 / 金额 / 状态 / 距结束天数;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listContracts（store 内已按 end_ts 排序）
//   2. 搜索:按 title / counterparty 模糊匹配;
//   3. 卡片:标题 / 对手方 / 种类 / 金额 / 状态 / 距结束天数 / 附件数;
//   4. 点击卡片 → /finance/editor/contract/:id;
//   5. 零知识纪律 —— 金额只显示整数元;附件名不外泄（仅显示数量）;
//
// 关联:
//   - tasks.md TR-1.4
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NInput, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceContract } from '../../finance/types'

const store = useFinanceStore()

const keyword = ref('')

const filtered = computed<FinanceContract[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return store.listContracts
  return store.listContracts.filter(
    (c) =>
      c.title.toLowerCase().includes(k) ||
      c.counterparty.toLowerCase().includes(k),
  )
})

function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/contract'
    return
  }
  window.location.hash = '#/finance/editor/contract/' + id
}

function formatAmount(v: string): string {
  const n = Number(v)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

function daysUntil(ms: number): number {
  return Math.ceil((ms - Date.now()) / 86400_000)
}

const KIND_LABEL: Record<string, string> = {
  rental: '租赁',
  service: '服务',
  purchase: '采购',
  loan: '借贷',
  other: '其他',
}

const STATUS_LABEL: Record<string, string> = {
  active: '执行中',
  expired: '已到期',
  terminated: '已解约',
  renewed: '已续约',
}

const STATUS_TYPE: Record<string, 'default' | 'warning' | 'success' | 'error'> = {
  active: 'default',
  expired: 'warning',
  terminated: 'error',
  renewed: 'success',
}
</script>

<template>
  <div class="ct-list">
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索合同标题 / 对手方"
        clearable
        size="small"
      />
    </div>

    <n-empty
      v-if="filtered.length === 0"
      :description="keyword ? '没有匹配的合同' : '还没有合同, 点击右上角新建'"
    />

    <div v-else class="grid">
      <n-card
        v-for="ct in filtered"
        :key="ct.id"
        hoverable
        class="card"
        @click="gotoEditor(ct.id)"
      >
        <template #header>
          <span class="title">{{ ct.title }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" type="info">
            {{ KIND_LABEL[ct.kind] ?? ct.kind }}
          </n-tag>
          <n-tag size="tiny" :bordered="false" :type="STATUS_TYPE[ct.status] ?? 'default'" style="margin-left:6px">
            {{ STATUS_LABEL[ct.status] ?? ct.status }}
          </n-tag>
        </template>
        <div class="counter">{{ ct.counterparty }}</div>
        <div class="amount">{{ formatAmount(ct.amount_minor) }}</div>
        <div class="sub">
          <span>{{ ct.currency }}</span>
          <span class="dot">·</span>
          <span>结束 {{ daysUntil(ct.end_ts) >= 0 ? daysUntil(ct.end_ts) + ' 天后' : '已结束 ' + (-daysUntil(ct.end_ts)) + ' 天' }}</span>
          <span class="dot">·</span>
          <span v-if="ct.auto_renew">自动续约</span>
          <span v-else>手动续约</span>
          <span class="dot">·</span>
          <span>附件 {{ ct.attachments.length }} 份</span>
        </div>
      </n-card>
    </div>
  </div>
</template>

<style scoped>
.ct-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  max-width: 360px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
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
.counter {
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