<script setup lang="ts">
// 阶段 4b — 周视图（tasks.md Task 8 / TR-8.2）。
//
// 设计目标：
//   1. **7 列 × N 行（每小时一行）**：列 = 周一到周日；行 = 00:00..23:00。
//      每格高度约 48px，固定行高便于事件块 absolute 定位。
//   2. **事件横跨**：occurrence.start_ts/end_ts 决定 top/height（绝对定位
//      在列内），跨日由父级或本视图切分；本期简化：跨日 occurrence 仅在
//      start_ts 所在日的列内渲染（颜色块表达"还在继续"），后续日不再重渲
//      —— 与多数日历产品行为一致。
//   3. **全天事件**：列顶独立栅格（allDay 行）。
//   4. **数据**：store.occurrencesInWindow({from, to}) 一次性拉整周。
//   5. **零知识**：仅读取内存中 store.list；不写日志。
//
// 本视图不显式接受 tzOffsetMin（与 MonthView 同款：取运行时 offset）。

import { computed, ref } from 'vue'
import { NButton, NSpin, NEmpty } from 'naive-ui'
import { useEventRulesStore } from '../stores/event-rules'
import { tsToLocalDayKey } from '../events/editor'
import type { EventRule, Occurrence } from '../events/types'
import EventBlock from '../components/EventBlock.vue'

// ---- props/emit ----
//   weekStartDayKey: 周一本地日历日 'YYYY-MM-DD'（父级计算）
const props = withDefaults(
  defineProps<{
    weekStartDayKey: string
    loading?: boolean
  }>(),
  { loading: false },
)
const emit = defineEmits<{
  (e: 'shift-week', delta: number): void
  (e: 'event-click', payload: { rule: EventRule; occurrence: Occurrence }): void
  (e: 'new-event', dayKey: string): void
}>()

const store = useEventRulesStore()

// ---- 本地时区 ----
const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()

// ---- 7 天列（周一开头） ----
interface DayColumn {
  dayKey: string
  dayOfMonth: number
  isToday: boolean
}
const todayKey = tsToLocalDayKey(Date.now(), tzOffsetMin)

const days = computed<DayColumn[]>(() => {
  const out: DayColumn[] = []
  const [y, m, d] = props.weekStartDayKey.split('-').map(Number)
  const baseMs = Date.UTC(y, m - 1, d, 0, 0, 0, 0) - tzOffsetMin * 60_000
  for (let i = 0; i < 7; i++) {
    const ms = baseMs + i * 86_400_000
    const k = tsToLocalDayKey(ms, tzOffsetMin)
    const local = new Date(ms + tzOffsetMin * 60_000)
    out.push({
      dayKey: k,
      dayOfMonth: local.getUTCDate(),
      isToday: k === todayKey,
    })
  }
  return out
})

// ---- 24 小时行（HH） ----
const HOURS = Array.from({ length: 24 }, (_, i) => i)
const ROW_HEIGHT_PX = 48 // 单小时行高

// ---- 时间窗 [from, to)（周一 00:00 本地起 7 天） ----
const windowFrom = computed(() => {
  const [y, m, d] = props.weekStartDayKey.split('-').map(Number)
  return Date.UTC(y, m - 1, d, 0, 0, 0, 0) - tzOffsetMin * 60_000
})
const windowTo = computed(() => windowFrom.value + 7 * 86_400_000)

const allOccurrences = computed<Occurrence[]>(() =>
  store.occurrencesInWindow({ from: windowFrom.value, to: windowTo.value }),
)

const ruleById = computed<Map<string, EventRule>>(() => {
  const m = new Map<string, EventRule>()
  for (const r of store.list) m.set(r.id, r)
  return m
})

/** 单日 all_day 事件（顶部独立栅格）。 */
function allDayFor(dayKey: string): Occurrence[] {
  return allOccurrences.value.filter(
    (o) => o.all_day && tsToLocalDayKey(o.start_ts, tzOffsetMin) === dayKey,
  )
}
/** 单日 非全天 事件（按 start_ts asc）。 */
function timedFor(dayKey: string): Occurrence[] {
  return allOccurrences.value.filter(
    (o) =>
      !o.all_day &&
      tsToLocalDayKey(o.start_ts, tzOffsetMin) === dayKey &&
      o.end_ts > windowFrom.value + days.value.findIndex((d) => d.dayKey === dayKey) * 86_400_000 &&
      o.start_ts < windowFrom.value + (days.value.findIndex((d) => d.dayKey === dayKey) + 1) * 86_400_000,
  )
}

/** 由 occurrence 算 top/height (px)。 */
interface BlockPos {
  top: number
  height: number
}
function posOf(o: Occurrence, dayStartMs: number): BlockPos {
  // 把 occurrence 截断到当天范围内
  const startMs = Math.max(o.start_ts, dayStartMs)
  const endMs = Math.min(o.end_ts, dayStartMs + 86_400_000)
  const startMin = (startMs - dayStartMs) / 60_000
  const durMin = (endMs - startMs) / 60_000
  return {
    top: (startMin / 60) * ROW_HEIGHT_PX,
    height: Math.max(20, (durMin / 60) * ROW_HEIGHT_PX), // 最小 20px 保证可见
  }
}

function dayStartMsOf(dayKey: string): number {
  const idx = days.value.findIndex((d) => d.dayKey === dayKey)
  return windowFrom.value + idx * 86_400_000
}

function onEventClick(p: { rule: EventRule; occurrence: Occurrence }) {
  emit('event-click', p)
}
function onNewEvent(dayKey: string) {
  emit('new-event', dayKey)
}

// ---- "now" 红线 ----
const nowTick = ref(Date.now())
const tickHandle = ref<number | null>(null)
import { onMounted, onBeforeUnmount } from 'vue'
onMounted(() => {
  tickHandle.value = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 60_000) // 1 分钟一刷
})
onBeforeUnmount(() => {
  if (tickHandle.value !== null) window.clearInterval(tickHandle.value)
})
const nowTop = computed<number>(() => {
  const [y, m, d] = todayKey.split('-').map(Number)
  const todayStartMs = Date.UTC(y, m - 1, d, 0, 0, 0, 0) - tzOffsetMin * 60_000
  const min = (nowTick.value - todayStartMs) / 60_000
  return Math.max(0, Math.min(24 * 60, min)) / 60 * ROW_HEIGHT_PX
})

/** 周范围标签（YYYY年M月D日 – YYYY年M月D日）。 */
const weekLabel = computed<string>(() => {
  const start = days.value[0]
  const end = days.value[6]
  const [y1, m1, d1] = start.dayKey.split('-').map(Number)
  const [y2, m2, d2] = end.dayKey.split('-').map(Number)
  return `${y1}年${m1}月${d1}日 – ${y2}年${m2}月${d2}日`
})
</script>

<template>
  <div class="week-view">
    <!-- 周切换条 -->
    <div class="week-bar">
      <n-button size="small" quaternary @click="emit('shift-week', -1)">
        ‹ 上一周
      </n-button>
      <span class="week-label">{{ weekLabel }}</span>
      <n-button size="small" quaternary @click="emit('shift-week', 1)">
        下一周 ›
      </n-button>
    </div>

    <div v-if="loading" class="state-block">
      <n-spin size="medium" description="正在同步日程…" />
    </div>
    <n-empty
      v-else-if="allOccurrences.length === 0"
      class="state-block"
      description="本周暂无事件"
    />

    <!-- 周主体：左侧时间列 + 7 个日列 -->
    <div v-else class="week-grid" :style="{ gridTemplateRows: `auto repeat(24, ${ROW_HEIGHT_PX}px)` }">
      <!-- 表头：周表头 + 7 日列头 -->
      <div class="corner" />
      <div
        v-for="d in days"
        :key="d.dayKey"
        class="day-head"
        :class="{ today: d.isToday }"
      >
        <span class="day-num">{{ d.dayOfMonth }}</span>
        <button
          type="button"
          class="add-btn"
          :title="`新建事件于 ${d.dayKey}`"
          @click="onNewEvent(d.dayKey)"
        >
          +
        </button>
      </div>

      <!-- 全天行（仅当存在 allDay 事件时显示；本期固定显示） -->
      <div class="all-day-row-label">全天</div>
      <div v-for="d in days" :key="`allday-${d.dayKey}`" class="all-day-cell">
        <EventBlock
          v-for="o in allDayFor(d.dayKey)"
          :key="o.instance_id"
          compact
          :rule="ruleById.get(o.rule_id)!"
          :occurrence="o"
          @click="onEventClick"
        />
      </div>

      <!-- 24 行 × 7 列 -->
      <template v-for="h in HOURS" :key="`row-${h}`">
        <div class="hour-label">
          {{ String(h).padStart(2, '0') }}:00
        </div>
        <div
          v-for="d in days"
          :key="`cell-${h}-${d.dayKey}`"
          class="cell"
          :class="{ today: d.isToday }"
        >
          <!-- 事件块（绝对定位） -->
          <EventBlock
            v-for="o in timedFor(d.dayKey)"
            :key="o.instance_id"
            show-time
            class="event-abs"
            :rule="ruleById.get(o.rule_id)!"
            :occurrence="o"
            :style="{
              position: 'absolute',
              left: '2px',
              right: '2px',
              top: posOf(o, dayStartMsOf(d.dayKey)).top + 'px',
              height: posOf(o, dayStartMsOf(d.dayKey)).height + 'px',
            }"
            @click="onEventClick"
          />
          <!-- "now" 红线 -->
          <div
            v-if="d.isToday && h === Math.floor((nowTop / ROW_HEIGHT_PX))"
            class="now-line"
            :style="{ top: nowTop + 'px' }"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.week-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.week-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.week-label {
  font-size: 14px;
  font-weight: 600;
  min-width: 220px;
  text-align: center;
}
.state-block {
  margin: 32px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* 7 列 × (1 表头 + 1 全天 + 24 时) 网格 */
.week-grid {
  display: grid;
  grid-template-columns: 64px repeat(7, 1fr);
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  overflow: hidden;
}
.corner {
  background: #fafbfc;
  border-bottom: 1px solid #efeff2;
  border-right: 1px solid #efeff2;
}
.day-head {
  background: #fafbfc;
  border-bottom: 1px solid #efeff2;
  border-right: 1px solid #efeff2;
  padding: 6px 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.day-head:last-child {
  border-right: none;
}
.day-head.today {
  background: #e8f0fe;
}
.day-num {
  font-size: 12px;
  font-weight: 600;
}
.add-btn {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: #8a8f9c;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  padding: 0;
  display: none;
}
.day-head:hover .add-btn {
  display: block;
}
.add-btn:hover {
  background: #fff;
  color: #1a66ff;
}

/* 全天行 */
.all-day-row-label {
  background: #fafbfc;
  border-right: 1px solid #efeff2;
  border-bottom: 1px solid #efeff2;
  padding: 4px 6px;
  font-size: 11px;
  color: #8a8f9c;
  display: flex;
  align-items: center;
}
.all-day-cell {
  background: #fff;
  border-right: 1px solid #efeff2;
  border-bottom: 1px solid #efeff2;
  padding: 4px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-height: 36px;
}
.all-day-cell:last-child {
  border-right: none;
}

/* 时段行 */
.hour-label {
  background: #fafbfc;
  border-right: 1px solid #efeff2;
  border-bottom: 1px solid #efeff2;
  padding: 2px 6px;
  font-size: 11px;
  color: #8a8f9c;
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.cell {
  border-right: 1px solid #efeff2;
  border-bottom: 1px solid #efeff2;
  position: relative;
  min-height: 48px;
}
.cell:last-child {
  border-right: none;
}
.cell.today {
  background: #fafbfd;
}
.event-abs {
  z-index: 1;
}
.now-line {
  position: absolute;
  left: 0;
  right: 0;
  height: 1px;
  background: #d03050;
  z-index: 2;
  pointer-events: none;
}
.now-line::before {
  content: '';
  position: absolute;
  left: -4px;
  top: -3px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d03050;
}
</style>
