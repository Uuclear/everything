package com.everything.eve.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.collector.SkipReason
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.location.LocationPermissionGate
import com.everything.eve.collector.location.core.TrackStopReason
import com.everything.eve.collector.source.PermissionGate
import com.everything.eve.ui.auth.formatMillis
import kotlinx.coroutines.launch

/**
 * 数据采集页（spec FR-10 / AC-5、AC-8、AC-9、AC-11）：
 *  1. 顶部合规告知卡片（自托管、端到端加密、约 15 分钟周期非实时、不跟随系统删除）；
 *  2. 周期采集总开关 + 立即采集（WorkInfo 反馈进行中/成功/失败）；
 *  3. 三类采集行：图标/名称/权限用途 + 开关 + 五态状态行；
 *  4. 位置轨迹卡片（阶段 4a Task 7，置于通话记录之后）：开关前置检查 +
 *     合规告知 + 四状态分支（未授权/仅前台/齐备采集中/MK 不可用降级）+ 立即上传；
 *  5. 保活区：忽略电池优化、应用详情入口、国产 ROM 自启动提示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectorScreen(onBack: () -> Unit, vm: CollectorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 正在请求权限的类别（launcher 回调时据此确定目标开关）
    var pendingKind by remember { mutableStateOf<CollectorKind?>(null) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val kind = pendingKind
        pendingKind = null
        if (kind == null) return@rememberLauncherForActivityResult
        // 记录"已申请过"：永久拒绝判定（已申请 && rationale=false && 未授权）依赖此标记，
        // 派生计算而非内存态，页面/进程重建后"去系统设置"按钮依然正确（review P3-2）
        vm.markPermissionRequested(kind)
        if (granted) {
            vm.setKindEnabled(kind, true)
        } else {
            // rationale=true 说明还可再弹窗，提示一句；否则由卡片派生态展示"去系统设置"按钮
            val activity = context as? Activity
            val canAskAgain = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it, PermissionGate.requiredPermission(kind),
                )
            } ?: true
            if (canAskAgain) {
                scope.launch { snackbar.showSnackbar("未授予权限，${kindLabel(kind)}采集未开启") }
            }
        }
        vm.refresh()
    }

    // FINE 定位权限申请（位置轨迹卡片专用；沿用 P3-2 派生模式：
    // 持久化"已申请过"标记 + 实时 rationale 推导永久拒绝，禁止 remember 内存态）
    val finePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.markLocationPermissionRequested()
        if (granted) {
            if (LocationPermissionGate.backgroundGranted(context)) {
                // 权限齐备才置开关并启动 FGS（服务四分支前置检查再复核一次）
                vm.setLocationTrackingEnabled(true)
            } else {
                // 后台定位（API29+）不能伪造运行时弹窗：引导去系统设置选"始终允许"，
                // 用户返回后再次拨开关即可（ON_RESUME 刷新使卡片进入"仅前台"分支）
                openAppDetails(context) {
                    scope.launch { snackbar.showSnackbar("无法打开系统设置，请手动前往应用管理") }
                }
            }
        } else {
            // rationale=true 说明还可再弹窗，提示一句；否则由卡片派生态展示"去系统设置"按钮
            val activity = context as? Activity
            val canAskAgain = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.ACCESS_FINE_LOCATION,
                )
            } ?: true
            if (canAskAgain) {
                scope.launch { snackbar.showSnackbar("未授予定位权限，轨迹采集未开启") }
            }
        }
        vm.refresh()
    }

    // 从系统设置页返回（ON_RESUME）时重采样权限/白名单快照
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据采集") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 合规告知 ----
            item { ComplianceCard() }

            // ---- 周期总开关 + 立即采集 ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("后台周期采集", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "约每 15 分钟采集并同步一次（非实时）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = state.masterEnabled,
                                onCheckedChange = { vm.setMasterEnabled(it) },
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { vm.collectNow() },
                                enabled = state.oneShot != OneShotStatus.RUNNING,
                            ) { Text("立即采集") }
                            Text(
                                when (state.oneShot) {
                                    OneShotStatus.IDLE -> ""
                                    OneShotStatus.RUNNING -> "采集中…"
                                    OneShotStatus.SUCCEEDED -> "本轮已完成"
                                    OneShotStatus.FAILED -> "本轮失败，将由周期任务重试"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            // ---- 三类采集行 ----
            items(state.kinds, key = { it.kind.shortName }) { view ->
                // 永久拒绝派生判定：已申请过 && 系统 rationale=false（不再弹窗）&& 仍未授权。
                // 组合时计算，ON_RESUME / 权限回调触发 refresh 后自动重算（review P3-2）
                val activity = context as? Activity
                val foreverDenied = !view.permissionGranted &&
                    vm.wasPermissionRequested(view.kind) &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, PermissionGate.requiredPermission(view.kind),
                        )
                    } == true
                CollectorKindCard(
                    view = view,
                    foreverDenied = foreverDenied,
                    onToggle = { want ->
                        if (!want) {
                            vm.setKindEnabled(view.kind, false)
                        } else if (view.permissionGranted) {
                            vm.setKindEnabled(view.kind, true)
                        } else {
                            pendingKind = view.kind
                            permLauncher.launch(PermissionGate.requiredPermission(view.kind))
                        }
                    },
                    onOpenSystemSettings = {
                        openAppDetails(context) {
                            scope.launch { snackbar.showSnackbar("无法打开系统设置，请手动前往应用管理") }
                        }
                    },
                )
            }

            // ---- 位置轨迹卡片（阶段 4a Task 7，置于通话记录之后、保活区之前）----
            item {
                val location = state.location
                // FINE 永久拒绝派生判定（P3-2 模式：持久化标记 + 实时 rationale，
                // 组合时计算，ON_RESUME / 权限回调触发 refresh 后自动重算）
                val activity = context as? Activity
                val fineForeverDenied = !location.fineGranted &&
                    vm.wasLocationPermissionRequested() &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            it, Manifest.permission.ACCESS_FINE_LOCATION,
                        )
                    } == true
                LocationCard(
                    view = location,
                    fineForeverDenied = fineForeverDenied,
                    oneShot = state.oneShot,
                    onToggle = { want ->
                        if (!want) {
                            vm.setLocationTrackingEnabled(false)
                        } else when {
                            // 前置检查 1：FINE 未授权 → 运行时申请（P3-2 派生模式）
                            !location.fineGranted ->
                                finePermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            // 前置检查 2：后台定位未授权 → 跳系统设置（try/catch 兜底）
                            !location.backgroundGranted ->
                                openAppDetails(context) {
                                    scope.launch {
                                        snackbar.showSnackbar("无法打开系统设置，请手动前往应用管理")
                                    }
                                }
                            // 前置检查 3：齐备才置开关并启动 FGS
                            else -> vm.setLocationTrackingEnabled(true)
                        }
                    },
                    onOpenSystemSettings = {
                        openAppDetails(context) {
                            scope.launch { snackbar.showSnackbar("无法打开系统设置，请手动前往应用管理") }
                        }
                    },
                    onUploadNow = { vm.uploadNow() },
                )
            }

            // ---- 保活区 ----
            item {
                KeepAliveCard(
                    whitelisted = state.batteryWhitelisted,
                    onRequestIgnoreBattery = {
                        requestIgnoreBattery(context) {
                            scope.launch { snackbar.showSnackbar("当前系统不支持该设置页，请在应用管理中手动配置") }
                        }
                    },
                    onOpenDetails = {
                        openAppDetails(context) {
                            scope.launch { snackbar.showSnackbar("无法打开系统设置，请手动前往应用管理") }
                        }
                    },
                )
            }
        }
    }
}

/** 合规告知卡片：数据去向、周期特性、删除语义、可关闭承诺。 */
@Composable
private fun ComplianceCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("采集前请知悉", style = MaterialTheme.typography.titleSmall)
            Text(
                "· 采集内容在本机端到端加密后，仅存入您自己部署的服务器，服务端无法读取明文。\n" +
                    "· 采集约每 15 分钟执行一次，并非实时同步。\n" +
                    "· 已采集的加密副本不跟随系统删除：在系统中删除短信/联系人不会删除资料库副本。\n" +
                    "· 每类采集都可随时单独关闭，关闭后立即停止读取对应数据源。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 单个采集类别卡片：开关行 + 权限用途 + 五态状态行 + 授权引导。 */
@Composable
private fun CollectorKindCard(
    view: CollectorKindView,
    foreverDenied: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(kindIcon(view.kind), contentDescription = null)
                    Column {
                        Text(kindLabel(view.kind), style = MaterialTheme.typography.titleSmall)
                        Text(kindPurpose(view.kind), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(
                    checked = view.enabled && view.permissionGranted,
                    onCheckedChange = onToggle,
                )
            }

            // 五态状态行（TR-6.2）：未授权 / 已授权未采集 / 已采集 / 未解锁跳过 / 失败
            when {
                !view.permissionGranted -> Text(
                    "未授权：开启时需授予「${kindPermissionName(view.kind)}」权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                view.lastSkipReason == SkipReason.MK_UNAVAILABLE.wireName -> Text(
                    "上次跳过：主密钥未解锁，解锁后自动继续",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                view.lastSkipReason != null -> Text(
                    "上次失败：${skipReasonText(view.lastSkipReason)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                !view.enabled || view.lastRunAt == null -> Text(
                    if (view.enabled) "已授权，等待首次采集" else "已授权，未开启采集",
                    style = MaterialTheme.typography.bodySmall,
                )

                else -> Text(
                    "本地已采集 ${view.localCount} 条 · 上次成功 ${formatMillis(view.lastRunAt)}" +
                        " · 本轮扫描 ${view.lastScannedCount} 行",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 永久拒绝：引导去系统设置手动开启
            if (foreverDenied) {
                TextButton(onClick = onOpenSystemSettings) { Text("去系统设置开启权限") }
            }
        }
    }
}

/**
 * 位置轨迹卡片（阶段 4a Task 7 第 4 卡片）：
 * 开关行 + 合规告知（缓冲三要素/常驻通知可见/随时关闭）+ 权限态三项 +
 * 四状态分支（未授权/仅前台/齐备采集中/MK 不可用降级）+ 数据行 + 立即上传。
 */
@Composable
private fun LocationCard(
    view: LocationCardView,
    fineForeverDenied: Boolean,
    oneShot: OneShotStatus,
    onToggle: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onUploadNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Column {
                        Text("位置轨迹", style = MaterialTheme.typography.titleSmall)
                        Text("持续记录到访轨迹，加密存入资料库", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // 开关态 = 用户意图 && 权限现实（权限被回收时 FGS 已自停，开关不显示假开）
                Switch(
                    checked = view.enabled && view.fineGranted && view.backgroundGranted,
                    onCheckedChange = onToggle,
                )
            }

            // 合规告知（任务要求三要素 + 常驻通知可见 + 随时关闭）
            Text(
                "· 明文轨迹点仅在本机短暂缓冲，加密成块后即删除，最长保留 24 小时。\n" +
                    "· 采集期间有常驻通知可见；服务端只存密文块，无法读取轨迹明文。\n" +
                    "· 可随时关闭，关闭后立即停止采集。",
                style = MaterialTheme.typography.bodySmall,
            )

            // 权限态三项（任务要求状态行含权限态：前台 / 后台 / 通知）
            Text(
                "前台定位 ${permMark(view.fineGranted)} ·" +
                    " 后台定位 ${permMark(view.backgroundGranted)} ·" +
                    " 通知 ${permMark(view.notificationsGranted)}",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- 四状态分支（TR-7.2）----
            when {
                // 分支 1：未授权（FINE 缺失）
                !view.fineGranted -> Text(
                    "未授权：开启时需授予「精确位置」权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                // 分支 2：仅前台（后台定位未选"始终允许"，无法持续采集）
                !view.backgroundGranted -> Text(
                    "仅前台定位：持续采集需在系统设置中将位置权限选为「始终允许」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                // 分支 4：MK 不可用降级（已开启但主密钥未解锁，FGS 已自停/不会启动，FR-8）
                view.enabled && !view.mkAvailable -> Text(
                    "主密钥未解锁：轨迹采集已暂停，解锁后自动恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                // 分支 3：齐备采集中
                view.enabled -> Text(
                    "采集中（前台服务运行中）",
                    style = MaterialTheme.typography.bodySmall,
                )

                // 权限齐备但未开启
                else -> Text(
                    "已授权，未开启采集",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 数据行：今日已采点数 / 待传块数 / 上次上传时间（任务要求三项齐备）
            Text(
                "今日已采 ${view.todayPoints} 点 · 待传 ${view.pendingBlocks} 块 · 上次上传 " +
                    (if (view.lastUploadAt > 0) formatMillis(view.lastUploadAt) else "从未"),
                style = MaterialTheme.typography.bodySmall,
            )

            // 服务最近停止原因（枚举翻译，启动成功即清除；NFR-1 绝不展示坐标明文）
            view.lastStopReason?.let {
                Text(
                    "最近停止：${trackStopReasonText(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 永久拒绝引导：系统不再弹窗时去设置手动开启（P3-2 派生态）
            if (fineForeverDenied) {
                TextButton(onClick = onOpenSystemSettings) { Text("去系统设置开启权限") }
            }
            // 仅前台引导：后台定位只能由用户在系统设置选"始终允许"
            if (view.fineGranted && !view.backgroundGranted) {
                TextButton(onClick = onOpenSystemSettings) { Text("去系统设置选「始终允许」") }
            }

            // 立即上传（对齐阶段 3"立即采集"模式：同一一次性任务 + WorkInfo 反馈）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onUploadNow,
                    enabled = oneShot != OneShotStatus.RUNNING,
                ) { Text("立即上传") }
                Text(
                    when (oneShot) {
                        OneShotStatus.IDLE -> ""
                        OneShotStatus.RUNNING -> "上传中…"
                        OneShotStatus.SUCCEEDED -> "本轮已完成"
                        OneShotStatus.FAILED -> "本轮失败，将由周期任务重试"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 权限态标识：已授权 ✓ / 未授权 ✗（仅符号，无业务数据）。 */
private fun permMark(granted: Boolean): String = if (granted) "✓" else "✗"

/** 轨迹服务停止原因枚举 → 中文文案（仅枚举映射，绝不展示业务明文）。 */
private fun trackStopReasonText(wireName: String): String = when (wireName) {
    TrackStopReason.DISABLED.wireName -> "采集开关已关闭"
    TrackStopReason.MK_UNAVAILABLE.wireName -> "主密钥未解锁"
    TrackStopReason.FINE_DENIED.wireName -> "定位权限未授予或已被回收"
    TrackStopReason.BACKGROUND_DENIED.wireName -> "后台定位未选「始终允许」"
    TrackStopReason.SERVICE_ERROR.wireName -> "采集服务异常"
    else -> "未知原因"
}

/** 保活区：电池优化白名单 + 应用详情入口 + 国产 ROM 自启动提示。 */
@Composable
private fun KeepAliveCard(
    whitelisted: Boolean,
    onRequestIgnoreBattery: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("保持后台采集", style = MaterialTheme.typography.titleSmall)
            Text(
                "小米/华为/OPPO/vivo 等国产系统会严格限制后台任务：除下方电池优化设置外，" +
                    "通常还需在「设置 → 应用管理 → 本应用」中允许「自启动」与「后台运行」，" +
                    "否则周期采集可能被系统延迟。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (whitelisted) {
                Text("已加入电池优化白名单", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = onRequestIgnoreBattery) { Text("请求忽略电池优化") }
            }
            OutlinedButton(onClick = onOpenDetails) { Text("打开应用详情设置") }
        }
    }
}

// ---- 类别展示元数据 ----

private fun kindLabel(kind: CollectorKind): String = when (kind) {
    CollectorKind.CONTACT -> "通讯录"
    CollectorKind.SMS -> "短信"
    CollectorKind.CALLLOG -> "通话记录"
}

private fun kindIcon(kind: CollectorKind): ImageVector = when (kind) {
    CollectorKind.CONTACT -> Icons.Default.Person
    CollectorKind.SMS -> Icons.Default.Email
    CollectorKind.CALLLOG -> Icons.Default.Phone
}

private fun kindPurpose(kind: CollectorKind): String = when (kind) {
    CollectorKind.CONTACT -> "读取联系人（姓名/电话/邮箱等），加密存入资料库"
    CollectorKind.SMS -> "读取短信内容，加密存入资料库"
    CollectorKind.CALLLOG -> "读取通话号码/时长/类型，加密存入资料库"
}

private fun kindPermissionName(kind: CollectorKind): String = when (kind) {
    CollectorKind.CONTACT -> "读取联系人"
    CollectorKind.SMS -> "读取短信"
    CollectorKind.CALLLOG -> "读取通话记录"
}

/** 跳过原因枚举 → 中文文案（仅枚举映射，绝不展示业务明文）。 */
private fun skipReasonText(wireName: String): String = when (wireName) {
    SkipReason.MK_UNAVAILABLE.wireName -> "主密钥未解锁"
    SkipReason.PERMISSION_DENIED.wireName -> "权限未授予或已被回收"
    SkipReason.NOT_PAIRED.wireName -> "设备未配对"
    SkipReason.SOURCE_ERROR.wireName -> "系统数据源异常"
    else -> "未知原因"
}

// ---- 系统设置跳转（均带 ActivityNotFound/Security 兜底，TR-6.3）----

/** 打开本应用详情页（权限管理/自启动入口都在这里）。 */
private fun openAppDetails(context: Context, onError: () -> Unit) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    } catch (_: ActivityNotFoundException) {
        onError()
    } catch (_: SecurityException) {
        onError()
    }
}

/**
 * 请求加入电池优化白名单；部分 ROM 未实现该页面（或未声明对应清单权限被拦截）
 * 时降级到电池优化列表页，仍不可用则回调 onError 提示手动配置。
 */
private fun requestIgnoreBattery(context: Context, onError: () -> Unit) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            onError()
        }
    }
}
