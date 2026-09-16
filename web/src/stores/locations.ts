// 轨迹页 store（阶段 4a / tasks.md Task 9）：按月装载密文块、内存解密分析、
// 选日与日历高亮、place 命名索引。
//
// 零知识红线（TR-9.2 / Notes）：
//   - 明文坐标只驻本 store 内存（timelines），绝不写 localStorage/IndexedDB、
//     不进日志；网络层只见 cipher base64；
//   - 未解锁（mk=null）不缓存任何明文：置 'locked' 错误态并清空缓存；
//   - reset()（锁定/退出登录时由 AppShell 调用）清空全部明文。
//
// 可测性（TR-9.1）：装载/切日/高亮逻辑全部委托 src/locations/month.ts 与
// places.ts 的纯函数，本文件只做状态编排与副作用（网络/解密入口）。

import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
import { listLocations } from '../api/locations'
import type { ApiLocationBlock } from '../locations/core/decode'
import type { DayTimeline } from '../locations/core/types'
import {
  decryptAndAnalyze,
  daysWithDataOf,
  monthRangeUtc,
  pickInitialDay,
  shiftYearMonth,
  todayDayKey,
  tzOffsetMinNow,
} from '../locations/month'
import { indexPlacesById } from '../locations/places'
import { useAuthStore } from './auth'
import { useVaultStore } from './vault'

/** 装载错误态：'' 正常；locked 未解锁；load_failed 网络/服务端失败；decrypt_failed 解密失败。 */
export type LocationsError = '' | 'locked' | 'load_failed' | 'decrypt_failed'

export const useLocationsStore = defineStore('locations', () => {
  const auth = useAuthStore()
  const vault = useVaultStore()

  // ---- 状态 ----

  /** 当前展示年/月（本地日历月；初值为今天所在月）。 */
  const now = new Date()
  const year = ref(now.getFullYear())
  const month = ref(now.getMonth() + 1)
  const loading = ref(false)
  const error = ref<LocationsError>('')
  /**
   * 本地日时间线缓存（Map<dayKey, DayTimeline>，仅内存）。
   * shallowRef + 整体替换：Map 内对象不可变，避免深层响应式开销。
   */
  const timelines = shallowRef<Map<string, DayTimeline>>(new Map())
  /** 当前选中本地日（null = 未选/全月无数据）。 */
  const selectedDay = ref<string | null>(null)

  /** 并发守卫：快速切月时只采纳最后一次 loadMonth 的结果。 */
  let loadSeq = 0

  // ---- 派生（getters）----

  /** 日历高亮集合（有数据日 dayKey 升序数组；视图层转 Set 查询）。 */
  const daysWithData = computed(() => daysWithDataOf(timelines.value))

  /** 选中日的时间线（无数据日/未选为 null，视图展示空态）。 */
  const selectedTimeline = computed<DayTimeline | null>(() =>
    selectedDay.value === null ? null : (timelines.value.get(selectedDay.value) ?? null),
  )

  /**
   * place 命名索引：键 = 记录 id（`place:{geohash7}`），值含 name/version。
   * 数据源为 vault store 的 placeRecords 独立缓存（version 取自该缓存，
   * Task 10 命名覆盖时 +1）。
   */
  const placesByGeohash = computed(() => indexPlacesById(vault.placeRecords.values()))

  // ---- 动作 ----

  /**
   * 装载指定本地月：本地月初 00:00 → 次月月初 00:00 换算 UTC 毫秒
   * （本地月最长 31 天，跨度天然 ≤62 天契约内）→ listLocations →
   * 逐块 decryptBlock → detectStays → splitByLocalDay 缓存。
   *
   * 未解锁（mk=null）置 'locked' 错误态且不保留任何明文；
   * 任一块解密失败整体置 'decrypt_failed'（不做半拉子展示、不泄坐标）。
   */
  async function loadMonth(y: number, m: number): Promise<void> {
    const seq = ++loadSeq
    loading.value = true
    error.value = ''
    year.value = y
    month.value = m
    try {
      // 未解锁：清空明文缓存并引导解锁（Notes：不缓存任何明文）。
      if (!auth.unlocked || !auth.sodium || !auth.masterKey) {
        timelines.value = new Map()
        selectedDay.value = null
        error.value = 'locked'
        return
      }
      const tz = tzOffsetMinNow()
      const { from, to } = monthRangeUtc(y, m, tz)
      let raw: ApiLocationBlock[]
      try {
        raw = await listLocations(from, to)
      } catch {
        if (seq !== loadSeq) return // 已被更新的装载取代
        error.value = 'load_failed'
        return
      }
      if (seq !== loadSeq) return
      try {
        timelines.value = decryptAndAnalyze(auth.sodium, auth.masterKey, raw, tz)
      } catch {
        // 解密失败：清空明文，置错误态（绝不容错展示部分坐标）。
        timelines.value = new Map()
        selectedDay.value = null
        error.value = 'decrypt_failed'
        return
      }
      // 切日：保留仍有效的原选日 → 今天 → 最近有数据日 → null。
      selectedDay.value = pickInitialDay(timelines.value, todayDayKey(tz), selectedDay.value)
    } finally {
      if (seq === loadSeq) loading.value = false
    }
  }

  /** 月份平移（视图层上一月/下一月按钮）。 */
  function shiftMonth(delta: number): Promise<void> {
    const next = shiftYearMonth(year.value, month.value, delta)
    return loadMonth(next.year, next.month)
  }

  /** 选日（日历格子点击；无数据日也允许选中，视图展示空态）。 */
  function selectDay(dayKey: string): void {
    selectedDay.value = dayKey
  }

  /** 锁定/退出登录：清空全部明文与状态（TR-9.2）。 */
  function reset(): void {
    loadSeq++ // 使进行中的装载结果失效
    timelines.value = new Map()
    selectedDay.value = null
    loading.value = false
    error.value = ''
    const d = new Date()
    year.value = d.getFullYear()
    month.value = d.getMonth() + 1
  }

  return {
    year,
    month,
    loading,
    error,
    timelines,
    selectedDay,
    daysWithData,
    selectedTimeline,
    placesByGeohash,
    loadMonth,
    shiftMonth,
    selectDay,
    reset,
  }
})
