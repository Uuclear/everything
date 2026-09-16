package com.everything.eve.ui.auth

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everything.eve.ServiceLocator
import com.everything.eve.auth.LoginOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TOTP 两步验证步骤。[submit] 由宿主提供（登录或锁屏解锁都走 auth.finishMfa），
 * 结果三态由宿主的 [onOutcome] 统一路由。
 */
@Composable
fun MfaStep(
    onOutcome: (LoginOutcome) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val deviceName = "android-${Build.MODEL}"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("两步验证", style = MaterialTheme.typography.headlineSmall)
        Text(
            "请打开你的身份验证器（Google Authenticator 等），输入当前 6 位验证码。",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("6 位动态码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = !busy, onClick = onBack) { Text("返回") }
            Button(
                enabled = !busy && code.length == 6,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onOutcome(ServiceLocator.auth.finishMfa(code, deviceName))
                        } catch (e: Exception) {
                            // 服务端错码统一 401 invalid_totp；限流 429。
                            error = if (e.message?.contains("429") == true)
                                "验证失败次数过多，请 15 分钟后再试" else "验证码错误或已过期"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text("验证")
            }
        }
    }
}

/**
 * 新设备配对等待步骤：展示配对码/指纹/倒计时，每 3 秒轮询状态。
 * approved（auth.isUnlocked 翻转）→ [onUnlocked]；拒绝/过期/令牌失效 → 提示并停留可返回。
 */
@Composable
fun PairingWaitStep(
    onUnlocked: () -> Unit,
    onCancel: () -> Unit,
    onUseRecovery: () -> Unit,
) {
    val auth = ServiceLocator.auth
    val pairing = auth.currentPairing
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var status by remember { mutableStateOf("正在等待批准…") }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 倒计时 1s 一跳。
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    // 3 秒轮询配对状态。
    LaunchedEffect(Unit) {
        while (isActive && !failed) {
            try {
                val st = auth.pairingPoll()
                if (ServiceLocator.auth.isUnlocked.value) {
                    onUnlocked()
                    return@LaunchedEffect
                }
                when (st.state) {
                    "rejected" -> {
                        failed = true
                        status = "该设备的登录请求已被拒绝"
                        auth.rejectPairing()
                    }
                    "expired" -> {
                        failed = true
                        status = "审批请求已过期（15 分钟），请返回重新登录"
                        auth.rejectPairing()
                    }
                }
            } catch (e: Exception) {
                // pending 令牌过期（401/403）：结束等待，引导恢复或重登。
                failed = true
                status = "审批等待已过期，请重新登录或改用恢复码"
            }
            delay(3_000)
        }
    }

    val remainMs = (pairing?.expiresAt ?: now) - now
    val minutes = (remainMs / 60_000).coerceAtLeast(0)
    val seconds = ((remainMs / 1_000) % 60).coerceAtLeast(0)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("等待已批准设备授权", style = MaterialTheme.typography.headlineSmall)
        Text(
            "这是一台新设备。请在你已登录的设备上确认本次登录，并核对设备指纹一致。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("配对码（在已登录设备上核对）", style = MaterialTheme.typography.labelMedium)
        Text(
            pairing?.pairingCode.orEmpty(),
            fontSize = 34.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Text("设备指纹：${pairing?.fingerprint.orEmpty()}", fontFamily = FontFamily.Monospace)
        Text(
            "剩余 %d:%02d".format(minutes, seconds),
            color = if (remainMs < 5 * 60_000) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
        Text(status, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            TextButton(enabled = !false, onClick = {
                scope.launch { auth.rejectPairing(); onCancel() }
            }) { Text("取消") }
            TextButton(onClick = {
                scope.launch { auth.rejectPairing(); onUseRecovery() }
            }) { Text("无法访问旧设备？改用恢复码") }
        }
    }
}

/**
 * 恢复码强制备份步骤：ScrollView 大字分组展示 + 必须勾选确认才能进入。
 * 注册后、恢复重置后共用；改密轮换时同样使用。
 */
@Composable
fun RecoveryBackupStep(
    formattedCode: String,
    onConfirm: () -> Unit,
    confirmText: String = "我已备份，进入资料库",
    onCancel: (() -> Unit)? = null,
) {
    var acknowledged by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("新的恢复码已生成，旧恢复码立即作废", style = MaterialTheme.typography.titleMedium)
        Text(
            "忘记主密码且没有恢复码 = 你的全部数据将永久丢失且无法找回。" +
                "服务器不存储恢复码，也无法帮你重置。请抄写或打印到离线安全的位置，" +
                "不要截图、不要存网盘、不要发给任何人。",
            style = MaterialTheme.typography.bodySmall,
        )
        // 4 组×8 字符，等宽大字，便于人工抄写核对。
        formattedCode.split("-").forEach { group ->
            Text(
                group,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
            Text("我已将恢复码保存在离线、安全的位置，并理解丢失它将导致数据永久丢失")
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text("取消（保持退出）") }
            }
            Button(enabled = acknowledged, onClick = onConfirm) { Text(confirmText) }
        }
        Text(
            "恢复码不区分大小写，输入时可省略连字符",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** 恢复向导第一步的结果：携带恢复会话与已离线解开的 MK。 */
class RecoverySessionHolder(
    var token: String = "",
    var mk: ByteArray? = null,
)

/**
 * 忘记主密码恢复向导（3 步：验证恢复码 → 新主密码 → 备份新恢复码）。
 * 完成后当前设备以新密码身份直接解锁进入。
 */
@Composable
fun RecoveryWizard(
    onCompleted: () -> Unit,
    onBack: () -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    var username by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPassword2 by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val session = remember { RecoverySessionHolder() }
    val deviceName = "android-${Build.MODEL}"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("忘记主密码恢复", style = MaterialTheme.typography.headlineSmall)
        Text("步骤：1 验证恢复码 → 2 设置新主密码 → 3 保存新恢复码")

        if (step == 1) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text("恢复码 XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(enabled = !busy, onClick = onBack) { Text("返回登录") }
                Button(
                    enabled = !busy && username.isNotBlank() && code.isNotBlank(),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val r = ServiceLocator.auth.recoveryStart(
                                    username.trim(), code,
                                )
                                session.token = r.recoveryToken
                                session.mk = r.mk
                                step = 2
                            } catch (e: Exception) {
                                // 错码/用户不存在同形：服务端统一“用户名或恢复码错误”。
                                error = "用户名或恢复码错误"
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("验证并继续") }
            }
        }

        if (step == 2) {
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("新主密码（至少 8 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPassword2,
                onValueChange = { newPassword2 = it },
                label = { Text("再次输入新主密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && newPassword.length >= 8 && newPassword == newPassword2,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val gen = ServiceLocator.auth.recoveryReset(
                                session.token,
                                session.mk!!,
                                newPassword,
                                username.trim(),
                                deviceName,
                            )
                            newCode = gen.formatted
                            step = 3
                        } catch (e: Exception) {
                            error = e.message ?: "恢复重置失败"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重置并继续") }
        }

        if (step == 3) {
            RecoveryBackupStep(
                formattedCode = newCode,
                onConfirm = onCompleted,
                confirmText = "我已备份，进入资料库",
            )
        }
    }
}

/** 毫秒时间戳 → “MM-dd HH:mm”。 */
fun formatMillis(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
