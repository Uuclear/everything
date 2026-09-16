package com.everything.eve.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.auth.LoginOutcome
import com.everything.eve.ui.auth.MfaStep
import com.everything.eve.ui.auth.PairingWaitStep
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    onLoggedOut: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenCollector: () -> Unit,
    // 阶段 4b Task 9 / TR-9.1：日历入口回调（沿用 4a 既有 onOpenCollector / onOpenDevices
    // 模式镜像新增；AppNav.kt 中绑定 nav.navigate(Routes.CALENDAR)）。
    onOpenCalendar: () -> Unit,
    vm: VaultViewModel = viewModel(),
) {
    val unlocked by ServiceLocator.auth.isUnlocked.collectAsState()

    if (!unlocked) {
        // 全屏锁屏：密码 → MFA → 待审批 三态在同一覆盖层内接续。
        UnlockFlow(onUnlocked = { vm.syncNow() }, onLoggedOut = onLoggedOut)
        return
    }

    val notes by vm.notes.collectAsState()
    val lastSync by vm.lastSyncAt.collectAsState()
    var showCreate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("加密笔记") },
                actions = {
                    TextButton(onClick = onOpenCollector) { Text("采集") }
                    TextButton(onClick = onOpenDevices) { Text("设备") }
                    // 阶段 4b Task 9 / TR-9.1：日历入口（沿用 4a 既有「采集/设备」TextButton 镜像模式；
                    // 调 onOpenCalendar → AppNav 路由到 CalendarScreen）。
                    TextButton(onClick = onOpenCalendar) { Text(stringResource(R.string.nav_calendar)) }
                    TextButton(onClick = { vm.syncNow() }) {
                        Text("同步\n${formatSyncTime(lastSync)}")
                    }
                    TextButton(onClick = {
                        ServiceLocator.auth.logout()
                        onLoggedOut()
                    }) { Text("退出") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notes, key = { it.id }) { note ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(note.title, style = MaterialTheme.typography.titleMedium)
                        if (note.body.isNotBlank()) {
                            Text(note.body, modifier = Modifier.padding(top = 4.dp))
                        }
                        Text(
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(note.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateNoteDialog(
            onDismiss = { showCreate = false },
            onConfirm = { title, body ->
                vm.createNote(title, body)
                showCreate = false
            },
        )
    }
}

/** 锁屏三态：密码输入；启用 TOTP 的账户进入 MFA；新设备进入配对等待。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockFlow(onUnlocked: () -> Unit, onLoggedOut: () -> Unit) {
    // password / mfa / pairing
    var step by remember { mutableStateOf("password") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("资料库已锁定") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                "mfa" -> MfaStep(
                    onOutcome = { outcome ->
                        when (outcome) {
                            is LoginOutcome.Approved -> onUnlocked()
                            is LoginOutcome.Pending -> step = "pairing"
                            is LoginOutcome.MfaRequired -> Unit
                        }
                    },
                    onBack = {
                        ServiceLocator.auth.rejectMfa()
                        step = "password"
                    },
                )

                "pairing" -> PairingWaitStep(
                    onUnlocked = onUnlocked,
                    onCancel = { step = "password" },
                    // 锁屏页无法内嵌恢复向导：直接退出到欢迎页走完整恢复流程。
                    onUseRecovery = {
                        ServiceLocator.auth.logout()
                        onLoggedOut()
                    },
                )

                else -> {
                    Text(
                        "主密钥仅保存在内存中，刷新页面后需要重新解锁" +
                            "（${ServiceLocator.auth.username}）",
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("主密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !busy && password.isNotEmpty(),
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val outcome = ServiceLocator.auth.login(
                                        ServiceLocator.auth.username,
                                        password,
                                        "android-unlock",
                                    )
                                    password = ""
                                    when (outcome) {
                                        is LoginOutcome.Approved -> onUnlocked()
                                        is LoginOutcome.MfaRequired -> step = "mfa"
                                        is LoginOutcome.Pending -> step = "pairing"
                                    }
                                } catch (e: Exception) {
                                    error = e.message ?: "主密码错误"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("解锁") }
                    TextButton(onClick = {
                        ServiceLocator.auth.logout()
                        onLoggedOut()
                    }) { Text("切换账户") }
                }
            }
        }
    }
}

@Composable
private fun CreateNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建加密笔记") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true)
                OutlinedTextField(body, { body = it }, label = { Text("正文") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title, body) }) {
                Text("加密保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
