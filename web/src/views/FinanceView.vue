<script setup lang="ts">
// ============================================================================
// 财务主视图（stage5-finance / Task 9 / TR-9.1）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.1
// 路径: web/src/views/FinanceView.vue
// 作用: 财务模块顶级视图；承载 Dashboard + 账户 / 卡 / 流水三类列表之间的
//       Tab 切换，以及跳转到对应编辑器的入口（与既有阶段 4a / 4b 同款风格）。
//
// 设计要点:
//   1. Composition API + <script setup lang="ts">;
//   2. 不引入新框架 —— 沿用 naive-ui + Pinia;
//   3. 零知识纪律 —— UI 文案/通知不渲染金额数字外露（仅 ¥ + 千分位整数）、
//      不渲染卡号后四位（仅显示尾号标签）、不渲染具体日期数字;
//   4. 不修改 financeStore / aggregator —— 本视图仅消费 store 数据;
//   5. financeStore 当前未接 vault 加密链路（明文 localStorage hydrate），
//      本任务不做加密切换（那是 T11 范围）;零知识仅作用于 UI 层文案。
//
// 关联:
//   - tasks.md TR-9.1（Web 财务主视图）
//   - tasks.md TR-9.7（财务路由注册 + AppShell NavBar 接入）
//   - web/src/stores/finance.ts（数据源）
//   - web/src/finance/aggregator.ts（聚合纯函数）
// ============================================================================

import { computed, onMounted, ref } from 'vue'
import { NButton, NRadioGroup, NRadio, NEmpty, NSpace } from 'naive-ui'
import FinanceDashboard from './finance/FinanceDashboard.vue'
import FinanceAccountList from './finance/FinanceAccountList.vue'
import FinanceCardList from './finance/FinanceCardList.vue'
import FinanceTxList from './finance/FinanceTxList.vue'
// 阶段 5 v2 —— 4 子类型列表（TR-1.4）
import FinanceSubscriptionList from './finance/FinanceSubscriptionList.vue'
import FinancePolicyList from './finance/FinancePolicyList.vue'
import FinanceLoanList from './finance/FinanceLoanList.vue'
import FinanceContractList from './finance/FinanceContractList.vue'
// 阶段 5 v2 B6 —— 预算列表（FR-V2-F 预算硬约束）
import BudgetList from './finance/BudgetList.vue'
import { useFinanceStore } from '../stores/finance'

const store = useFinanceStore()

// ========== Tab 切换状态 ==========
// 阶段 5 v2 —— 扩展 4 子类型 Tab（subscription / policy / loan / contract）；
// B6 追加 budgets（预算）。
type Tab =
  | 'dashboard'
  | 'accounts'
  | 'cards'
  | 'txs'
  | 'subscriptions'
  | 'policies'
  | 'loans'
  | 'contracts'
  | 'budgets'
const activeTab = ref<Tab>('dashboard')

// ========== 启动 hydration ==========
// 首屏前确保 store 已 hydrate —— 否则 listAccounts / listCards / listTxs 为空。
const hydrated = ref(false)
onMounted(async () => {
  if (!store.hydrated) {
    store.hydrate()
  }
  hydrated.value = true
})

// ========== 派生计数 ==========
const accountCount = computed(() => store.listAccounts.length)
const cardCount = computed(() => store.listCards.length)
const txCount = computed(() => store.listTxs.length)
const subCount = computed(() => store.listSubscriptions.length)
const polCount = computed(() => store.listPolicies.length)
const loanCount = computed(() => store.listLoans.length)
const ctCount = computed(() => store.listContracts.length)
const budgetCount = computed(() => store.listBudgets.length)

// ========== 新建跳转 ==========
// 编辑器走独立路由 /finance/editor/{type}/:id? —— 不在主视图内嵌对话框,
// 与 4a / 4b CalendarView 的 EventEditorDialog 模式区分（财务编辑器独立页面
// 用于承接更长的字段表单，且便于分享直达链接）。
function gotoEditor(
  type: 'account' | 'card' | 'tx' | 'subscription' | 'policy' | 'loan' | 'contract' | 'budget',
): void {
  const hash = '#/finance/editor/' + type
  window.location.hash = hash
}
</script>

<template>
  <div class="finance-view">
    <div class="page-head">
      <h2>财务</h2>
      <n-space class="head-tools" :size="10">
        <n-button v-if="activeTab === 'accounts'" size="small" type="primary" @click="gotoEditor('account')">
          + 新建账户
        </n-button>
        <n-button v-if="activeTab === 'cards'" size="small" type="primary" @click="gotoEditor('card')">
          + 新建卡
        </n-button>
        <n-button v-if="activeTab === 'txs'" size="small" type="primary" @click="gotoEditor('tx')">
          + 新建流水
        </n-button>
        <!-- 阶段 5 v2 —— 4 子类型新建按钮 -->
        <n-button v-if="activeTab === 'subscriptions'" size="small" type="primary" @click="gotoEditor('subscription')">
          + 新建订阅
        </n-button>
        <n-button v-if="activeTab === 'policies'" size="small" type="primary" @click="gotoEditor('policy')">
          + 新建保单
        </n-button>
        <n-button v-if="activeTab === 'loans'" size="small" type="primary" @click="gotoEditor('loan')">
          + 新建借款
        </n-button>
        <n-button v-if="activeTab === 'contracts'" size="small" type="primary" @click="gotoEditor('contract')">
          + 新建合同
        </n-button>
        <!-- B6 —— 预算新建按钮（列表内也有同入口） -->
        <n-button v-if="activeTab === 'budgets'" size="small" type="primary" @click="gotoEditor('budget')">
          + 新建预算
        </n-button>
      </n-space>
    </div>

    <n-radio-group v-model:value="activeTab" size="small" class="tabs">
      <n-radio value="dashboard">看板</n-radio>
      <n-radio value="accounts">账户 ({{ accountCount }})</n-radio>
      <n-radio value="cards">卡 ({{ cardCount }})</n-radio>
      <n-radio value="txs">流水 ({{ txCount }})</n-radio>
      <!-- 阶段 5 v2 —— 4 子类型 Tab -->
      <n-radio value="subscriptions">订阅 ({{ subCount }})</n-radio>
      <n-radio value="policies">保单 ({{ polCount }})</n-radio>
      <n-radio value="loans">借款 ({{ loanCount }})</n-radio>
      <n-radio value="contracts">合同 ({{ ctCount }})</n-radio>
      <n-radio value="budgets">预算 ({{ budgetCount }})</n-radio>
    </n-radio-group>

    <div v-if="!hydrated" class="state-block">
      <n-empty description="正在载入财务资料…" />
    </div>

    <template v-else>
      <FinanceDashboard v-if="activeTab === 'dashboard'" />
      <FinanceAccountList v-else-if="activeTab === 'accounts'" />
      <FinanceCardList v-else-if="activeTab === 'cards'" />
      <FinanceTxList v-else-if="activeTab === 'txs'" />
      <!-- 阶段 5 v2 —— 4 子类型列表挂载 -->
      <FinanceSubscriptionList v-else-if="activeTab === 'subscriptions'" />
      <FinancePolicyList v-else-if="activeTab === 'policies'" />
      <FinanceLoanList v-else-if="activeTab === 'loans'" />
      <FinanceContractList v-else-if="activeTab === 'contracts'" />
      <BudgetList v-else-if="activeTab === 'budgets'" />
    </template>
  </div>
</template>

<style scoped>
.finance-view {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
h2 {
  margin: 0;
  font-size: 18px;
}
.head-tools {
  align-items: center;
}
.tabs {
  display: flex;
}
.state-block {
  margin: 48px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
</style>
