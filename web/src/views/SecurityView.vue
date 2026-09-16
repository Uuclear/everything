<script setup lang="ts">
// 安全设置：修改主密码（可选轮换恢复码）、TOTP 两步验证的启用/禁用。
import { onMounted, ref } from 'vue'
import {
  NCard,
  NForm,
  NFormItem,
  NInput,
  NButton,
  NSpace,
  NAlert,
  NCheckbox,
  NTag,
  NSpin,
  useMessage,
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { api, ApiError, type TOTPSetup } from '../api/client'
import { getRefreshToken } from '../api/client'
import RecoveryCodeCard from '../components/RecoveryCodeCard.vue'

const message = useMessage()
const auth = useAuthStore()

// ---- 修改主密码 ----
const newPassword = ref('')
const newPassword2 = ref('')
const rotateRecovery = ref(true)
const changing = ref(false)
const rotatedCode = ref('') // 轮换成功后的新恢复码（强制展示）

async function changePassword() {
  if (newPassword.value.length < 8) {
    message.warning('新主密码至少 8 位')
    return
  }
  if (newPassword.value !== newPassword2.value) {
    message.warning('两次输入的新主密码不一致')
    return
  }
  changing.value = true
  try {
    // 传入当前 refresh token：服务端借改密一次性令牌吊销旧 refresh 族并换发新族。
    const recovery = await auth.changePassword(
      newPassword.value,
      rotateRecovery.value,
      getRefreshToken() ?? undefined,
    )
    newPassword.value = ''
    newPassword2.value = ''
    if (recovery) {
      rotatedCode.value = recovery.formatted
    } else {
      message.success('主密码已修改，其他设备已全部退出登录')
    }
  } catch (e) {
    message.error(e instanceof ApiError ? `${e.message}（${e.code}）` : '修改失败，请重试')
  } finally {
    changing.value = false
  }
}

// ---- TOTP ----
const totpLoading = ref(false)
const totpEnabled = ref(false)
const setup = ref<TOTPSetup | null>(null)
const confirmCode = ref('') // 启用/禁用时输入的 6 位码
const actionLoading = ref(false)

async function loadTotp() {
  totpLoading.value = true
  try {
    const st = await api.totpStatus()
    totpEnabled.value = st.enabled
  } catch {
    // 状态拉取失败不阻塞页面
  } finally {
    totpLoading.value = false
  }
}

async function startSetup() {
  actionLoading.value = true
  try {
    // 服务端生成 TOTP secret（未确认态），返回 otpauth 二维码；确认前不影响登录。
    setup.value = await api.totpSetup()
    confirmCode.value = ''
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '初始化失败')
  } finally {
    actionLoading.value = false
  }
}

async function confirmEnable() {
  if (!/^\d{6}$/.test(confirmCode.value)) {
    message.warning('请输入认证器中的 6 位码确认')
    return
  }
  actionLoading.value = true
  try {
    await api.totpEnable(confirmCode.value)
    totpEnabled.value = true
    setup.value = null
    message.success('两步验证已启用，下次登录需要输入动态码')
  } catch (e) {
    message.error(e instanceof ApiError ? `确认码错误（${e.code}）` : '启用失败')
  } finally {
    actionLoading.value = false
  }
}

async function disableTotp() {
  if (!/^\d{6}$/.test(confirmCode.value)) {
    message.warning('请输入当前 6 位动态码以关闭两步验证')
    return
  }
  actionLoading.value = true
  try {
    await api.totpDisable(confirmCode.value)
    totpEnabled.value = false
    confirmCode.value = ''
    message.success('两步验证已关闭')
  } catch (e) {
    message.error(e instanceof ApiError ? `动态码错误（${e.code}）` : '关闭失败')
  } finally {
    actionLoading.value = false
  }
}

onMounted(loadTotp)
</script>

<template>
  <div class="page">
    <!-- 轮换新恢复码：强制备份遮罩 -->
    <div v-if="rotatedCode" class="overlay">
      <n-card class="overlay-card" :bordered="false">
        <RecoveryCodeCard :code="rotatedCode" rotated @confirm="rotatedCode = ''"
          @cancel="rotatedCode = ''" />
      </n-card>
    </div>

    <h2 class="page-title">安全设置</h2>

    <n-card title="修改主密码" class="section">
      <n-alert type="warning" :show-icon="false" class="hint">
        修改后其他已登录设备将立即退出，需要用新主密码重新登录；本机令牌自动换发。
      </n-alert>
      <n-form @keyup.enter="changePassword">
        <n-form-item label="新主密码（至少 8 位）">
          <n-input v-model:value="newPassword" type="password" show-password-on="click"
            autocomplete="new-password" />
        </n-form-item>
        <n-form-item label="再次输入新主密码">
          <n-input v-model:value="newPassword2" type="password" show-password-on="click"
            autocomplete="new-password" />
        </n-form-item>
        <n-checkbox v-model:checked="rotateRecovery" class="rotate">
          同时轮换恢复码（旧恢复码立即作废，需要重新备份）
        </n-checkbox>
        <n-space justify="end">
          <n-button type="primary" :loading="changing" @click="changePassword">修改主密码</n-button>
        </n-space>
      </n-form>
    </n-card>

    <n-card title="两步验证（TOTP）" class="section">
      <n-spin :show="totpLoading">
        <n-space align="center" class="totp-head">
          <span>状态：</span>
          <n-tag :type="totpEnabled ? 'success' : 'default'" size="small" round>
            {{ totpEnabled ? '已启用' : '未启用' }}
          </n-tag>
        </n-space>

        <!-- 未启用：setup 流程 -->
        <template v-if="!totpEnabled">
          <div v-if="!setup" class="totp-action">
            <n-button size="small" :loading="actionLoading" @click="startSetup">
              启用两步验证
            </n-button>
          </div>
          <div v-else class="setup">
            <n-alert type="info" :show-icon="false" class="hint">
              用 Google Authenticator / 1Password 等扫码，或手动录入密钥，然后输入 6 位码确认。
            </n-alert>
            <div class="qr-wrap">
              <img :src="setup.qr_data_uri" alt="TOTP 二维码" width="180" height="180" />
              <div class="secret">
                <div class="secret-label">手动录入密钥</div>
                <div class="secret-value">{{ setup.secret }}</div>
              </div>
            </div>
            <n-input v-model:value="confirmCode" placeholder="输入 6 位动态码" maxlength="6"
              class="code-input" @keyup.enter="confirmEnable" />
            <n-space justify="end">
              <n-button quaternary @click="setup = null">取消</n-button>
              <n-button type="primary" :loading="actionLoading" @click="confirmEnable">
                确认启用
              </n-button>
            </n-space>
          </div>
        </template>

        <!-- 已启用：输入当前动态码关闭 -->
        <template v-else>
          <n-alert type="error" :show-icon="false" class="hint">
            关闭后登录仅依赖主密码，账户安全性降低。
          </n-alert>
          <n-input v-model:value="confirmCode" placeholder="输入当前 6 位动态码" maxlength="6"
            class="code-input" @keyup.enter="disableTotp" />
          <n-space justify="end">
            <n-button type="error" ghost size="small" :loading="actionLoading" @click="disableTotp">
              关闭两步验证
            </n-button>
          </n-space>
        </template>
      </n-spin>
    </n-card>
  </div>
</template>

<style scoped>
.page {
  max-width: 720px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-title {
  margin: 0;
  font-size: 18px;
}
.section {
  border-radius: 12px;
}
.hint {
  margin-bottom: 12px;
  line-height: 1.7;
}
.rotate {
  margin-bottom: 12px;
}
.totp-head {
  margin-bottom: 10px;
}
.totp-action {
  margin-top: 6px;
}
.qr-wrap {
  display: flex;
  gap: 18px;
  align-items: center;
  margin: 10px 0 14px;
}
.secret-label {
  font-size: 12px;
  color: #8a8f9c;
  margin-bottom: 4px;
}
.secret-value {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 15px;
  word-break: break-all;
}
.code-input {
  margin-bottom: 12px;
  max-width: 240px;
  letter-spacing: 4px;
  text-align: center;
}
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(8, 10, 18, 0.82);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.overlay-card {
  width: 520px;
  max-width: 100%;
  border-radius: 14px;
}
</style>
