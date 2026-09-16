package com.everything.eve.ui.screens

import android.app.Application
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.core.content.ContextCompat
import com.everything.eve.ServiceLocator
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.core.CollectorKind
import com.everything.eve.collector.location.LocationPermissionGate
import com.everything.eve.collector.location.LocationTrackingService
import com.everything.eve.collector.source.PermissionGate
import com.everything.eve.data.CollectorStateEntity
import com.everything.eve.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 一次性"立即采集"任务的展示状态（仅枚举，不携带任何业务数据）。 */
enum class OneShotStatus { IDLE, RUNNING, SUCCEEDED, FAILED }

/** 单个采集类别的行状态；五态文案（未授权/已授权未采集/已采集/未解锁跳过/失败）由 Screen 推导。 */
data class CollectorKindView(
    val kind: CollectorKind,
    val enabled: Boolean,
    val permissionGranted: Boolean,
    val lastRunAt: Long?,
    val lastScannedCount: Int,
    val localCount: Int,
    val lastSkipReason: String?,
)

/**
 * 位置轨迹卡片（阶段 4a Task 7 第 4 卡片）的行状态。
 *
 * 四状态分支由 Screen 依本模型推导：未授权（!fineGranted）/ 仅前台
 * （fine 有而 background 无）/ 齐备采集中（enabled && 权限齐 && MK 在）/
 * MK 不可用降级（enabled && !mkAvailable）。
 */
data class LocationCardView(
    val enabled: Boolean = false,
    val fineGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
    val notificationsGranted: Boolean = false,
    val mkAvailable: Boolean = false,
    val todayPoints: Int = 0,
    val pendingBlocks: Int = 0,
    /** 最近一次上行成功的毫秒时间戳；0 表示从未上传。 */
    val lastUploadAt: Long = 0L,
    /** 轨迹服务最近停止原因（TrackStopReason.wireName）；null 表示无。 */
    val lastStopReason: String? = null,
)

/** 采集页整体 UI 状态。 */
data class CollectorUiState(
    val masterEnabled: Boolean = true,
    val batteryWhitelisted: Boolean = true,
    val kinds: List<CollectorKindView> = emptyList(),
    val oneShot: OneShotStatus = OneShotStatus.IDLE,
    val location: LocationCardView = LocationCardView(),
)

/**
 * 采集页 ViewModel：
 *  - Room（游标状态/条数）走 Flow 直驱；
 *  - 权限、开关、电池白名单等不可观测快照由 [refresh] 触发重采样；
 *  - "立即采集"通过轮询 WorkManager WorkInfo 反馈进行中/成功/失败。
 */
class CollectorViewModel(app: Application) : AndroidViewModel(app) {

    private val stateDao = ServiceLocator.db.collectorStateDao()
    private val recordDao = ServiceLocator.db.recordDao()
    private val locationDao = ServiceLocator.db.locationDao()

    /** 快照重采样信号（权限回调/页面恢复/开关变更后 +1）。 */
    private val refreshTick = MutableStateFlow(0)
    private val oneShotState = MutableStateFlow(OneShotStatus.IDLE)
    private var pollJob: Job? = null

    /** 各类别本地已采集条数（与 CollectorKind.entries 顺序对齐）。 */
    private val countsFlow = combine(
        CollectorKind.entries.map { kind -> recordDao.countByModule(kind.module) },
    ) { arr -> arr.toList() }

    /**
     * 今日已采点数：refreshTick 触发日界重采样（跨零点场景），
     * 其后由 Room 失效表驱动实时刷新（与 FGS 常驻通知同 countSince 口径）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val todayPointsFlow = refreshTick.flatMapLatest {
        locationDao.observeCountSince(dayStartTs())
    }

    /** 位置卡片实时统计：今日点数 + 待传块数（Room Flow 直驱，封块/上行后自动刷新）。 */
    private val locationStatsFlow = combine(
        todayPointsFlow,
        locationDao.observeOutboxCount(),
    ) { today, pending -> today to pending }

    val state = combine(
        refreshTick,
        stateDao.observeAll(),
        countsFlow,
        oneShotState,
        locationStatsFlow,
    ) { _, states, counts, oneShot, locationStats ->
        snapshot(states, counts, oneShot, locationStats)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectorUiState())

    /** 汇总一次完整快照：设置 + 权限 + 电池白名单 + Room 状态。 */
    private fun snapshot(
        states: List<CollectorStateEntity>,
        counts: List<Int>,
        oneShot: OneShotStatus,
        locationStats: Pair<Int, Int>,
    ): CollectorUiState {
        val ctx = getApplication<Application>()
        val granted = PermissionGate.grantedKinds(ctx)
        val pm = ctx.getSystemService(PowerManager::class.java)
        return CollectorUiState(
            masterEnabled = CollectorSettings.isMasterEnabled(ctx),
            batteryWhitelisted = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true,
            kinds = CollectorKind.entries.mapIndexed { index, kind ->
                val st = states.firstOrNull { it.kind == kind.shortName }
                CollectorKindView(
                    kind = kind,
                    enabled = CollectorSettings.isEnabled(ctx, kind),
                    permissionGranted = kind in granted,
                    lastRunAt = st?.lastRunAt,
                    lastScannedCount = st?.lastScannedCount ?: 0,
                    localCount = counts.getOrElse(index) { 0 },
                    lastSkipReason = st?.lastSkipReason,
                )
            },
            oneShot = oneShot,
            location = LocationCardView(
                enabled = CollectorSettings.isLocationTrackingEnabled(ctx),
                fineGranted = LocationPermissionGate.fineGranted(ctx),
                backgroundGranted = LocationPermissionGate.backgroundGranted(ctx),
                notificationsGranted = LocationPermissionGate.notificationsGranted(ctx),
                mkAvailable = ServiceLocator.auth.masterKey != null,
                todayPoints = locationStats.first,
                pendingBlocks = locationStats.second,
                lastUploadAt = CollectorSettings.locationLastUploadAt(ctx),
                lastStopReason = CollectorSettings.locationLastStopReason(ctx),
            ),
        )
    }

    /** 权限结果回调 / ON_RESUME / 开关变更后调用：重采样不可观测快照源。 */
    fun refresh() {
        refreshTick.value = refreshTick.value + 1
    }

    /** 权限申请回调后记录"已申请过"（永久拒绝派生判定依赖此标记）。 */
    fun markPermissionRequested(kind: CollectorKind) {
        CollectorSettings.markPermissionRequested(getApplication(), kind)
    }

    /** 是否已申请过该类权限（Screen 层结合 rationale 派生"永久拒绝"）。 */
    fun wasPermissionRequested(kind: CollectorKind): Boolean =
        CollectorSettings.wasPermissionRequested(getApplication(), kind)

    /** 定位权限申请回调后记录"已申请过"（P3-2 派生判定依赖此持久化标记）。 */
    fun markLocationPermissionRequested() {
        CollectorSettings.markLocationPermissionRequested(getApplication())
    }

    /** 是否已申请过定位权限（Screen 结合 rationale 派生"永久拒绝"）。 */
    fun wasLocationPermissionRequested(): Boolean =
        CollectorSettings.wasLocationPermissionRequested(getApplication())

    /**
     * 轨迹开关（阶段 4a Task 7）：
     *  - 开：置开关并尝试拉起 FGS——服务 onStartCommand 四分支前置检查复核
     *    （开关/MK/FINE/后台定位），任一不满足记枚举原因自停，UI 不重复判定；
     *  - 关：置开关并 stopService（服务未运行时为安全空操作），
     *    明文缓冲由 24h 过期红线兜底清理。
     *
     * 拉起路径与 BootReceiver 自愈同款：ContextCompat.startForegroundService +
     * 双路异常静默降级（部分 ROM 后台拉起限制下不崩溃，等服务下次触发窗口）。
     */
    fun setLocationTrackingEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        CollectorSettings.setLocationTrackingEnabled(ctx, enabled)
        if (enabled) {
            runCatching {
                ContextCompat.startForegroundService(
                    ctx, LocationTrackingService.startIntent(ctx),
                )
            }
        } else {
            runCatching { ctx.stopService(LocationTrackingService.startIntent(ctx)) }
        }
        refresh()
    }

    /**
     * 立即上传（Task 7）：对齐阶段 3"立即采集"模式——复用同一一次性唯一任务
     * （CollectorWorker.doWork 末尾兜底含 packPending + tryUpload），
     * 进行中/成功/失败由既有 oneShot 状态反馈。
     */
    fun uploadNow() = collectNow()

    fun setKindEnabled(kind: CollectorKind, enabled: Boolean) {
        CollectorSettings.setEnabled(getApplication(), kind, enabled)
        refresh()
        // 开启后立即跑一轮，让用户马上看到采集进展
        if (enabled) collectNow()
    }

    /** 周期采集总开关：开则（重新）注册唯一周期任务，关则取消。 */
    fun setMasterEnabled(enabled: Boolean) {
        val ctx = getApplication<Application>()
        CollectorSettings.setMasterEnabled(ctx, enabled)
        if (enabled) SyncScheduler.schedulePeriodic(ctx) else SyncScheduler.cancelPeriodic(ctx)
        refresh()
    }

    /** 立即采集：触发一次性唯一任务，并轮询 WorkInfo 直至结束态。 */
    fun collectNow() {
        val ctx = getApplication<Application>()
        SyncScheduler.requestCollectNow(ctx)
        oneShotState.value = OneShotStatus.RUNNING
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val wm = WorkManager.getInstance(ctx)
            while (true) {
                val info = withContext(Dispatchers.IO) {
                    // REPLACE 策略下同一名称至多一个活跃任务
                    wm.getWorkInfosForUniqueWork(SyncScheduler.ONESHOT_NAME).get().firstOrNull()
                } ?: run {
                    oneShotState.value = OneShotStatus.IDLE
                    return@launch
                }
                when (info.state) {
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                    WorkInfo.State.BLOCKED,
                    -> delay(500)

                    WorkInfo.State.SUCCEEDED -> {
                        oneShotState.value = OneShotStatus.SUCCEEDED
                        refresh() // 条数与状态行即时更新
                        return@launch
                    }

                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED,
                    -> {
                        oneShotState.value = OneShotStatus.FAILED
                        refresh()
                        return@launch
                    }
                }
            }
        }
    }

    /** 本地时区今日零点（UTC 毫秒）："今日已采点数"统计口径，与 FGS 常驻通知一致。 */
    private fun dayStartTs(): Long =
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
