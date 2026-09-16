<script setup lang="ts">
// 新设备等待审批面板：展示 6 位配对码与设备指纹，3 秒轮询状态；
// 已批准设备审批后，MK 经 X25519 crypto_box 端到端密封下发，本端开箱解锁。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { NAlert, NButton, NSpace, NTag, useMessage } from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '../api/client'

const emit = defineEmits<{ unlocked: []; 'use-recovery': [] }>()

const auth = useAuthStore()
const message = useMessage()

// 剩余秒数（每秒刷新）；为 0 表示审批窗口已过期。
// 注意服务端 pairing.expires_at 是毫秒级 Unix 时间戳（见 pairing.go UnixMilli 判定）。
const now = ref(Date.now())
const remainSeconds = computed(() =>
  Math.max(0, Math.floor(((auth.pendingPairing?.expires_at ?? now.value) - now.value) / 1000)),
)
const countdown = computed(() => {
  const m = Math.floor(remainSeconds.value / 60)
  const s = remainSeconds.value % 60
  return `${m}:${s.toString().padStart(2, '0')}`
})

let tickTimer = 0
let pollTimer = 0
let stopped = false

async function poll() {
  if (stopped || !auth.pendingAccess) return
  try {
    const st = await auth.finishPairing()
    if (st.state === 'approved') {
      stop()
      message.success('设备已获批准，资料库已解锁')
      emit('unlocked')
      return
    }
    if (st.state === 'rejected' || st.state === 'expired') {
      stop()
      message.error(st.state === 'rejected' ? '该设备的登录请求已被拒绝' : '审批请求已过期，请重新登录')
      auth.rejectPairingState()
      return
    }
  } catch (e) {
    // pending 令牌过期（15 分钟）后接口 401：结束等待并提示。
    if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
      stop()
      message.error('审批等待已过期，请重新登录或改用恢复码')
      auth.rejectPairingState()
    }
    // 网络错误等：保留状态，下一轮继续。
  }
  pollTimer = window.setTimeout(poll, 3000)
}

function stop() {
  stopped = true
  window.clearTimeout(tickTimer)
  window.clearTimeout(pollTimer)
}

/** 放弃等待：回到账号密码表单。 */
function cancel() {
  stop()
  auth.rejectPairingState()
}

/** 没有已批准设备可用时：改用恢复码完成账户接管。 */
function useRecovery() {
  stop()
  auth.rejectPairingState()
  emit('use-recovery')
}

onMounted(() => {
  tickTimer = window.setInterval(() => (now.value = Date.now()), 1000)
  pollTimer = window.setTimeout(poll, 3000)
})
onBeforeUnmount(stop)
</script>

<template>
  <div v-if="auth.pendingPairing" class="pending">
    <h2 class="title">等待已批准设备授权</h2>
    <n-alert type="info" :show-icon="false" class="hint">
      这是一台新设备。请在你<b>已登录的设备</b>上确认本次登录，并核对设备指纹一致。
    </n-alert>

    <div class="pairing-box">
      <div class="pairing-label">配对码（在已登录设备上输入）</div>
      <div class="pairing-code">{{ auth.pendingPairing.pairing_code }}</div>
      <div class="pairing-meta">
        设备名：{{ auth.pendingPairing.device_name }}
      </div>
      <div class="pairing-meta mono">
        指纹：{{ auth.pendingPairing.fingerprint }}
      </div>
      <n-tag :type="remainSeconds > 0 ? 'warning' : 'error'" size="small" :bordered="false">
        {{ remainSeconds > 0 ? `剩余 ${countdown}` : '已过期' }}
      </n-tag>
    </div>

    <div class="dots">
      <span /><span /><span />
      正在等待批准…
    </div>

    <n-space justify="space-between" class="row">
      <n-button quaternary size="small" :disabled="false" @click="cancel">取消</n-button>
      <n-button tertiary size="small" type="warning" @click="useRecovery">
        无法访问旧设备？改用恢复码
      </n-button>
    </n-space>
  </div>
</template>

<style scoped>
.title {
  margin: 0 0 12px;
  font-size: 19px;
}
.hint {
  margin-bottom: 14px;
  line-height: 1.7;
}
.pairing-box {
  border: 1px solid #2e3650;
  border-radius: 10px;
  padding: 16px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: #141926;
}
.pairing-label {
  font-size: 12px;
  color: #8a8f9c;
}
.pairing-code {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 40px;
  font-weight: 700;
  letter-spacing: 12px;
  color: #5eead4;
  padding-left: 12px; /* 抵消字距造成的视觉偏移 */
}
.pairing-meta {
  font-size: 12px;
  color: #aab0c0;
  word-break: break-all;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
}
.dots {
  text-align: center;
  color: #8a8f9c;
  font-size: 13px;
  margin: 14px 0 4px;
}
.row {
  margin-top: 10px;
}
</style>
