package com.everything.eve.collector.location

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.everything.eve.MainActivity
import com.everything.eve.ServiceLocator
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.location.core.LocationParams
import com.everything.eve.collector.location.core.PointFilter
import com.everything.eve.collector.location.core.RateDecider
import com.everything.eve.collector.location.core.TrackPoint
import com.everything.eve.collector.location.core.TrackStartCheck
import com.everything.eve.collector.location.core.TrackStopReason
import com.everything.eve.collector.location.db.LocationPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * 位置轨迹前台定位服务（阶段 4a Task 6）。
 *
 * 生命周期与分支决策（tasks.md Task 6 / spec FR-1、FR-2、FR-8）：
 *  - [onStartCommand] 前置检查四分支：轨迹开关开 && MK 非空 && FINE 已授权 &&
 *    后台定位"始终允许"（判定逻辑抽为纯函数 [TrackStartCheck.blockedReason]，
 *    可 JVM 单测逐条定位）；任一不满足 → 记 [TrackStopReason] 枚举原因后
 *    stopSelf，不弹窗打扰；
 *  - 启动成功：常驻通知（channel `location_tracking`，LOW 重要性，
 *    文案仅"轨迹采集中 · 今日 N 点"，无坐标）→ 注册 LocationManager
 *    （GPS_PROVIDER 优先，isProviderEnabled=false 时 NETWORK_PROVIDER 兜底，
 *    两者都不可用则注册到 GPS 挂起、等 onProviderEnabled 回调自动恢复）；
 *  - 采样参数：minDistance 恒 [LocationParams.MIN_DISTANCE_M]（25m），
 *    minTime 取 [RateDecider.decideInterval] 输出（静止降频 300s / 常规 60s），
 *    每次收点后重算，档位变化时 removeUpdates + 重新 requestLocationUpdates；
 *  - onLocationChanged：[PointFilter.accept] 通过 → 写 location_points →
 *    [LocationPackager.packPending] 封块密封 →（Task 7 挂载点：LocationUploader
 *    .tryUpload 在线即传）→ 刷新通知今日点数；
 *  - MK 监听：AuthManager.isUnlocked StateFlow 变 false（MK 变 null）即
 *    removeUpdates + stopForeground(STOP_FOREGROUND_REMOVE) + stopSelf；
 *  - [onDestroy] 释放 listener 与协程域；异常一律翻译为 [TrackStopReason] 枚举。
 *
 * 零知识约束：本服务不写任何日志；坐标明文只经"Location → Room 行"流转，
 * 绝不进通知文案 / SharedPreferences / 异常消息。
 */
class LocationTrackingService : Service() {

    companion object {
        /** 常驻通知 channel id（tasks.md Task 6 权威值）。 */
        const val CHANNEL_ID = "location_tracking"

        /** 常驻通知 id（固定值，计数刷新即同 id 更新）。 */
        private const val NOTIFICATION_ID = 4101

        /** 拉起本服务的 Intent（BootReceiver 自愈 / 解锁恢复挂钩共用）。 */
        fun startIntent(context: Context): Intent =
            Intent(context, LocationTrackingService::class.java)
    }

    /** 服务协程域：listener 回调与 MK 监听在主线程，库读写切 IO。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val locationManager by lazy {
        getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val locationDao by lazy { ServiceLocator.db.locationDao() }
    private val packager by lazy { ServiceLocator.locationPackager }
    private val uploader by lazy { ServiceLocator.locationUploader }
    private val auth by lazy { ServiceLocator.auth }

    /** 当前采样请求的 minTime 档位（毫秒），初始常规档。 */
    @Volatile
    private var currentIntervalMs: Long = LocationParams.MIN_TIME_MS

    /** 当前注册的 provider（GPS 优先 / NETWORK 兜底）。 */
    @Volatile
    private var currentProvider: String = LocationManager.GPS_PROVIDER

    /** 是否已注册定位监听（onStartCommand 幂等锚点）。 */
    @Volatile
    private var tracking: Boolean = false

    // ---- 定位监听 ----

    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {
            // 精度过滤：无精度或 >100m 直接丢弃（FR-1）
            val acc = if (location.hasAccuracy()) location.accuracy else null
            if (!PointFilter.accept(acc)) return
            val accepted = acc ?: return // accept 已保证非空，这里仅做类型收窄

            serviceScope.launch(Dispatchers.IO) {
                try {
                    ingestPoint(location, accepted)
                } catch (e: Exception) {
                    // 写库/封块异常：翻译为枚举原因（坐标绝不进异常消息），
                    // 服务不停止——滞留明文由 24h 过期红线兜底，下个点继续
                    CollectorSettings.setLocationLastStopReason(
                        applicationContext, TrackStopReason.SERVICE_ERROR.wireName,
                    )
                }
            }
        }

        /** provider 恢复可用：重选 provider（GPS 恢复则切回优先档）。 */
        override fun onProviderEnabled(provider: String) {
            reselectAndRegister()
        }

        /** provider 被禁用：重选 provider（GPS 禁用则 NETWORK 兜底/挂起等待）。 */
        override fun onProviderDisabled(provider: String) {
            reselectAndRegister()
        }
    }

    // ---- Service 生命周期 ----

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ---- 前置检查四分支（tasks.md Task 6）：开关 && MK && FINE && 后台定位 ----
        val blocked = TrackStartCheck.blockedReason(
            trackingEnabled = CollectorSettings.isLocationTrackingEnabled(this),
            mkAvailable = auth.masterKey != null,
            fineGranted = LocationPermissionGate.fineGranted(this),
            backgroundGranted = LocationPermissionGate.backgroundGranted(this),
        )
        if (blocked != null) {
            // 记枚举原因后退出，不弹窗打扰（启动失败尚未进前台，无需 stopForeground）
            CollectorSettings.setLocationLastStopReason(this, blocked.wireName)
            stopSelf()
            return START_STICKY
        }

        // ---- 启动成功路径（START_STICKY 重启/重复拉起幂等）----
        if (tracking) return START_STICKY
        CollectorSettings.setLocationLastStopReason(this, null)

        try {
            ensureNotificationChannel()
            startForegroundWithNotification(todayCount = null)
            registerLocationUpdates()
            tracking = true
            watchMasterKey()
            // 服务重启后档位判定自然延续：按 Room 既有窗口点重算一次采样档位
            serviceScope.launch(Dispatchers.IO) { refreshSamplingRate(System.currentTimeMillis()) }
        } catch (e: SecurityException) {
            // FINE 在检查与注册之间被撤销：按权限缺失语义停止
            halt(TrackStopReason.FINE_DENIED)
        } catch (e: Exception) {
            halt(TrackStopReason.SERVICE_ERROR)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // 释放 listener 与协程域（removeUpdates 幂等防御：未注册时调用安全）
        runCatching { locationManager.removeUpdates(locationListener) }
        tracking = false
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- 采样注册与静止降频 ----

    /**
     * provider 选择：GPS 优先；GPS 不可用时 NETWORK 兜底；两者都不可用返回 GPS
     * ——注册到 disabled provider 合法且不收点，等价于"挂起等待 provider 回调"
     * （onProviderEnabled 触发后重选恢复）。
     */
    private fun selectProvider(): String = when {
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
            LocationManager.GPS_PROVIDER
        locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
            LocationManager.NETWORK_PROVIDER
        else -> LocationManager.GPS_PROVIDER
    }

    /** 按当前档位与当前 provider 注册定位请求（前置检查已保证 FINE 授权）。 */
    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        currentProvider = selectProvider()
        locationManager.requestLocationUpdates(
            currentProvider,
            currentIntervalMs,
            LocationParams.MIN_DISTANCE_M, // minDistance 恒 25m（tasks.md Task 6）
            locationListener,
        )
    }

    /** provider 状态变化后的重选与重注册（仅服务在采时动作）。 */
    private fun reselectAndRegister() {
        if (!tracking) return
        val preferred = selectProvider()
        if (preferred == currentProvider) return
        try {
            locationManager.removeUpdates(locationListener)
            registerLocationUpdates()
        } catch (e: SecurityException) {
            halt(TrackStopReason.FINE_DENIED)
        }
    }

    /**
     * 静止降频重算（IO 线程）：取 Room 最近窗口点（ts ≥ now-10min，tasks.md
     * Task 6 Notes）经 [RateDecider.decideInterval] 判定，档位变化时回主线程
     * removeUpdates + 重新 requestLocationUpdates。
     */
    private suspend fun refreshSamplingRate(now: Long) {
        val windowStart = now - LocationParams.STILL_WINDOW_MS
        val recent = locationDao.oldestFirst()
            .filter { it.ts >= windowStart }
            .map {
                TrackPoint(
                    ts = it.ts, lat = it.lat, lon = it.lon, acc = it.acc,
                    speed = it.speed, bearing = it.bearing,
                    altitude = it.altitude, provider = it.provider,
                )
            }
        val decided = RateDecider.decideInterval(recent, now)
        if (decided == currentIntervalMs) return
        withContext(Dispatchers.Main) {
            if (!tracking) return@withContext
            try {
                locationManager.removeUpdates(locationListener)
                currentIntervalMs = decided
                registerLocationUpdates()
            } catch (e: SecurityException) {
                halt(TrackStopReason.FINE_DENIED)
            }
        }
    }

    // ---- 收点入库与封块 ----

    /**
     * 单点处理（IO 线程）：写 location_points → 封块密封 → 通知计数刷新 →
     * 采样档位重算。Task 7 挂载点：LocationUploader.tryUpload()（在线即传）
     * 在 packPending 之后调用，本任务不实现。
     */
    private suspend fun ingestPoint(location: Location, acc: Float) {
        val now = System.currentTimeMillis()
        // 采样时刻取定位时刻（设备系统时间换算的 UTC 毫秒），异常值回退当前时刻
        val ts = if (location.time > 0) location.time else now
        locationDao.insert(
            LocationPointEntity(
                ts = ts,
                lat = location.latitude,
                lon = location.longitude,
                acc = acc,
                speed = if (location.hasSpeed()) location.speed else null,
                bearing = if (location.hasBearing()) location.bearing else null,
                altitude = if (location.hasAltitude()) location.altitude else null,
                provider = location.provider,
                createdAt = now,
            ),
        )

        // 封块密封编排（明文 → 密文 outbox；MK 缺失时其内部翻译枚举跳过）
        packager.packPending()
        // 在线即传（Task 7）：不依赖 MK（outbox 只有密文），异常已被外层
        // catch 翻译为 SERVICE_ERROR 且服务不停——失败块留队下轮窗口重试
        uploader.tryUpload()

        // 通知计数刷新："轨迹采集中 · 今日 N 点"
        refreshNotification(locationDao.countSince(dayStartTs(now)))

        // 静止降频：每次收点后重算采样档位
        refreshSamplingRate(now)
    }

    // ---- 通知 ----

    /** 创建（幂等）通知 channel：location_tracking，LOW 重要性（不发声不震动）。 */
    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "位置轨迹",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "轨迹采集常驻通知" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 构建常驻通知：文案仅"轨迹采集中 · 今日 N 点"（零坐标，NFR-1）。
     *
     * @param todayCount 今日已采点数；null 表示启动瞬间尚未统计（文案省略计数）。
     */
    private fun buildNotification(todayCount: Int?): Notification {
        val text = if (todayCount == null) "轨迹采集中" else "轨迹采集中 · 今日 $todayCount 点"
        // 点击通知回到主界面（不携带任何轨迹数据）
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Everything")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 进入前台（API29+ 显式携带 foregroundServiceType=location）。 */
    private fun startForegroundWithNotification(todayCount: Int?) {
        val notification = buildNotification(todayCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** 同 id 更新常驻通知文案（今日计数变化）。 */
    private fun refreshNotification(todayCount: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(todayCount))
    }

    // ---- MK 监听与停止 ----

    /** MK 监听：解锁态变 false（MK 变 null）即停采退出前台（FR-8）。 */
    private fun watchMasterKey() {
        serviceScope.launch {
            auth.isUnlocked.collect { unlocked ->
                if (!unlocked) halt(TrackStopReason.MK_UNAVAILABLE)
            }
        }
    }

    /**
     * 统一停止路径：记枚举原因 → removeUpdates →
     * stopForeground(STOP_FOREGROUND_REMOVE) → stopSelf。
     */
    private fun halt(reason: TrackStopReason) {
        CollectorSettings.setLocationLastStopReason(this, reason.wireName)
        runCatching { locationManager.removeUpdates(locationListener) }
        tracking = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 时间工具 ----

    /** 本地时区今日零点（UTC 毫秒）：通知"今日 N 点"统计口径。 */
    private fun dayStartTs(now: Long): Long =
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
