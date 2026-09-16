<script setup lang="ts">
// 银行卡列表：列表只暴露尾号；完整卡号/CVC 需进详情并主动揭示。
import { NList, NListItem, NThing, NEmpty, NButton } from 'naive-ui'
import { useRecordPage } from '../composables/useRecordPage'
import { uiSearch as query } from '../composables/useUiSearch'
import type { CardData } from '../types/vault'
import RecordEditor from '../components/RecordEditor.vue'
import RecordViewer from '../components/RecordViewer.vue'

const { items, editorShow, viewerShow, editing, viewing, create, open, edit, remove } =
  useRecordPage('card')

function tail(num?: string): string {
  return num ? `•••• ${num.slice(-4)}` : ''
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>银行卡</h2>
      <n-button type="primary" size="small" @click="create">新建银行卡</n-button>
    </div>

    <n-empty v-if="items.length === 0"
      :description="query.query ? '没有匹配的银行卡' : '还没有银行卡，点击右上角新建'" />
    <n-list v-else hoverable clickable bordered>
      <n-list-item v-for="r in items" :key="r.id" @click="open(r)">
        <n-thing>
          <template #header>{{ (r.data as CardData).title }}</template>
          <template #header-extra>
            <span class="mono">{{ tail((r.data as CardData).number) }}</span>
          </template>
          <template #description>
            <span class="sub">{{ (r.data as CardData).cardholder || '—' }}</span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>

    <RecordEditor v-model:show="editorShow" kind="card" :record="editing" />
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
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 1px;
}
.sub {
  font-size: 13px;
  color: #555;
}
</style>
