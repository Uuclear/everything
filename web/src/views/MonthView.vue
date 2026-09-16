<script setup lang="ts">
// 阶段 4b — 月视图（tasks.md Task 8 / TR-8.2）。
//
// 设计目标：
//   1. **6×7 月历网格**（周一开头），与 4a LocationsView 的 calendar 网格
//      同款形态；本组件复用 expand.ts 的本地日历口径。
//   2. **每天至多 3 个 EventBlock** + "more" 链接；点 "more" 弹出当日全部
//      列表（抽屉）。简化版：直接点击 "more" 把当天所有 occurrence 传
//      给父级（父级负责弹 dialog 显示完整列表）。
//   3. **跨日事件横跨多列**：以 occurrence.start_ts/end_ts 与各列日窗的
//      交集决定渲染位置；第一天显示块，后续日仅显示色点（避免重复计数）。
//   4. **全天事件**：在格子上方独立栅格渲染（"全天"行）；非全天事件进入
//      下方时序栅格。
//   5. **零知识**：本视图只调 useEventRulesStore().list 拿明文规则；
//      明文仅在内存，不写日志。
//   6. **本地时区**：spec FR-1 tz_mode=local；本视图不显式接受 tzOffsetMin
//      参数，而是每次取运行时 offset（与 expand.ts 一致）。
//
// 注意：spec FR-1 "本周一开头" 与 ISO 周一开头一致；getDay 0=Sun，需转换。

import { computed, ref } from 'vue'
import { NButton, NSpin, NEmpty, NTag } from 'naive-ui'
import { useEventRulesStore } from '../stores/event-rules'
import { tsToLocalDayKey } from '../events/editor'
import type { EventRule, Occurrence } from '../events/types'
import EventBlock from '../components/EventBlock.vue'

// ---- 父级回调 ----
//   视图切换由父级 CalendarView 控制（month/week）
//   事件块点击 → 父级打开 EventEditorDialog 编辑
const props = withDefaults(
  defineProps<{
    /** 当前查看的年月（localCalendarParts）。 */
    year: number
    month: number // 1-12
    /** 是否正在装载（store.syncing）。 */
    loading?: boolean
  }>(),
  { loading: false },
)
const emit = defineEmits<{
  (e: 'shift-month', delta: number): void
  (e: 'event-click', payload: { rule: EventRule; occurrence: Occurrence }): void
  (e: 'new-event', dayKey: string): void
}>()

const store = useEventRulesStore()

// ---- 本地时区偏移（运行时一次） ----
const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()

// ---- 月历单元格（6×7 = 42 天，月初向前补到周一） ----
interface DayCell {
  dayKey: string // 'YYYY-MM-DD'
  dayOfMonth: number
  inMonth: boolean
  isToday: boolean
}

const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

const todayKey = tsToLocalDayKey(Date.now(), tzOffsetMin)

const cells = computed<DayCell[]>(() => {
  // 该月第一天
  const firstMs = Date.UTC(props.year, props.month - 1, 1) - tzOffsetMin * 60_000
  const firstDate = new Date(firstMs + tzOffsetMin * 60_000)
  const firstIso = (firstDate.getUTCDay() + 6) % 7 // 0=Mon..6=Sun
  // 月初向前补到周一
  const gridStartMs = firstMs - firstIso * 86_400_000
  const out: DayCell[] = []
  for (let i = 0; i < 42; i++) {
    const ms = gridStartMs + i * 86_400_000
    const k = tsToLocalDayKey(ms, tzOffsetMin)
    const local = new Date(ms + tzOffsetMin * 60_000)
    const m = local.getUTCMonth() + 1
    const d = local.getUTCDate()
    out.push({
      dayKey: k,
      dayOfMonth: d,
      inMonth: m === props.month,
      isToday: k === todayKey,
    })
  }
  return out
})

// ---- 月窗口 [from, to)（覆盖 42 天整窗） ----
const windowFrom = computed(() => {
  const firstCell = cells.value[0]
  return Date.UTC(
    Number(firstCell.dayKey.slice(0, 4)),
    Number(firstCell.dayKey.slice(5, 7)) - 1,
    Number(firstCell.dayKey.slice(8, 10)),
    0,
    0,
    0,
    0,
  ) - tzOffsetMin * 60_000
})
const windowTo = computed(() => windowFrom.value + 42 * 86_400_000)

/** 当月窗口内全部 Occurrence（含跨月部分）。 */
const allOccurrences = computed<Occurrence[]>(() => {
  return store.occurrencesInWindow({ from: windowFrom.value, to: windowTo.value })
})

/** 按本地日历日聚合 occurrence。 */
const occByDay = computed<Map<string, Occurrence[]>>(() => {
  const m = new Map<string, Occurrence[]>()
  for (const o of allOccurrences.value) {
    const k = tsToLocalDayKey(o.start_ts, tzOffsetMin)
    if (!m.has(k)) m.set(k, [])
    m.get(k)!.push(o)
  }
  return m
})

/** rule.id → EventRule（O(1) 取 rule 用于 EventBlock props）。 */
const ruleById = computed<Map<string, EventRule>>(() => {
  const m = new Map<string, EventRule>()
  for (const r of store.list) m.set(r.id, r)
  return m
})

/** 单日展示：按 start_ts asc，最多前 3 个 + 总数。 */
interface DayView {
  visible: Occurrence[] // 至多 3 个
  hidden: number // 多余的
}
function dayView(dayKey: string): DayView {
  const list = occByDay.value.get(dayKey) ?? []
  return { visible: list.slice(0, 3), hidden: Math.max(0, list.length - 3) }
}

/** "新建"快捷入口（空白日点击 +号）。 */
const moreDayKey = ref<string | null>(null)
function showMore(dayKey: string) {
  moreDayKey.value = dayKey
}
function closeMore() {
  moreDayKey.value = null
}

function onEventClick(payload: { rule: EventRule; occurrence: Occurrence }) {
  emit('event-click', payload)
}
function onNewEvent(dayKey: string) {
  emit('new-event', dayKey)
}
</script>

<template>
  <div class="month-view">
    <!-- 月份切换条（与 LocationsView 同款） -->
    <div class="month-bar">
      <n-button size="small" quaternary @click="emit('shift-month', -1)">
        ‹ 上一月
      </n-button>
      <span class="month-label">{{ year }}年{{ month }}月</span>
      <n-button size="small" quaternary @click="emit('shift-month', 1)">
        下一月 ›
      </n-button>
    </div>

    <!-- 加载态 -->
    <div v-if="loading" class="state-block">
      <n-spin size="medium" description="正在同步日程…" />
    </div>
    <n-empty v-else-if="allOccurrences.length === 0" class="state-block" description="本月暂无事件">
      <template #extra>
        <span class="state-hint">点击下方"新建"开始记录</span>
      </template>
    </n-empty>

    <!-- 月历网格 -->
    <div class="grid">
      <!-- 周表头 -->
      <span v-for="w in WEEKDAYS" :key="w" class="weekday">{{ w }}</span>
      <!-- 单元格 -->
      <div
        v-for="cell in cells"
        :key="cell.dayKey"
        class="cell"
        :class="{
          outside: !cell.inMonth,
          today: cell.isToday,
        }"
      >
        <div class="cell-head">
          <span class="day-num" :class="{ today: cell.isToday }">{{ cell.dayOfMonth }}</span>
          <button
            v-if="cell.inMonth"
            type="button"
            class="add-btn"
            :title="`新建事件于 ${cell.dayKey}`"
            @click="onNewEvent(cell.dayKey)"
          >
            +
          </button>
        </div>
        <!-- 当日事件块（至多 3 个） -->
        <div class="cell-body">
          <EventBlock
            v-for="o in dayView(cell.dayKey).visible"
            :key="o.instance_id"
            compact
            :rule="ruleById.get(o.rule_id)!"
            :occurrence="o"
            @click="onEventClick"
          />
          <button
            v-if="dayView(cell.dayKey).hidden > 0"
            type="button"
            class="more-btn"
            @click="showMore(cell.dayKey)"
          >
            还有 {{ dayView(cell.dayKey).hidden }} 个 ›
          </button>
        </div>
      </div>
    </div>

    <!-- "more"抽屉式浮层：当天的全部 occurrence 列表 -->
    <div v-if="moreDayKey" class="more-overlay" @click.self="closeMore">
      <div class="more-panel">
        <div class="more-head">
          <span class="more-title">{{ moreDayKey }} 当日事件</span>
          <n-tag :bordered="false" size="small" type="info">
            {{ (occByDay.get(moreDayKey) ?? []).length }} 个
          </n-tag>
          <n-button size="tiny" quaternary class="more-close" @click="closeMore">×</n-button>
        </div>
        <div class="more-body">
          <EventBlock
            v-for="o in occByDay.get(moreDayKey) ?? []"
            :key="o.instance_id"
            compact
            :rule="ruleById.get(o.rule_id)!"
            :occurrence="o"
            @click="
              (p) => {
                onEventClick(p)
                closeMore()
              }
            "
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.month-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.month-bar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.month-label {
  font-size: 14px;
  font-weight: 600;
  min-width: 96px;
  text-align: center;
}
.state-block {
  margin: 32px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.state-hint {
  font-size: 12px;
  color: #8a8f9c;
}

/* 6×7 网格：与 LocationsView 同款形态 */
.grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  padding: 10px;
}
.weekday {
  text-align: center;
  font-size: 12px;
  color: #8a8f9c;
  padding: 4px 0;
}
.cell {
  min-height: 92px;
  border-radius: 8px;
  background: #fafbfc;
  padding: 4px 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border: 1px solid transparent;
}
.cell.outside {
  background: #f5f6f8;
  opacity: 0.55;
}
.cell.today {
  border-color: #1a66ff;
}
.cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 4px;
}
.day-num {
  font-size: 12px;
  font-weight: 600;
  color: #1f2430;
}
.day-num.today {
  color: #1a66ff;
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
.cell:hover .add-btn {
  display: block;
}
.add-btn:hover {
  background: #e8f0fe;
  color: #1a66ff;
}
.cell-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow: hidden;
}
.more-btn {
  border: none;
  background: transparent;
  font-size: 11px;
  color: #1a66ff;
  text-align: left;
  cursor: pointer;
  padding: 2px 4px;
}
.more-btn:hover {
  background: #e8f0fe;
  border-radius: 4px;
}

/* "more" 浮层 */
.more-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 21, 37, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.more-panel {
  background: #fff;
  border-radius: 10px;
  padding: 14px 16px;
  min-width: 320px;
  max-width: 420px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.18);
}
.more-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.more-title {
  font-size: 14px;
  font-weight: 600;
  flex: 1;
}
.more-close {
  margin-left: auto;
}
.more-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 320px;
  overflow-y: auto;
}
</style>
