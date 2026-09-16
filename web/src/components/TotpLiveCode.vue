<script setup lang="ts">
// TOTP 详情组件：每秒刷新剩余秒数，计数器进位时重算当前 6/8 位码，
// 环形进度随 period 归零；点击复制，30 秒后自动清空剪贴板。
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { totpCode, decodeBase32 } from '../crypto/totp'
import type { TotpConfig } from '../types/vault'
import { useClipboard } from '../composables/useClipboard'
import { useMessage } from 'naive-ui'

const props = defineProps<{ config: TotpConfig }>()
const message = useMessage()
const { copySecret, countdown } = useClipboard()

const period = ref(props.config.period ?? 30)
const digits = ref(props.config.digits ?? 6)
let secretBytes: Uint8Array
try {
  secretBytes = decodeBase32(props.config.secret)
} catch {
  secretBytes = new Uint8Array(0)
}

const now = ref(Math.floor(Date.now() / 1000))
const code = ref('------')
let timer = 0

/** 仅在时间计数器进位时重新做一次 HMAC，避免每秒无谓计算。 */
async function recompute(atSeconds: number) {
  try {
    code.value = await totpCode(secretBytes, {
      digits: digits.value,
      period: period.value,
      at: new Date(atSeconds * 1000),
    })
  } catch {
    code.value = '-'.repeat(digits.value)
  }
}
watch(() => props.config, async () => {
  secretBytes = decodeBase32(props.config.secret)
  period.value = props.config.period ?? 30
  digits.value = props.config.digits ?? 6
  await recompute(now.value)
})

onMounted(() => {
  let lastCounter = -1
  void recompute(now.value)
  timer = window.setInterval(() => {
    now.value = Math.floor(Date.now() / 1000)
    const counter = Math.floor(now.value / period.value)
    if (counter !== lastCounter) {
      lastCounter = counter
      void recompute(now.value)
    }
  }, 1000)
})
onBeforeUnmount(() => window.clearInterval(timer))

// 周期内剩余秒数（用于环形与临近过期变色）。
function remainAt(t: number): number {
  return period.value - (t % period.value)
}
const urgent = ref(false)
watch(
  now,
  (t) => {
    urgent.value = remainAt(t) <= 5
  },
  { immediate: true },
)

// 环形进度参数。
const R = 16
const C = 2 * Math.PI * R
function dashAt(t: number): number {
  return C * (remainAt(t) / period.value)
}

async function copy() {
  if (await copySecret(code.value)) {
    message.success(`动态码已复制，${countdown.value} 秒后自动清空`)
  } else {
    message.error('当前浏览器不允许剪贴板写入')
  }
}
</script>

<template>
  <div class="totp">
    <button type="button" class="code" :class="{ urgent }" @click="copy"
      title="点击复制（30 秒后自动清空）">
      <span class="digits">{{ code.slice(0, Math.ceil(digits / 2)) }} {{ code.slice(Math.ceil(digits / 2)) }}</span>
    </button>
    <svg key="ring" width="40" height="40" viewBox="0 0 40 40" class="ring">
      <circle cx="20" cy="20" :r="R" class="track" />
      <circle
        cx="20"
        cy="20"
        :r="R"
        class="progress"
        :class="{ urgent }"
        :stroke-dasharray="`${dashAt(now)} ${C}`"
        transform="rotate(-90 20 20)"
      />
      <text x="20" y="24" text-anchor="middle" class="num">{{ remainAt(now) }}</text>
    </svg>
  </div>
</template>

<style scoped>
.totp {
  display: flex;
  align-items: center;
  gap: 12px;
}
.code {
  background: none;
  border: 1px solid #2e3650;
  border-radius: 8px;
  padding: 6px 12px;
  cursor: pointer;
}
.digits {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 22px;
  letter-spacing: 2px;
  color: #5eead4;
}
.code.urgent .digits {
  color: #f0a020;
}
.ring {
  flex: none;
}
.track {
  fill: none;
  stroke: #eef0f5;
  stroke-width: 3;
}
.progress {
  fill: none;
  stroke: #18a058;
  stroke-width: 3;
  stroke-linecap: round;
  transition: stroke-dasharray 0.9s linear;
}
.progress.urgent {
  stroke: #d03050;
}
.num {
  font-size: 11px;
  fill: #8a8f9c;
}
</style>
