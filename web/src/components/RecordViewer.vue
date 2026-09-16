<script setup lang="ts">
// 记录详情：敏感字段默认遮蔽，点击显式揭示；复制按钮走 30 秒自动清空剪贴板。
import { computed, ref } from 'vue'
import {
  NModal,
  NButton,
  NSpace,
  NTag,
  NDescriptions,
  NDescriptionsItem,
  useMessage,
  useDialog,
} from 'naive-ui'
import type { DecryptedRecord } from '../types/vault'
import {
  IDENTITY_KIND_LABELS,
  type CardData,
  type IdentityData,
  type LoginData,
  type NoteData,
} from '../types/vault'
import { expiryLevel } from '../stores/vault'
import { useClipboard } from '../composables/useClipboard'
import TotpLiveCode from './TotpLiveCode.vue'

const props = defineProps<{ show: boolean; record: DecryptedRecord | null }>()
const emit = defineEmits<{
  'update:show': [v: boolean]
  edit: [record: DecryptedRecord]
  delete: [record: DecryptedRecord]
}>()

const message = useMessage()
const dialog = useDialog()
const { copySecret, countdown } = useClipboard()

const reveal = ref<Record<string, boolean>>({})

const login = computed(() => (props.record?.type === 'login' ? (props.record.data as LoginData) : null))
const note = computed(() =>
  props.record && (props.record.type === 'note' || props.record.module === 'note')
    ? (props.record.data as NoteData)
    : null,
)
const card = computed(() => (props.record?.type === 'card' ? (props.record.data as CardData) : null))
const identity = computed(() =>
  props.record?.module === 'identity' ? (props.record.data as IdentityData) : null,
)

function masked(secret: string, visible: boolean, head = 0): string {
  if (visible) return secret
  if (head > 0) return `•••• ${secret.slice(-head)}`
  return '•'.repeat(Math.min(12, Math.max(6, secret.length)))
}

async function copy(label: string, value: string) {
  if (await copySecret(value)) message.success(`${label}已复制，${countdown.value} 秒后自动清空`)
  else message.error('当前浏览器不允许剪贴板写入')
}

function confirmDelete() {
  if (!props.record) return
  const r = props.record
  dialog.error({
    title: '删除记录',
    content: `确定删除「${(r.data as { title?: string }).title}」吗？删除会同步到所有设备且无法恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: () => emit('delete', r),
  })
}

function maskCardNumber(num: string, visible: boolean): string {
  if (visible) return num
  return num ? `•••• •••• •••• ${num.slice(-4)}` : ''
}

const levelColor: Record<string, string> = {
  expired: 'error',
  soon: 'warning',
  upcoming: 'info',
}
</script>

<template>
  <n-modal
    v-if="record"
    :show="show"
    preset="card"
    :title="(record.data as any).title"
    class="viewer-modal"
    @update:show="emit('update:show', $event)"
  >
    <!-- 登录项 -->
    <template v-if="login">
      <n-descriptions label-placement="left" bordered :column="1" size="small">
        <n-descriptions-item label="用户名">
          <n-space align="center" justify="space-between">
            <span>{{ login.username || '—' }}</span>
            <n-button v-if="login.username" size="tiny" quaternary
              @click="copy('用户名', login.username!)">复制</n-button>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item label="密码">
          <n-space align="center" justify="space-between">
            <span class="mono">
              {{ login.password ? masked(login.password, !!reveal.password) : '—' }}
            </span>
            <n-space>
              <n-button v-if="login.password" size="tiny" quaternary
                @click="reveal.password = !reveal.password">
                {{ reveal.password ? '隐藏' : '显示' }}
              </n-button>
              <n-button v-if="login.password" size="tiny" quaternary type="primary"
                @click="copy('密码', login.password!)">复制</n-button>
            </n-space>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item v-if="login.urls?.length" label="网址">
          <div v-for="(u, i) in login.urls" :key="i">
            <a :href="u" target="_blank" rel="noopener noreferrer">{{ u }}</a>
          </div>
        </n-descriptions-item>
        <n-descriptions-item v-if="login.notes" label="备注">
          <pre class="multiline">{{ login.notes }}</pre>
        </n-descriptions-item>
        <n-descriptions-item v-if="login.totp?.secret" label="动态码">
          <TotpLiveCode :config="login.totp" />
        </n-descriptions-item>
      </n-descriptions>
    </template>

    <!-- 安全笔记 -->
    <template v-else-if="note">
      <pre class="note-body">{{ note.body || '（空笔记）' }}</pre>
    </template>

    <!-- 银行卡 -->
    <template v-else-if="card">
      <n-descriptions label-placement="left" bordered :column="1" size="small">
        <n-descriptions-item label="持卡人">{{ card.cardholder || '—' }}</n-descriptions-item>
        <n-descriptions-item label="卡号">
          <n-space align="center" justify="space-between">
            <span class="mono">{{ card.number ? maskCardNumber(card.number, !!reveal.number) : '—' }}</span>
            <n-space>
              <n-button v-if="card.number" size="tiny" quaternary
                @click="reveal.number = !reveal.number">
                {{ reveal.number ? '隐藏' : '显示' }}
              </n-button>
              <n-button v-if="card.number" size="tiny" quaternary type="primary"
                @click="copy('卡号', card.number!)">复制</n-button>
            </n-space>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item label="有效期">
          <template v-if="card.exp_month && card.exp_year">
            {{ String(card.exp_month).padStart(2, '0') }} / {{ card.exp_year }}
          </template>
          <template v-else>—</template>
        </n-descriptions-item>
        <n-descriptions-item label="CVC">
          <n-space align="center" justify="space-between">
            <span class="mono">{{ card.cvv ? masked(card.cvv, !!reveal.cvv, 0) : '—' }}</span>
            <n-space>
              <n-button v-if="card.cvv" size="tiny" quaternary
                @click="reveal.cvv = !reveal.cvv">{{ reveal.cvv ? '隐藏' : '显示' }}</n-button>
              <n-button v-if="card.cvv" size="tiny" quaternary type="primary"
                @click="copy('CVC', card.cvv!)">复制</n-button>
            </n-space>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item v-if="card.notes" label="备注">
          <pre class="multiline">{{ card.notes }}</pre>
        </n-descriptions-item>
      </n-descriptions>
    </template>

    <!-- 证件 -->
    <template v-else-if="identity">
      <n-descriptions label-placement="left" bordered :column="1" size="small">
        <n-descriptions-item label="类型">
          <n-space align="center">
            <span>{{ IDENTITY_KIND_LABELS[identity.kind] }}</span>
            <n-tag v-if="identity.expires_on" size="small"
              :type="(levelColor[expiryLevel(identity.expires_on) ?? ''] as any) ?? 'default'">
              {{ identity.expires_on }} 到期
            </n-tag>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item label="姓名">{{ identity.name || '—' }}</n-descriptions-item>
        <n-descriptions-item label="证件号码">
          <n-space align="center" justify="space-between">
            <span class="mono">{{ identity.number || '—' }}</span>
            <n-button v-if="identity.number" size="tiny" quaternary
              @click="copy('证件号码', identity.number!)">复制</n-button>
          </n-space>
        </n-descriptions-item>
        <n-descriptions-item label="签发机构">{{ identity.issuer || '—' }}</n-descriptions-item>
        <n-descriptions-item label="签发日期">{{ identity.issued_on || '—' }}</n-descriptions-item>
        <n-descriptions-item label="到期日期">{{ identity.expires_on || '—' }}</n-descriptions-item>
        <n-descriptions-item v-if="identity.notes" label="备注">
          <pre class="multiline">{{ identity.notes }}</pre>
        </n-descriptions-item>
      </n-descriptions>
    </template>

    <template #footer>
      <n-space justify="space-between">
        <n-button type="error" ghost size="small" @click="confirmDelete">删除</n-button>
        <n-space>
          <n-button @click="emit('update:show', false)">关闭</n-button>
          <n-button type="primary" @click="record && emit('edit', record)">编辑</n-button>
        </n-space>
      </n-space>
    </template>
  </n-modal>
</template>

<style>
.viewer-modal {
  width: 560px;
  max-width: 94vw;
}
</style>
<style scoped>
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 1px;
}
.multiline,
.note-body {
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  font-family: inherit;
  line-height: 1.7;
}
.note-body {
  max-height: 50vh;
  overflow: auto;
  background: #f7f8fa;
  border-radius: 8px;
  padding: 12px;
}
</style>
