<script setup lang="ts">
// 证件列表：类型徽标 + 到期状态色点（红=已过期，橙≤30 天，黄≤90 天）。
import { NList, NListItem, NThing, NEmpty, NButton, NTag } from 'naive-ui'
import { useRecordPage } from '../composables/useRecordPage'
import { uiSearch as query } from '../composables/useUiSearch'
import { IDENTITY_KIND_LABELS, type IdentityData } from '../types/vault'
import { expiryDays, expiryLevel } from '../stores/vault'
import RecordEditor from '../components/RecordEditor.vue'
import RecordViewer from '../components/RecordViewer.vue'

const { items, editorShow, viewerShow, editing, viewing, create, open, edit, remove } =
  useRecordPage('identity')

const levelMeta: Record<string, { type: 'error' | 'warning' | 'info'; label: (n: number) => string }> = {
  expired: { type: 'error', label: () => '已过期' },
  soon: { type: 'warning', label: (n) => `${n} 天后到期` },
  upcoming: { type: 'info', label: (n) => `${n} 天后到期` },
}

function tagOf(d: IdentityData) {
  if (!d.expires_on) return null
  const level = expiryLevel(d.expires_on)
  if (!level) return null
  return { type: levelMeta[level].type, text: levelMeta[level].label(expiryDays(d.expires_on)) }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>证件</h2>
      <n-button type="primary" size="small" @click="create">新建证件</n-button>
    </div>

    <n-empty v-if="items.length === 0"
      :description="query.query ? '没有匹配的证件' : '还没有证件，点击右上角新建'" />
    <n-list v-else hoverable clickable bordered>
      <n-list-item v-for="r in items" :key="r.id" @click="open(r)">
        <n-thing>
          <template #header>
            <span class="title">{{ (r.data as IdentityData).title }}</span>
            <n-tag size="tiny" :bordered="false" class="t">
              {{ IDENTITY_KIND_LABELS[(r.data as IdentityData).kind] }}
            </n-tag>
          </template>
          <template #header-extra>
            <n-tag v-if="tagOf(r.data as IdentityData)" size="small"
              :type="tagOf(r.data as IdentityData)!.type">
              {{ tagOf(r.data as IdentityData)!.text }}
            </n-tag>
          </template>
          <template #description>
            <span class="sub">
              {{ (r.data as IdentityData).name || '—' }}
              <template v-if="(r.data as IdentityData).number">
                · •••• {{ (r.data as IdentityData).number!.slice(-4) }}
              </template>
            </span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>

    <RecordEditor v-model:show="editorShow" kind="identity" :record="editing" />
    <RecordViewer v-model:show="viewerShow" :record="viewing" @edit="edit" @delete="remove" />
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
h2 {
  margin: 0;
  font-size: 18px;
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
