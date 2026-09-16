<script setup lang="ts">
// ============================================================================
// 订阅编辑器视图（stage5-finance-v2 / TR-1.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.3（4 个 v2 子类型编辑器之一）
// 路径: web/src/views/finance/FinanceSubscriptionEditor.vue
// 作用: 订阅（type='subscription'）新建 / 编辑
//       —— 字段:name + provider + amount_minor + currency +
//       billing_cycle + custom_days + start_ts + next_renewal_ts +
//       reminders + active + category。
//
// 设计要点:
//   1. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinanceSubscription
//      字节级一致;
//   2. 编辑/新建双模式 —— 通过路由参数 id 区分:
//      - /finance/editor/subscription         → 新建(空表单)
//      - /finance/editor/subscription/:id     → 编辑(预填 store.byId)
//   3. 保存 → store.addSubscription / store.updateSubscription;
//   4. 校验 —— name + provider 必填且 ≤200 字符;
//      amount_minor 非负数字串;custom_days 仅在 billing_cycle='custom_days'
//      时必填且 >0;
//   5. next_renewal_ts 由 nextSubscriptionRenewal() 纯函数推算
//      （编辑时可手动覆盖, 保存时再次校验非零）;
//   6. 零知识纪律 —— amount_minor 仅显示整数元（隐藏小数点精度）。
//
// 关联:
//   - tasks.md TR-1.3
//   - web/src/stores/finance.ts
//   - web/src/finance/types.ts（FinanceSubscription / BillingCycle / Category）
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
  FinanceSubscription,
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
const BILLING_CYCLE_OPTIONS = [
  { label: '月付', value: 'monthly' },
  { label: '季付', value: 'quarterly' },
  { label: '年付', value: 'yearly' },
  { label: '自定义天数', value: 'custom_days' },
]
const CATEGORY_OPTIONS = [
  { label: '娱乐', value: 'entertainment' },
  { label: '效率', value: 'productivity' },
  { label: '生活缴费', value: 'utility' },
  { label: '其他', value: 'other' },
]
const REMINDER_PRESETS = [
  { label: '当日', value: 0 },
  { label: '前 1 天', value: 1440 },
  { label: '前 3 天', value: 4320 },
  { label: '前 7 天', value: 10080 },
]

// ========== 表单字段 ==========
const name = ref('')
const provider = ref('')
const amountYuan = ref<number>(0)
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const billingCycle = ref<'monthly' | 'quarterly' | 'yearly' | 'custom_days'>('monthly')
const customDays = ref<number | null>(null)
const startTs = ref<number>(Date.now())
const nextRenewalTs = ref<number>(Date.now() + 30 * 86400_000)
const reminders = ref<number[]>([0, 1440])
const active = ref(true)
const category = ref<'entertainment' | 'productivity' | 'utility' | 'other'>('other')
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
    const cached = store.byId('subscription', editingId.value)
    if (cached) {
      const s = cached.data as unknown as FinanceSubscription
      name.value = s.name
      provider.value = s.provider
      amountYuan.value = Math.floor(Number(s.amount_minor) || 0)
      currency.value = s.currency
      billingCycle.value = s.billing_cycle
      customDays.value = s.custom_days
      startTs.value = s.start_ts
      nextRenewalTs.value = s.next_renewal_ts
      reminders.value = [...s.reminders]
      active.value = s.active
      category.value = s.category
    } else {
      message.warning('未找到该订阅, 可能已删除')
    }
  }
})

// ========== next_renewal_ts 纯函数推算 ==========
/**
 * 由 start_ts + billing_cycle(+ custom_days) 推算下次续费时刻。
 *
 * 算法：先取 start_ts 后第一个 billing_cycle 边界对应的"周期对齐点"，再
 * 持续 +1 周期直到 > 当前 nextRenewalTs（或 >= now）。
 *
 * 简化实现 —— 月付按 30 天步进，季付按 90 天，年付按 365 天；
 * 实际订阅续费受月份天数/闰年影响，但编辑器 UI 仅作"建议值"，用户可手动覆盖。
 */
function nextSubscriptionRenewal(
  startMs: number,
  cycle: 'monthly' | 'quarterly' | 'yearly' | 'custom_days',
  days: number | null,
  hintMs: number,
): number {
  const stepDays =
    cycle === 'monthly' ? 30
    : cycle === 'quarterly' ? 90
    : cycle === 'yearly' ? 365
    : Math.max(1, Number(days) || 0)
  const stepMs = stepDays * 86400_000
  if (stepMs <= 0) return hintMs
  let t = startMs + stepMs
  while (t < hintMs) t += stepMs
  return t
}

// ========== 自动推算 next_renewal_ts（编辑期实时刷新） ==========
function recomputeRenewal() {
  if (billingCycle.value !== 'custom_days') customDays.value = null
  nextRenewalTs.value = nextSubscriptionRenewal(
    startTs.value,
    billingCycle.value,
    customDays.value,
    nextRenewalTs.value,
  )
}

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const n = name.value.trim()
  if (n.length === 0) e.name = '名称必填'
  else if (n.length > 200) e.name = '名称 ≤200 字符'
  const pv = provider.value.trim()
  if (pv.length === 0) e.provider = '服务商必填'
  else if (pv.length > 200) e.provider = '服务商 ≤200 字符'
  if (!Number.isFinite(amountYuan.value) || amountYuan.value < 0)
    e.amount = '金额须为非负整数'
  if (billingCycle.value === 'custom_days') {
    if (customDays.value == null || customDays.value <= 0)
      e.customDays = '自定义周期天数必填且 >0'
  }
  if (!Number.isFinite(nextRenewalTs.value) || nextRenewalTs.value <= 0)
    e.nextRenewal = '下次扣费时刻须为合法时间戳'
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
    const payload: FinanceSubscription = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 2,
      name: name.value.trim(),
      provider: provider.value.trim(),
      amount_minor: String(Math.floor(amountYuan.value)) + '.00',
      currency: currency.value,
      billing_cycle: billingCycle.value,
      custom_days: billingCycle.value === 'custom_days' ? customDays.value : null,
      start_ts: startTs.value,
      next_renewal_ts: nextRenewalTs.value,
      reminders: reminders.value.length ? reminders.value : [0],
      active: active.value,
      category: category.value,
      created_at: editingId.value
        ? (store.byId('subscription', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
    }
    if (editingId.value) {
      store.updateSubscription(payload)
    } else {
      store.addSubscription(payload)
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
/**
 * 简易 UUID v4 —— 浏览器原生 crypto.randomUUID 优先, 退化 Math.random。
 */
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
      <h2>{{ editingId ? '编辑订阅' : '新建订阅' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="120">
        <n-form-item label="名称" :feedback="errors.name" :validation-status="errors.name ? 'error' : undefined">
          <n-input v-model:value="name" placeholder="例如 Netflix" maxlength="200" />
        </n-form-item>
        <n-form-item label="服务商" :feedback="errors.provider" :validation-status="errors.provider ? 'error' : undefined">
          <n-input v-model:value="provider" placeholder="服务商" maxlength="200" />
        </n-form-item>
        <n-form-item label="金额(元)" :feedback="errors.amount" :validation-status="errors.amount ? 'error' : undefined">
          <n-input-number v-model:value="amountYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="计费周期">
          <n-select v-model:value="billingCycle" :options="BILLING_CYCLE_OPTIONS" @update:value="recomputeRenewal" />
        </n-form-item>
        <n-form-item v-if="billingCycle === 'custom_days'" label="自定义天数" :feedback="errors.customDays" :validation-status="errors.customDays ? 'error' : undefined">
          <n-input-number v-model:value="customDays" :min="1" :precision="0" @update:value="recomputeRenewal" />
        </n-form-item>
        <n-form-item label="起始日">
          <n-date-picker v-model:value="startTs" type="date" @update:value="recomputeRenewal" />
        </n-form-item>
        <n-form-item label="下次扣费" :feedback="errors.nextRenewal" :validation-status="errors.nextRenewal ? 'error' : undefined">
          <n-date-picker v-model:value="nextRenewalTs" type="datetime" />
        </n-form-item>
        <n-form-item label="提醒">
          <n-select
            v-model:value="reminders"
            multiple
            :options="REMINDER_PRESETS"
            placeholder="可多选"
          />
        </n-form-item>
        <n-form-item label="启用">
          <n-switch v-model:value="active" />
        </n-form-item>
        <n-form-item label="分类">
          <n-select v-model:value="category" :options="CATEGORY_OPTIONS" />
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
</style>