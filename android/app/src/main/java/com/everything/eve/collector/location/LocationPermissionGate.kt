package com.everything.eve.collector.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 阶段 4a 定位权限门（对齐阶段 3 PermissionGate 的单点风格）：
 * 定位相关权限的唯一直接检查点，其余代码不得散落 checkSelfPermission。
 *
 * 权限分组（spec FR-2）：
 *  - ACCESS_FINE_LOCATION：运行时权限，采集的必要条件；
 *  - ACCESS_BACKGROUND_LOCATION（API29+）：不能伪造运行时弹窗，
 *    只能由用户去系统设置页选"始终允许"，UI 层负责引导；
 *  - POST_NOTIFICATIONS（API33+）：常驻通知所需，缺失不阻塞采集本体
 *    （FR-2 明确通知权限"不阻塞未授权用户用其它功能"），故不进 canTrack。
 */
object LocationPermissionGate {

    /** 精确位置权限（ACCESS_FINE_LOCATION）是否已授权 */
    fun fineGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 后台定位"始终允许"是否已授权。
     * API29 以下无后台定位权限概念（FINE 即可后台采集），直接视为已授权。
     */
    fun backgroundGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 通知权限是否已授权（API33+ 的运行时权限 POST_NOTIFICATIONS）。
     * API33 以下无通知运行时权限，直接视为已授权。
     */
    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 轨迹采集可运行的组合判定：FINE 已授权 && 后台"始终允许"已授权。
     * 注意：不含通知权限（通知缺失只影响常驻通知展示，不阻塞采集本体）。
     * 开关态与 MK 可用性不在此处判定（由服务的启动决策负责）。
     */
    fun canTrack(context: Context): Boolean =
        fineGranted(context) && backgroundGranted(context)
}
