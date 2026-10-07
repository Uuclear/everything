<script setup lang="ts">
// 「今日与我」仪表盘：7 日内提醒 + 当日日程 + 今日轨迹点数（只读投影）。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NButton, NCard, NEmpty, NList, NListItem, NSpace, NSpin, NTag, NThing } from 'naive-ui'
import { useEventRulesStore } from '../stores/event-rules'
import { useFinanceStore } from '../stores/finance'
import { useItemsStore } from '../stores/items'
import { useLocationsStore } from '../stores/locations'
import { expiryLevel, useVaultStore } from '../stores/vault'
import { todayDayKey, tzOffsetMinNow } from '../locations/month'
import {
  HOME_HORIZON_DAYS,
  financeCardRemindersWithinDays,
  financeV2RemindersWithinDays,
  identitiesExpiringWithinDays,
  itemRemindersWithinDays,
  occurrencesOnLocalDay,
} from '../vault/aggregate'
import { IDENTITY_KIND_LABELS, type IdentityData } from '../types/vault'

const router = useRouter()
const vault = useVaultStore()
const finance = useFinanceStore()
const items = useItemsStore()
const events = useEventRulesStore()
const locations = useLocationsStore()

const booting = ref(true)
const bootError = ref<string | null>(null)

const nowMs = computed(() => Date.now())
const tz = tzOffsetMinNow()
const todayKey = computed(() => todayDayKey(tz))

onMounted(async () => {
  booting.value = true
  bootError.value = null
  try {
    const tasks: Promise<unknown>[] = []
    if (!finance.hydrated) finance.hydrate()
    tasks.push(events.pull().catch(() => undefined))
    tasks.push(items.pull(true).catch(() => undefined))
    const d = new Date()
    tasks.push(locations.loadMonth(d.getFullYear(), d.getMonth() + 1).catch(() => undefined))
    await Promise.all(tasks)
  } catch {
    bootError.value = '部分数据加载失败，可稍后重试同步'
  } finally {
    booting.value = false
  }
})

const identityRows = computed(() =>
  identitiesExpiringWithinDays(vault.byKind('identity'), HOME_HORIZON_DAYS),
)

const itemRows = computed(() => itemRemindersWithinDays(items.list, HOME_HORIZON_DAYS, nowMs.value))

const cardRows = computed(() =>
  financeCardRemindersWithinDays(finance.listCards, HOME_HORIZON_DAYS, nowMs.value),
)

const v2Rows = computed(() =>
  financeV2RemindersWithinDays(finance.upcomingV2Reminders(nowMs.value), HOME_HORIZON_DAYS, nowMs.value),
)

const todayEvents = computed(() => {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  const end = new Date(start)
  end.setDate(end.getDate() + HOME_HORIZON_DAYS)
  const occs = events.occurrencesInWindow({ from: start.getTime(), to: end.getTime() })
  return occurrencesOnLocalDay(occs, todayKey.value)
})

const todayPoints = computed(() => {
  const tl = locations.timelines.get(todayKey.value)
  if (!tl) {
    if (locations.loading) return { state: 'loading' as const }
    if (locations.error) return { state: 'error' as const }
    return { state: 'empty' as const }
  }
  return { state: 'ok' as const, count: tl.pointCount }
})

function formatTrigger(ms: number): string {
  const d = new Date(ms)
  return `${d.getMonth() + 1}/${d.getDate()} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}

function v2Label(kind: string): string {
  if (kind === 'subscription_renewal') return '订阅续费'
  if (kind === 'policy_expiry') return '保单到期'
  if (kind === 'loan_due') return '借款到期'
  return '财务提醒'
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>今日与我</h2>
      <n-space>
        <n-button size="small" quaternary @click="router.push({ name: 'archive' })">证件墙</n-button>
        <n-button size="small" quaternary @click="router.push({ name: 'calendar' })">日历</n-button>
      </n-space>
    </div>

    <n-spin v-if="booting" class="boot">
      <template #description>正在汇总本地数据…</template>
    </n-spin>

    <p v-if="bootError" class="warn">{{ bootError }}</p>

    <div v-if="!booting" class="grid">
      <n-card title="7 日内证件到期" size="small" class="card">
        <n-empty v-if="identityRows.length === 0" size="small" description="暂无即将到期证件" />
        <n-list v-else size="small">
          <n-list-item v-for="row in identityRows" :key="row.record.id">
            <n-thing>
              <template #header>
                {{ (row.record.data as IdentityData).title }}
                <n-tag size="tiny" :bordered="false">
                  {{ IDENTITY_KIND_LABELS[(row.record.data as IdentityData).kind] }}
                </n-tag>
              </template>
              <template #header-extra>
                <n-tag
                  v-if="expiryLevel((row.record.data as IdentityData).expires_on ?? '')"
                  size="small"
                  :type="
                    expiryLevel((row.record.data as IdentityData).expires_on ?? '') === 'soon'
                      ? 'warning'
                      : 'info'
                  "
                >
                  {{ row.days }} 天后
                </n-tag>
              </template>
            </n-thing>
          </n-list-item>
        </n-list>
      </n-card>

      <n-card title="提醒与账单" size="small" class="card">
        <n-empty
          v-if="itemRows.length === 0 && cardRows.length === 0 && v2Rows.length === 0"
          size="small"
          description="未来 7 天暂无物品/财务提醒"
        />
        <n-list v-else size="small">
          <n-list-item v-for="row in itemRows" :key="`item-${row.id}`">
            <n-thing :title="row.name" :description="`保修提醒 · ${formatTrigger(row.triggerMs)}`" />
          </n-list-item>
          <n-list-item v-for="row in cardRows" :key="`card-${row.id}`">
            <n-thing :title="row.name" :description="`账单/还款 · ${formatTrigger(row.triggerMs)}`" />
          </n-list-item>
          <n-list-item v-for="row in v2Rows" :key="`v2-${row.kind}-${row.id}`">
            <n-thing :title="v2Label(row.kind)" :description="formatTrigger(row.triggerMs)" />
          </n-list-item>
        </n-list>
      </n-card>

      <n-card title="今日日程" size="small" class="card">
        <n-empty v-if="todayEvents.length === 0" size="small" description="今天没有日程" />
        <n-list v-else size="small">
          <n-list-item v-for="occ in todayEvents" :key="occ.instance_id">
            <n-thing
              :title="occ.title"
              :description="new Date(occ.start_ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })"
            />
          </n-list-item>
        </n-list>
      </n-card>

      <n-card title="今日轨迹" size="small" class="card">
        <template v-if="todayPoints.state === 'loading'">
          <n-spin size="small" />
        </template>
        <template v-else-if="todayPoints.state === 'error'">
          <n-empty size="small" description="轨迹未加载，请打开轨迹页重试" />
          <n-button size="tiny" class="mt" @click="router.push({ name: 'locations' })">打开轨迹</n-button>
        </template>
        <template v-else-if="todayPoints.state === 'empty'">
          <n-empty size="small" description="今日暂无轨迹数据" />
        </template>
        <template v-else>
          <p class="stat">已记录 <strong>{{ todayPoints.count }}</strong> 个定位点</p>
          <n-button size="tiny" quaternary @click="router.push({ name: 'locations' })">查看地图</n-button>
        </template>
      </n-card>
    </div>
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
.boot {
  padding: 24px 0;
}
.warn {
  color: #c97a00;
  font-size: 13px;
  margin: 0 0 12px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.card {
  min-height: 120px;
}
.stat {
  margin: 0 0 8px;
  font-size: 14px;
}
.mt {
  margin-top: 8px;
}
</style>
