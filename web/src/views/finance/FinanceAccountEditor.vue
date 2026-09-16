<script setup lang="ts">
// ============================================================================
// 账户编辑器视图（stage5-finance / Task 9 / TR-9.6a）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.6a
// 路径: web/src/views/finance/FinanceAccountEditor.vue
// 作用: 账户新建 / 编辑 —— 字段:name + kind + currency + balance + archived
//       开关 + 颜色（v1 阶段色板简化默认）。
//
// 设计要点:
//   1. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinanceAccount
//      字节级一致;
//   2. 编辑/新建双模式 —— 通过路由参数 id 区分:
//      - /finance/editor/account         → 新建(空表单)
//      - /finance/editor/account/:id     → 编辑(预填 store.byId)
//   3. 保存 → store.addAccount / store.updateAccount;
//   4. 校验 —— name 必填且 ≤40 字符;balance 非负数字;
//   5. 零知识纪律 —— 余额输入框不显示小数点精度(仅整数);
//
// 关联:
//   - tasks.md TR-9.6a
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
  NEmpty,
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { AccountKind, FinanceAccount, CurrencyCode } from '../../finance/types'
import { DEFAULT_ACCOUNT_COLOR, DEFAULT_CURRENCY } from '../../finance/types'

const route = useRoute()
const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 字段 ==========
const KIND_OPTIONS = [
  { label: '现金', value: 'cash' },
  { label: '定期', value: 'deposit' },
  { label: '投资', value: 'stock' },
  { label: '电子钱包', value: 'wallet' },
  { label: '其他', value: 'other' },
]
const CURRENCY_OPTIONS = [
  { label: 'CNY (人民币)', value: 'CNY' },
  { label: 'USD (美元)', value: 'USD' },
  { label: 'HKD (港币)', value: 'HKD' },
  { label: 'EUR (欧元)', value: 'EUR' },
  { label: 'JPY (日元)', value: 'JPY' },
]

const name = ref('')
const kind = ref<AccountKind>('cash')
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const balanceYuan = ref<number>(0)
const archived = ref(false)
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
    const cached = store.byId('account', editingId.value)
    if (cached) {
      const a = cached.data as FinanceAccount
      name.value = a.name
      kind.value = a.kind
      currency.value = a.currency
      balanceYuan.value = Math.floor(Number(a.balance) || 0)
      archived.value = a.archived
    } else {
      message.warning('未找到该账户,可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const n = name.value.trim()
  if (n.length === 0) e.name = '名称必填'
  else if (n.length > 40) e.name = '名称 ≤40 字符'
  if (!Number.isFinite(balanceYuan.value) || balanceYuan.value < 0)
    e.balance = '余额须为非负整数'
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
    const payload: FinanceAccount = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 1,
      name: name.value.trim(),
      kind: kind.value,
      currency: currency.value,
      balance: String(Math.floor(balanceYuan.value)) + '.00',
      note: null,
      icon: null,
      color: DEFAULT_ACCOUNT_COLOR,
      archived: archived.value,
      created_at: editingId.value
        ? (store.byId('account', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
    }
    if (editingId.value) {
      store.updateAccount(payload)
    } else {
      store.addAccount(payload)
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
  store.archiveAccount(editingId.value)
  message.success('已归档')
  router.replace({ name: 'finance' })
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
  // 退化路径 —— 实际浏览器均支持 randomUUID, 此处仅作为兜底。
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
      <h2>{{ editingId ? '编辑账户' : '新建账户' }}</h2>
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
          <n-input v-model:value="name" placeholder="例如 现金钱包" maxlength="40" />
        </n-form-item>
        <n-form-item label="类型">
          <n-select v-model:value="kind" :options="KIND_OPTIONS" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="余额(元)" :feedback="errors.balance" :validation-status="errors.balance ? 'error' : undefined">
          <n-input-number v-model:value="balanceYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="归档">
          <n-switch v-model:value="archived" />
          <span class="hint">归档后不再计入看板</span>
        </n-form-item>
      </n-form>
    </n-card>

    <n-empty v-if="!editingId && false" description="占位空态(永不命中)" />
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
