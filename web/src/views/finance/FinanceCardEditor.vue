<script setup lang="ts">
// ============================================================================
// 卡编辑器视图（stage5-finance / Task 9 / TR-9.6b）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.6b
// 路径: web/src/views/finance/FinanceCardEditor.vue
// 作用: 卡新建 / 编辑 —— 字段:name + brand + statement_day + due_day_offset
//       + masked_pan + archived。
//
// 设计要点:
//   1. masked_pan 校验 —— 调 luhn.ts Luhn.validate + extractLast4:
//      - 校验通过 → 入库 last4 (4 位数字字符串);
//      - 校验失败 → 弹错并清空输入;
//      - 完整卡号仅在校验瞬间驻留内存, 不持久化;
//
//   2. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinanceCard
//      字节级一致（v1 字段子集 —— billingDay / dueDay / last4 / issuer /
//      kind / archived / includeInNetAssets）;
//
//   3. 校验 —— billingDay 1-31;dueDay 1-31;name 必填且 ≤40 字符;
//   4. 零知识纪律 —— 不显示完整卡号;不显示额度数字外露;
//   5. 不修改 financeStore / luhn —— 仅消费。
//
// 关联:
//   - tasks.md TR-9.6b
//   - web/src/finance/luhn.ts
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
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { CardKind, FinanceCard, CurrencyCode } from '../../finance/types'
import { DEFAULT_CARD_COLOR, DEFAULT_CURRENCY } from '../../finance/types'
import { luhnValidate, extractLast4 } from '../../finance/luhn'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 选项 ==========
const KIND_OPTIONS = [
  { label: '信用卡', value: 'credit' },
  { label: '借记卡', value: 'debit' },
]
const BRAND_OPTIONS = [
  { label: 'Visa', value: 'visa' },
  { label: 'Master', value: 'master' },
  { label: 'UnionPay', value: 'unionpay' },
  { label: 'Amex', value: 'amex' },
  { label: 'JCB', value: 'jcb' },
  { label: 'Discover', value: 'discover' },
  { label: 'Unknown', value: 'unknown' },
]
const CURRENCY_OPTIONS = [
  { label: 'CNY (人民币)', value: 'CNY' },
  { label: 'USD (美元)', value: 'USD' },
  { label: 'HKD (港币)', value: 'HKD' },
]

// ========== 字段 ==========
const name = ref('')
const kind = ref<CardKind>('credit')
const issuer = ref('')
const brand = ref('unknown')
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const billingDay = ref<number | null>(15)
const dueDay = ref<number | null>(25)
// masked_pan 入参（仅校验瞬间驻留内存，不持久化）。
const maskedPan = ref('')
const archived = ref(false)
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
    const cached = store.byId('card', editingId.value)
    if (cached) {
      const c = cached.data as FinanceCard
      name.value = c.name
      kind.value = c.kind
      issuer.value = c.issuer ?? ''
      // brand 在 v1 schema 中由 luhn 推断,但 v1 type 里没 brand 字段;从
      // store 取出时若无对应字段则按 unknown 处理。
      brand.value = (c as unknown as { brand?: string }).brand ?? 'unknown'
      currency.value = c.currency
      billingDay.value = c.billing_day ?? null
      dueDay.value = c.due_day ?? null
      // 不预填 masked_pan —— 完整卡号不入库, 用户需重新输入。
      archived.value = c.archived
      includeInNetAssets.value = c.include_in_net_assets
    } else {
      message.warning('未找到该卡,可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const n = name.value.trim()
  if (n.length === 0) e.name = '名称必填'
  else if (n.length > 40) e.name = '名称 ≤40 字符'
  if (billingDay.value != null && (billingDay.value < 1 || billingDay.value > 31))
    e.billingDay = '账单日 1-31'
  if (dueDay.value != null && (dueDay.value < 1 || dueDay.value > 31))
    e.dueDay = '还款日偏移 1-31'
  // masked_pan —— 若已输入但 Luhn 失败, 算错误;若为空, 仅在 editing 模式算错误(新建未填 OK)
  const pan = maskedPan.value.trim()
  if (pan.length > 0 && !luhnValidate(pan)) e.pan = '卡号未通过 Luhn 校验'
  if (editingId.value && pan.length === 0 && store.byId('card', editingId.value) == null) {
    e.pan = '请输入卡号(Luhn 校验)'
  }
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
    // 处理 last4 —— 编辑模式: 已有 last4 优先, 新输入则调 luhn.extractLast4。
    let last4: string = ''
    if (editingId.value) {
      const existing = store.byId('card', editingId.value)
      const existingLast4 = (existing?.data as FinanceCard | undefined)?.last4 ?? ''
      const pan = maskedPan.value.trim()
      if (pan.length > 0) {
        const extracted = extractLast4(pan)
        if (extracted == null) {
          message.error('卡号未通过 Luhn 校验, 仅保留已存 last4')
          last4 = existingLast4
        } else {
          last4 = extracted
        }
      } else {
        last4 = existingLast4
      }
    } else {
      const extracted = extractLast4(maskedPan.value.trim())
      if (extracted == null) {
        message.error('请输入合法的卡号(Luhn 校验)')
        submitting.value = false
        return
      }
      last4 = extracted
    }

    const payload: FinanceCard = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 1,
      name: name.value.trim(),
      kind: kind.value,
      issuer: issuer.value.trim() || 'unknown',
      last4,
      currency: currency.value,
      credit_limit: '0.00',
      used_limit: null,
      billing_day: billingDay.value,
      due_day: dueDay.value,
      note: null,
      icon: null,
      color: DEFAULT_CARD_COLOR,
      archived: archived.value,
      include_in_net_assets: includeInNetAssets.value,
      created_at: editingId.value
        ? (store.byId('card', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
      // brand 字段为推断辅助,v1 schema 不强制保存;此处塞进 last4 前缀注释
      // 提示 —— store 不会保留额外字段, 故 brand 仅在 UI 上下文使用。
      ...({ brand: brand.value } as Record<string, unknown>),
    }
    if (editingId.value) {
      store.updateCard(payload)
    } else {
      store.addCard(payload)
    }
    message.success('已保存')
    router.replace({ name: 'finance' })
  } catch (e) {
    message.error('保存失败:' + String(e))
  } finally {
    submitting.value = false
  }
}

function archive() {
  if (!editingId.value) return
  store.archiveCard(editingId.value)
  message.success('已归档')
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

// 输入提示: 卡号仅做 Luhn 校验, 不入库完整值;在 UI 上提示用户。
</script>

<template>
  <div class="editor">
    <div class="page-head">
      <h2>{{ editingId ? '编辑卡' : '新建卡' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button v-if="editingId" @click="archive" type="warning" ghost>
          归档
        </n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="100">
        <n-form-item label="名称" :feedback="errors.name" :validation-status="errors.name ? 'error' : undefined">
          <n-input v-model:value="name" placeholder="例如 招行信用卡" maxlength="40" />
        </n-form-item>
        <n-form-item label="类型">
          <n-select v-model:value="kind" :options="KIND_OPTIONS" />
        </n-form-item>
        <n-form-item label="发卡行">
          <n-input v-model:value="issuer" placeholder="例如 招商银行" maxlength="40" />
        </n-form-item>
        <n-form-item label="卡品牌">
          <n-select v-model:value="brand" :options="BRAND_OPTIONS" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="账单日(1-31)" :feedback="errors.billingDay" :validation-status="errors.billingDay ? 'error' : undefined">
          <n-input-number v-model:value="billingDay" :min="1" :max="31" :precision="0" />
        </n-form-item>
        <n-form-item label="还款日 offset" :feedback="errors.dueDay" :validation-status="errors.dueDay ? 'error' : undefined">
          <n-input-number v-model:value="dueDay" :min="1" :max="31" :precision="0" />
          <span class="hint">账单日后 N 天</span>
        </n-form-item>
        <n-form-item label="卡号" :feedback="errors.pan" :validation-status="errors.pan ? 'error' : undefined">
          <n-input
            v-model:value="maskedPan"
            placeholder="完整卡号(仅做 Luhn 校验,不持久化)"
            maxlength="19"
            clearable
          />
          <span class="hint">
            {{
              maskedPan.length === 0
                ? '空: 编辑模式下保留原 last4'
                : luhnValidate(maskedPan)
                  ? '✓ Luhn 通过, 仅保留后四位入库'
                  : '✗ Luhn 未通过, 修正后可保存'
            }}
          </span>
        </n-form-item>
        <n-form-item label="计入净资产">
          <n-switch v-model:value="includeInNetAssets" />
        </n-form-item>
        <n-form-item label="归档">
          <n-switch v-model:value="archived" />
          <span class="hint">归档后不再计入看板</span>
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
