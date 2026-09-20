<script setup lang="ts">
// ============================================================================
// 预算列表视图（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 路径: web/src/views/finance/BudgetList.vue
// 作用: 列出全部预算（store.listBudgets），展示周期中文口径 / 分类 /
//       预警与拦截阈值百分数 / 启用状态；支持新建 / 编辑 / 删除。
//
// 零知识纪律（本视图铁律）:
//   1. 【不显示任何金额数字】——预算额度字段在本视图永不读取、永不渲染；
//   2. 不渲染有效期起止时间戳 / 日期数字，周期仅展示中文口径；
//   3. 列表文案只允许“百分比 + 分类名 + 状态”，与 budgetGate 同纪律。
// ============================================================================

import { onMounted } from 'vue'
import { NButton, NCard, NEmpty, NPopconfirm, NSpace, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceBudget } from '../../finance/types'

const store = useFinanceStore()

onMounted(() => {
  if (!store.hydrated) store.hydrate()
})

// ========== 中文口径映射（仅语义文案，无数字回显） ==========
const SCOPE_LABEL: Record<FinanceBudget['scope'], string> = {
  monthly: '月度',
  weekly: '周度',
  yearly: '年度',
  custom: '自定义',
}

/** 分类展示：'all' 为预算特殊值，展示为“全部分类”；其余展示原值。 */
function categoryLabel(category: string): string {
  return category === 'all' ? '全部分类' : category
}

/** 阈值行文案：只含百分数，不含额度。 */
function thresholdText(b: FinanceBudget): string {
  return `预警 ${b.warning_threshold_pct}% / 拦截 ${b.block_threshold_pct}%`
}

// ========== 跳转（hash 路由，与既有 v2 列表同款） ==========
function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/budget'
    return
  }
  window.location.hash = '#/finance/editor/budget/' + id
}

async function onDelete(id: string): Promise<void> {
  await store.deleteBudget(id)
}
</script>

<template>
  <div class="budget-list">
    <div class="toolbar">
      <n-button
        data-testid="budget-add-btn"
        size="small"
        type="primary"
        @click="gotoEditor(null)"
      >
        新建预算
      </n-button>
    </div>

    <n-empty
      v-if="store.listBudgets.length === 0"
      description="还没有预算，点击上方新建"
    />

    <div v-else class="grid">
      <n-card
        v-for="b in store.listBudgets"
        :key="b.id"
        hoverable
        class="card"
        :class="{ inactive: !b.active }"
        @click="gotoEditor(b.id)"
      >
        <template #header>
          <span class="title">{{ SCOPE_LABEL[b.scope] }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" :type="b.active ? 'success' : 'default'">
            {{ b.active ? '启用' : '已停用' }}
          </n-tag>
        </template>
        <div class="category">{{ categoryLabel(b.category) }}</div>
        <div class="threshold">{{ thresholdText(b) }}</div>
        <div class="actions" @click.stop>
          <n-space :size="8">
            <n-button size="tiny" @click="gotoEditor(b.id)">编辑</n-button>
            <n-popconfirm @positive-click="onDelete(b.id)">
              <template #trigger>
                <n-button size="tiny" type="error" ghost>删除</n-button>
              </template>
              确认删除该预算？删除后不再参与超支拦截。
            </n-popconfirm>
          </n-space>
        </div>
      </n-card>
    </div>
  </div>
</template>

<style scoped>
.budget-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  display: flex;
  justify-content: flex-end;
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
.category {
  font-size: 14px;
  color: #374151;
  margin: 4px 0;
}
.threshold {
  font-size: 13px;
  color: #6b7280;
  margin: 4px 0;
}
.actions {
  margin-top: 8px;
}
</style>
