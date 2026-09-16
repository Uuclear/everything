package com.everything.eve.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.api.DeviceInfo
import com.everything.eve.api.PairingInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicesUiState(
    val devices: List<DeviceInfo> = emptyList(),
    val pairings: List<PairingInfo> = emptyList(),
    val loading: Boolean = false,
    val busyId: String = "", // 审批/吊销进行中的条目 id（防重复点击）
    val message: String? = null,
)

/**
 * 设备与审批屏（审批端）。
 * Android 不维护常驻 SSE，进入页面后由 UI 层每 5 秒前台轮询一次（TR-12.2 允许）。
 */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                val devices = ServiceLocator.auth.listDevices()
                val pairings = ServiceLocator.auth.listPairings()
                _state.value = _state.value.copy(
                    devices = devices,
                    // 仅展示仍 pending 且未过期（服务端时间戳为毫秒）。
                    pairings = pairings.filter {
                        it.state == "pending" && it.expiresAt > System.currentTimeMillis()
                    },
                    loading = false,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false, message = e.message ?: "加载设备列表失败",
                )
            }
        }
    }

    /** 批准：用内存 MK 一次性 crypto_box 密封后提交（MK 端到端送达，服务端只透传）。 */
    fun approve(pairing: PairingInfo) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = pairing.id)
            try {
                ServiceLocator.auth.approvePairing(pairing.id, pairing.devicePublicKey)
                _state.value = _state.value.copy(busyId = "", message = "已批准并下发密钥")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busyId = "", message = e.message ?: "批准失败",
                )
            }
        }
    }

    fun reject(pairing: PairingInfo) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = pairing.id)
            try {
                ServiceLocator.auth.rejectPairing(pairing.id)
                _state.value = _state.value.copy(busyId = "", message = "已拒绝该设备")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busyId = "", message = e.message ?: "操作失败",
                )
            }
        }
    }

    fun revoke(device: DeviceInfo) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busyId = device.id)
            try {
                ServiceLocator.auth.revokeDevice(device.id)
                _state.value = _state.value.copy(busyId = "", message = "设备已吊销")
                refresh()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    busyId = "", message = e.message ?: "吊销失败",
                )
            }
        }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
