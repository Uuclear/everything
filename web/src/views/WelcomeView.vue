<script setup lang="ts">
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
  useMessage,
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const tab = ref<'login' | 'register'>('register')
const username = ref(auth.username)
const password = ref('')
const loading = ref(false)

async function submit() {
  if (!username.value || !password.value) {
    message.warning('请输入用户名和主密码')
    return
  }
  loading.value = true
  try {
    if (tab.value === 'register') {
      await auth.register(username.value.trim(), password.value)
      message.success('账户已创建，主密钥已在本地生成并包裹上传')
    } else {
      await auth.login(username.value.trim(), password.value)
      message.success('登录成功，资料库已解锁')
    }
    router.push({ name: 'vault' })
  } catch (e) {
    if (e instanceof ApiError) {
      message.error(`${e.message}（${e.code}）`)
    } else {
      message.error('操作失败，请检查服务端地址与网络')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="welcome">
    <n-card class="welcome-card" title="Everything" :bordered="false">
      <p class="subtitle">你的人生操作系统 · 零知识加密</p>
      <n-tabs v-model:value="tab" animated>
        <n-tab-pane name="register" tab="创建账户">
          <n-alert type="info" :show-icon="false" class="hint">
            主密码是唯一解锁方式，遗忘后数据无法恢复。请使用强密码并妥善记忆。
          </n-alert>
        </n-tab-pane>
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
        <n-space>
          <n-button type="primary" :loading="loading" @click="submit">
            {{ tab === 'register' ? '创建并进入' : '登录并解锁' }}
          </n-button>
        </n-space>
      </n-form>
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
  width: 420px;
  max-width: 100%;
  border-radius: 14px;
}
.subtitle {
  margin: -8px 0 12px;
  color: #8a8f9c;
  font-size: 13px;
}
.hint {
  margin-bottom: 12px;
}
</style>
