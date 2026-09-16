<script setup lang="ts">
// ============================================================================
// 应收/应付借款编辑器视图（stage5-finance-v2 / TR-1.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.3（4 个 v2 子类型编辑器之一）
// 路径: web/src/views/finance/FinanceLoanEditor.vue
// 作用: 借款条目（type='loan'）新建 / 编辑
//       —— 字段:counterparty + principal_minor + currency + direction +
//       issue_ts + due_ts + interest_rate_apy_bps + status + paid_minor +
//       reminders + linked_account_id + include_in_net_assets。
//
// 设计要点:
//   1. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinanceLoan
//      字节级一致;
//   2. 编辑/新建双模式 —— 通过路由参数 id 区分;
//   3. 保存 → store.addLoan / store.updateLoan;
//   4. 校验 —— counterparty 必填且 ≤200 字符;
//      principal_minor / paid_minor 非负数字串;due_ts > issue_ts;
//      paid_minor ≤ principal_minor;
//   5. 零知识纪律 —— 对手方名称不渲染到通知文案;金额仅显示整数元;
//
// 关联:
//   - tasks.md TR-1.3
//   - web/src/stores/finance.ts
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
  NSwitch,
  NSpace,
  NButton,
  NDatePicker,
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type {
  CurrencyCode,
  FinanceLoan,
  LoanDirection as _LoanDirection,
  LoanStatus,
} from '../../finance/types'
import { DEFAULT_CURRENCY } from '../../finance/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 选项常量 ==========
const CURRENCY_OPTIONS = [
  { label: 'CNY (人民币)', value: 'CNY' },
  { label: 'USD (美元)', value: 'USD' },
  { label: 'HKD (港币)', value: 'HKD' },
  { label: 'EUR (欧元)', value: 'EUR' },
  { label: 'JPY (日元)', value: 'JPY' },
]
const DIRECTION_OPTIONS = [
  { label: '借出（应收）', value: 'lent' },
  { label: '借入（应付）', value: 'borrowed' },
]
const STATUS_OPTIONS = [
  { label: '进行中', value: 'active' },
  { label: '部分已还', value: 'partially_paid' },
  { label: '已结清', value: 'paid' },
  { label: '逾期', value: 'overdue' },
]
const REMINDER_PRESETS = [
  { label: '当日', value: 0 },
  { label: '前 3 天', value: 4320 },
  { label: '前 7 天', value: 10080 },
  { label: '前 30 天', value: 43200 },
]

// ========== 表单字段 ==========
const counterparty = ref('')
const principalYuan = ref<number>(0)
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const direction = ref<'lent' | 'borrowed'>('lent')
const issueTs = ref<number>(Date.now())
const dueTs = ref<number>(Date.now() + 90 * 86400_000)
const interestRateApyBps = ref<number>(0)
const status = ref<LoanStatus>('active')
const paidYuan = ref<number>(0)
const reminders = ref<number[]>([0, 4320])
const linkedAccountId = ref<string | null>(null)
const includeInNetAssets = ref(true)
const submitting = ref(false)

// ========== 模式判断 ==========
const editingId = computed<string | null>(() => {
  const p = route.params.id
  return typeof p === 'string' && p.length > 0 ? p : null
})

// ========== 预填 ==========
onMounted(() => {
  if (!store.hydrated) store.hydrate()
  if (editingId.value) {
    const cached = store.byId('loan', editingId.value)
    if (cached) {
      const l = cached.data as unknown as FinanceLoan
      counterparty.value = l.counterparty
      principalYuan.value = Math.floor(Number(l.principal_minor) || 0)
      currency.value = l.currency
      direction.value = l.direction
      issueTs.value = l.issue_ts
      dueTs.value = l.due_ts
      interestRateApyBps.value = l.interest_rate_apy_bps
      status.value = l.status
      paidYuan.value = Math.floor(Number(l.paid_minor) || 0)
      reminders.value = [...l.reminders]
      linkedAccountId.value = l.linked_account_id
      includeInNetAssets.value = l.include_in_net_assets
    } else {
      message.warning('未找到该借款, 可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const cp = counterparty.value.trim()
  if (cp.length === 0) e.counterparty = '对手方必填'
  else if (cp.length > 200) e.counterparty = '对手方 ≤200 字符'
  if (!Number.isFinite(principalYuan.value) || principalYuan.value <= 0)
    e.principal = '本金须为正整数'
  if (!Number.isFinite(paidYuan.value) || paidYuan.value < 0)
    e.paid = '已还金额须为非负整数'
  else if (paidYuan.value > principalYuan.value)
    e.paid = '已还金额不能超过本金'
  if (!Number.isFinite(interestRateApyBps.value) || interestRateApyBps.value < 0)
    e.rate = '年化利率基点须 ≥0'
  if (dueTs.value <= issueTs.value)
    e.due = '到期时刻须晚于放款时刻'
  return e
})

// ========== 保存 ==========
function save() {
  if (Object.keys(errors.value).length > 0) {
    message.error('请修正表单错误后再保存')
    return
  }
  submitting.value = true
  try {
    const now = Date.now()
    const payload: FinanceLoan = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 2,
      counterparty: counterparty.value.trim(),
      principal_minor: String(Math.floor(principalYuan.value)) + '.00',
      currency: currency.value,
      direction: direction.value,
      issue_ts: issueTs.value,
      due_ts: dueTs.value,
      interest_rate_apy_bps: Math.floor(interestRateApyBps.value),
      status: status.value,
      paid_minor: String(Math.floor(paidYuan.value)) + '.00',
      reminders: reminders.value.length ? reminders.value : [0],
      linked_account_id: linkedAccountId.value,
      include_in_net_assets: includeInNetAssets.value,
      created_at: editingId.value
        ? (store.byId('loan', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
    }
    if (editingId.value) {
      store.updateLoan(payload)
    } else {
      store.addLoan(payload)
    }
    message.success('已保存')
    router.replace({ name: 'finance' })
  } catch (e) {
    message.error('保存失败:' + String(e))
  } finally {
    submitting.value = false
  }
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
</script>

<template>
  <div class="editor">
    <div class="page-head">
      <h2>{{ editingId ? '编辑借款' : '新建借款' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="120">
        <n-form-item label="对手方" :feedback="errors.counterparty" :validation-status="errors.counterparty ? 'error' : undefined">
          <n-input v-model:value="counterparty" placeholder="对手方" maxlength="200" />
        </n-form-item>
        <n-form-item label="方向">
          <n-select v-model:value="direction" :options="DIRECTION_OPTIONS" />
        </n-form-item>
        <n-form-item label="本金(元)" :feedback="errors.principal" :validation-status="errors.principal ? 'error' : undefined">
          <n-input-number v-model:value="principalYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="已还金额(元)" :feedback="errors.paid" :validation-status="errors.paid ? 'error' : undefined">
          <n-input-number v-model:value="paidYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="年化利率(bps)" :feedback="errors.rate" :validation-status="errors.rate ? 'error' : undefined">
          <n-input-number v-model:value="interestRateApyBps" :min="0" :precision="0" placeholder="基点 (10000 = 100%)" />
        </n-form-item>
        <n-form-item label="放款日">
          <n-date-picker v-model:value="issueTs" type="date" />
        </n-form-item>
        <n-form-item label="到期日" :feedback="errors.due" :validation-status="errors.due ? 'error' : undefined">
          <n-date-picker v-model:value="dueTs" type="date" />
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="status" :options="STATUS_OPTIONS" />
        </n-form-item>
        <n-form-item label="提醒">
          <n-select
            v-model:value="reminders"
            multiple
            :options="REMINDER_PRESETS"
            placeholder="可多选"
          />
        </n-form-item>
        <n-form-item label="计入净资产">
          <n-switch v-model:value="includeInNetAssets" />
          <span class="hint">借入时可关闭（仅展示, 不计入看板）</span>
        </n-form-item>
        <n-form-item label="关联账户">
          <n-input v-model:value="linkedAccountId" placeholder="可选, 关联账户 id" />
        </n-form-item>
      </n-form>
    </n-card>
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
</style>