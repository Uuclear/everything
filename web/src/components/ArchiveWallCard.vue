<script setup lang="ts">
// 证件墙单卡：缩略图 + 到期色条 + 类型标签（不展示完整 PAN/证号）。
import { computed, toRef } from 'vue'
import { NCard, NTag } from 'naive-ui'
import { useFinanceStore } from '../stores/finance'
import { expiryDays, expiryLevel, useVaultStore } from '../stores/vault'
import { useVaultThumb } from '../composables/useVaultThumb'
import { IDENTITY_KIND_LABELS, type IdentityData } from '../types/vault'
const props = defineProps<{
  kind: 'identity' | 'finance_card'
  title: string
  subtitle?: string
  identityKind?: IdentityData['kind']
  expiresOn?: string
  attachmentId?: string | null
  attachmentSha256?: string
  attachmentModule?: 'identity' | 'finance'
  last4?: string
  billingDay?: number | null
  dueDay?: number | null
}>()

const emit = defineEmits<{ click: [] }>()

const finance = useFinanceStore()
const vault = useVaultStore()

const channel = () =>
  props.attachmentModule === 'finance'
    ? finance.getAttachmentChannel()
    : vault.getAttachmentChannel('identity')

const attId = toRef(props, 'attachmentId')
const attHash = toRef(props, 'attachmentSha256')
const { url, loading } = useVaultThumb(attId, channel, attHash)

const barClass = computed(() => {
  if (!props.expiresOn) return 'bar-neutral'
  const level = expiryLevel(props.expiresOn)
  if (level === 'expired') return 'bar-expired'
  if (level === 'soon') return 'bar-soon'
  if (level === 'upcoming') return 'bar-upcoming'
  return 'bar-neutral'
})

const expiryText = computed(() => {
  if (!props.expiresOn) return null
  const days = expiryDays(props.expiresOn)
  if (days < 0) return `已过期 ${-days} 天`
  if (days === 0) return '今日到期'
  return `${days} 天后到期`
})

const typeLabel = computed(() => {
  if (props.kind === 'finance_card') return '银行卡'
  if (props.identityKind) return IDENTITY_KIND_LABELS[props.identityKind]
  return '证件'
})

const financeMeta = computed(() => {
  if (props.kind !== 'finance_card') return ''
  const parts: string[] = []
  if (props.last4) parts.push(`•••• ${props.last4}`)
  if (props.billingDay) parts.push(`账单日 ${props.billingDay}`)
  if (props.dueDay) parts.push(`还款 +${props.dueDay} 天`)
  return parts.join(' · ')
})
</script>

<template>
  <n-card class="wall-card" size="small" hoverable @click="emit('click')">
    <div class="bar" :class="barClass" />
    <div class="thumb">
      <img v-if="url" :src="url" alt="" class="img" />
      <div v-else class="placeholder">
        <span v-if="loading">…</span>
        <span v-else>{{ typeLabel }}</span>
      </div>
    </div>
    <div class="body">
      <div class="row">
        <span class="title">{{ title }}</span>
        <n-tag size="tiny" :bordered="false">{{ typeLabel }}</n-tag>
      </div>
      <p v-if="subtitle" class="sub">{{ subtitle }}</p>
      <p v-if="financeMeta" class="sub mono">{{ financeMeta }}</p>
      <p v-if="expiryText" class="exp">{{ expiryText }}</p>
    </div>
  </n-card>
</template>

<style scoped>
.wall-card {
  overflow: hidden;
  cursor: pointer;
}
.bar {
  height: 4px;
  margin: -12px -12px 8px;
}
.bar-expired {
  background: #d03050;
}
.bar-soon {
  background: #f0a020;
}
.bar-upcoming {
  background: #2080f0;
}
.bar-neutral {
  background: #e0e0e6;
}
.thumb {
  height: 120px;
  border-radius: 8px;
  background: #f0f1f5;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  margin-bottom: 8px;
}
.img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.placeholder {
  font-size: 13px;
  color: #8a8f9c;
}
.body {
  font-size: 13px;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}
.title {
  font-weight: 600;
}
.sub {
  margin: 4px 0 0;
  color: #666;
  font-size: 12px;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.exp {
  margin: 6px 0 0;
  font-size: 12px;
  color: #c97a00;
}
</style>
