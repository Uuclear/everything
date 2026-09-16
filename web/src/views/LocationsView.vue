<script setup lang="ts">
// 轨迹页（阶段 4a / tasks.md Task 9 + Task 10）：月份切换 + 日历高亮 + 当日统计
// + 时间线 + leaflet 地图 + 轨迹回放 + visit 命名（"标记地点"对话框）。
//
// 零知识红线（TR-9.2 / Task 10）：页面只渲染 store 内存中的分析产物；
// 明文坐标不落盘、不进日志；错误态文案不含任何坐标信息。
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NAlert, NButton, NEmpty, NInput, NModal, NSpin, NTag } from 'naive-ui'
import LocationMap from '../components/LocationMap.vue'
import { useAuthStore } from '../stores/auth'
import { useLocationsStore } from '../stores/locations'
import { useVaultStore } from '../stores/vault'
import {
  calendarCells,
  formatDurationMs,
  formatHHmm,
  formatKm,
  timelineItems,
  tzOffsetMinNow,
  type TimelineItem,
} from '../locations/month'
import { SPEED_LEVELS, positionAt } from '../locations/playback'
import { visitPlaceName } from '../locations/places'
import type { TrackPoint, Visit } from '../locations/core/types'

const auth = useAuthStore()
const loc = useLocationsStore()
const vault = useVaultStore()

// 本地时区偏移：页面生命周期内取一次即可（跨时区旅行场景刷新页面后重取）。
const tz = tzOffsetMinNow()

// 初次进入装载当前月（未解锁时 store 置 'locked' 错误态，见下引导）。
onMounted(() => {
  void loc.loadMonth(loc.year, loc.month)
})

// ---- 日历 ----
const cells = computed(() => calendarCells(loc.year, loc.month, tz))
/** 有数据日集合（O(1) 高亮查询）。 */
const dataDays = computed(() => new Set(loc.daysWithData))
const monthLabel = computed(() => `${loc.year}年${loc.month}月`)
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']

// ---- 当日统计与时间线 ----
const timeline = computed(() => loc.selectedTimeline)
const stats = computed(() => timeline.value?.stats ?? null)
const items = computed(() => (timeline.value ? timelineItems(timeline.value) : []))

/** visit 卡标题：place 索引查名，未命名显示"未命名地点"（不展示坐标）。 */
function visitTitle(v: Visit): string {
  return visitPlaceName(loc.placesByGeohash, v) ?? '未命名地点'
}

/** HH:mm 区间（跨夜 visit 的结束时刻归次日，由"跨夜"标注表达）。 */
function timeRange(startTs: number, endTs: number): string {
  return `${formatHHmm(startTs, tz)}–${formatHHmm(endTs, tz)}`
}

function retry() {
  void loc.loadMonth(loc.year, loc.month)
}

// ---- 轨迹回放（Task 10）：rAF 驱动，虚拟时间 = 真实流逝 × 倍速 ----

const playing = ref(false)
/** 倍速档位下标（SPEED_LEVELS = [1, 4, 16, 60]，循环切换）。 */
const speedIdx = ref(0)
const speed = computed(() => SPEED_LEVELS[speedIdx.value])
/** 回放虚拟时刻（UTC 毫秒；null = 未开始回放，隐藏播放头）。 */
const playT = ref<number | null>(null)
let rafId = 0
/** 上一帧 rAF 时间戳（首帧 dt 记 0，避免挂载瞬间跳变）。 */
let lastFrameTs = 0

/**
 * 回放点流：当日全部 trip 段轨迹点按 ts 升序拼接。
 * visit 簇不保留点（DayTimeline 契约），播放头在 visit 时段内于两段间线性过渡。
 */
const playPoints = computed<TrackPoint[]>(() => {
  const tl = timeline.value
  if (!tl) return []
  return tl.trips.flatMap((trip) => trip.points).sort((a, b) => a.ts - b.ts)
})
const playStart = computed(() => playPoints.value[0]?.ts ?? null)
const playEnd = computed(() => playPoints.value[playPoints.value.length - 1]?.ts ?? null)
/** 播放头插值位置（positionAt 纯函数；null 时地图隐藏 marker）。 */
const playheadPos = computed(() =>
  playT.value === null ? null : positionAt(playPoints.value, playT.value),
)

/** rAF 帧回调：按真实流逝 × 倍速推进虚拟时间，到末尾自动停住。 */
function tick(frameTs: number) {
  if (!playing.value) return
  const dt = lastFrameTs === 0 ? 0 : frameTs - lastFrameTs
  lastFrameTs = frameTs
  const end = playEnd.value
  if (playT.value === null || end === null) {
    stopPlayback()
    return
  }
  playT.value = Math.min(playT.value + dt * speed.value, end)
  if (playT.value >= end) {
    stopPlayback() // 播到末尾：停住但保留播放头在终点
    return
  }
  rafId = requestAnimationFrame(tick)
}

function startPlayback() {
  const start = playStart.value
  const end = playEnd.value
  if (start === null || end === null || start >= end) return
  // 未开始或已停在末尾：从头播。
  if (playT.value === null || playT.value >= end) playT.value = start
  playing.value = true
  lastFrameTs = 0
  rafId = requestAnimationFrame(tick)
}

function stopPlayback() {
  playing.value = false
  if (rafId) {
    cancelAnimationFrame(rafId)
    rafId = 0
  }
}

function togglePlayback() {
  if (playing.value) stopPlayback()
  else startPlayback()
}

/** 倍速循环：1x → 4x → 16x → 60x → 1x。 */
function cycleSpeed() {
  speedIdx.value = (speedIdx.value + 1) % SPEED_LEVELS.length
}

/** 时间轴滑块拖动：直接定位虚拟时刻（播放中则从该处继续）。 */
function onSeek(e: Event) {
  playT.value = Number((e.target as HTMLInputElement).value)
}

/** 回放联动高亮：最后一个 startTs ≤ t 的时间线条目为"当前项"。 */
const activeItemKey = computed<string | null>(() => {
  const t = playT.value
  if (t === null) return null
  let active: TimelineItem | null = null
  for (const it of items.value) {
    if (it.startTs <= t) active = it
    else break // items 已按 startTs 升序
  }
  return active ? `${active.kind}-${active.startTs}` : null
})

// 选日/切月变化：停止回放并隐藏播放头（时间点流已整体更换）。
watch(timeline, () => {
  stopPlayback()
  playT.value = null
})

onBeforeUnmount(stopPlayback)

// ---- visit 命名（Task 10）："标记地点"对话框 ----

/** 正在命名的 visit（null = 对话框关闭）。 */
const namingVisit = ref<Visit | null>(null)
/** 自定义名称输入。 */
const namingText = ref('')
const namingSaving = ref(false)

/** 打开对话框：预填该 visit 已有名称（便于改名）。 */
function openNaming(v: Visit) {
  namingText.value = visitPlaceName(loc.placesByGeohash, v) ?? ''
  namingVisit.value = v
}

function closeNaming() {
  if (namingSaving.value) return // 保存中禁止误关
  namingVisit.value = null
}

/** 提交命名：geohash7 → place 记录幂等覆盖（见 vault.savePlace）。 */
async function submitNaming(name: string, category: string) {
  const v = namingVisit.value
  const trimmed = name.trim()
  if (!v || !trimmed || namingSaving.value) return
  namingSaving.value = true
  try {
    await vault.savePlace(trimmed, category, v)
    namingVisit.value = null // 缓存已即时更新，时间线/地图自动显示新名
  } finally {
    namingSaving.value = false
  }
}

/** 自定义名称提交（空文本忽略）。 */
function submitCustomNaming() {
  void submitNaming(namingText.value, 'custom')
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>轨迹</h2>
      <!-- 月份切换：上一月 / 当前月 / 下一月 -->
      <div class="month-switch">
        <n-button size="small" quaternary :disabled="loc.loading" @click="loc.shiftMonth(-1)">
          ‹ 上一月
        </n-button>
        <span class="month-label">{{ monthLabel }}</span>
        <n-button size="small" quaternary :disabled="loc.loading" @click="loc.shiftMonth(1)">
          下一月 ›
        </n-button>
      </div>
    </div>

    <!-- 加载态 -->
    <div v-if="loc.loading" class="state-block">
      <n-spin size="medium" description="正在装载轨迹…" />
    </div>

    <!-- 未解锁：引导解锁（mk=null 时不缓存任何明文） -->
    <n-empty
      v-else-if="loc.error === 'locked'"
      class="state-block"
      description="轨迹已端到端加密，解锁后查看轨迹"
    >
      <template #extra>
        <span class="state-hint">请在顶部锁定界面输入主密码解锁（{{ auth.username }}）</span>
      </template>
    </n-empty>

    <!-- 解密失败：不泄坐标，仅提示可重试 -->
    <n-alert v-else-if="loc.error === 'decrypt_failed'" type="error" class="state-block" title="轨迹数据解密失败">
      密文块无法由当前主密钥解开（可能来自其他账户或数据损坏）。坐标不会被展示。
      <n-button size="tiny" class="retry-btn" @click="retry">重试</n-button>
    </n-alert>

    <!-- 网络/服务端失败 -->
    <n-alert v-else-if="loc.error === 'load_failed'" type="warning" class="state-block" title="轨迹装载失败">
      网络或服务端异常，请稍后重试。
      <n-button size="tiny" class="retry-btn" @click="retry">重试</n-button>
    </n-alert>

    <template v-else>
      <!-- 日历网格（周一开头；有数据日期高亮点标；选中日载入） -->
      <div class="calendar">
        <span v-for="w in WEEKDAYS" :key="w" class="weekday">{{ w }}</span>
        <button
          v-for="c in cells"
          :key="c.dayKey"
          type="button"
          class="day-cell"
          :class="{
            outside: !c.inMonth,
            selected: c.dayKey === loc.selectedDay,
            'has-data': dataDays.has(c.dayKey),
          }"
          :disabled="!c.inMonth"
          @click="loc.selectDay(c.dayKey)"
        >
          <span class="day-num">{{ c.dayOfMonth }}</span>
          <span v-if="c.inMonth && dataDays.has(c.dayKey)" class="data-dot" />
        </button>
      </div>

      <!-- 当日统计行：总距离（km 一位小数）/ 移动时长 / 停留数 / 轨迹点数 -->
      <div v-if="stats" class="stats-row">
        <div class="stat">
          <span class="stat-value">{{ formatKm(stats.distanceM) }}</span>
          <span class="stat-label">总距离 km</span>
        </div>
        <div class="stat">
          <span class="stat-value">{{ formatDurationMs(stats.movingMs) }}</span>
          <span class="stat-label">移动时长</span>
        </div>
        <div class="stat">
          <span class="stat-value">{{ stats.visitCount }}</span>
          <span class="stat-label">停留</span>
        </div>
        <div class="stat">
          <span class="stat-value">{{ stats.pointCount }}</span>
          <span class="stat-label">轨迹点</span>
        </div>
      </div>

      <div class="main-split">
        <!-- 时间线（左栏）：visit 卡与 trip 段按开始时刻混排 -->
        <div class="timeline">
          <n-empty
            v-if="items.length === 0"
            description="当日无轨迹数据"
            class="timeline-empty"
          />
          <template v-else>
            <div
              v-for="item in items"
              :key="`${item.kind}-${item.startTs}`"
              class="timeline-item"
              :class="{ 'play-active': `${item.kind}-${item.startTs}` === activeItemKey }"
            >
              <!-- visit 卡：名称 / HH:mm–HH:mm / 时长 / 跨夜标注 / 标记地点 -->
              <div v-if="item.kind === 'visit'" class="visit-card">
                <div class="visit-head">
                  <span class="visit-dot" />
                  <span class="visit-name">{{ visitTitle(item.visit) }}</span>
                  <n-tag v-if="item.visit.overnight" size="tiny" type="warning" :bordered="false">
                    跨夜
                  </n-tag>
                  <!-- 标记地点（Task 10）：打开命名对话框 -->
                  <n-button size="tiny" quaternary class="mark-btn" @click="openNaming(item.visit)">
                    标记地点
                  </n-button>
                </div>
                <div class="visit-meta">
                  {{ timeRange(item.visit.startTs, item.visit.endTs) }} ·
                  停留 {{ formatDurationMs(item.visit.endTs - item.visit.startTs) }}
                </div>
              </div>
              <!-- trip 段：距离 / 时长 / 起止时刻 -->
              <div v-else class="trip-row">
                <span class="trip-icon">→</span>
                <span class="trip-text">
                  移动 {{ formatKm(item.trip.distanceM) }} km ·
                  {{ formatDurationMs(item.trip.endTs - item.trip.startTs) }} ·
                  {{ timeRange(item.trip.startTs, item.trip.endTs) }}
                </span>
              </div>
            </div>
          </template>
        </div>

        <!-- 右栏：leaflet 地图（Task 10）+ 回放控制条 -->
        <div class="map-column">
          <LocationMap :timeline="timeline" :places="loc.placesByGeohash" :playhead="playheadPos" />
          <!-- 回放控制条：播放/暂停、倍速循环、时间轴滑块、当前虚拟时刻 -->
          <div v-if="playStart !== null && playEnd !== null && playStart < playEnd" class="playback-bar">
            <n-button size="small" type="primary" secondary @click="togglePlayback">
              {{ playing ? '暂停' : '播放' }}
            </n-button>
            <n-button size="small" quaternary class="speed-btn" @click="cycleSpeed">
              {{ speed }}x
            </n-button>
            <input
              type="range"
              class="seek"
              :min="playStart"
              :max="playEnd"
              :value="playT ?? playStart"
              aria-label="回放时间轴"
              @input="onSeek"
            />
            <span class="play-time">{{ playT !== null ? formatHHmm(playT, tz) : '--:--' }}</span>
          </div>
        </div>
      </div>
    </template>

    <!-- "标记地点"对话框（Task 10）：快捷 家/公司 + 自定义名称 -->
    <n-modal
      :show="namingVisit !== null"
      preset="card"
      title="标记地点"
      class="naming-modal"
      :mask-closable="!namingSaving"
      @update:show="closeNaming"
    >
      <div class="naming-quick">
        <n-button
          size="small"
          secondary
          :loading="namingSaving"
          @click="submitNaming('家', 'home')"
        >
          家
        </n-button>
        <n-button
          size="small"
          secondary
          :loading="namingSaving"
          @click="submitNaming('公司', 'work')"
        >
          公司
        </n-button>
      </div>
      <div class="naming-custom">
        <n-input
          v-model:value="namingText"
          size="small"
          placeholder="自定义名称"
          maxlength="64"
          @keyup.enter="submitCustomNaming"
        />
        <n-button
          size="small"
          type="primary"
          :disabled="!namingText.trim()"
          :loading="namingSaving"
          @click="submitCustomNaming"
        >
          保存
        </n-button>
      </div>
      <div class="naming-hint">同位置重复命名将覆盖旧名称（端到端加密，服务端只见密文）</div>
    </n-modal>
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
.month-switch {
  display: flex;
  align-items: center;
  gap: 8px;
}
.month-label {
  font-size: 14px;
  font-weight: 600;
  min-width: 84px;
  text-align: center;
}
.state-block {
  margin: 48px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.state-hint {
  font-size: 12px;
  color: #8a8f9c;
}
.retry-btn {
  margin-left: 8px;
}

/* 日历网格：7 列等宽，周一开头 */
.calendar {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  padding: 10px;
  margin-bottom: 12px;
}
.weekday {
  text-align: center;
  font-size: 12px;
  color: #8a8f9c;
  padding: 4px 0;
}
.day-cell {
  position: relative;
  height: 40px;
  border: none;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  font-size: 13px;
  color: #1f2430;
}
.day-cell:hover:not(:disabled) {
  background: #f0f2f5;
}
.day-cell:disabled {
  cursor: default;
}
.day-cell.outside {
  color: #c3c7d0;
}
.day-cell.has-data {
  font-weight: 600;
}
.day-cell.selected {
  background: #e8f0fe;
  color: #1a66ff;
}
.data-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #1a66ff;
}
.day-cell.selected .data-dot {
  background: #1a66ff;
}

/* 当日统计行 */
.stats-row {
  display: flex;
  gap: 12px;
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  padding: 12px 16px;
  margin-bottom: 12px;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 96px;
}
.stat-value {
  font-size: 17px;
  font-weight: 700;
}
.stat-label {
  font-size: 12px;
  color: #8a8f9c;
}

/* 左时间线 + 右地图占位 */
.main-split {
  display: flex;
  gap: 12px;
  align-items: stretch;
}
.timeline {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  padding: 12px 16px;
}
.timeline-empty {
  margin: 32px 0;
}
.timeline-item + .timeline-item {
  margin-top: 10px;
}
.visit-card {
  border: 1px solid #efeff2;
  border-radius: 8px;
  padding: 10px 12px;
}
.visit-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.visit-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #18a058;
  flex: none;
}
.visit-name {
  font-size: 14px;
  font-weight: 600;
}
.visit-meta {
  margin-top: 4px;
  margin-left: 16px;
  font-size: 12px;
  color: #8a8f9c;
}
.trip-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 12px;
  font-size: 12px;
  color: #8a8f9c;
}
.trip-icon {
  color: #b6bcc9;
}

/* 回放联动高亮：当前时间线条目淡蓝底（与日历选中日同色系）。 */
.timeline-item.play-active .visit-card,
.timeline-item.play-active.trip-row,
.timeline-item.play-active .trip-row {
  background: #e8f0fe;
  border-radius: 8px;
}
.mark-btn {
  margin-left: auto;
  color: #1a66ff;
}

/* 右栏：地图 + 回放控制条 */
.map-column {
  flex: none;
  width: 420px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.playback-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 1px solid #efeff2;
  border-radius: 10px;
  padding: 8px 12px;
}
.speed-btn {
  min-width: 44px;
  font-variant-numeric: tabular-nums;
}
.seek {
  flex: 1;
  min-width: 0;
  accent-color: #1a66ff;
}
.play-time {
  font-size: 12px;
  color: #8a8f9c;
  font-variant-numeric: tabular-nums;
  min-width: 40px;
  text-align: right;
}

/* "标记地点"对话框 */
.naming-modal {
  max-width: 360px;
}
.naming-quick {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.naming-custom {
  display: flex;
  gap: 8px;
}
.naming-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #8a8f9c;
}
</style>
