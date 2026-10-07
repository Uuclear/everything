<script setup lang="ts">
// 顶栏全局搜索结果浮层（仅解锁后内存明文；点击跳转对应模块）。
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { NEmpty, NList, NListItem, NSpin, NTag, NThing } from 'naive-ui'
import { useGlobalSearch } from '../composables/useGlobalSearch'
import { uiSearch } from '../composables/useUiSearch'
import type { GlobalSearchHit, GlobalSearchModule } from '../vault/aggregate'

const router = useRouter()
const { hits, searching } = useGlobalSearch()

const visible = computed(() => uiSearch.query.trim().length > 0)

const moduleLabel: Record<GlobalSearchModule, string> = {
  identity: '证件',
  item: '物品',
  finance_tx: '财务',
  pass: '密码库',
}

function go(hit: GlobalSearchHit) {
  uiSearch.query = ''
  switch (hit.module) {
    case 'identity':
      router.push({ name: 'identities' })
      break
    case 'item':
      router.push({ name: 'item-detail', params: { id: hit.id } })
      break
    case 'finance_tx':
      router.push({ name: 'finance-editor-tx', params: { id: hit.id } })
      break
    case 'pass':
      router.push({ name: 'logins' })
      break
  }
}
</script>

<template>
  <div v-if="visible" class="panel">
    <n-spin v-if="searching" size="small" class="spin" />
    <n-empty
      v-else-if="hits.length === 0"
      size="small"
      description="没有匹配结果（仅搜索标题/名称/备注，不含卡号）"
    />
    <n-list v-else hoverable clickable size="small" bordered>
      <n-list-item v-for="hit in hits" :key="`${hit.module}-${hit.id}`" @click="go(hit)">
        <n-thing>
          <template #header>
            <span class="title">{{ hit.title }}</span>
            <n-tag size="tiny" :bordered="false" class="tag">{{ moduleLabel[hit.module] }}</n-tag>
          </template>
          <template v-if="hit.subtitle" #description>
            <span class="sub">{{ hit.subtitle }}</span>
          </template>
        </n-thing>
      </n-list-item>
    </n-list>
  </div>
</template>

<style scoped>
.panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  width: 100%;
  z-index: 20;
  background: #fff;
  border: 1px solid #e8e8ec;
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(15, 21, 37, 0.12);
  padding: 8px;
  max-height: min(60vh, 420px);
  overflow: auto;
}
.spin {
  display: flex;
  justify-content: center;
  padding: 16px;
}
.title {
  margin-right: 8px;
}
.tag {
  vertical-align: middle;
}
.sub {
  font-size: 12px;
  color: #8a8f9c;
}
@media (max-width: 768px) {
  .panel {
    left: 16px;
  }
}
</style>
