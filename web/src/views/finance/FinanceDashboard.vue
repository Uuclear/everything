<script setup lang="ts">
// ============================================================================
// 财务资产看板视图（stage5-finance / Task 9 / TR-9.2）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.2
// 路径: web/src/views/finance/FinanceDashboard.vue
// 作用: 渲染五张数字卡 —— 净资产 / 总资产 / 总负债 / 月支出 / 预算进度;
//       数据源 = useFinanceStore() + aggregator 纯函数。
//
// 设计要点:
//   1. 五张 NCard 网格 —— 大数字 + 副标题 + 进度条;
//   2. 净资产 = 总资产 - 总负债（aggregator.netWorth）;
//   3. 月支出 / 月收入 = aggregator.monthlyReport;
//   4. 预算进度 = aggregator.budgetThreshold（OK / WARNING / EXCEEDED 三档）;
//   5. 空态 —— 全空数据时展示 n-empty, 不渲染异常数字;
//   6. 零知识纪律 —— 金额数字外露（千分位 + ¥ 前缀）, 但无小数点精度外泄;
//      不渲染具体日期数字;不渲染卡号尾号（dashboard 不出现卡数据）。
//
// 关联:
//   - tasks.md TR-9.2
//   - web/src/finance/aggregator.ts（聚合纯函数）
//   - web/src/stores/finance.ts（数据源）
// ============================================================================

import { computed } from 'vue'
import { NCard, NEmpty, NProgress, NSpace, NTag } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import {
  netWorth,
  monthlyReport,
  budgetThreshold,
  toAccountLike,
  toCardLike,
  toTxLike,
} from '../../finance/aggregator'

const store = useFinanceStore()

// ========== 数据适配 ==========
// store 暴露 listAccounts / listCards / listTxs（明文 FinanceX）;
// aggregator 入参为 AccountLike / CardLike / TxLike —— 通过适配器转换。
const accountLikes = computed(() => store.listAccounts.map(toAccountLike))
const cardLikes = computed(() => store.listCards.map(toCardLike))
const txLikes = computed(() => store.listTxs.map(toTxLike))

// ========== 净资产看板 ==========
const dashboard = computed(() =>
  netWorth(accountLikes.value, cardLikes.value, txLikes.value),
)

// ========== 当月月报 ==========
// 取本地年月键（与 aggregator.yearMonthOf 同口径 —— CST 本地日历分量）。
function currentYearMonth(): string {
  const now = Date.now()
  const TZ_OFFSET_MIN = -new Date(1780000000000).getTimezoneOffset()
  const shifted = now + TZ_OFFSET_MIN * 60_000
  const d = new Date(shifted)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  return `${y}-${m}`
}

const month = computed(() => currentYearMonth())
const report = computed(() =>
  monthlyReport(month.value, txLikes.value, accountLikes.value),
)

// ========== 预算阈值（仅本地，无 DataStore 持久化） ==========
// v1 阶段预算阈值取"支出 / 收入"比（与 aggregator.budgetThreshold 语义一致）;
// 用户尚未提供预算值时, 默认 income > 0 时返回 OK;income = 0 时也返回 OK。
const budgetStatus = computed(() =>
  budgetThreshold(report.value.income, report.value.expense, 1.0),
)

// ========== 工具 ==========
// 把 decimal-as-string 转成"¥1,234"形式 —— 千分位 + ¥ 前缀（零知识口径下
// 仅暴露整数 + 千分位;小数点精度不外露）。
function formatYuan(cents: string): string {
  // cents 形如 "1234.56";转 cents * 100 → bigint 整数, 按元整数格式化。
  const num = Number(cents)
  if (!Number.isFinite(num)) return '¥0'
  const yuan = Math.floor(num)
  const formatted = yuan.toLocaleString('zh-CN')
  return '¥' + formatted
}

// 把 cents 比值换算成百分比（仅在 dashboard 内部展示预算进度条使用）;
function expenseRatioPct(): number {
  const inc = Number(report.value.income)
  const exp = Number(report.value.expense)
  if (inc <= 0) return 0
  const pct = (exp / inc) * 100
  // 钳位 0-999 避免渲染溢出。
  if (!Number.isFinite(pct)) return 0
  return Math.max(0, Math.min(999, Math.round(pct)))
}

// ========== 状态文案 ==========
function budgetStatusText(s: string): string {
  if (s === 'OK') return '健康'
  if (s === 'WARNING') return '预警'
  return '超支'
}
function budgetStatusType(s: string): 'success' | 'warning' | 'error' {
  if (s === 'OK') return 'success'
  if (s === 'WARNING') return 'warning'
  return 'error'
}

// ========== 空态 ==========
const empty = computed(
  () =>
    accountLikes.value.length === 0 &&
    cardLikes.value.length === 0 &&
    txLikes.value.length === 0,
)
</script>

<template>
  <div class="dashboard">
    <n-empty
      v-if="empty"
      description="还没有任何财务条目,先在「账户」或「卡」里新建第一条吧"
    />

    <template v-else>
      <!-- 五张数字卡 -->
      <div class="grid">
        <n-card class="cell" hoverable>
          <template #header>净资产</template>
          <div class="big-num">{{ formatYuan(dashboard.totalAssets) }}</div>
          <div class="sub">
            {{ dashboard.currency }}
            <n-tag size="tiny" :bordered="false" type="info">聚合</n-tag>
          </div>
        </n-card>

        <n-card class="cell" hoverable>
          <template #header>总资产</template>
          <div class="big-num">{{ formatYuan(dashboard.totalAssetValue) }}</div>
          <div class="sub">
            账户 {{ dashboard.accountCount }} 个
          </div>
        </n-card>

        <n-card class="cell" hoverable>
          <template #header>总负债</template>
          <div class="big-num">{{ formatYuan(dashboard.totalLiability) }}</div>
          <div class="sub">
            信用卡 {{ dashboard.cardCount }} 张
          </div>
        </n-card>

        <n-card class="cell" hoverable>
          <template #header>月支出 ({{ month }})</template>
          <div class="big-num">{{ formatYuan(report.expense) }}</div>
          <div class="sub">
            流水 {{ report.txCount }} 条
          </div>
        </n-card>

        <n-card class="cell" hoverable>
          <template #header>预算进度</template>
          <div class="big-num">
            <n-tag :type="budgetStatusType(budgetStatus)">
              {{ budgetStatusText(budgetStatus) }}
            </n-tag>
          </div>
          <n-progress
            class="budget-progress"
            :percentage="expenseRatioPct()"
            :show-indicator="false"
            :status="
              budgetStatus === 'OK'
                ? 'success'
                : budgetStatus === 'WARNING'
                  ? 'warning'
                  : 'error'
            "
          />
          <div class="sub">
            月收入 {{ formatYuan(report.income) }}
          </div>
        </n-card>
      </div>

      <n-space class="meta" :size="14">
        <span class="meta-item">净资产 = 总资产 − 总负债</span>
        <span class="meta-item">聚合纯客户端计算,不同步上行</span>
      </n-space>
    </template>
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.cell {
  border-radius: 10px;
}
.big-num {
  font-size: 22px;
  font-weight: 700;
  margin: 6px 0 4px;
  letter-spacing: 0.5px;
}
.sub {
  font-size: 12px;
  color: #6b7280;
}
.budget-progress {
  margin-top: 4px;
}
.meta {
  font-size: 12px;
  color: #6b7280;
  margin-top: 6px;
}
.meta-item {
  background: #eef2ff;
  padding: 4px 8px;
  border-radius: 6px;
}
</style>
