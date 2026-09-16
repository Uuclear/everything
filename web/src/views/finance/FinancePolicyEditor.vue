<script setup lang="ts">
// ============================================================================
// 保单编辑器视图（stage5-finance-v2 / TR-1.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.3（4 个 v2 子类型编辑器之一）
// 路径: web/src/views/finance/FinancePolicyEditor.vue
// 作用: 保单（type='policy'）新建 / 编辑
//       —— 字段:name + policy_number + policy_number_encrypted + provider +
//       premium_minor + currency + billing_cycle + start_ts + expiry_ts +
//       reminders + coverage_minor + active + linked_account_id。
//
// 设计要点:
//   1. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinancePolicy
//      字节级一致;
//   2. 编辑/新建双模式 —— 通过路由参数 id 区分;
//   3. 保存 → store.addPolicy / store.updatePolicy;
//   4. 校验 —— name + provider + policy_number 必填且分别 ≤200/200/100 字符;
//      premium_minor / coverage_minor 非负数字串;expiry_ts > start_ts;
//   5. 零知识纪律 —— 保单号明文入库后即由服务端加密, UI 仅显示最后 4 位;
//      premium_minor / coverage_minor 只显示整数元。
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
  FinancePolicy,
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
  { label: '一次性', value: 'single' },
]
const REMINDER_PRESETS = [
  { label: '当日', value: 0 },
  { label: '前 7 天', value: 10080 },
  { label: '前 30 天', value: 43200 },
  { label: '前 90 天', value: 129600 },
]

// ========== 表单字段 ==========
const name = ref('')
const policyNumber = ref('')
const policyNumberEncrypted = ref(true)
const provider = ref('')
const premiumYuan = ref<number>(0)
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const billingCycle = ref<'monthly' | 'quarterly' | 'yearly' | 'single'>('yearly')
const startTs = ref<number>(Date.now())
const expiryTs = ref<number>(Date.now() + 365 * 86400_000)
const reminders = ref<number[]>([0, 10080, 43200])
const coverageYuan = ref<number>(0)
const active = ref(true)
const linkedAccountId = ref<string | null>(null)
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
    const cached = store.byId('policy', editingId.value)
    if (cached) {
      const p = cached.data as unknown as FinancePolicy
      name.value = p.name
      policyNumber.value = p.policy_number
      policyNumberEncrypted.value = p.policy_number_encrypted
      provider.value = p.provider
      premiumYuan.value = Math.floor(Number(p.premium_minor) || 0)
      currency.value = p.currency
      billingCycle.value = p.billing_cycle
      startTs.value = p.start_ts
      expiryTs.value = p.expiry_ts
      reminders.value = [...p.reminders]
      coverageYuan.value = Math.floor(Number(p.coverage_minor) || 0)
      active.value = p.active
      linkedAccountId.value = p.linked_account_id
    } else {
      message.warning('未找到该保单, 可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const n = name.value.trim()
  if (n.length === 0) e.name = '名称必填'
  else if (n.length > 200) e.name = '名称 ≤200 字符'
  const pv = provider.value.trim()
  if (pv.length === 0) e.provider = '保险公司必填'
  else if (pv.length > 200) e.provider = '保险公司 ≤200 字符'
  const pn = policyNumber.value.trim()
  if (pn.length === 0) e.policyNumber = '保单号必填'
  else if (pn.length > 100) e.policyNumber = '保单号 ≤100 字符'
  if (!Number.isFinite(premiumYuan.value) || premiumYuan.value < 0)
    e.premium = '保费须为非负整数'
  if (!Number.isFinite(coverageYuan.value) || coverageYuan.value < 0)
    e.coverage = '保额须为非负整数'
  if (expiryTs.value <= startTs.value)
    e.expiry = '到期时刻须晚于起始时刻'
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
    const payload: FinancePolicy = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 2,
      name: name.value.trim(),
      policy_number: policyNumber.value.trim(),
      policy_number_encrypted: policyNumberEncrypted.value,
      provider: provider.value.trim(),
      premium_minor: String(Math.floor(premiumYuan.value)) + '.00',
      currency: currency.value,
      billing_cycle: billingCycle.value,
      start_ts: startTs.value,
      expiry_ts: expiryTs.value,
      reminders: reminders.value.length ? reminders.value : [0],
      coverage_minor: String(Math.floor(coverageYuan.value)) + '.00',
      active: active.value,
      linked_account_id: linkedAccountId.value,
      attachments: [],
      created_at: editingId.value
        ? (store.byId('policy', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
    }
    if (editingId.value) {
      store.updatePolicy(payload)
    } else {
      store.addPolicy(payload)
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
      <h2>{{ editingId ? '编辑保单' : '新建保单' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="120">
        <n-form-item label="名称" :feedback="errors.name" :validation-status="errors.name ? 'error' : undefined">
          <n-input v-model:value="name" placeholder="例如 平安重疾险" maxlength="200" />
        </n-form-item>
        <n-form-item label="保单号" :feedback="errors.policyNumber" :validation-status="errors.policyNumber ? 'error' : undefined">
          <n-input v-model:value="policyNumber" placeholder="保单号" maxlength="100" />
        </n-form-item>
        <n-form-item label="保单号加密">
          <n-switch v-model:value="policyNumberEncrypted" />
          <span class="hint">默认加密入库（明文存储 = false 时由服务端按需提示）</span>
        </n-form-item>
        <n-form-item label="保险公司" :feedback="errors.provider" :validation-status="errors.provider ? 'error' : undefined">
          <n-input v-model:value="provider" placeholder="例如 平安" maxlength="200" />
        </n-form-item>
        <n-form-item label="保费(元)" :feedback="errors.premium" :validation-status="errors.premium ? 'error' : undefined">
          <n-input-number v-model:value="premiumYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="保额(元)" :feedback="errors.coverage" :validation-status="errors.coverage ? 'error' : undefined">
          <n-input-number v-model:value="coverageYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="计费周期">
          <n-select v-model:value="billingCycle" :options="BILLING_CYCLE_OPTIONS" />
        </n-form-item>
        <n-form-item label="起始日">
          <n-date-picker v-model:value="startTs" type="date" />
        </n-form-item>
        <n-form-item label="到期日" :feedback="errors.expiry" :validation-status="errors.expiry ? 'error' : undefined">
          <n-date-picker v-model:value="expiryTs" type="date" />
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