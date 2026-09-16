package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.api.DeviceInfo
import com.everything.eve.api.PairingInfo
import com.everything.eve.ui.auth.formatMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 设备与审批（审批端）：
 *  - 待审批卡：设备名/配对码/指纹/剩余时间，批准（端到端下发 MK）或拒绝；
 *  - 已登录设备：当前设备标记、state、最近活跃、吊销（当前设备禁用）。
 * 进入页面每 5 秒前台轮询（无 SSE 的兼容方案）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit, vm: DevicesViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.refresh()
        // 5 秒前台轮询：配对请求/处理结果/设备列表变化均能及时反映。
        while (isActive) {
            delay(5_000)
            vm.refresh()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设备与审批") },
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
            item {
                Text("待审批设备（${state.pairings.size}）", style = MaterialTheme.typography.titleMedium)
            }
            if (state.pairings.isEmpty()) {
                item { Text("暂无待确认的登录请求", style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.pairings, key = { it.id }) { pairing ->
                PairingCard(
                    pairing = pairing,
                    busy = state.busyId == pairing.id,
                    onApprove = { vm.approve(pairing) },
                    onReject = { vm.reject(pairing) },
                )
            }

            item {
                Text(
                    "已登录设备（${state.devices.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            items(state.devices, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    busy = state.busyId == device.id,
                    onRevoke = { vm.revoke(device) },
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    pairing: PairingInfo,
    busy: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val remainMs = (pairing.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    pairing.deviceName.ifEmpty { "未知设备" },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text("配对码 ${pairing.pairingCode}", fontFamily = FontFamily.Monospace)
            Text("设备指纹：${pairing.fingerprint}", fontFamily = FontFamily.Monospace)
            Text("请求时间：${formatMillis(pairing.createdAt)}")
            Text(
                "剩余 %d:%02d".format(remainMs / 60_000, (remainMs / 1_000) % 60),
                color = if (remainMs < 5 * 60_000) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "批准前请与申请人当面/电话核对设备名与指纹；批准后主密钥经端到端加密直接送达该设备。",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = onApprove) { Text("批准并下发密钥") }
                OutlinedButton(enabled = !busy, onClick = onReject) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    busy: Boolean,
    onRevoke: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(device.name.ifEmpty { "未知设备" }, style = MaterialTheme.typography.titleSmall)
                if (device.current) Text("本机", style = MaterialTheme.typography.labelSmall)
                Text(
                    "state=${device.state}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("设备指纹：${device.fingerprint}", fontFamily = FontFamily.Monospace)
            Text("最近活跃：${formatMillis(device.lastSeen)}")
            // 服务端拒绝吊销当前设备；UI 同步禁用。
            OutlinedButton(
                enabled = !busy && !device.current,
                onClick = onRevoke,
            ) { Text(if (device.current) "当前设备不可吊销" else "吊销") }
        }
    }
}
