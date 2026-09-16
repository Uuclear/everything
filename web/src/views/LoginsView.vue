<script setup lang="ts">
// 登录项列表：标题 + 用户名 + 站点 host；点击进详情（复制密码/TOTP）。
import { NList, NListItem, NThing, NEmpty, NButton, NTag } from 'naive-ui'
import { useRecordPage } from '../composables/useRecordPage'
import { uiSearch as query } from '../composables/useUiSearch'
import type { LoginData } from '../types/vault'
import RecordEditor from '../components/RecordEditor.vue'
import RecordViewer from '../components/RecordViewer.vue'

const { items, editorShow, viewerShow, editing, viewing, create, open, edit, remove } =
  useRecordPage('login')

function hostOf(url?: string): string {
  if (!url) return ''
  try {
    return new URL(url).host
  } catch {
    return url
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>登录项</h2>
      <n-button type="primary" size="small" @click="create">新建登录项</n-button>
    </div>

    <n-empty v-if="items.length === 0"
      :description="query.query ? '没有匹配的登录项' : '还没有登录项，点击右上角新建'" />
    <n-list v-else hoverable clickable bordered>
      <n-list-item v-for="r in items" :key="r.id" @click="open(r)">
        <n-thing>
          <template #header>
            <span class="title">{{ (r.data as LoginData).title }}</span>
            <n-tag v-if="(r.data as LoginData).totp?.secret" size="tiny" type="success" class="t">
              TOTP
            </n-tag>
          </template>
          <template #header-extra>
            <span class="host">{{ hostOf((r.data as LoginData).urls?.[0]) }}</span>
          </template>
          <template #description>
            <span class="sub">{{ (r.data as LoginData).username || '—' }}</span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>

    <RecordEditor v-model:show="editorShow" kind="login" :record="editing" />
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
.host {
  font-size: 12px;
  color: #8a8f9c;
}
.sub {
  font-size: 13px;
  color: #555;
}
</style>
