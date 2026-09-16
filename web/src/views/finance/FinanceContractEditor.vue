<script setup lang="ts">
// ============================================================================
// 合同编辑器视图（stage5-finance-v2 / TR-1.3）
// ============================================================================
//
// 任务: stage5-finance-v2 / TR-1.3（4 个 v2 子类型编辑器之一）
// 路径: web/src/views/finance/FinanceContractEditor.vue
// 作用: 合同（type='contract'）新建 / 编辑
//       —— 字段:title + counterparty + kind + amount_minor + currency +
//       signed_ts + start_ts + end_ts + auto_renew + notice_period_days +
//       notice_deadline_ts + status + linked_account_id + attachments。
//
// 设计要点:
//   1. 字段口径与 docs/schemas/finance.schema.json#/$defs/FinanceContract
//      字节级一致;
//   2. 编辑/新建双模式 —— 通过路由参数 id 区分;
//   3. 保存 → store.addContract / store.updateContract;
//   4. 校验 —— title + counterparty 必填且 ≤200 字符;
//      amount_minor 非负数字串;end_ts > start_ts;
//      notice_deadline_ts = end_ts - notice_period_days * 86400_000 自动推算;
//   5. 附件 attachments 本期仅展示元数据列表 UI（v2 B3 接入 records 通道）；
//   6. 零知识纪律 —— amount_minor 只显示整数元;
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
  NDivider,
  NList,
  NListItem,
  NThing,
  NEmpty,
  NPopconfirm,
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type {
  AttachmentRef,
  CurrencyCode,
  FinanceContract,
  ContractKind,
  ContractStatus,
} from '../../finance/types'
import { DEFAULT_CURRENCY } from '../../finance/types'
import { uploadFile, deleteAttachment } from '../../finance/attachment'
import AttachmentViewer from './AttachmentViewer.vue'

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
const KIND_OPTIONS = [
  { label: '租赁', value: 'rental' },
  { label: '服务', value: 'service' },
  { label: '采购', value: 'purchase' },
  { label: '借贷', value: 'loan' },
  { label: '其他', value: 'other' },
]
const STATUS_OPTIONS = [
  { label: '执行中', value: 'active' },
  { label: '已到期', value: 'expired' },
  { label: '已解约', value: 'terminated' },
  { label: '已续约', value: 'renewed' },
]

// ========== 表单字段 ==========
const title = ref('')
const counterparty = ref('')
const kind = ref<ContractKind>('service')
const amountYuan = ref<number>(0)
const currency = ref<CurrencyCode>(DEFAULT_CURRENCY)
const signedTs = ref<number>(Date.now())
const startTs = ref<number>(Date.now())
const endTs = ref<number>(Date.now() + 365 * 86400_000)
const autoRenew = ref(false)
const noticePeriodDays = ref<number>(30)
const status = ref<ContractStatus>('active')
const linkedAccountId = ref<string | null>(null)
const submitting = ref(false)

// ========== 附件子状态（TR-3.4） ==========
/** 当前合同的附件列表（编辑期变更缓存 + 保存时落 payload）。 */
const attachmentsList = ref<AttachmentRef[]>([])
/** 隐藏的 file input 引用。 */
const fileInputRef = ref<HTMLInputElement | null>(null)
/** 当前预览的附件 id（null = 弹层关闭）。 */
const previewingId = ref<string | null>(null)
/** 当前预览的附件 mime。 */
const previewingMime = ref<string | null>(null)
/** AttachmentViewer 显隐。 */
const showViewer = ref(false)
/** 上传中标记。 */
const uploading = ref(false)

// ========== 模式判断 ==========
const editingId = computed<string | null>(() => {
  const p = route.params.id
  return typeof p === 'string' && p.length > 0 ? p : null
})

// ========== 自动推算 notice_deadline_ts ==========
const noticeDeadlineTs = computed(() => endTs.value - noticePeriodDays.value * 86400_000)

// ========== 预填 ==========
onMounted(() => {
  if (!store.hydrated) store.hydrate()
  if (editingId.value) {
    const cached = store.byId('contract', editingId.value)
    if (cached) {
      const c = cached.data as unknown as FinanceContract
      title.value = c.title
      counterparty.value = c.counterparty
      kind.value = c.kind
      amountYuan.value = Math.floor(Number(c.amount_minor) || 0)
      currency.value = c.currency
      signedTs.value = c.signed_ts
      startTs.value = c.start_ts
      endTs.value = c.end_ts
      autoRenew.value = c.auto_renew
      noticePeriodDays.value = c.notice_period_days
      status.value = c.status
      linkedAccountId.value = c.linked_account_id
      // 附件列表：优先 store 二级索引（pull 后最全），其次 payload 兜底。
      const fromStore = store.getAttachmentsForRecord(editingId.value)
      attachmentsList.value = fromStore.length > 0
        ? fromStore
        : [...(c.attachments ?? [])]
    } else {
      message.warning('未找到该合同, 可能已删除')
    }
  }
})

// ========== 校验 ==========
const errors = computed(() => {
  const e: Record<string, string> = {}
  const t = title.value.trim()
  if (t.length === 0) e.title = '标题必填'
  else if (t.length > 200) e.title = '标题 ≤200 字符'
  const cp = counterparty.value.trim()
  if (cp.length === 0) e.counterparty = '对手方必填'
  else if (cp.length > 200) e.counterparty = '对手方 ≤200 字符'
  if (!Number.isFinite(amountYuan.value) || amountYuan.value < 0)
    e.amount = '金额须为非负整数'
  if (endTs.value <= startTs.value)
    e.end = '结束时刻须晚于起始时刻'
  if (!Number.isFinite(noticePeriodDays.value) || noticePeriodDays.value < 0)
    e.notice = '提前通知期(天)须 ≥0'
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
    const payload: FinanceContract = {
      id: editingId.value ?? cryptoRandomId(),
      schema_version: 2,
      title: title.value.trim(),
      counterparty: counterparty.value.trim(),
      kind: kind.value,
      amount_minor: String(Math.floor(amountYuan.value)) + '.00',
      currency: currency.value,
      signed_ts: signedTs.value,
      start_ts: startTs.value,
      end_ts: endTs.value,
      auto_renew: autoRenew.value,
      notice_period_days: Math.floor(noticePeriodDays.value),
      notice_deadline_ts: noticeDeadlineTs.value,
      status: status.value,
      linked_account_id: linkedAccountId.value,
      // 附件列表 —— 直接取 attachmentsList 快照（编辑期增删缓存）。
      attachments: [...attachmentsList.value],
      created_at: editingId.value
        ? (store.byId('contract', editingId.value)?.createdAt ?? now)
        : now,
      updated_at: now,
    }
    if (editingId.value) {
      store.updateContract(payload)
    } else {
      store.addContract(payload)
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

// ========== 附件操作（TR-3.4） ==========

/** 触发隐藏 file input 点击。 */
function onUploadAttachment(): void {
  if (!editingId.value) {
    message.warning('请先保存合同后再上传附件')
    return
  }
  fileInputRef.value?.click()
}

/**
 * 处理 file 选中事件 —— 调 attachment.ts.uploadFile 上传，
 * 成功后写入 store + 本地 attachmentsList。
 */
async function handleFile(event: Event): Promise<void> {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file || !editingId.value) {
    target.value = ''
    return
  }
  uploading.value = true
  try {
    const ch = store.getAttachmentChannel()
    const result = await uploadFile(editingId.value, file, ch)
    if (result.ok) {
      store.addAttachment(editingId.value, result.value)
      attachmentsList.value = store.getAttachmentsForRecord(editingId.value)
      message.success('已上传附件')
    } else {
      message.error(result.error)
    }
  } catch {
    message.error('上传失败')
  } finally {
    uploading.value = false
    target.value = ''
  }
}

/** 打开预览弹层。 */
function previewAttachment(att: AttachmentRef): void {
  previewingId.value = att.id
  previewingMime.value = att.mime
  showViewer.value = true
}

/** 删除附件（带二次确认）。 */
async function removeAttachmentRef(id: string): Promise<void> {
  if (!editingId.value) return
  try {
    const ch = store.getAttachmentChannel()
    const result = await deleteAttachment(id, ch)
    if (result.ok) {
      store.removeAttachment(editingId.value, id)
      attachmentsList.value = store.getAttachmentsForRecord(editingId.value)
      message.success('已删除附件')
    } else {
      message.error(result.error)
    }
  } catch {
    message.error('删除失败')
  }
}

/** 字节数 → 人类可读字符串。 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
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
      <h2>{{ editingId ? '编辑合同' : '新建合同' }}</h2>
      <n-space :size="10">
        <n-button @click="cancel">取消</n-button>
        <n-button type="primary" :loading="submitting" @click="save">保存</n-button>
      </n-space>
    </div>

    <n-card>
      <n-form label-placement="left" label-width="120">
        <n-form-item label="标题" :feedback="errors.title" :validation-status="errors.title ? 'error' : undefined">
          <n-input v-model:value="title" placeholder="合同标题" maxlength="200" />
        </n-form-item>
        <n-form-item label="对手方" :feedback="errors.counterparty" :validation-status="errors.counterparty ? 'error' : undefined">
          <n-input v-model:value="counterparty" placeholder="对手方" maxlength="200" />
        </n-form-item>
        <n-form-item label="种类">
          <n-select v-model:value="kind" :options="KIND_OPTIONS" />
        </n-form-item>
        <n-form-item label="金额(元)" :feedback="errors.amount" :validation-status="errors.amount ? 'error' : undefined">
          <n-input-number v-model:value="amountYuan" :min="0" :precision="0" placeholder="整数" />
        </n-form-item>
        <n-form-item label="货币">
          <n-select v-model:value="currency" :options="CURRENCY_OPTIONS" />
        </n-form-item>
        <n-form-item label="签约日">
          <n-date-picker v-model:value="signedTs" type="date" />
        </n-form-item>
        <n-form-item label="起始日">
          <n-date-picker v-model:value="startTs" type="date" />
        </n-form-item>
        <n-form-item label="结束日" :feedback="errors.end" :validation-status="errors.end ? 'error' : undefined">
          <n-date-picker v-model:value="endTs" type="date" />
        </n-form-item>
        <n-form-item label="自动续约">
          <n-switch v-model:value="autoRenew" />
        </n-form-item>
        <n-form-item label="提前通知(天)" :feedback="errors.notice" :validation-status="errors.notice ? 'error' : undefined">
          <n-input-number v-model:value="noticePeriodDays" :min="0" :precision="0" />
        </n-form-item>
        <n-form-item label="通知截止时刻">
          <span class="readonly">{{ new Date(noticeDeadlineTs).toLocaleString() }}</span>
        </n-form-item>
        <n-form-item label="状态">
          <n-select v-model:value="status" :options="STATUS_OPTIONS" />
        </n-form-item>
        <n-form-item label="关联账户">
          <n-input v-model:value="linkedAccountId" placeholder="可选, 关联账户 id" />
        </n-form-item>
      </n-form>

      <n-divider />
      <!-- 附件区块（TR-3.4）：上传 + 列表 + 预览/删除 -->
      <div class="attachments-section">
        <div class="attachments-head">
          <h3>附件</h3>
          <n-space :size="8">
            <n-button :disabled="!editingId || uploading" @click="onUploadAttachment">
              {{ uploading ? '上传中...' : '上传附件' }}
            </n-button>
          </n-space>
        </div>
        <!-- 隐藏的 file input（accept 限定 PDF / JPG / PNG） -->
        <input
          ref="fileInputRef"
          type="file"
          accept="application/pdf,image/jpeg,image/png"
          style="display: none;"
          data-testid="attachment-file-input"
          @change="handleFile"
        />
        <n-empty
          v-if="attachmentsList.length === 0"
          description="尚未上传附件"
          size="small"
          style="margin-top: 12px;"
        />
        <n-list v-else bordered style="margin-top: 12px;">
          <n-list-item v-for="att in attachmentsList" :key="att.id">
            <n-thing
              :title="att.mime"
              :description="`${formatSize(att.size)} · ${att.sha256.slice(0, 8)}`"
              content-style="display: flex; align-items: center; gap: 6px; margin-top: 6px;"
            >
              <n-button text type="primary" @click="previewAttachment(att)">
                查看
              </n-button>
              <n-popconfirm @positive-click="removeAttachmentRef(att.id)">
                <template #trigger>
                  <n-button text type="error">删除</n-button>
                </template>
                确定删除该附件?
              </n-popconfirm>
            </n-thing>
          </n-list-item>
        </n-list>
      </div>

      <!-- 附件预览弹层 -->
      <AttachmentViewer
        v-model:show="showViewer"
        :attachment-id="previewingId"
        :mime="previewingMime"
      />
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
.readonly {
  color: #6b7280;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
</style>