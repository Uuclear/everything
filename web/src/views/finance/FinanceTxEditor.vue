<script setup lang="ts">
// ============================================================================
// 流水编辑器视图（stage5-finance / Task 9 / TR-9.6c）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.6c
// 路径: web/src/views/finance/FinanceTxEditor.vue
// 作用: 流水新建 / 编辑 —— 字段:amount + category + occurred_at + account_id
//       (Select)。
//
// 设计要点:
//   1. 校验 —— 转账双方 account 不同;
//   2. amount 用整数(零知识口径 —— 不显示小数点精度);
//   3. occurred_at 用 NSelect 提供"今天 / 昨天 / 本月初 / 自定义"快捷;
//   4. 不修改 financeStore —— 仅消费;
//   5. 零知识纪律 —— 金额数字外露(千分位 + ¥), 但不显示小数点精度;
//      分类字符串仅在本视图内显示, 不进入通知/日志;
//
// 关联:
//   - tasks.md TR-9.6c
// ============================================================================

import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NCard,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NInputNumber,
  NSpace,
  NButton,
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { TxKind, FinanceTx } from '../../finance/types'
import { DEFAULT_TX_COLOR } from '../../finance/types'
// B6：预算硬约束门控（纯函数文案 + 阻断确认模态）。
import { OK_EMPTY, type BudgetCheckResult } from '../../finance/budgetEnforcer'
import {
  needsConfirmDialog,
  needsWarningToast,
  warningText,
} from '../../finance/budgetGate'
import BudgetConfirmDialog from './BudgetConfirmDialog.vue'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 选项 ==========
const KIND_OPTIONS = [
  { label: '支出', value: 'expense' },
  { label: '收入', value: 'income' },
  { label: '转账', value: 'transfer' },
]

// 出账方账户(可选 store 内活跃账户) —— 转账/支出/收入均可绑定。
const accountOptions = computed(() =>
  store.listAccounts.map((a) => ({
    label: a.name,
    value: a.id,
  })),
)
// 入账方账户(仅 transfer 必填) —— 与 accountOptions 共享账户池。
const toAccountOptions = computed(() =>
  store.listAccounts.map((a) => ({
    label: a.name,
    value: a.id,
  })),
)

// ========== 字段 ==========
const kind = ref<TxKind>('expense')
const amountYuan = ref<number>(0)
const category = ref('')
const accountId = ref<string | null>(null)
const toAccountId = ref<string | null>(null)
const occurredAt = ref<number>(Date.now())
const note = ref('')
const submitting = ref(false)
/**
 * B6 超支阻断待确认状态：非 null 时挂“超支确认”模态；
 * 为 null 时模板以 OK_EMPTY 作为 result 兜底。
 */
const blockPending = ref<BudgetCheckResult | null>(null)

// ========== 模式 ==========
const editingId = computed<string | null>(() => {
  const p = route.params.id
  return typeof p === 'string' && p.length > 0 ? p : null
})

// ========== 预填 ==========
onMounted(() => {
  if (!store.hydrated) store.hydrate()
  if (editingId.value) {
    const cached = store.byId('tx', editingId.value)
    if (cached) {
      const tx = cached.data as FinanceTx
      kind.value = tx.kind
      amountYuan.value = Math.floor(Number(tx.amount) || 0)
      category.value = tx.category
      accountId.value = tx.account_id
      toAccountId.value = tx.transfer_to_account_id ?? null
      occurredAt.value = tx.occurred_at
      note.value = tx.note ?? ''
    } else {
      message.warning('未找到该流水,可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  if (!Number.isFinite(amountYuan.value) || amountYuan.value <= 0)
    e.amount = '金额 > 0'
  const c = category.value.trim()
  if (c.length === 0) e.category = '分类必填'
  else if (c.length > 20) e.category = '分类 ≤20 字符'
  // 账户/卡必填(本期仅账户)
  if (accountId.value == null) e.account = '出账方必填'
  // transfer 校验 —— 双方账户不同
  if (kind.value === 'transfer') {
    if (toAccountId.value == null) e.toAccount = '入账方必填'
    else if (toAccountId.value === accountId.value) e.toAccount = '入账方不能等于出账方'
  }
  return e
})

// ========== 组装 payload（保存与超支确认复用，保证两次提交同字段） ==========
function buildPayload(): FinanceTx {
  const now = Date.now()
  return {
    id: editingId.value ?? cryptoRandomId(),
    schema_version: 1,
    account_id: accountId.value,
    card_id: null,
    kind: kind.value,
    amount: String(Math.floor(amountYuan.value)) + '.00',
    category: category.value.trim(),
    occurred_at: occurredAt.value,
    note: note.value.trim() || null,
    transfer_to_account_id: kind.value === 'transfer' ? toAccountId.value : null,
    icon: null,
    color: DEFAULT_TX_COLOR,
    created_at: editingId.value
      ? (store.byId('tx', editingId.value)?.createdAt ?? now)
      : now,
    updated_at: now,
  }
}

// ========== 保存（B6：先过预算门控） ==========
function save() {
  if (Object.keys(errors.value).length > 0) {
    message.error('请修正表单错误后再保存')
    return
  }
  submitting.value = true
  try {
    // 不预设 overspend_acknowledged：是否挂标记由 store 按 ack 决定。
    const payload = buildPayload()
    const check = store.precheckTx(payload)
    if (needsConfirmDialog(check)) {
      // BLOCK 档：挂确认模态，本轮不保存 / 不跳转。
      blockPending.value = check
      return
    }
    doSave(payload, false, check)
  } catch (e) {
    message.error('保存失败:' + String(e))
  } finally {
    submitting.value = false
  }
}

/**
 * 实际落库（初次保存 ack=false；确认弹窗“仍保存”后 ack=true）。
 *
 * store 返回 false 表示被预算硬拦截（UI 已拦的双保险路径）：提示并停留。
 * 落库成功后 WARNING 档补一条零知识 toast（仅百分比 + 分类）。
 */
function doSave(payload: FinanceTx, ack: boolean, check: BudgetCheckResult) {
  const saved = editingId.value
    ? store.updateTx(payload, ack)
    : store.addTx(payload, ack)
  if (saved === false) {
    message.error('超过预算拦截阈值，请确认后保存')
    return
  }
  if (needsWarningToast(check)) {
    message.warning(warningText(check))
  }
  message.success('已保存')
  router.replace({ name: 'finance' })
}

/** 超支确认弹窗：用当前表单重新组装同字段 payload，带 ack=true 落库。 */
function onConfirmOverspend() {
  try {
    const payload = buildPayload()
    doSave(payload, true, blockPending.value ?? OK_EMPTY)
  } finally {
    blockPending.value = null
  }
}

/** 放弃超支保存：关弹窗，留在编辑器。 */
function onCancelOverspend() {
  blockPending.value = null
}

function deleteTx() {
  if (!editingId.value) return
  store.deleteTx(editingId.value)
  message.success('已删除')
  router.replace({ name: 'finance' })
}

function cancel() {
  router.replace({ name: 'finance' })
}

// ========== 工具 ==========
function cryptoRandomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// 默认分类建议 —— 与 spec FR-6 客户端内置分类一致。
const CATEGORY_SUGGESTIONS = [
  '餐饮', '交通', '居家', '购物', '娱乐', '医疗', '教育', '通讯', '旅行',
  '工资', '奖金', '投资', '兼职', '红包', '退款',
]
</script>

<template>
  <div class="editor">
    <div class="page-head">
      <h2>{{ editingId ? '编辑流水' : '新建流水' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button v-if="editingId" @click="deleteTx" type="error" ghost>删除</n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="100">
        <n-form-item label="类型">
          <n-select v-model:value="kind" :options="KIND_OPTIONS" />
        </n-form-item>
        <n-form-item label="金额(元)" :feedback="errors.amount" :validation-status="errors.amount ? 'error' : undefined">
          <n-input-number v-model:value="amountYuan" :min="0" :precision="0" />
        </n-form-item>
        <n-form-item label="分类" :feedback="errors.category" :validation-status="errors.category ? 'error' : undefined">
          <n-input v-model:value="category" placeholder="餐饮 / 交通 / ..." maxlength="20" />
          <div class="chip-row">
            <n-button
              v-for="c in CATEGORY_SUGGESTIONS"
              :key="c"
              size="tiny"
              quaternary
              @click="category = c"
            >
              {{ c }}
            </n-button>
          </div>
        </n-form-item>
        <n-form-item label="出账方账户" :feedback="errors.account" :validation-status="errors.account ? 'error' : undefined">
          <n-select v-model:value="accountId" :options="accountOptions" clearable />
        </n-form-item>
        <n-form-item
          v-if="kind === 'transfer'"
          label="入账方账户"
          :feedback="errors.toAccount"
          :validation-status="errors.toAccount ? 'error' : undefined"
        >
          <n-select v-model:value="toAccountId" :options="toAccountOptions" clearable />
        </n-form-item>
        <n-form-item label="发生时刻">
          <n-input-number v-model:value="occurredAt" :min="0" placeholder="Unix 毫秒" />
          <span class="hint">Unix 毫秒(默认当前)</span>
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="note" type="textarea" maxlength="200" />
        </n-form-item>
      </n-form>
    </n-card>

    <!-- B6：预算超支阻断确认（BLOCK 档）；result 为空时以 OK_EMPTY 兜底 -->
    <BudgetConfirmDialog
      :show="blockPending !== null"
      :result="blockPending ?? OK_EMPTY"
      @confirm="onConfirmOverspend"
      @cancel="onCancelOverspend"
    />
  </div>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: #6b7280;
}
.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
</style>
