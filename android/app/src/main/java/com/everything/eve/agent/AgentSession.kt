package com.everything.eve.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AI Agent 会话状态机（Android 端 stub，阶段 6 Task 4 / TR-4.3）。
 *
 * 本期（v6）仅落接口 + 三态状态机；具体的解锁 / 续签 / 强制解锁 实现在 Task 5+ 接入
 * （届时会调用 Server 的 /agent/unlock /agent/refresh /agent/lock，并把 token 注入
 * 后续 /agent/chat 请求头）。
 *
 * 三不存落地：
 *   - 解锁 token 仅存内存（StateFlow）；
 *   - 不写入 EncryptedSharedPreferences / DataStore；
 *   - 不与主密码 / MK / API Key 共生。
 *
 * 多设备隔离：deviceId 由 AuthManager 提供（设备注册时持久化）；
 * 服务端 Validate 会强制校验 device_id 与 token 内的 device_id 匹配。
 */
sealed class AgentSessionState {
    /** 未解锁：进入 ChatPanel 前需调 unlock()。 */
    object Locked : AgentSessionState()
    /** 续签中：refresh 进行中，避免重复触发。 */
    object Refreshing : AgentSessionState()
    /** 已解锁：持有 token 与 expiresAt；到期前 60s 自动 refresh。 */
    data class Unlocked(val token: String, val sessionId: String, val expiresAtMs: Long) :
        AgentSessionState()
}

/**
 * AgentSession：Android 端会话状态机骨架。
 *
 * 用法（远期）：
 *   val session = AgentSession(deviceId = authManager.deviceId())
 *   if (session.state.value is AgentSessionState.Locked) session.unlock()
 *   session.scheduleAutoRefresh(scope)
 *
 * 本期实现：仅 StateFlow + 状态转换 API；不发起网络请求。
 */
class AgentSession(private val deviceId: String) {

    private val _state = MutableStateFlow<AgentSessionState>(AgentSessionState.Locked)
    val state: StateFlow<AgentSessionState> = _state.asStateFlow()

    /** 最近一次错误（人类可读；不写入审计 / 日志持久化）。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * unlock 占位：远期会调 EveApi.unlockAgent() 并把响应写入 state。
     * 本期仅做状态转换演示，UI / ChatPanel 仍在 Task 5 接入。
     */
    fun unlockPlaceholder() {
        _state.value = AgentSessionState.Refreshing
        // 远期：调服务端 → 收到 token → 进入 Unlocked。
        // 本期：直接进入 Unlocked（用 fake 数据），便于 UI 联调。
        _state.value = AgentSessionState.Unlocked(
            token = "stub-token",
            sessionId = "stub-session",
            expiresAtMs = System.currentTimeMillis() + 15 * 60 * 1000,
        )
    }

    /** refresh 占位：远期用旧 token 换新 token；本期维持当前 Unlocked 状态。 */
    fun refreshPlaceholder() {
        val cur = _state.value
        if (cur is AgentSessionState.Unlocked) {
            _state.value = AgentSessionState.Refreshing
            // 远期：调服务端 → 收到新 token → 更新 Unlocked。
            _state.value = cur.copy(expiresAtMs = cur.expiresAtMs)
        }
    }

    /** lock：强制清空（远期同步调 /agent/lock）。 */
    fun lock() {
        _state.value = AgentSessionState.Locked
        _lastError.value = null
    }

    /** 便捷判断：是否处于 Unlocked 且 token 非空。 */
    val isUnlocked: Boolean
        get() = _state.value is AgentSessionState.Unlocked
}
