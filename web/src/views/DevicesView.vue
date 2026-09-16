<script setup lang="ts">
// 设备管理：
// 1) 待审批配对（新设备登录确认）——核对设备名/指纹，批准时用对方 X25519 公钥
//    crypto_box 密封 MK 端到端下发，服务端永远接触不到 MK 明文；
// 2) 已批准设备列表与吊销（不能吊销自己）；
// 3) SSE 事件实时插入/移除配对卡片。
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  NCard,
  NButton,
  NSpace,
  NTag,
  NList,
  NListItem,
  NThing,
  NEmpty,
  NSpin,
  NAlert,
  NBadge,
  useMessage,
  useDialog,
} from 'naive-ui'
import { useAuthStore } from '../stores/auth'
import { api, ApiError, type DeviceInfo, type PairingInfo } from '../api/client'
import { eventsStatus, getSharedChannel } from '../stores/events'

const message = useMessage()
const dialog = useDialog()
const auth = useAuthStore()

const devices = ref<DeviceInfo[]>([])
const pairings = ref<PairingInfo[]>([])
const loading = ref(false)
const busyId = ref('') // 审批/吊销进行中的条目 id（防重复点击）

// 倒计时每秒重算。服务端 pairing 时间戳（created_at/expires_at）均为毫秒。
const now = ref(Date.now())
let tick = 0

const pendingPairings = computed(() =>
  pairings.value
    .filter((p) => p.state === 'pending' && p.expires_at > now.value)
    .sort((a, b) => a.created_at - b.created_at),
)

function remain(p: PairingInfo): string {
  const s = Math.max(0, Math.floor((p.expires_at - now.value) / 1000))
  return `${Math.floor(s / 60)}:${(s % 60).toString().padStart(2, '0')}`
}

async function refresh() {
  loading.value = true
  try {
    const [d, p] = await Promise.all([api.listDevices(), api.listPairings()])
    devices.value = d.devices
    pairings.value = p.pairings
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '加载设备列表失败')
  } finally {
    loading.value = false
  }
}

/** 批准：store 内部用审批端一次性临时密钥对 MK 做 crypto_box 密封。 */
async function approve(p: PairingInfo) {
  busyId.value = p.id
  try {
    await auth.approvePairing(p.id, p.device_public_key)
    message.success(`已批准设备：${p.device_name}`)
    await refresh()
  } catch (e) {
    message.error(e instanceof ApiError ? `${e.message}（${e.code}）` : '批准失败')
  } finally {
    busyId.value = ''
  }
}

async function reject(p: PairingInfo) {
  busyId.value = p.id
  try {
    await api.rejectPairing(p.id)
    message.info('已拒绝该设备的登录请求')
    await refresh()
  } catch (e) {
    message.error(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    busyId.value = ''
  }
}

function confirmRevoke(d: DeviceInfo) {
  if (d.current) return // 服务端同样禁止自吊销，UI 提前拦截
  dialog.warning({
    title: '吊销设备',
    content: `确定吊销「${d.name}」吗？该设备将立即退出登录，再次使用需要重新审批。`,
    positiveText: '吊销',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.revokeDevice(d.id)
        message.success('设备已吊销')
        await refresh()
      } catch (e) {
        message.error(e instanceof ApiError ? e.message : '吊销失败')
      }
    },
  })
}

function formatTime(ts: number): string {
  return ts ? new Date(ts).toLocaleString() : '—'
}

let offBus: (() => void) | null = null
onMounted(() => {
  void refresh()
  tick = window.setInterval(() => (now.value = Date.now()), 1000)
  // 配对请求/处理结果/设备列表变化均实时刷新（30s 兜底轮询在主布局通道内）。
  const channel = getSharedChannel()
  offBus = channel.bus.on((e) => {
    if (
      e.type === 'device_pairing_requested' ||
      e.type === 'device_pairing_resolved' ||
      e.type === 'device_list_changed'
    ) {
      void refresh()
    }
  })
})
onBeforeUnmount(() => {
  window.clearInterval(tick)
  offBus?.()
})
</script>

<template>
  <div class="page">
    <div class="head">
      <h2>设备与审批</h2>
      <n-tag size="small" :type="eventsStatus.connected ? 'success' : 'warning'" round>
        {{ eventsStatus.connected ? '实时连接' : '重连中/轮询' }}
      </n-tag>
    </div>

    <n-spin :show="loading">
      <!-- 待审批 -->
      <n-card class="section">
        <template #header>
          <n-space align="center">
            <span>待审批设备</span>
            <n-badge v-if="pendingPairings.length" :value="pendingPairings.length" :max="9" />
          </n-space>
        </template>
        <n-empty v-if="pendingPairings.length === 0" description="暂无待确认的登录请求" />
        <n-list v-else hoverable clickable bordered>
          <n-list-item v-for="p in pendingPairings" :key="p.id">
            <n-thing>
              <template #header>
                <n-space align="center">
                  <strong>{{ p.device_name || '未知设备' }}</strong>
                  <n-tag size="tiny" type="warning">配对码 {{ p.pairing_code }}</n-tag>
                  <n-tag size="tiny" :type="p.expires_at - now < 5 * 60 * 1000 ? 'error' : 'default'">
                    剩余 {{ remain(p) }}
                  </n-tag>
                </n-space>
              </template>
              <template #description>
                <div class="meta">请求时间：{{ formatTime(p.created_at) }}</div>
                <div class="meta mono">设备指纹：{{ p.fingerprint }}</div>
              </template>
              <n-space class="actions">
                <n-button type="primary" size="small" :loading="busyId === p.id" @click="approve(p)">
                  批准并下发密钥
                </n-button>
                <n-button size="small" :disabled="busyId === p.id" @click="reject(p)">
                  拒绝
                </n-button>
              </n-space>
            </n-thing>
          </n-list-item>
        </n-list>
        <n-alert type="info" :show-icon="false" class="hint">
          批准前请与申请人当面/电话核对设备名与指纹；批准后主密钥经端到端加密直接送达该设备。
        </n-alert>
      </n-card>

      <!-- 已批准设备 -->
      <n-card title="已登录设备" class="section">
        <n-list hoverable bordered>
          <n-list-item v-for="d in devices" :key="d.id">
            <n-thing>
              <template #header>
                <n-space align="center">
                  <strong>{{ d.name || '未知设备' }}</strong>
                  <n-tag v-if="d.current" size="tiny" type="success">本机</n-tag>
                  <n-tag v-else-if="d.state !== 'approved'" size="tiny" type="error">
                    {{ d.state }}
                  </n-tag>
                </n-space>
              </template>
              <template #description>
                <div class="meta">最近活跃：{{ formatTime(d.last_seen) }}</div>
                <div class="meta mono">指纹：{{ d.fingerprint }}</div>
              </template>
              <n-space v-if="!d.current && d.state === 'approved'" class="actions">
                <n-button size="small" type="error" ghost @click="confirmRevoke(d)">吊销</n-button>
              </n-space>
            </n-thing>
          </n-list-item>
        </n-list>
      </n-card>
    </n-spin>
  </div>
</template>

<style scoped>
.page {
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head h2 {
  margin: 0;
  font-size: 18px;
}
.section {
  border-radius: 12px;
}
.meta {
  font-size: 12px;
  color: #8a8f9c;
}
.mono {
  font-family: 'JetBrains Mono', Consolas, monospace;
  word-break: break-all;
}
.actions {
  margin-top: 8px;
}
.hint {
  margin-top: 12px;
  line-height: 1.7;
}
</style>
