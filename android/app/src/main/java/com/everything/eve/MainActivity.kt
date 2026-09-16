package com.everything.eve

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.everything.eve.collector.CollectorSettings
import com.everything.eve.collector.location.LocationPermissionGate
import com.everything.eve.collector.location.LocationTrackingService
import com.everything.eve.ui.AppNav
import com.everything.eve.ui.theme.EveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EveTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
        // 阶段 4a 解锁恢复挂钩（FR-8）：MK 解锁后自动恢复轨迹采集——
        // BootReceiver/服务自身在 MK 不可用时会降级退出，此处是唯一的恢复拉起点
        observeUnlockAndResumeTracking()
    }

    /** 监听解锁态：变 true 且轨迹开关开、权限齐备时拉起前台定位服务。 */
    private fun observeUnlockAndResumeTracking() {
        lifecycleScope.launch {
            ServiceLocator.auth.isUnlocked.collect { unlocked ->
                if (unlocked) resumeLocationTrackingIfEligible()
            }
        }
    }

    /** 恢复拉起的前置复核（服务 onStartCommand 还会再查一次四分支）。 */
    private fun resumeLocationTrackingIfEligible() {
        if (!CollectorSettings.isLocationTrackingEnabled(this)) return
        if (!LocationPermissionGate.canTrack(this)) return
        try {
            ContextCompat.startForegroundService(
                this,
                LocationTrackingService.startIntent(this),
            )
        } catch (e: IllegalStateException) {
            // 系统启动限制（含 API31+ 后台限制子类）：降级等待下次解锁/启动，不崩溃
        }
    }
}
