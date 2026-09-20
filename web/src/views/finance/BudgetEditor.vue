<script setup lang="ts">
// ============================================================================
// 预算编辑器视图（stage5-finance-v2 / B6 / FR-V2-F）
// ============================================================================
//
// 路径: web/src/views/finance/BudgetEditor.vue
// 作用: 预算（type='budget'）新建 / 编辑。
//       /finance/editor/budget        → 新建；
//       /finance/editor/budget/:id    → 编辑（store.byId('budget', id) 预填）。
//
// 零知识纪律：
//   1. 金额输入仅在本表单内可见（编辑预算必须录入额度），保存后列表页
//      不回显任何金额数字；
//   2. 错误提示直接用 validateBudget 的 reason（字段级文案，本身不含
//      金额 / 日期等敏感数值）；
//   3. 时间只用 NDatePicker 选择，文案中不回显毫秒数字。
//
// 金额口径：NInputNumber 拿到的是 JS number，仅在组装 payload 时用
// toFixed(2) 拼两位小数字符串（如 1000 → "1000.00"）；toFixed 只负责
// 字符串组装，不参与任何金额运算（运算在 budgetEnforcer 内走 bigint）。
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
import type { CurrencyCode, FinanceBudget } from '../../finance/types'
import { DEFAULT_CURRENCY, validateBudget } from '../../finance/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 选项常量 ==========
const SCOPE_OPTIONS = [
  { label: '月度', value: 'monthly' },
  { label: '周度', value: 'weekly' },
  { label: '年度', value: 'yearly' },
  { label: '自定义', value: 'custom' },
]
const CURRENCY_OPTIONS = [
  { label: 'CNY (人民币)', value: 'CNY' },
  { label: 'USD (美元)', value: 'USD' },
  { label: 'EUR (欧元)', value: 'EUR' },
  { label: 'JPY (日元)', value: 'JPY' },
  { label: 'HKD (港币)', value: 'HKD' },
]

// ========== 默认时间：本月 1 号零点 / 次年同月 1 号零点 ==========
function defaultStart(): number {
  const now = new Date()
  return new Date(now.getFullYear(), now.getMonth(), 1).getTime()
}
function defaultEnd(): number {
  const now = new Date()
  return new Date(now.getFullYear() + 1, now.getMonth(), 1).getTime()
}

// ========== 表单字段 ==========
const scope = ref<FinanceBudget['scope']>('monthly')
/** “全部分类”开关：开 → category='all' 且禁用自由输入。 */
const allCategory = ref(true)
const categoryText = ref('')
const amountYuan = ref<number | null>(0)
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const startTs = ref<number>(defaultStart())
const endTs = ref<number>(defaultEnd())
const warningPct = ref<number>(80)
const blockPct = ref<number>(100)
const active = ref(true)
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
    const cached = store.byId('budget', editingId.value)
    if (cached) {
      const b = cached.data as unknown as FinanceBudget
      scope.value = b.scope
      allCategory.value = b.category === 'all'
      categoryText.value = b.category === 'all' ? '' : b.category
      amountYuan.value = Number(b.amount_minor)
      currency.value = b.currency
      startTs.value = b.start_ts
      endTs.value = b.end_ts
      warningPct.value = b.warning_threshold_pct
      blockPct.value = b.block_threshold_pct
      active.value = b.active
    } else {
      message.warning('未找到该预算，可能已删除')
    }
  }
})

// ========== 校验（UI 层轻校验；硬闸门以 validateBudget 为准） ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  if (!allCategory.value) {
    const c = categoryText.value.trim()
    if (c.length === 0) e.category = '分类必填'
    else if (c.length > 20) e.category = '分类 ≤20 字符'
  }
  if (amountYuan.value == null || !Number.isFinite(amountYuan.value) || amountYuan.value < 0) {
    e.amount = '金额须为不小于 0 的数字'
  }
  if (!Number.isFinite(startTs.value) || !Number.isFinite(endTs.value)) {
    e.period = '有效期必填'
  } else if (endTs.value < startTs.value) {
    e.period = '结束时刻不能早于起始时刻'
  }
  return e
})

// ========== 组装 payload（新建 id / 编辑沿用 + created_at 保留） ==========
function buildPayload(): FinanceBudget {
  const now = Date.now()
  return {
    id: editingId.value ?? cryptoRandomId(),
    schema_version: 2,
    scope: scope.value,
    category: allCategory.value ? 'all' : categoryText.value.trim(),
    // toFixed(2) 仅用于组装两位小数字符串，不参与运算。
    amount_minor: String(Number(amountYuan.value ?? 0).toFixed(2)),
    currency: currency.value,
    start_ts: startTs.value,
    end_ts: endTs.value,
    warning_threshold_pct: Math.round(warningPct.value),
    block_threshold_pct: Math.round(blockPct.value),
    active: active.value,
    created_at: editingId.value
      ? (store.byId('budget', editingId.value)?.createdAt ?? now)
      : now,
    updated_at: now,
  }
}

// ========== 保存 ==========
function save(): void {
  if (Object.keys(errors.value).length > 0) {
    message.error('请修正表单错误后再保存')
    return
  }
  const payload = buildPayload()
  // 硬闸门：先跑纯函数校验，失败直接展示字段级原因（不含金额）。
  const result = validateBudget(payload)
  if (!result.ok) {
    message.error(result.reason)
    return
  }
  submitting.value = true
  try {
    if (editingId.value) {
      store.updateBudget(payload)
    } else {
      store.addBudget(payload)
    }
    message.success('已保存')
    router.replace({ name: 'finance' })
  } catch (e) {
    message.error('保存失败:' + String(e))
  } finally {
    submitting.value = false
  }
}

function cancel(): void {
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
      <h2>{{ editingId ? '编辑预算' : '新建预算' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button
          data-testid="budget-editor-save"
          type="primary"
          :loading="submitting"
          @click="save"
        >
          保存
        </n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="120">
        <n-form-item label="周期">
          <n-select v-model:value="scope" :options="SCOPE_OPTIONS" />
        </n-form-item>
        <n-form-item label="全部分类">
          <n-switch v-model:value="allCategory" />
          <span class="hint">开启后该预算覆盖全部分类</span>
        </n-form-item>
        <n-form-item
          v-if="!allCategory"
          label="分类"
          :feedback="errors.category"
          :validation-status="errors.category ? 'error' : undefined"
        >
          <n-input
            v-model:value="categoryText"
            placeholder="餐饮 / 交通 / ..."
            maxlength="20"
          />
        </n-form-item>
        <n-form-item
          label="金额(元)"
          :feedback="errors.amount"
          :validation-status="errors.amount ? 'error' : undefined"
        >
          <n-input-number v-model:value="amountYuan" :precision="2" :min="0" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item
          label="有效期起"
          :feedback="errors.period"
          :validation-status="errors.period ? 'error' : undefined"
        >
          <n-date-picker v-model:value="startTs" type="datetime" />
        </n-form-item>
        <n-form-item label="有效期止">
          <n-date-picker v-model:value="endTs" type="datetime" />
        </n-form-item>
        <n-form-item label="预警阈值(%)">
          <n-input-number v-model:value="warningPct" :min="1" :max="10000" :precision="0" />
        </n-form-item>
        <n-form-item label="拦截阈值(%)">
          <n-input-number v-model:value="blockPct" :min="1" :max="10000" :precision="0" />
        </n-form-item>
        <n-form-item label="启用">
          <n-switch v-model:value="active" />
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
