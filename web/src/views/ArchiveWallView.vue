<script setup lang="ts">
// 证件 / 资产档案墙：证件与财务卡片网格预览（解密缩略图仅驻内存）。
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NEmpty, NSpin } from 'naive-ui'
import ArchiveWallCard from '../components/ArchiveWallCard.vue'
import RecordViewer from '../components/RecordViewer.vue'
import { useFinanceStore } from '../stores/finance'
import { useVaultStore } from '../stores/vault'
import type { DecryptedRecord, IdentityData } from '../types/vault'
import type { FinanceCard } from '../finance/types'

const router = useRouter()
const vault = useVaultStore()
const finance = useFinanceStore()

const viewerShow = ref(false)
const viewing = ref<DecryptedRecord | null>(null)

const identityCards = computed(() =>
  vault
    .byKind('identity')
    .map((r) => {
      const d = r.data as IdentityData
      const attachmentId = d.front_attachment_id ?? d.scan_attachment_ids?.[0] ?? null
      const meta = attachmentId ? vault.getAttachmentMeta(attachmentId) : undefined
      return {
        record: r,
        attachmentId,
        sha256: meta?.sha256,
      }
    }),
)

const financeCards = computed(() =>
  finance.listCards
    .filter((c) => !c.archived)
    .map((c) => {
      const attachmentId = c.card_face_attachment_id ?? null
      const meta = attachmentId ? finance.getAttachmentMeta(attachmentId) : undefined
      return { card: c, attachmentId, sha256: meta?.sha256 }
    }),
)

const empty = computed(() => identityCards.value.length === 0 && financeCards.value.length === 0)

function openIdentity(r: DecryptedRecord) {
  viewing.value = r
  viewerShow.value = true
}

function openFinanceCard(card: FinanceCard) {
  router.push({ name: 'finance-editor-card', params: { id: card.id } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>证件墙</h2>
      <p class="hint">影像仅在解锁后于内存中解密预览，不会写入浏览器持久存储。</p>
    </div>

    <n-spin v-if="vault.syncing && empty" size="small">
      <template #description>同步中…</template>
    </n-spin>

    <n-empty v-else-if="empty" description="还没有证件或银行卡档案，先去添加证件或财务卡片" />

    <div v-else class="grid">
      <ArchiveWallCard
        v-for="row in identityCards"
        :key="row.record.id"
        kind="identity"
        :title="(row.record.data as IdentityData).title"
        :subtitle="(row.record.data as IdentityData).name"
        :identity-kind="(row.record.data as IdentityData).kind"
        :expires-on="(row.record.data as IdentityData).expires_on"
        :attachment-id="row.attachmentId"
        :attachment-sha256="row.sha256"
        attachment-module="identity"
        @click="openIdentity(row.record)"
      />
      <ArchiveWallCard
        v-for="row in financeCards"
        :key="row.card.id"
        kind="finance_card"
        :title="row.card.name"
        :subtitle="row.card.issuer"
        :last4="row.card.last4"
        :billing-day="row.card.billing_day"
        :due-day="row.card.due_day"
        :attachment-id="row.attachmentId"
        :attachment-sha256="row.sha256"
        attachment-module="finance"
        @click="openFinanceCard(row.card)"
      />
    </div>

    <RecordViewer
      v-model:show="viewerShow"
      :record="viewing"
      @edit="viewerShow = false"
      @delete="(r) => { void vault.remove(r); viewerShow = false }"
    />
  </div>
</template>

<style scoped>
.page-head {
  margin-bottom: 12px;
}
h2 {
  margin: 0;
  font-size: 18px;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #8a8f9c;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
</style>
