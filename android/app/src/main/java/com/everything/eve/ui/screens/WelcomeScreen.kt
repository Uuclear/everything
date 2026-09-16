package com.everything.eve.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.everything.eve.ServiceLocator
import com.everything.eve.auth.LoginOutcome
import com.everything.eve.ui.auth.MfaStep
import com.everything.eve.ui.auth.PairingWaitStep
import com.everything.eve.ui.auth.RecoveryBackupStep
import com.everything.eve.ui.auth.RecoveryWizard
import kotlinx.coroutines.launch

/** 欢迎页内部流程：账号密码 / 注册后备份恢复码 / MFA / 等待审批 / 恢复向导。 */
private enum class WelcomeStep { CREDENTIALS, REGISTER_BACKUP, MFA, PAIRING, RECOVERY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(onEntered: () -> Unit) {
    var step by remember { mutableStateOf(WelcomeStep.CREDENTIALS) }
    var registerMode by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf(ServiceLocator.auth.serverUrl) }
    var username by remember { mutableStateOf(ServiceLocator.auth.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var newRecoveryCode by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deviceName = "android-${Build.MODEL}"

    /** 三态分流：approved 进资料库；pending 等待审批；mfa_required 进二步验证。 */
    fun routeOutcome(outcome: LoginOutcome) {
        when (outcome) {
            is LoginOutcome.Approved -> onEntered()
            is LoginOutcome.Pending -> step = WelcomeStep.PAIRING
            is LoginOutcome.MfaRequired -> step = WelcomeStep.MFA
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Everything") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                WelcomeStep.CREDENTIALS -> {
                    Text(
                        if (registerMode) "创建账户" else "登录解锁",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("主密码（不会发送明文）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    ServiceLocator.setServer(server.trim())
                                    if (registerMode) {
                                        // 注册成功：先强制备份恢复码，再进入资料库。
                                        val recovery = ServiceLocator.auth.register(
                                            username.trim(), password, deviceName,
                                        )
                                        newRecoveryCode = recovery.formatted
                                        step = WelcomeStep.REGISTER_BACKUP
                                    } else {
                                        routeOutcome(
                                            ServiceLocator.auth.login(
                                                username.trim(), password, deviceName,
                                            ),
                                        )
                                    }
                                } catch (e: Exception) {
                                    snackbar.showSnackbar(e.message ?: "操作失败")
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (registerMode) "创建并进入" else "登录并解锁")
                    }
                    TextButton(onClick = { registerMode = !registerMode }) {
                        Text(if (registerMode) "已有账户？去登录" else "首次使用？创建账户")
                    }
                    TextButton(onClick = { step = WelcomeStep.RECOVERY }) {
                        Text("忘记主密码 / 新设备恢复")
                    }
                }

                WelcomeStep.REGISTER_BACKUP -> RecoveryBackupStep(
                    formattedCode = newRecoveryCode,
                    onConfirm = onEntered,
                    confirmText = "我已备份，进入资料库",
                )

                WelcomeStep.MFA -> MfaStep(
                    onOutcome = { outcome ->
                        // MFA 后仍可能 approved 或 pending（新设备）。
                        busy = false
                        routeOutcome(outcome)
                    },
                    onBack = {
                        ServiceLocator.auth.rejectMfa()
                        step = WelcomeStep.CREDENTIALS
                    },
                )

                WelcomeStep.PAIRING -> PairingWaitStep(
                    onUnlocked = onEntered,
                    onCancel = { step = WelcomeStep.CREDENTIALS },
                    onUseRecovery = { step = WelcomeStep.RECOVERY },
                )

                WelcomeStep.RECOVERY -> RecoveryWizard(
                    onCompleted = onEntered,
                    onBack = { step = WelcomeStep.CREDENTIALS },
                )
            }
        }
    }
}
