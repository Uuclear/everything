<script setup lang="ts">
// 轨迹地图组件（阶段 4a / tasks.md Task 10）：
//   - L.map + L.tileLayer，瓦片 URL 读 localStorage['eve.locations.tileUrl']，
//     缺省 OSM 源 + 署名；设置入口可自定义源（isValidTileUrl 校验，非法不保存）；
//   - trip 段统一配色 polyline；visit 渲染 circleMarker + 100m circle，
//     命名/未命名两态配色；播放头 L.marker（divIcon 纯 CSS，规避 vite 下
//     leaflet 默认 png 图标的路径问题，见 Task 10 Notes）；
//   - 选日变化（timeline 引用替换）时重绘并 fitBounds。
//
// 零知识红线：组件只消费 props 传入的内存明文，自身不持久化任何坐标；
// 唯一落盘项是瓦片 URL（非轨迹数据，tasks.md 明文纪律的唯一例外）。

import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import L from 'leaflet'
import { NButton, NInput, NPopover } from 'naive-ui'
import type { DayTimeline } from '../locations/core/types'
import type { PlaybackPosition } from '../locations/playback'
import type { PlaceIndexEntry } from '../locations/places'
import { visitPlaceName } from '../locations/places'
import {
  DEFAULT_TILE_URL,
  OSM_ATTRIBUTION,
  isValidTileUrl,
  loadTileUrl,
  saveTileUrl,
} from '../locations/tile'

const props = defineProps<{
  /** 当前选中本地日的时间线（null = 无数据/未选，清空图层）。 */
  timeline: DayTimeline | null
  /** place 命名索引（visit 两态配色与命名的数据源）。 */
  places: Map<string, PlaceIndexEntry>
  /** 播放头插值位置（null = 未回放，隐藏 marker）。 */
  playhead: PlaybackPosition | null
}>()

// ---- 配色（统一契约：trip 蓝；visit 命名绿 / 未命名橙）----
const TRIP_COLOR = '#1a66ff'
const VISIT_NAMED_COLOR = '#18a058'
const VISIT_UNNAMED_COLOR = '#f0a020'
/** visit 覆盖半径（与 place 记录 radius_m 契约一致）。 */
const VISIT_RADIUS_M = 100

const mapEl = ref<HTMLDivElement | null>(null)
let map: L.Map | null = null
let tileLayer: L.TileLayer | null = null
/** 轨迹图层组（trip polyline + visit 圈），重绘时整体清空重建。 */
let trackGroup: L.LayerGroup | null = null
/** 播放头 marker（懒创建，随后仅 setLatLng 移动）。 */
let headMarker: L.Marker | null = null

// ---- 瓦片源设置入口 ----
const tileInput = ref('')
const tileError = ref('')
const settingsOpen = ref(false)

/** 打开设置弹层：回填当前生效的 URL，清空上次错误提示。 */
function openSettings() {
  tileInput.value = loadTileUrl()
  tileError.value = ''
  settingsOpen.value = true
}

/** 保存瓦片源：校验通过才持久化并热替换 tileLayer；非法值提示不保存。 */
function applyTileUrl() {
  if (!isValidTileUrl(tileInput.value)) {
    tileError.value = '需以 http(s):// 开头且包含 {z}/{x}/{y} 占位符'
    return
  }
  if (!saveTileUrl(tileInput.value)) return // localStorage 不可用：保守不动
  tileError.value = ''
  settingsOpen.value = false
  mountTileLayer() // 热替换瓦片层（不动轨迹图层与播放头）
}

/** 按当前配置（重新）挂载瓦片层；仅缺省 OSM 源附署名。 */
function mountTileLayer() {
  if (!map) return
  if (tileLayer) {
    tileLayer.remove()
    tileLayer = null
  }
  const url = loadTileUrl()
  tileLayer = L.tileLayer(url, {
    maxZoom: 19,
    attribution: url === DEFAULT_TILE_URL ? OSM_ATTRIBUTION : '',
  }).addTo(map)
}

// ---- 轨迹渲染 ----

/** 重建当日轨迹图层并按内容 fitBounds；无内容时清空不动视角。 */
function redraw() {
  if (!map || !trackGroup) return
  trackGroup.clearLayers()

  const tl = props.timeline
  if (!tl) return

  // trip 段：统一配色折线（段内点即 ts 升序）。
  for (const trip of tl.trips) {
    if (trip.points.length < 2) continue // 单点段无折线可画
    L.polyline(
      trip.points.map((p) => [p.lat, p.lon] as [number, number]),
      { color: TRIP_COLOR, weight: 3, opacity: 0.8 },
    ).addTo(trackGroup)
  }

  // visit：circleMarker（质心点）+ circle（100m 覆盖圈），命名/未命名两态配色。
  for (const visit of tl.visits) {
    const named = visitPlaceName(props.places, visit) !== null
    const color = named ? VISIT_NAMED_COLOR : VISIT_UNNAMED_COLOR
    const center: [number, number] = [visit.centerLat, visit.centerLon]
    L.circleMarker(center, {
      radius: 6,
      color,
      weight: 2,
      fillColor: color,
      fillOpacity: 0.9,
    }).addTo(trackGroup)
    L.circle(center, {
      radius: VISIT_RADIUS_M,
      color,
      weight: 1,
      opacity: 0.6,
      fillColor: color,
      fillOpacity: 0.08,
    }).addTo(trackGroup)
  }

  // 有内容时自适应视野（visit 圈也算入 bounds）。
  const bounds = L.featureGroup(trackGroup.getLayers() as L.Layer[]).getBounds()
  if (bounds.isValid()) {
    map.fitBounds(bounds.pad(0.15))
  }
}

/** 播放头位置更新：null 隐藏；否则懒创建 divIcon marker 并移动。 */
function updateHead() {
  if (!map) return
  if (!props.playhead) {
    if (headMarker) {
      headMarker.remove()
      headMarker = null
    }
    return
  }
  const at: [number, number] = [props.playhead.lat, props.playhead.lon]
  if (!headMarker) {
    headMarker = L.marker(at, {
      // divIcon 纯 CSS 圆点：规避 vite 下 leaflet 默认 png 图标资源路径问题。
      icon: L.divIcon({ className: 'playback-head-icon', iconSize: [14, 14] }),
      interactive: false,
      keyboard: false,
      zIndexOffset: 1000, // 始终在 visit 圈之上
    }).addTo(map)
  } else {
    headMarker.setLatLng(at)
  }
}

onMounted(() => {
  if (!mapEl.value) return
  map = L.map(mapEl.value, {
    center: [30, 110], // 初始视野（首次 fitBounds 前仅占位，不含任何用户坐标）
    zoom: 4,
    zoomControl: true,
  })
  mountTileLayer()
  trackGroup = L.layerGroup().addTo(map)
  redraw()
  updateHead()
})

onBeforeUnmount(() => {
  // 销毁地图实例，释放瓦片请求与事件监听（明文图层随 DOM 一并移除）。
  map?.remove()
  map = null
  tileLayer = null
  trackGroup = null
  headMarker = null
})

// 选日变化 / 命名变化 → 重绘（timeline 与 places 均为整体替换的不可变引用）。
watch(() => [props.timeline, props.places], redraw)
// 回放推进 → 移动播放头。
watch(() => props.playhead, updateHead)
</script>

<template>
  <div class="location-map">
    <!-- leaflet 挂载容器 -->
    <div ref="mapEl" class="map-canvas" />
    <!-- 瓦片源设置入口（右上角齿轮；瓦片请求会暴露大致视窗给瓦片服务商，可自配源） -->
    <n-popover
      v-model:show="settingsOpen"
      trigger="manual"
      placement="bottom-end"
      :show-arrow="false"
    >
      <template #trigger>
        <button type="button" class="settings-btn" title="瓦片源设置" @click="openSettings">⚙</button>
      </template>
      <div class="settings-panel">
        <div class="settings-title">自定义瓦片源</div>
        <n-input
          v-model:value="tileInput"
          size="small"
          placeholder="https://…/{z}/{x}/{y}.png"
          :status="tileError ? 'error' : undefined"
        />
        <div v-if="tileError" class="settings-error">{{ tileError }}</div>
        <div class="settings-hint">留空即恢复缺省（OSM）；瓦片请求会向源站暴露大致浏览区域</div>
        <div class="settings-actions">
          <n-button size="tiny" quaternary @click="tileInput = DEFAULT_TILE_URL">恢复缺省</n-button>
          <n-button size="tiny" type="primary" @click="applyTileUrl">保存</n-button>
        </div>
      </div>
    </n-popover>
  </div>
</template>

<style scoped>
.location-map {
  position: relative;
  flex: none;
  width: 420px;
  min-height: 320px;
  border: 1px solid #efeff2;
  border-radius: 10px;
  overflow: hidden;
}
.map-canvas {
  position: absolute;
  inset: 0;
}
/* 设置按钮悬浮于地图右上角（避开 leaflet 缩放控件所在的左上）。 */
.settings-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 500; /* 高于 leaflet pane（400） */
  width: 28px;
  height: 28px;
  border: 1px solid #d6dae3;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
.settings-btn:hover {
  background: #f0f2f5;
}
.settings-panel {
  width: 260px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.settings-title {
  font-size: 13px;
  font-weight: 600;
}
.settings-error {
  font-size: 12px;
  color: #d03050;
}
.settings-hint {
  font-size: 12px;
  color: #8a8f9c;
}
.settings-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
/*
 * 播放头样式（divIcon 的 DOM 由 leaflet 在运行时创建，须 :deep 穿透 scoped）；
 * 同时覆盖 leaflet-div-icon 默认的白底边框。
 */
:deep(.playback-head-icon) {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #d03050;
  border: 2px solid #fff;
  box-shadow: 0 0 4px rgba(0, 0, 0, 0.45);
  box-sizing: border-box;
}
</style>
