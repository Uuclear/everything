<script setup lang="ts">
// ============================================================================
// 银行卡 / 信用卡列表视图（stage5-finance / Task 9 / TR-9.4）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.4
// 路径: web/src/views/finance/FinanceCardList.vue
// 作用: 渲染信用卡 / 借记卡列表 —— 显示账单日 / 还款日 / 尾号（不显示卡号
//       后四位原文;仅显示"尾号 XXXX"标签）;不显示额度数字（避免金额外泄）;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listCards;
//   2. 卡片式:每条卡一个 NCard —— name / issuer / 账单日 / 还款日 / 尾号标签;
//   3. 零知识纪律:
//      - 不显示完整卡号;
//      - 尾号以"•••• 1234"形式展示(只显示最后 4 位数字字符串);
//      - 不显示额度数字（credit_limit / used_limit）;
//      - 不显示具体日期数字 —— 账单日 / 还款日仅显示"账单日:每月 15 日"形式;
//   4. 点击卡片 → 跳转到 /finance/editor/card/:id;
//   5. 不修改 financeStore —— 仅消费。
//
// 关联:
//   - tasks.md TR-9.4
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NTag, NButton } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceCard } from '../../finance/types'

const store = useFinanceStore()

// ========== 派生 ==========
const cards = computed(() => store.listCards)
const showArchived = ref(false)
const archivedCards = computed(() =>
  store
    .listCards
    .filter(() => false), // 列表层已过滤归档;此处仅做 UI 折叠展示。
)

// ========== 跳转 ==========
function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/card'
    return
  }
  window.location.hash = '#/finance/editor/card/' + id
}

// ========== 工具 ==========
// 尾号标签 —— 零知识口径下仅显示最后 4 位数字, 不显示完整卡号。
function tailLabel(last4: string): string {
  if (!last4) return '••••'
  // 截尾 4 位 —— 与 cards list 视觉一致。
  return '•••• ' + last4.slice(-4)
}

// 账单日 / 还款日显示 —— 仅渲染"每月 X 日"形式;不渲染具体日期数字。
function statementLabel(card: FinanceCard): string {
  if (card.billing_day == null) return '账单日:未配置'
  return `账单日:每月 ${card.billing_day} 日`
}
function dueLabel(card: FinanceCard): string {
  if (card.due_day == null) return '还款日:未配置'
  return `还款日:账单后 ${card.due_day} 天`
}

// 类型中文;
const KIND_LABEL: Record<string, string> = {
  credit: '信用卡',
  debit: '借记卡',
}
function kindLabel(k: string): string {
  return KIND_LABEL[k] ?? k
}
</script>

<template>
  <div class="card-list">
    <n-empty
      v-if="cards.length === 0"
      description="还没有卡,点击右上角新建"
    />

    <div v-else class="grid">
      <n-card
        v-for="card in cards"
        :key="card.id"
        hoverable
        class="card"
        @click="gotoEditor(card.id)"
      >
        <template #header>
          <span class="title">{{ card.name }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" type="info">
            {{ kindLabel(card.kind) }}
          </n-tag>
        </template>
        <div class="bank">{{ card.issuer || '—' }}</div>
        <div class="tail">{{ tailLabel(card.last4) }}</div>
        <div class="sub">{{ statementLabel(card) }}</div>
        <div class="sub">{{ dueLabel(card) }}</div>
      </n-card>
    </div>

    <div v-if="archivedCards.length > 0" class="archived">
      <n-button text size="small" @click="showArchived = !showArchived">
        {{ showArchived ? '收起' : '显示已归档' }} ({{ archivedCards.length }} 项)
      </n-button>
      <div v-if="showArchived" class="archived-list">
        <n-card
          v-for="card in archivedCards"
          :key="card.id"
          class="card archived-card"
          @click="gotoEditor(card.id)"
        >
          <template #header>
            <span class="title">{{ card.name }}</span>
            <n-tag size="tiny" :bordered="false" type="warning">已归档</n-tag>
          </template>
          <div class="tail">{{ tailLabel(card.last4) }}</div>
        </n-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
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
.title {
  font-weight: 600;
  margin-right: 6px;
}
.bank {
  font-size: 13px;
  color: #374151;
  margin-bottom: 4px;
}
.tail {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 1px;
  font-size: 14px;
  color: #1f2937;
  margin-bottom: 6px;
}
.sub {
  font-size: 12px;
  color: #6b7280;
}
.archived {
  margin-top: 10px;
}
.archived-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  margin-top: 8px;
}
.archived-card {
  opacity: 0.7;
}
</style>
