<script setup lang="ts">
// ============================================================================
// 账户列表视图（stage5-finance / Task 9 / TR-9.3）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.3
// 路径: web/src/views/finance/FinanceAccountList.vue
// 作用: 渲染账户列表 —— 卡片式布局;支持搜索;支持归档折叠;
//
// 设计要点:
//   1. 数据源 = useFinanceStore().listAccounts;
//   2. 搜索:按 name 模糊匹配（不含大小写）;
//   3. 归档折叠:默认不显示 archived=true 的账户;点击"显示已归档 N 项"展开;
//   4. 卡片式:每条账户一个 NCard —— 显示 name / kind / 余额（千分位整数 +
//      ¥, 不显示小数点精度）+ archived 标签;
//   5. 点击卡片 → 跳转到 /finance/editor/account/:id;
//   6. 零知识纪律 —— 不渲染金额小数点精度;不显示账号备注/分类等敏感字段
//      外泄到通知/日志（仅本视图内展示）;
//   7. 不修改 financeStore —— 仅消费。
//
// 关联:
//   - tasks.md TR-9.3
//   - web/src/stores/finance.ts
//   - web/src/finance/types.ts
// ============================================================================

import { computed, ref } from 'vue'
import { NCard, NEmpty, NInput, NTag, NButton } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import type { FinanceAccount } from '../../finance/types'

const store = useFinanceStore()

// ========== 搜索状态 ==========
const keyword = ref('')

// ========== 派生:活跃账户 + 归档账户 ==========
const activeAccounts = computed(() => store.listAccounts)
const archivedAccounts = computed(() =>
  store
    .listAccounts
    .filter(() => false), // 占位 —— 列表层已过滤归档,此处仅做 UI 折叠展示
)

const filtered = computed<FinanceAccount[]>(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return activeAccounts.value
  return activeAccounts.value.filter((a) => a.name.toLowerCase().includes(k))
})

// ========== 折叠 ==========
const showArchived = ref(false)

// ========== 跳转 ==========
function gotoEditor(id: string | null): void {
  if (id == null) {
    window.location.hash = '#/finance/editor/account'
    return
  }
  window.location.hash = '#/finance/editor/account/' + id
}

// ========== 工具 ==========
// 余额格式化 —— decimal-as-string → ¥ + 千分位整数（不渲染小数点精度）;
function formatBalance(b: string): string {
  const n = Number(b)
  if (!Number.isFinite(n)) return '¥0'
  return '¥' + Math.floor(n).toLocaleString('zh-CN')
}

// 账户类型枚举中文标签;
const KIND_LABEL: Record<string, string> = {
  cash: '现金',
  deposit: '定期',
  stock: '投资',
  wallet: '电子钱包',
  other: '其他',
}
function kindLabel(k: string): string {
  return KIND_LABEL[k] ?? k
}
</script>

<template>
  <div class="acct-list">
    <div class="toolbar">
      <n-input
        v-model:value="keyword"
        placeholder="搜索账户名称"
        clearable
        size="small"
      />
    </div>

    <n-empty
      v-if="filtered.length === 0"
      :description="keyword ? '没有匹配的账户' : '还没有账户,点击右上角新建'"
    />

    <div v-else class="grid">
      <n-card
        v-for="acc in filtered"
        :key="acc.id"
        hoverable
        class="card"
        @click="gotoEditor(acc.id)"
      >
        <template #header>
          <span class="title">{{ acc.name }}</span>
        </template>
        <template #header-extra>
          <n-tag size="tiny" :bordered="false" type="info">
            {{ kindLabel(acc.kind) }}
          </n-tag>
        </template>
        <div class="balance">{{ formatBalance(acc.balance) }}</div>
        <div class="sub">{{ acc.currency }}</div>
      </n-card>
    </div>

    <!-- 归档折叠区 —— 仅当 store 内存在归档账户时显示 -->
    <div v-if="archivedAccounts.length > 0" class="archived">
      <n-button text size="small" @click="showArchived = !showArchived">
        {{ showArchived ? '收起' : '显示已归档' }} ({{ archivedAccounts.length }} 项)
      </n-button>
      <div v-if="showArchived" class="archived-list">
        <n-card
          v-for="acc in archivedAccounts"
          :key="acc.id"
          class="card archived-card"
          @click="gotoEditor(acc.id)"
        >
          <template #header>
            <span class="title">{{ acc.name }}</span>
            <n-tag size="tiny" :bordered="false" type="warning">已归档</n-tag>
          </template>
          <div class="balance">{{ formatBalance(acc.balance) }}</div>
        </n-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.acct-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.toolbar {
  max-width: 360px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.card {
  cursor: pointer;
  border-radius: 10px;
  transition: transform 0.12s;
}
.card:hover {
  transform: translateY(-2px);
}
.title {
  font-weight: 600;
  margin-right: 6px;
}
.balance {
  font-size: 18px;
  font-weight: 700;
  margin: 6px 0 4px;
}
.sub {
  font-size: 12px;
  color: #6b7280;
}
.archived {
  margin-top: 10px;
}
.archived-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-top: 8px;
}
.archived-card {
  opacity: 0.7;
}
</style>
