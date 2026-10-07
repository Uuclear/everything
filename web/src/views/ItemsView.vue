<script setup lang="ts">
// 物品列表：分类筛选 + 标签搜索 + 新建/扫码
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NEmpty, NInput, NSpin, NTag, NSpace } from 'naive-ui'
import ItemEditorDialog from '../components/ItemEditorDialog.vue'
import ItemScanner from '../components/ItemScanner.vue'
import { useItemsStore } from '../stores/items'
import { CATEGORY_OPTIONS } from '../items/editor'
import type { Item, ItemCategory } from '../items/types'

const store = useItemsStore()
const router = useRouter()

const loading = ref(true)
const tagQuery = ref('')
const categoryFilter = ref<ItemCategory | 'all'>('all')
const editorShow = ref(false)
const scannerShow = ref(false)

onMounted(async () => {
  try {
    await store.pull(true)
  } finally {
    loading.value = false
  }
})

const filtered = computed(() => {
  let rows = store.list
  if (categoryFilter.value !== 'all') {
    rows = rows.filter((i) => i.category === categoryFilter.value)
  }
  const q = tagQuery.value.trim().toLowerCase()
  if (q) {
    rows = rows.filter((i) => i.tags.some((t) => t.toLowerCase().includes(q)))
  }
  return rows
})

function categoryLabel(cat: ItemCategory): string {
  return CATEGORY_OPTIONS.find((o) => o.value === cat)?.label ?? cat
}

function openDetail(item: Item) {
  router.push({ name: 'item-detail', params: { id: item.id } })
}

function onScanned(id: string) {
  const hit = store.byId(id)
  if (hit) {
    openDetail(hit.data)
    return
  }
  router.push({ name: 'item-detail', params: { id } })
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1>物品</h1>
      <NSpace>
        <NButton data-testid="fab_scan" @click="scannerShow = true">扫码</NButton>
        <NButton type="primary" data-testid="fab_new" @click="editorShow = true">新建</NButton>
      </NSpace>
    </header>

    <NSpace class="filters" wrap>
      <NTag
        :type="categoryFilter === 'all' ? 'primary' : 'default'"
        checkable
        :checked="categoryFilter === 'all'"
        @update:checked="categoryFilter = 'all'"
      >
        全部
      </NTag>
      <NTag
        v-for="opt in CATEGORY_OPTIONS"
        :key="opt.value"
        checkable
        :checked="categoryFilter === opt.value"
        :type="categoryFilter === opt.value ? 'primary' : 'default'"
        @update:checked="categoryFilter = opt.value"
      >
        {{ opt.label }}
      </NTag>
    </NSpace>

    <NInput
      v-model:value="tagQuery"
      placeholder="按标签搜索"
      clearable
      data-testid="search_tags"
      style="max-width: 320px; margin: 12px 0"
    />

    <NSpin v-if="loading" />
    <NEmpty v-else-if="filtered.length === 0" description="暂无物品" />
    <div v-else class="grid" data-testid="grid_items">
      <div
        v-for="item in filtered"
        :key="item.id"
        class="card"
        :data-testid="`item_card_${item.id}`"
        @click="openDetail(item)"
      >
        <div class="card-title">{{ item.name }}</div>
        <div class="card-meta">{{ categoryLabel(item.category) }}</div>
        <NSpace v-if="item.tags.length" size="small" style="margin-top: 8px">
          <NTag v-for="t in item.tags" :key="t" size="small">{{ t }}</NTag>
        </NSpace>
      </div>
    </div>

    <ItemEditorDialog v-model:show="editorShow" @saved="(i) => openDetail(i)" />
    <ItemScanner v-model:show="scannerShow" @scanned="onScanned" />
  </div>
</template>

<style scoped>
.page {
  padding: 16px 20px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.card {
  border: 1px solid var(--n-border-color, #eee);
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
}
.card:hover {
  border-color: #18a058;
}
.card-title {
  font-weight: 600;
}
.card-meta {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 4px;
}
</style>
