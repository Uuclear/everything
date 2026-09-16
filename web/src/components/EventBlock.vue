<script setup lang="ts">
// 阶段 4b — 单事件显示块（tasks.md Task 8 / TR-8.2）。
//
// 设计目标：
//   1. **最小复用单元**：MonthView / WeekView 都用它；本身不做数据拉取，
//      纯展示一个 Occurrence + 关联 EventRule；点击回调由父级传入。
//   2. **零知识**：只读 store.list 后父级把 rule.data 透传到这里；本组件
//      不再调 store，避免多层订阅造成的密文缓存冗余。
//   3. **色板一致**：与 editor.ts COLOR_OPTIONS 的 hex 严格对齐；同一事件
//      所有展开实例同色（继承自 rule.color，Occurrence.color 与 rule.color
//      同值）。
//   4. **形态**：窄列（月视图）只显示标题色块；周视图整列宽时显示 HH:mm–HH:mm。
//   5. **跨日/全天**：由父级决定显示形态（MonthView 全天走独立栅格；WeekView
//      跨日由父级计算 colspan）——本组件只承担单格内的渲染。
import { computed } from 'vue'
import type { EventRule, Occurrence } from '../events/types'
import { COLOR_OPTIONS } from '../events/editor'

// ---- props/emit ----
// rule:  关联的明文 EventRule（提供 title/location_text 用于显示）
// occurrence: 展开实例（提供 start_ts/end_ts/color/all_day）
// compact: 月视图窄列模式（只显示小色块 + 标题前几字）；省略时分区间
// onClick: 点击事件块回调，由父级打开编辑器
const props = withDefaults(
  defineProps<{
    rule: EventRule
    occurrence: Occurrence
    compact?: boolean
    showTime?: boolean
  }>(),
  { compact: false, showTime: false },
)
const emit = defineEmits<{
  (e: 'click', payload: { rule: EventRule; occurrence: Occurrence }): void
}>()

// ---- 派生 ----
/** 由 color 枚举查 hex；COLOR_OPTIONS 是单一权威映射源。 */
const bgColor = computed<string>(() => {
  const opt = COLOR_OPTIONS.find((o) => o.value === props.occurrence.color)
  return opt?.hex ?? '#1a66ff'
})
/** 时间区间字符串（HH:mm–HH:mm）；跨日由父级决定是否需要展开（WeekView 用）。 */
const timeRange = computed<string>(() => {
  const s = new Date(props.occurrence.start_ts)
  const e = new Date(props.occurrence.end_ts)
  const f = (d: Date) =>
    `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  // 注：getHours/getMinutes 返回**本地**时钟，符合 spec "本地时区语义"。
  return `${f(s)}–${f(e)}`
})
/** 标题截断（窄列模式）。 */
const titleText = computed<string>(() => props.rule.title)

function onClick() {
  emit('click', { rule: props.rule, occurrence: props.occurrence })
}
</script>

<template>
  <button
    type="button"
    class="event-block"
    :class="{ compact, allDay: occurrence.all_day }"
    :style="{ borderLeftColor: bgColor, backgroundColor: bgColor + '22' }"
    :title="rule.title"
    @click="onClick"
  >
    <!-- 窄列模式（月视图）：仅显示标题前几字；色块由左侧 border 表达 -->
    <template v-if="compact">
      <span class="dot" :style="{ backgroundColor: bgColor }" />
      <span class="title-compact">{{ titleText }}</span>
    </template>
    <!-- 完整模式（周视图）：HH:mm–HH:mm + 标题 -->
    <template v-else>
      <span v-if="showTime" class="time">{{ timeRange }}</span>
      <span class="title">{{ titleText }}</span>
    </template>
  </button>
</template>

<style scoped>
.event-block {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  border: none;
  border-left: 3px solid transparent;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  white-space: nowrap;
  color: #1f2430;
  background: #f5f6f8;
  transition: filter 0.12s;
}
.event-block:hover {
  filter: brightness(0.96);
}
.event-block.compact {
  padding: 1px 4px;
  font-size: 11px;
  gap: 4px;
}
.event-block.allDay {
  font-weight: 600;
}
.event-block .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex: none;
}
.event-block .title,
.event-block .title-compact {
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 500;
}
.event-block .time {
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: #4a5060;
  flex: none;
}
</style>
