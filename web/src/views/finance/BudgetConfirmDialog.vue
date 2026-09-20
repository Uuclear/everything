<script setup lang="ts">
// ============================================================================
// 超支确认对话框（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 路径: web/src/views/finance/BudgetConfirmDialog.vue
// 作用: 流水保存前 precheckTx 命中 BLOCK 档时弹出的阻断式确认模态。
//       用户选“仍保存”→ emit confirm（调用方带 ack=true 重新保存）；
//       选“返回修改”→ emit cancel（留在编辑器，不写任何数据）。
//
// 零知识纪律：
//   1. 正文文案由 budgetGate.confirmText 纯函数生成，只含百分比；
//   2. 模态内不渲染预算金额 / 已花金额 / 日期 / 卡号 / 对手方；
//   3. 结果中的 bigint 金额字段仅用于判定，本组件一概不读取展示。
// ============================================================================

import { NModal, NCard, NSpace, NButton } from 'naive-ui'
import type { BudgetCheckResult } from '../../finance/budgetEnforcer'
import { confirmText } from '../../finance/budgetGate'

defineProps<{
  /** 是否展示（由调用方以 blockPending 是否为空控制）。 */
  show: boolean
  /** 本次预算判定结果（show=false 时调用方传 OK_EMPTY 兜底）。 */
  result: BudgetCheckResult
}>()

const emit = defineEmits<{
  /** 用户确认超支仍保存。 */
  (e: 'confirm'): void
  /** 用户放弃保存、返回修改。 */
  (e: 'cancel'): void
}>()
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    role="dialog"
    aria-modal="true"
    :bordered="false"
    title="超支确认"
    style="width: 420px; max-width: 92%;"
    :mask-closable="false"
    :close-on-esc="false"
    @update:show="(v: boolean) => { if (!v) emit('cancel') }"
  >
    <n-card :bordered="false" class="confirm-body">
      <p class="confirm-text">{{ confirmText(result) }}</p>
    </n-card>
    <template #footer>
      <n-space justify="end" :size="10">
        <n-button data-testid="budget-confirm-cancel" @click="emit('cancel')">
          返回修改
        </n-button>
        <n-button
          data-testid="budget-confirm-save"
          type="error"
          @click="emit('confirm')"
        >
          仍保存
        </n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.confirm-body {
  padding: 4px 0;
}
.confirm-text {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: #374151;
}
</style>
