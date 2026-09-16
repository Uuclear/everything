package com.everything.eve.collector.location.core

/**
 * 轨迹服务停止/阻止启动原因（阶段 4a Task 6）。
 *
 * 零知识红线：对外只暴露枚举 wireName（可持久化、可 UI 展示），
 * 任何异常一律翻译为枚举，绝不携带坐标等业务明文。
 */
enum class TrackStopReason(val wireName: String) {
    /** 轨迹采集开关关闭：用户未启用或服务启动时开关已被关。 */
    DISABLED("disabled"),

    /** 主密钥不可用（未解锁 / 运行中被锁定）：MK 变 null 即停采退出前台（FR-8）。 */
    MK_UNAVAILABLE("mk_unavailable"),

    /** 精确位置权限（ACCESS_FINE_LOCATION）未授权或运行中被撤销。 */
    FINE_DENIED("fine_denied"),

    /** 后台定位未选"始终允许"（API29+ ACCESS_BACKGROUND_LOCATION）。 */
    BACKGROUND_DENIED("background_denied"),

    /** 采样/封块/注册监听等运行时异常：服务兜底停止原因。 */
    SERVICE_ERROR("service_error"),
}

/**
 * 轨迹前台服务启动前置检查（tasks.md Task 6 四分支）的纯函数判定。
 *
 * 四分支（任一不满足即阻止启动，按 tasks.md 列举顺序短路）：
 *  1. 轨迹开关开；
 *  2. MK 非空（内存态解锁）；
 *  3. FINE 已授权；
 *  4. 后台定位"始终允许"。
 *
 * 纯 Kotlin：不依赖 Android Framework，四分支可 JVM 单测逐条定位（TR-6.2）。
 */
object TrackStartCheck {

    /**
     * 返回阻止启动的原因；null 表示四条件齐备、允许启动。
     *
     * @param trackingEnabled 轨迹采集开关（CollectorSettings.isLocationTrackingEnabled）
     * @param mkAvailable     主密钥是否在内存（AuthManager.masterKey != null）
     * @param fineGranted     FINE 权限（LocationPermissionGate.fineGranted）
     * @param backgroundGranted 后台"始终允许"（LocationPermissionGate.backgroundGranted）
     */
    fun blockedReason(
        trackingEnabled: Boolean,
        mkAvailable: Boolean,
        fineGranted: Boolean,
        backgroundGranted: Boolean,
    ): TrackStopReason? = when {
        !trackingEnabled -> TrackStopReason.DISABLED
        !mkAvailable -> TrackStopReason.MK_UNAVAILABLE
        !fineGranted -> TrackStopReason.FINE_DENIED
        !backgroundGranted -> TrackStopReason.BACKGROUND_DENIED
        else -> null
    }
}
