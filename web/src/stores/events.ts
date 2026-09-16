// SSE 实时事件通道：
// 1) 用 approved access 换取 5 分钟的 events 令牌（只能用于 /events，最小权限）；
// 2) EventSource 以 ?token= 建连，服务端 30s ping 保活；
// 3) 断线退避重连（1s→2s→5s 封顶），重连时重新换取 events 令牌；
// 4) 另设 30s 兜底轮询回调，SSE 不可用的网络环境下数据仍能最终一致。
import { reactive } from 'vue'
import { api } from '../api/client'

/** 服务端 hub 发布的事件（与 Go sync.Event 对齐）。 */
export interface ServerEvent {
  type:
    | 'records_changed'
    | 'device_pairing_requested'
    | 'device_pairing_resolved'
    | 'device_list_changed'
  module?: string
  record_id?: string
}

type Handler = (e: ServerEvent) => void

// 退避表：毫秒。连续失败依次取 1s/2s，之后固定 5s；成功建连后重置。
const BACKOFF = [1000, 2000, 5000]
const FALLBACK_INTERVAL = 30_000

class EventBus {
  private handlers = new Set<Handler>()
  on(h: Handler): () => void {
    this.handlers.add(h)
    return () => this.handlers.delete(h)
  }
  emit(e: ServerEvent) {
    this.handlers.forEach((h) => h(e))
  }
}

/** 通道的可观察状态（供 UI 显示连接指示点）。 */
export const eventsStatus = reactive({
  connected: false,
  /** 收到最后一个事件的时间戳（毫秒），供 UI 调试/刷新指示。 */
  lastEventAt: 0,
})

/**
 * 通道控制器（非响应式资源：EventSource/定时器不应被 Pinia 代理）。
 * start() 返回该句柄，组件卸载时调用 stop()。
 */
export class EventsChannel {
  private es: EventSource | null = null
  private reconnectTimer = 0
  private fallbackTimer = 0
  private retries = 0
  private stopped = false
  readonly bus = new EventBus()

  /**
   * @param onFallback 30 秒兜底回调（通常做一次全量/增量同步）。
   */
  constructor(private onFallback?: () => void) {}

  async start() {
    this.stopped = false
    await this.connect()
    if (this.onFallback) {
      this.fallbackTimer = window.setInterval(() => {
        if (!this.stopped) this.onFallback!()
      }, FALLBACK_INTERVAL)
    }
  }

  private async connect() {
    if (this.stopped) return
    let token: string
    try {
      // events 令牌 5 分钟有效；每次（重）连都换新牌，旧牌自然过期。
      token = (await api.eventsToken()).events_token
    } catch {
      this.scheduleReconnect()
      return
    }
    if (this.stopped) return

    const url = `/api/v1/events?token=${encodeURIComponent(token)}`
    const es = new EventSource(url)
    this.es = es

    es.onopen = () => {
      this.retries = 0
      eventsStatus.connected = true
    }
    // 服务端按 `event: <type>\ndata: <json>` 发帧；按类型逐一挂监听，
    // 未命名的 ping/connected 帧（以 ':' 注释开头）浏览器自动忽略。
    const types: ServerEvent['type'][] = [
      'records_changed',
      'device_pairing_requested',
      'device_pairing_resolved',
      'device_list_changed',
    ]
    types.forEach((type) => {
      es.addEventListener(type, (ev: MessageEvent) => {
        let payload: ServerEvent = { type }
        try {
          payload = { ...(JSON.parse(ev.data) as object), type } as ServerEvent
        } catch {
          // 数据体异常时仍通知（至少触发一次同步/刷新）。
        }
        eventsStatus.lastEventAt = Date.now()
        this.bus.emit(payload)
      })
    })

    es.onerror = () => {
      // EventSource 自身也会自动重连，但它无法重新换取会过期的 events 令牌，
      // 因此统一关闭并由我们按退避表重建。
      eventsStatus.connected = false
      es.close()
      this.es = null
      this.scheduleReconnect()
    }
  }

  private scheduleReconnect() {
    if (this.stopped) return
    const delay = BACKOFF[Math.min(this.retries, BACKOFF.length - 1)]
    this.retries += 1
    window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = window.setTimeout(() => void this.connect(), delay)
  }

  stop() {
    this.stopped = true
    window.clearTimeout(this.reconnectTimer)
    window.clearInterval(this.fallbackTimer)
    this.es?.close()
    this.es = null
    eventsStatus.connected = false
  }
}

// 全应用单例：主布局解锁后 start、退出时 stop；其余视图经 bus.on 订阅。
let shared: EventsChannel | null = null
export function getSharedChannel(onFallback?: () => void): EventsChannel {
  if (!shared) shared = new EventsChannel(onFallback)
  return shared
}
