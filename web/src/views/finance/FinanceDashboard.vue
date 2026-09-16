<script setup lang="ts">
// ============================================================================
// 财务资产看板视图（stage5-finance / Task 9 / TR-9.2 + stage5-finance-v2 B1 余下）
// ============================================================================
//
// 任务: stage5-finance / Task 9 / TR-9.2 + stage5-finance-v2 / TR-1.6
// 路径: web/src/views/finance/FinanceDashboard.vue
// 作用: 渲染五张数字卡（净资产 / 总资产 / 总负债 / 月支出 / 预算进度）;
//       数据源 = useFinanceStore() + aggregator 纯函数。
//       stage5-finance-v2 B1 余下 —— 追加 4 张"提醒窗口"卡:
//         即将续费订阅 / 即将到期保单 / 待还借款 / 即将结束合同。
//
// 设计要点:
//   1. 五张 NCard 网格 —— 大数字 + 副标题 + 进度条;
//   2. 净资产 = 总资产 - 总负债（aggregator.netWorth）;
//   3. 月支出 / 月收入 = aggregator.monthlyReport;
//   4. 预算进度 = aggregator.budgetThreshold（OK / WARNING / EXCEEDED 三档）;
//   5. v2 四张卡 —— 仅展示"提醒窗口内"的条目计数 + 最早到期项的相对天数;
//      不外露具体日期数字;不外露金额小数点精度;不渲染保单号尾号。
//   6. 空态 —— 全空数据时展示 n-empty, 不渲染异常数字;
//   7. 零知识纪律 —— 金额数字外露（千分位 + ¥ 前缀）, 但无小数点精度外泄;
//      不渲染具体日期数字;不渲染卡号尾号（dashboard 不出现卡数据）。
//
// 关联:
//   - tasks.md TR-9.2 / TR-1.6
//   - web/src/finance/aggregator.ts（聚合纯函数）
//   - web/src/stores/finance.ts（数据源）
//   - web/src/finance/types.ts（v2 子类型契约）
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
    txLikes.value.length === 0 &&
    store.listSubscriptions.length === 0 &&
    store.listPolicies.length === 0 &&
    store.listLoans.length === 0 &&
    store.listContracts.length === 0,
)

// ========== v2 提醒窗口卡片（stage5-finance-v2 / TR-1.6） ==========
// 提醒窗口阈值（天）—— 窗口内即视为"即将发生"。
//   SUBSCRIPTION_WINDOW_DAYS = 30   —— 下次扣费 30 天内
//   POLICY_WINDOW_DAYS       = 90   —— 保单到期 90 天内
//   CONTRACT_WINDOW_DAYS     = 90   —— 合同结束 90 天内
const SUBSCRIPTION_WINDOW_DAYS = 30
const POLICY_WINDOW_DAYS = 90
const CONTRACT_WINDOW_DAYS = 90
const MS_PER_DAY = 86_400_000

// 取本地"今日零点"时间戳（CST）—— 与 aggregator.yearMonthOf 口径一致,
// 避免跨时区导致"今天"被算成"昨天"。
function startOfTodayTs(): number {
  const now = Date.now()
  const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()
  const shifted = now + tzOffsetMin * 60_000
  const d = new Date(shifted)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) - tzOffsetMin * 60_000
}

// 距离 today 的整数天数（向下取整 —— "还有 X 天"语义）。
function daysUntil(ts: number): number {
  const today = startOfTodayTs()
  return Math.floor((ts - today) / MS_PER_DAY)
}

// 通用"窗口内最早项"拾取 —— 找不到则 null。
function earliestWithin<T extends { ts: number }>(
  rows: T[],
  windowDays: number,
): { row: T; days: number } | null {
  const today = startOfTodayTs()
  const horizon = today + windowDays * MS_PER_DAY
  let best: { row: T; days: number } | null = null
  for (const row of rows) {
    if (typeof row.ts !== 'number' || !Number.isFinite(row.ts)) continue
    if (row.ts < today) continue
    if (row.ts > horizon) continue
    const d = daysUntil(row.ts)
    if (best === null || d < best.days) best = { row, days: d }
  }
  return best
}

// ========== 即将续费订阅 ==========
const subInWindow = computed(() =>
  store.listSubscriptions.filter((s) => {
    const ts = (s as unknown as { next_renewal_ts: number }).next_renewal_ts
    if (typeof ts !== 'number' || !Number.isFinite(ts)) return false
    return ts >= startOfTodayTs() && ts <= startOfTodayTs() + SUBSCRIPTION_WINDOW_DAYS * MS_PER_DAY
  }),
)
const subEarliest = computed(() =>
  earliestWithin(
    store.listSubscriptions.map((s) => ({
      id: (s as unknown as { id?: string }).id ?? '',
      ts: (s as unknown as { next_renewal_ts: number }).next_renewal_ts,
    })),
    SUBSCRIPTION_WINDOW_DAYS,
  ),
)
const subWindowCount = computed(() => subInWindow.value.length)
function subWindowHint(): string {
  const e = subEarliest.value
  if (!e) return subWindowCount.value === 0 ? '无' : `${subWindowCount.value} 项待关注`
  return `最近 ${e.days} 天内`
}

// ========== 即将到期保单 ==========
const polInWindow = computed(() =>
  store.listPolicies.filter((p) => {
    const ts = (p as unknown as { expiry_ts: number }).expiry_ts
    if (typeof ts !== 'number' || !Number.isFinite(ts)) return false
    return ts >= startOfTodayTs() && ts <= startOfTodayTs() + POLICY_WINDOW_DAYS * MS_PER_DAY
  }),
)
const polEarliest = computed(() =>
  earliestWithin(
    store.listPolicies.map((p) => ({
      id: (p as unknown as { id?: string }).id ?? '',
      ts: (p as unknown as { expiry_ts: number }).expiry_ts,
    })),
    POLICY_WINDOW_DAYS,
  ),
)
const polWindowCount = computed(() => polInWindow.value.length)
function polWindowHint(): string {
  const e = polEarliest.value
  if (!e) return polWindowCount.value === 0 ? '无' : `${polWindowCount.value} 项待关注`
  return `最近 ${e.days} 天内`
}

// ========== 待还借款（status != paid 的全部条目，无窗口） ==========
const loanPending = computed(() =>
  store.listLoans.filter(
    (l) => (l as unknown as { status: string }).status !== 'paid',
  ),
)
const loanEarliestDue = computed(() =>
  earliestWithin(
    store.listLoans
      .filter((l) => (l as unknown as { status: string }).status !== 'paid')
      .map((l) => ({
        id: (l as unknown as { id?: string }).id ?? '',
        ts: (l as unknown as { due_ts: number }).due_ts,
      })),
    365 * 10, // 借款到期窗口拉宽到 10 年, 仅展示"最近一笔"提醒
  ),
)
const loanPendingCount = computed(() => loanPending.value.length)
function loanHint(): string {
  const e = loanEarliestDue.value
  if (!e) return loanPendingCount.value === 0 ? '无' : `${loanPendingCount.value} 笔待还`
  return `下一笔 ${e.days} 天内到期`
}

// ========== 即将结束合同 ==========
const ctInWindow = computed(() =>
  store.listContracts.filter((c) => {
    const ts = (c as unknown as { end_ts: number }).end_ts
    if (typeof ts !== 'number' || !Number.isFinite(ts)) return false
    return ts >= startOfTodayTs() && ts <= startOfTodayTs() + CONTRACT_WINDOW_DAYS * MS_PER_DAY
  }),
)
const ctEarliest = computed(() =>
  earliestWithin(
    store.listContracts.map((c) => ({
      id: (c as unknown as { id?: string }).id ?? '',
      ts: (c as unknown as { end_ts: number }).end_ts,
    })),
    CONTRACT_WINDOW_DAYS,
  ),
)
const ctWindowCount = computed(() => ctInWindow.value.length)
function ctWindowHint(): string {
  const e = ctEarliest.value
  if (!e) return ctWindowCount.value === 0 ? '无' : `${ctWindowCount.value} 份待关注`
  return `最近 ${e.days} 天内`
}

// ========== 跳转（点击 v2 卡片 → 列表 tab） ==========
function gotoList(hash: string): void {
  window.location.hash = hash
}
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

      <!-- ========== v2 提醒窗口卡片（stage5-finance-v2 / TR-1.6） ========== -->
      <div class="v2-section">
        <div class="v2-title">提醒窗口</div>
        <div class="grid v2-grid">
          <n-card
            class="cell v2-cell"
            hoverable
            @click="gotoList('#/finance?tab=subscriptions')"
          >
            <template #header>即将续费订阅</template>
            <div class="big-num">{{ subWindowCount }}</div>
            <div class="sub">30 天内 / {{ subWindowHint() }}</div>
          </n-card>

          <n-card
            class="cell v2-cell"
            hoverable
            @click="gotoList('#/finance?tab=policies')"
          >
            <template #header>即将到期保单</template>
            <div class="big-num">{{ polWindowCount }}</div>
            <div class="sub">90 天内 / {{ polWindowHint() }}</div>
          </n-card>

          <n-card
            class="cell v2-cell"
            hoverable
            @click="gotoList('#/finance?tab=loans')"
          >
            <template #header>待还借款</template>
            <div class="big-num">{{ loanPendingCount }}</div>
            <div class="sub">未结清 / {{ loanHint() }}</div>
          </n-card>

          <n-card
            class="cell v2-cell"
            hoverable
            @click="gotoList('#/finance?tab=contracts')"
          >
            <template #header>即将结束合同</template>
            <div class="big-num">{{ ctWindowCount }}</div>
            <div class="sub">90 天内 / {{ ctWindowHint() }}</div>
          </n-card>
        </div>
        <n-space class="meta" :size="14">
          <span class="meta-item">v2 子类型 · 纯客户端提醒窗口</span>
          <span class="meta-item">不同步上行 · 不外露具体日期</span>
        </n-space>
      </div>
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
.v2-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}
.v2-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  letter-spacing: 0.4px;
}
.v2-grid {
  /* 复用 .grid 网格,4 张卡在小屏折行 */
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
}
.v2-cell {
  cursor: pointer;
  transition: transform 120ms ease;
}
.v2-cell:hover {
  transform: translateY(-1px);
}
</style>
