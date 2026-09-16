<script setup lang="ts">
// 欢迎/认证视图：登录（含 TOTP/设备审批分支，面板由 T9 组件承接）、
// 注册（强制恢复码备份）、忘记主密码恢复向导（恢复码 → 新密码 → 新恢复码）。
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NCard,
  NTabs,
  NTabPane,
  NForm,
  NFormItem,
  NInput,
  NButton,
  NSpace,
  NAlert,
  NSteps,
  NStep,
  useMessage,
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'
import { normalizeRecoveryCode, formatRecoveryCode } from '../crypto/crockford'
import type { LoginResponse } from '../api/client'
import RecoveryCodeCard from '../components/RecoveryCodeCard.vue'
import MfaPanel from '../components/MfaPanel.vue'
import PendingPanel from '../components/PendingPanel.vue'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

// ---- 总状态：auth=登录注册；registerBackup=注册后强制备份；recovery=恢复向导 ----
const view = ref<'auth' | 'registerBackup' | 'recovery'>('auth')
const tab = ref<'login' | 'register'>('register')
const username = ref(auth.username)
const password = ref('')
const loading = ref(false)
const registeredCode = ref('')

function enterVault() {
  router.push({ name: 'logins' })
}

// ---- 登录 / 注册 ----
async function submit() {
  if (!username.value.trim() || !password.value) {
    message.warning('请输入用户名和主密码')
    return
  }
  loading.value = true
  try {
    if (tab.value === 'register') {
      const recovery = await auth.register(username.value.trim(), password.value)
      registeredCode.value = recovery.formatted
      view.value = 'registerBackup' // 未确认备份前不进入资料库
    } else {
      const result = await auth.login(username.value.trim(), password.value)
      routeLoginResult(result)
    }
  } catch (e) {
    showAuthError(e)
  } finally {
    loading.value = false
  }
}

/** 按登录三态分流：approved 入库；pending/mfa 由全屏面板接续。 */
function routeLoginResult(result: LoginResponse) {
  if (result.status === 'approved') {
    message.success('登录成功，资料库已解锁')
    enterVault()
  }
  // pending / mfa_required 由模板中的对应面板接管（store 已存会话）。
}

function cancelRegisterBackup() {
  // 账户已在服务端创建；放弃仅本地退出，可用刚设置的主密码重新登录。
  auth.logout()
  registeredCode.value = ''
  view.value = 'auth'
  tab.value = 'login'
}

// ---- 恢复向导 ----
const recStep = ref(1)
const recUsername = ref(auth.username)
const recCodeInput = ref('')
const recCodeError = ref('')
const recoveryToken = ref('')
// 恢复向导期间 MK 短暂驻留组件内存（仅本向导使用，进入资料库后由 store 统一持有）。
const recMK = ref<Uint8Array | null>(null)
const newPassword = ref('')
const newPassword2 = ref('')
const newRecoveryCode = ref('')

function startRecovery() {
  view.value = 'recovery'
  recStep.value = 1
  recUsername.value = auth.username
  recCodeInput.value = ''
  recCodeError.value = ''
}

/** 恢复码输入框失焦：分组回显，非法时即时提示且不进入下一步。 */
function formatRecoveryInput() {
  const raw = recCodeInput.value
  if (!raw.trim()) return
  try {
    recCodeInput.value = formatRecoveryCode(normalizeRecoveryCode(raw))
    recCodeError.value = ''
  } catch (e) {
    recCodeError.value = (e as Error).message
  }
}

async function submitRecoveryCode() {
  recCodeError.value = ''
  try {
    normalizeRecoveryCode(recCodeInput.value) // 提交前再校验一次
  } catch (e) {
    recCodeError.value = (e as Error).message
    return
  }
  loading.value = true
  try {
    const session = await auth.recoveryStart(
      recUsername.value.trim(),
      recCodeInput.value,
    )
    recoveryToken.value = session.token
    recMK.value = session.mk
    recStep.value = 2
  } catch (e) {
    if (e instanceof ApiError && (e.status === 401 || e.status === 429)) {
      message.error(e.status === 429 ? '尝试次数过多，请 15 分钟后再试' : '用户名或恢复码错误')
    } else {
      showAuthError(e)
    }
  } finally {
    loading.value = false
  }
}

async function submitNewPassword() {
  if (newPassword.value.length < 8) {
    message.warning('新主密码至少 8 位')
    return
  }
  if (newPassword.value !== newPassword2.value) {
    message.warning('两次输入的新主密码不一致')
    return
  }
  loading.value = true
  try {
    if (!recMK.value) {
      message.error('恢复会话已失效，请重新开始')
      recStep.value = 1
      return
    }
    // 使用第 1 步离线解开的 MK 重新包裹（服务端全程接触不到 MK/新密码）。
    const recovery = await auth.recoveryReset(
      recoveryToken.value,
      recMK.value,
      newPassword.value,
      recUsername.value.trim(),
    )
    newRecoveryCode.value = recovery.formatted
    recMK.value = null
    recStep.value = 3
  } catch (e) {
    showAuthError(e)
  } finally {
    loading.value = false
  }
}

/** 第 3 步放弃：重置已生效但未备份新码，退出登录（新恢复码可重新发起恢复获取）。 */
function cancelNewRecoveryBackup() {
  auth.logout()
  newRecoveryCode.value = ''
  view.value = 'auth'
}

function showAuthError(e: unknown) {
  if (e instanceof ApiError) {
    message.error(`${e.message}（${e.code}）`)
  } else {
    message.error('操作失败，请检查服务端地址与网络')
  }
}
</script>

<template>
  <div class="welcome">
    <n-card class="welcome-card" :bordered="false">
      <!-- ===== 登录 / 注册 ===== -->
      <template v-if="view === 'auth'">
        <!-- 设备待审批全屏接续 -->
        <PendingPanel v-if="auth.pendingAccess" @unlocked="enterVault" @use-recovery="startRecovery" />
        <!-- TOTP 二次验证全屏接续（通过后若设备待审批会自动切到上面板） -->
        <MfaPanel v-else-if="auth.mfaToken" @resolved="routeLoginResult" />

        <template v-else>
          <h1 class="brand">Everything</h1>
          <p class="subtitle">你的人生操作系统 · 零知识加密</p>
          <n-tabs v-model:value="tab" animated>
            <n-tab-pane name="register" tab="创建账户" />
            <n-tab-pane name="login" tab="登录解锁" />
          </n-tabs>
          <n-form @keyup.enter="submit">
            <n-form-item label="用户名">
              <n-input v-model:value="username" placeholder="例如 alice" autocomplete="username" />
            </n-form-item>
            <n-form-item label="主密码">
              <n-input
                v-model:value="password"
                type="password"
                show-password-on="click"
                placeholder="主密码不会发送给服务器"
                :autocomplete="tab === 'register' ? 'new-password' : 'current-password'"
              />
            </n-form-item>
            <n-alert v-if="tab === 'register'" type="warning" :show-icon="false" class="hint">
              主密码无法找回，但注册时会生成<b>恢复码</b>作为最后的取回手段，
              请在下一页务必离线保存。
            </n-alert>
            <n-space justify="space-between" class="submit-row">
              <n-button quaternary size="small" @click="startRecovery">
                忘记主密码 / 新设备恢复
              </n-button>
              <n-button type="primary" :loading="loading" @click="submit">
                {{ tab === 'register' ? '创建账户' : '登录并解锁' }}
              </n-button>
            </n-space>
          </n-form>
        </template>
      </template>

      <!-- ===== 注册后：强制恢复码备份 ===== -->
      <template v-else-if="view === 'registerBackup'">
        <h2 class="step-title">账户恢复码（仅显示这一次）</h2>
        <RecoveryCodeCard
          :code="registeredCode"
          @confirm="enterVault"
          @cancel="cancelRegisterBackup"
        />
      </template>

      <!-- ===== 恢复向导 ===== -->
      <template v-else>
        <h2 class="step-title">忘记主密码恢复</h2>
        <n-steps :current="recStep" size="small" class="rec-steps">
          <n-step title="验证恢复码" />
          <n-step title="设置新主密码" />
          <n-step title="保存新恢复码" />
        </n-steps>

        <!-- 第 1 步：用户名 + 恢复码 -->
        <n-form v-if="recStep === 1" @keyup.enter="submitRecoveryCode">
          <n-alert type="error" :show-icon="false" class="hint">
            恢复成功后，当前所有已登录设备会被注销，旧恢复码立即作废，
            系统会为你生成新的恢复码。
          </n-alert>
          <n-form-item label="用户名">
            <n-input v-model:value="recUsername" autocomplete="username" />
          </n-form-item>
          <n-form-item label="恢复码" :validation-status="recCodeError ? 'error' : undefined"
            :feedback="recCodeError">
            <n-input
              v-model:value="recCodeInput"
              type="textarea"
              :rows="2"
              placeholder="XXXX-XXXX-..."
              class="recovery-input"
              @blur="formatRecoveryInput"
            />
          </n-form-item>
          <n-space justify="space-between">
            <n-button quaternary @click="view = 'auth'">返回登录</n-button>
            <n-button type="primary" :loading="loading" @click="submitRecoveryCode">
              验证并继续
            </n-button>
          </n-space>
        </n-form>

        <!-- 第 2 步：新主密码 -->
        <n-form v-else-if="recStep === 2" @keyup.enter="submitNewPassword">
          <n-form-item label="新主密码（至少 8 位）">
            <n-input v-model:value="newPassword" type="password" show-password-on="click"
              autocomplete="new-password" />
          </n-form-item>
          <n-form-item label="再次输入新主密码">
            <n-input v-model:value="newPassword2" type="password" show-password-on="click"
              autocomplete="new-password" />
          </n-form-item>
          <n-space justify="end">
            <n-button type="primary" :loading="loading" @click="submitNewPassword">
              重置并继续
            </n-button>
          </n-space>
        </n-form>

        <!-- 第 3 步：新恢复码强制备份 -->
        <template v-else>
          <RecoveryCodeCard
            :code="newRecoveryCode"
            rotated
            @confirm="enterVault"
            @cancel="cancelNewRecoveryBackup"
          />
        </template>
      </template>
    </n-card>
  </div>
</template>

<style scoped>
.welcome {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2a44 0%, #0f1525 100%);
  padding: 16px;
}
.welcome-card {
  width: 460px;
  max-width: 100%;
  border-radius: 14px;
}
.brand {
  margin: 0;
  font-size: 24px;
  letter-spacing: 1px;
}
.step-title {
  margin: 0 0 14px;
  font-size: 19px;
}
.subtitle {
  margin: 4px 0 12px;
  color: #8a8f9c;
  font-size: 13px;
}
.hint {
  margin: 4px 0 12px;
  line-height: 1.7;
}
.submit-row {
  width: 100%;
}
.rec-steps {
  margin: 4px 0 18px;
}
.recovery-input {
  font-family: 'JetBrains Mono', Consolas, monospace;
  letter-spacing: 1px;
}
</style>
