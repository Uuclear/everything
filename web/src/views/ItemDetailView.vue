<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NCard, NEmpty, NSpace, NTag, NModal } from 'naive-ui'
import ItemQrCard from '../components/ItemQrCard.vue'
import ItemEditorDialog from '../components/ItemEditorDialog.vue'
import { useItemsStore } from '../stores/items'
import { CATEGORY_OPTIONS, warrantyUntilLabel } from '../items/editor'

const route = useRoute()
const router = useRouter()
const store = useItemsStore()

const id = computed(() => String(route.params.id))
const cached = computed(() => store.byId(id.value))
const item = computed(() => cached.value?.data)

const editorShow = ref(false)
const deleteConfirm = ref(false)

function categoryLabel(cat: string): string {
  return CATEGORY_OPTIONS.find((o) => o.value === cat)?.label ?? cat
}

function onDelete() {
  deleteConfirm.value = true
}

async function confirmDelete() {
  if (!item.value) return
  await store.remove(item.value.id)
  deleteConfirm.value = false
  router.push({ name: 'items' })
}
</script>

<template>
  <div class="page">
    <NButton quaternary @click="router.push({ name: 'items' })">← 返回列表</NButton>

    <NEmpty v-if="!item" description="未找到该物品" data-testid="scanner_not_found" />

    <template v-else>
      <header class="page-head">
        <h1>{{ item.name }}</h1>
        <NSpace>
          <NButton data-testid="btn_edit" @click="editorShow = true">编辑</NButton>
          <NButton type="error" data-testid="btn_delete" @click="onDelete">删除</NButton>
        </NSpace>
      </header>

      <NCard title="基本信息" size="small">
        <p>分类：{{ categoryLabel(item.category) }}</p>
        <p v-if="item.brand">品牌：{{ item.brand }}</p>
        <p v-if="item.model">型号：{{ item.model }}</p>
        <p v-if="item.serial_no">序列号：{{ item.serial_no }}</p>
        <p v-if="item.location_text">位置：{{ item.location_text }}</p>
        <p>购买日：{{ new Date(item.purchase_date).toLocaleDateString() }}</p>
        <p>价格：{{ (item.purchase_price_cents / 100).toFixed(2) }} {{ item.currency }}</p>
        <p>保修：{{ item.warranty_duration_days }} 天</p>
        <p>保修截止：{{ warrantyUntilLabel(item.warranty_until_ts) }}</p>
        <p v-if="item.receipt_url">
          发票：
          <a :href="item.receipt_url" target="_blank" rel="noopener">链接</a>
        </p>
        <p v-if="item.note">备注：{{ item.note }}</p>
        <NSpace v-if="item.tags.length">
          <NTag v-for="t in item.tags" :key="t">{{ t }}</NTag>
        </NSpace>
      </NCard>

      <ItemQrCard :item-id="item.id" style="margin-top: 16px" />
    </template>

    <ItemEditorDialog v-model:show="editorShow" :item="item ?? null" />

    <NModal v-model:show="deleteConfirm" preset="dialog" title="确认删除？">
      <template #action>
        <NButton @click="deleteConfirm = false">取消</NButton>
        <NButton type="error" data-testid="btn_delete_confirm" @click="confirmDelete">删除</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.page {
  padding: 16px 20px;
  max-width: 720px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0;
}
</style>
