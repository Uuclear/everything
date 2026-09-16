<script setup lang="ts">
// TOTP 二次验证面板：密码正确且账户启用了 TOTP 时出现。
// 错码保留 mfa 会话可重试（服务端同限流：连续失败 5 次锁定 15 分钟）。
import { ref } from 'vue'
import { NInput, NButton, NSpace, NAlert, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'
import type { LoginResponse } from '../api/client'

const emit = defineEmits<{ resolved: [result: LoginResponse] }>()

const auth = useAuthStore()
const message = useMessage()
const code = ref('')
const loading = ref(false)

async function verify() {
  if (!/^\d{6}$/.test(code.value)) {
    message.warning('请输入认证器显示的 6 位数字')
    return
  }
  loading.value = true
  try {
    const result = await auth.finishMfa(code.value)
    code.value = ''
    if (result.status === 'pending' || result.status === 'approved') {
      // 必须先 emit 再清理 mfa 状态：本面板由 v-else-if="mfaToken" 控制，
      // 若先清空，await 恢复时 Vue 先 flush 卸载面板，而 Vue 对已卸载组件的
      // emit 静默丢弃，父组件就收不到 approved 事件（无法进入资料库）。
      emit('resolved', result)
      // emit 同步完成（父组件已发起路由跳转/切换到审批面板），现在销毁临时会话。
      auth.consumeMfa()
    }
  } catch (e) {
    if (e instanceof ApiError) {
      message.error(e.status === 429 ? '验证失败次数过多，请 15 分钟后再试' : `验证码错误（${e.code}）`)
    } else {
      message.error('网络异常，请重试')
    }
  } finally {
    loading.value = false
  }
}

/** 放弃验证：清空 mfa/密码内存状态，回到账号密码表单。 */
function cancel() {
  auth.rejectMfa()
}
</script>

<template>
  <div>
    <h2 class="mfa-title">两步验证</h2>
    <n-alert type="info" :show-icon="false" class="hint">
      请打开你的身份验证器（Google Authenticator 等），输入当前 6 位验证码。
    </n-alert>
    <n-input
      v-model:value="code"
      class="code-input"
      placeholder="000000"
      maxlength="6"
      inputmode="numeric"
      :disabled="loading"
      @keyup.enter="verify"
    />
    <n-space justify="space-between" class="row">
      <n-button quaternary size="small" :disabled="loading" @click="cancel">返回</n-button>
      <n-button type="primary" :loading="loading" @click="verify">验证</n-button>
    </n-space>
  </div>
</template>

<style scoped>
.mfa-title {
  margin: 0 0 12px;
  font-size: 19px;
}
.hint {
  margin-bottom: 14px;
  line-height: 1.7;
}
.code-input {
  text-align: center;
  font-size: 26px;
  letter-spacing: 10px;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
.row {
  margin-top: 14px;
}
</style>
