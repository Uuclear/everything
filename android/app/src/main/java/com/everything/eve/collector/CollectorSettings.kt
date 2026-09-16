package com.everything.eve.collector

import android.content.Context
import com.everything.eve.collector.core.CollectorKind

/**
 * 采集开关设置（普通 SharedPreferences，仅布尔键——零知识边界：
 * 这里绝不存明文业务数据，只存"用户是否启用某类采集"）。
 */
object CollectorSettings {
    private const val PREFS = "eve-collector"
    private const val KEY_MASTER = "master_enabled"

    /**
     * 阶段 4a 轨迹采集开关键（独立键，不进 CollectorKind 枚举——
     * 轨迹由前台服务持续采集，不走 CollectorWorker 的周期遍历，避免污染阶段 3 遍历逻辑）。
     */
    private const val KEY_LOCATION_TRACKING = "enabled_location_tracking"

    /** 阶段 4a "是否已申请过定位权限"标记键（沿用 P3-2 派生判定模式：持久化标记 + 实时 rationale 推导永久拒绝）。 */
    private const val KEY_LOCATION_PERM_REQUESTED = "perm_requested_location"

    /** 阶段 4a 轨迹服务最近停止原因键：仅存 TrackStopReason 枚举 wireName（零明文业务数据）。 */
    private const val KEY_LOCATION_LAST_STOP_REASON = "location_last_stop_reason"

    /** 阶段 4a 最近一次轨迹块上行成功的本地毫秒时间戳（采集卡片"上次上传时间"状态行；0 表示从未上传）。 */
    private const val KEY_LOCATION_LAST_UPLOAD_AT = "location_last_upload_at"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(kind: CollectorKind) = "enabled_${kind.shortName}"

    /** "是否已申请过该类权限"标记键：区分"从未申请"与"永久拒绝"（两种情况下系统 rationale 均为 false）。 */
    private fun keyPermRequested(kind: CollectorKind) = "perm_requested_${kind.shortName}"

    /** 默认关闭：用户必须在采集页显式开启并完成授权。 */
    fun isEnabled(context: Context, kind: CollectorKind): Boolean =
        prefs(context).getBoolean(key(kind), false)

    fun setEnabled(context: Context, kind: CollectorKind, enabled: Boolean) {
        prefs(context).edit().putBoolean(key(kind), enabled).apply()
    }

    /** 权限申请回调后调用（无论授予与否）：标记该类已发起过系统权限申请。 */
    fun markPermissionRequested(context: Context, kind: CollectorKind) {
        prefs(context).edit().putBoolean(keyPermRequested(kind), true).apply()
    }

    /** 是否已申请过该类权限；配合 rationale=false 可判定"永久拒绝"。 */
    fun wasPermissionRequested(context: Context, kind: CollectorKind): Boolean =
        prefs(context).getBoolean(keyPermRequested(kind), false)

    /** 周期采集总开关（默认开启；关闭后启动时不再注册周期任务，已有周期任务被取消）。 */
    fun isMasterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MASTER, true)

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MASTER, enabled).apply()
    }

    /** 轨迹采集开关（默认关闭：用户必须在采集页显式开启并完成授权）。 */
    fun isLocationTrackingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCATION_TRACKING, false)

    fun setLocationTrackingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCATION_TRACKING, enabled).apply()
    }

    /** 定位权限申请回调后调用（无论授予与否）：标记已发起过系统权限申请。 */
    fun markLocationPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_LOCATION_PERM_REQUESTED, true).apply()
    }

    /** 是否已申请过定位权限；配合 rationale=false 可判定"永久拒绝"（P3-2 派生模式）。 */
    fun wasLocationPermissionRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOCATION_PERM_REQUESTED, false)

    /** 轨迹服务最近停止原因（TrackStopReason.wireName）；null 表示无（启动成功时清除）。 */
    fun locationLastStopReason(context: Context): String? =
        prefs(context).getString(KEY_LOCATION_LAST_STOP_REASON, null)

    /** 记录/清除轨迹服务停止原因（仅存枚举 wireName，服务内不写日志明文）。 */
    fun setLocationLastStopReason(context: Context, wireName: String?) {
        val editor = prefs(context).edit()
        if (wireName == null) editor.remove(KEY_LOCATION_LAST_STOP_REASON)
        else editor.putString(KEY_LOCATION_LAST_STOP_REASON, wireName)
        editor.apply()
    }

    /** 最近一次轨迹块上行成功的毫秒时间戳；0 表示从未上传成功。 */
    fun locationLastUploadAt(context: Context): Long =
        prefs(context).getLong(KEY_LOCATION_LAST_UPLOAD_AT, 0L)

    /** 上行成功后记录时间戳（仅时间元数据，无任何轨迹内容）。 */
    fun setLocationLastUploadAt(context: Context, ts: Long) {
        prefs(context).edit().putLong(KEY_LOCATION_LAST_UPLOAD_AT, ts).apply()
    }
}
