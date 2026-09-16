<script setup lang="ts">
// 安全笔记列表（含阶段 1 旧 module=note 数据，读取时已统一归类）。
import { NList, NListItem, NThing, NEmpty, NButton } from 'naive-ui'
import { useRecordPage } from '../composables/useRecordPage'
import { uiSearch as query } from '../composables/useUiSearch'
import type { NoteData } from '../types/vault'
import RecordEditor from '../components/RecordEditor.vue'
import RecordViewer from '../components/RecordViewer.vue'

const { items, editorShow, viewerShow, editing, viewing, create, open, edit, remove } =
  useRecordPage('note')
</script>

<template>
  <div>
    <div class="page-head">
      <h2>安全笔记</h2>
      <n-button type="primary" size="small" @click="create">新建笔记</n-button>
    </div>

    <n-empty v-if="items.length === 0"
      :description="query.query ? '没有匹配的笔记' : '还没有笔记，点击右上角新建'" />
    <n-list v-else hoverable clickable bordered>
      <n-list-item v-for="r in items" :key="r.id" @click="open(r)">
        <n-thing :title="(r.data as NoteData).title" :time="''">
          <template #description>
            <span class="snippet">{{ (r.data as NoteData).body || '—' }}</span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>

    <RecordEditor v-model:show="editorShow" kind="note" :record="editing" />
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
.snippet {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 13px;
  color: #666;
}
</style>
