// AI Agent 会话状态机（Web 端，阶段 6 Task 4 / TR-4.2）。
//
// 三态：LOCKED（未解锁）→ UNLOCKED（已解锁，持有 token）→ REFRESHING（续签中）。
// 15 分钟过期：服务端 TTL；客户端到期前 1 分钟主动调 refresh 续签。
// 强制解锁：用户主动锁屏 / 登出 / 安全告警 → 清空 token，回到 LOCKED。
//
// 三不存落地：
//   - 解锁 token 仅存内存（ref），页面刷新即清空；
//   - 不写入 localStorage / sessionStorage；
//   - 不与主密码 / MK / API Key 共生。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '../api/client'

// 服务端 /agent/unlock 与 /agent/refresh 的响应字段。
interface UnlockResponse {
  token: string
  session_id: string
  expires_at: number // Unix 秒
}

// /agent/lock 请求体。
interface LockRequest {
  session_id: string
}

/** Agent 会话状态。 */
export type AgentSessionState = 'locked' | 'unlocked' | 'refreshing'

/**
 * useAgentSessionStore：Web 端 AI Agent 会话状态机。
 *
 * 用法：
 *   const s = useAgentSessionStore()
 *   if (s.isLocked) await s.unlock()  // 进 ChatPanel 前 unlock
 *   s.scheduleAutoRefresh()            // 页面加载后启动定时续签
 *   s.lock()                           // 退出 ChatPanel 时主动解锁
 */
export const useAgentSessionStore = defineStore('agentSession', () => {
  // 状态字段（全部内存，刷新即清零）。
  const state = ref<AgentSessionState>('locked')
  const token = ref<string>('')
  const sessionId = ref<string>('')
  const expiresAt = ref<number>(0) // Unix 秒
  const lastError = ref<string>('')
  const lastRefreshAt = ref<number>(0)

  // 定时续签句柄。
  let refreshTimer: ReturnType<typeof setTimeout> | null = null

  const isLocked = computed(() => state.value === 'locked')
  const isUnlocked = computed(() => state.value === 'unlocked' && token.value !== '')
  /** 距过期剩余秒数；负数表示已过期。 */
  const secondsToExpire = computed(() => {
    if (!expiresAt.value) return 0
    return Math.max(0, Math.floor(expiresAt.value - Date.now() / 1000))
  })
  /** 是否需要立即续签（剩余 < 60s）。 */
  const needsRefresh = computed(() => isUnlocked.value && secondsToExpire.value < 60)

  /** unlock：调服务端签发解锁 token；服务端从 access_token 推断 user/device。 */
  async function unlock(): Promise<void> {
    if (state.value === 'refreshing') return
    state.value = 'refreshing'
    lastError.value = ''
    try {
      const resp = await api.unlockAgent()
      applyUnlock(resp)
    } catch (err: any) {
      state.value = 'locked'
      token.value = ''
      sessionId.value = ''
      expiresAt.value = 0
      lastError.value = String(err?.message ?? err)
      throw err
    }
  }

  /** refresh：用旧 token 换新 token；服务端校验 user/device/TTL。 */
  async function refresh(): Promise<void> {
    if (!token.value) {
      throw new Error('无旧 token，无法 refresh')
    }
    if (state.value === 'refreshing') return
    state.value = 'refreshing'
    lastError.value = ''
    try {
      const resp = await api.refreshAgent(token.value)
      applyUnlock(resp)
    } catch (err: any) {
      // 续签失败：回到 locked（清空旧 token，避免带过期 token 反复重试）。
      state.value = 'locked'
      token.value = ''
      sessionId.value = ''
      expiresAt.value = 0
      lastError.value = String(err?.message ?? err)
      throw err
    }
  }

  /** lock：主动强制解锁。服务端清理 session_id；客户端清空内存。 */
  async function lock(): Promise<void> {
    const sid = sessionId.value
    // 本地先清（即便服务端失败也保证 UI 立即回到 locked）。
    state.value = 'locked'
    token.value = ''
    sessionId.value = ''
    expiresAt.value = 0
    lastError.value = ''
    stopAutoRefresh()
    if (sid) {
      try {
        await api.lockAgent({ session_id: sid } as LockRequest)
      } catch {
        // 忽略服务端错误：本地已清，幂等目标达成。
      }
    }
  }

  /**
   * scheduleAutoRefresh：到期前 60s 主动 refresh。
   * 单次定时器：refresh 成功后再次 schedule（避免长 setInterval 漂移）。
   */
  function scheduleAutoRefresh(): void {
    stopAutoRefresh()
    if (!isUnlocked.value) return
    const ms = Math.max(5_000, (secondsToExpire.value - 60) * 1000)
    refreshTimer = setTimeout(() => {
      void (async () => {
        try {
          await refresh()
          scheduleAutoRefresh()
        } catch {
          // refresh 失败 → 已是 locked，等下次手动 unlock。
        }
      })()
    }, ms)
  }

  function stopAutoRefresh(): void {
    if (refreshTimer != null) {
      clearTimeout(refreshTimer)
      refreshTimer = null
    }
  }

  /** 应用服务端 unlock/refresh 响应到本地状态。 */
  function applyUnlock(resp: UnlockResponse): void {
    token.value = resp.token
    sessionId.value = resp.session_id
    expiresAt.value = resp.expires_at
    lastRefreshAt.value = Date.now()
    state.value = 'unlocked'
  }

  /**
   * ensureUnlocked：业务侧在调 /agent/chat 前调用。
   * - 已是 unlocked 且未到 refresh 窗口：直接返回；
   * - 已 locked 或接近过期：自动 unlock/refresh；
   * - 异常向上抛（由 UI 跳解锁页）。
   */
  async function ensureUnlocked(): Promise<void> {
    if (isUnlocked.value && !needsRefresh.value) return
    if (isUnlocked.value && needsRefresh.value) {
      await refresh()
      return
    }
    await unlock()
  }

  return {
    // 状态。
    state,
    token,
    sessionId,
    expiresAt,
    lastError,
    lastRefreshAt,
    // 计算属性。
    isLocked,
    isUnlocked,
    secondsToExpire,
    needsRefresh,
    // 动作。
    unlock,
    refresh,
    lock,
    ensureUnlocked,
    scheduleAutoRefresh,
    stopAutoRefresh,
  }
})

/**
 * 便捷函数：从 access_token 是否到期推断 unlock 必要性。
 * 仅在 useAgentSessionStore 之外的初始化代码使用（例如路由守卫）。
 */
export function isAgentUnlocked(store: ReturnType<typeof useAgentSessionStore>): boolean {
  return store.isUnlocked && !store.needsRefresh
}
