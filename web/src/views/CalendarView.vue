<script setup lang="ts">
// 阶段 4b — 日历主页（tasks.md Task 8 / TR-8.3）。
//
// 设计目标：
//   1. **月/周视图切换**：n-radio-group 控制 mode（'month'|'week'）；
//   2. **同步状态**：onMounted 调 useEventRulesStore().pull()；
//   3. **事件块点击 → 打开 EventEditorDialog** 编辑；
//   4. **新建按钮 → 打开 EventEditorDialog** 空白（presetStartTs 为选中日）。
//   5. **风格与 LocationsView 同款**：page-head + 子视图组件 + 状态兜底。
//   6. **零知识**：与 4a 同款，错误态文案不含任何明文信息。

import { onMounted, ref } from 'vue'
import { NButton, NRadioGroup, NRadio, NEmpty } from 'naive-ui'
import MonthView from './MonthView.vue'
import WeekView from './WeekView.vue'
import EventEditorDialog from '../components/EventEditorDialog.vue'
import { useEventRulesStore } from '../stores/event-rules'
import { tsToLocalDayKey } from '../events/editor'
import type { EventRule, Occurrence } from '../events/types'

const store = useEventRulesStore()

// ---- 视图状态 ----
const mode = ref<'month' | 'week'>('month')
const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()

// 当前查看月（1-12）；默认本月
const today = new Date()
const currentYear = ref(today.getFullYear())
const currentMonth = ref(today.getMonth() + 1)

/** 当前查看周的周一 'YYYY-MM-DD'。 */
const currentWeekStartKey = ref<string>(
  tsToLocalDayKey(weekStartMs(today.getTime(), tzOffsetMin), tzOffsetMin),
)

function weekStartMs(ts: number, tz: number): number {
  const d = new Date(ts + tz * 60_000)
  const iso = (d.getUTCDay() + 6) % 7 // 0=Mon
  // 回到本周一 00:00 本地
  const y = d.getUTCFullYear()
  const m = d.getUTCMonth()
  const dd = d.getUTCDate()
  return Date.UTC(y, m, dd - iso, 0, 0, 0, 0) - tz * 60_000
}

// ---- 同步 ----
const initialLoading = ref(true)
onMounted(async () => {
  try {
    await store.pull(true)
  } catch {
    // 错误态由 store.syncing 之外显式 catch；不暴露错误明细
  } finally {
    initialLoading.value = false
  }
})

// ---- 编辑器状态 ----
const editorShow = ref(false)
const editorRule = ref<EventRule | null>(null)
const editorPresetStartTs = ref<number | null>(null)

/** 事件块点击：编辑已有事件。 */
function onEventClick(payload: { rule: EventRule; occurrence: Occurrence }) {
  editorRule.value = payload.rule
  editorPresetStartTs.value = null
  editorShow.value = true
}

/** 新建：从空白日开始，preset 为该日 09:00。 */
function onNewEvent(dayKey: string) {
  editorRule.value = null
  const [y, m, d] = dayKey.split('-').map(Number)
  // 该日 09:00 本地 → ms
  editorPresetStartTs.value = Date.UTC(y, m - 1, d, 9, 0, 0, 0) - tzOffsetMin * 60_000
  editorShow.value = true
}

function onEditorSaved() {
  editorShow.value = false
  editorRule.value = null
  editorPresetStartTs.value = null
}

function onEditorClosed() {
  editorShow.value = false
  editorRule.value = null
  editorPresetStartTs.value = null
}

// ---- 月/周切换 ----
function shiftMonth(delta: number) {
  let y = currentYear.value
  let m = currentMonth.value + delta
  if (m < 1) {
    m = 12
    y -= 1
  } else if (m > 12) {
    m = 1
    y += 1
  }
  currentYear.value = y
  currentMonth.value = m
}
function shiftWeek(delta: number) {
  const ms =
    Date.UTC(
      Number(currentWeekStartKey.value.slice(0, 4)),
      Number(currentWeekStartKey.value.slice(5, 7)) - 1,
      Number(currentWeekStartKey.value.slice(8, 10)),
    ) -
    tzOffsetMin * 60_000
  const next = ms + delta * 7 * 86_400_000
  currentWeekStartKey.value = tsToLocalDayKey(next, tzOffsetMin)
}

function newEvent() {
  // 顶部"新建"按钮：以当前查看月/周的首日为预填
  if (mode.value === 'month') {
    onNewEvent(
      `${currentYear.value}-${String(currentMonth.value).padStart(2, '0')}-01`,
    )
  } else {
    onNewEvent(currentWeekStartKey.value)
  }
}

// ---- 派生 ----
// 注：标题"日历"写在模板 h2，月份标签透到 MonthView 自身渲染；这里不再保留未用变量。
</script>

<template>
  <div>
    <div class="page-head">
      <h2>日历</h2>
      <div class="head-tools">
        <n-radio-group v-model:value="mode" size="small">
          <n-radio value="month">月</n-radio>
          <n-radio value="week">周</n-radio>
        </n-radio-group>
        <n-button size="small" type="primary" @click="newEvent">+ 新建</n-button>
      </div>
    </div>

    <!-- 初始加载 -->
    <div v-if="initialLoading" class="state-block">
      <n-empty description="正在同步日程…" />
    </div>

    <!-- 解锁态：store 在 unlock 后才可读；同步失败 → 提示重试 -->
    <template v-else>
      <MonthView
        v-if="mode === 'month'"
        :year="currentYear"
        :month="currentMonth"
        :loading="store.syncing"
        @shift-month="shiftMonth"
        @event-click="onEventClick"
        @new-event="onNewEvent"
      />
      <WeekView
        v-else
        :week-start-day-key="currentWeekStartKey"
        :loading="store.syncing"
        @shift-week="shiftWeek"
        @event-click="onEventClick"
        @new-event="onNewEvent"
      />
    </template>

    <!-- 编辑器对话框（新建 + 编辑两态合一；内部 props.rule? 区分） -->
    <EventEditorDialog
      :show="editorShow"
      :rule="editorRule"
      :preset-start-ts="editorPresetStartTs"
      @update:show="onEditorClosed"
      @saved="onEditorSaved"
    />
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
.head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
.state-block {
  margin: 48px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
</style>
