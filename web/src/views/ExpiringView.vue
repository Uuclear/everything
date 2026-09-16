<script setup lang="ts">
// 到期提醒：聚合全部证件中已过期或 90 天内到期的记录，按到期日升序。
import { ref } from 'vue'
import { NList, NListItem, NThing, NEmpty, NTag } from 'naive-ui'
import { useVaultStore, expiryDays, expiryLevel } from '../stores/vault'
import { IDENTITY_KIND_LABELS, type DecryptedRecord, type IdentityData } from '../types/vault'
import RecordViewer from '../components/RecordViewer.vue'

const vault = useVaultStore()
const viewerShow = ref(false)
const viewing = ref<DecryptedRecord | null>(null)

function levelTag(d: IdentityData) {
  const level = expiryLevel(d.expires_on ?? '')
  const days = expiryDays(d.expires_on ?? '')
  if (!level) return null
  if (level === 'expired') return { type: 'error' as const, text: `已过期 ${-days} 天` }
  if (level === 'soon') return { type: 'warning' as const, text: `${days} 天后到期` }
  return { type: 'info' as const, text: `${days} 天后到期` }
}

function open(r: DecryptedRecord) {
  viewing.value = r
  viewerShow.value = true
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>到期提醒</h2>
    </div>
    <p class="legend">
      <n-tag size="small" type="error">已过期</n-tag>
      <n-tag size="small" type="warning">30 天内</n-tag>
      <n-tag size="small" type="info">90 天内</n-tag>
    </p>

    <n-empty v-if="vault.expiring.length === 0" description="未来 90 天内没有证件到期" />
    <n-list v-else hoverable clickable bordered>
      <n-list-item v-for="r in vault.expiring" :key="r.id" @click="open(r)">
        <n-thing>
          <template #header>
            <span class="title">{{ (r.data as IdentityData).title }}</span>
            <n-tag size="tiny" :bordered="false" class="t">
              {{ IDENTITY_KIND_LABELS[(r.data as IdentityData).kind] }}
            </n-tag>
          </template>
          <template #header-extra>
            <n-tag size="small" :type="levelTag(r.data as IdentityData)!.type">
              {{ levelTag(r.data as IdentityData)!.text }}
            </n-tag>
          </template>
          <template #description>
            <span class="sub">
              {{ (r.data as IdentityData).name || '—' }} · 到期日
              {{ (r.data as IdentityData).expires_on }}
            </span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>

    <RecordViewer v-model:show="viewerShow" :record="viewing"
      @edit="viewerShow = false"
      @delete="(r) => { void vault.remove(r); viewerShow = false }" />
  </div>
</template>

<style scoped>
.page-head {
  margin-bottom: 8px;
}
h2 {
  margin: 0;
  font-size: 18px;
}
.legend {
  display: flex;
  gap: 8px;
  margin: 0 0 14px;
}
.title {
  margin-right: 8px;
}
.t {
  vertical-align: middle;
}
.sub {
  font-size: 13px;
  color: #555;
}
</style>
