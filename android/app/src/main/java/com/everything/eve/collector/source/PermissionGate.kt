package com.everything.eve.collector.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.everything.eve.collector.core.CollectorKind

/**
 * 采集权限门（单点维护 kind ↔ 系统权限映射）。
 *
 * 只读最小集：READ_CONTACTS / READ_SMS / READ_CALL_LOG（spec FR-7），
 * 不申请任何写权限；缺权限的类别由引擎记跳过原因后略过，不影响其他类。
 */
object PermissionGate {

    /** 该类别采集所需的唯一系统权限。 */
    fun requiredPermission(kind: CollectorKind): String = when (kind) {
        CollectorKind.CONTACT -> Manifest.permission.READ_CONTACTS
        CollectorKind.SMS -> Manifest.permission.READ_SMS
        CollectorKind.CALLLOG -> Manifest.permission.READ_CALL_LOG
    }

    /** 全部采集类别需要的权限数组（向导一次性申请）。 */
    val ALL_PERMISSIONS: Array<String> =
        CollectorKind.entries.map { requiredPermission(it) }.toTypedArray()

    /** 当前已授权的采集类别集合。 */
    fun grantedKinds(context: Context): Set<CollectorKind> =
        CollectorKind.entries.filter { kind ->
            ContextCompat.checkSelfPermission(
                context,
                requiredPermission(kind),
            ) == PackageManager.PERMISSION_GRANTED
        }.toSet()
}
