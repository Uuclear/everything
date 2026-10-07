<!-- 扫码：BarcodeDetector + 手动输入兜底 -->
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { NAlert, NButton, NInput, NModal, NSpace } from 'naive-ui'

const props = defineProps<{
  show: boolean
}>()
const emit = defineEmits<{
  'update:show': [v: boolean]
  scanned: [payload: string]
}>()

const manualId = ref('')
const cameraError = ref(false)
const videoRef = ref<HTMLVideoElement | null>(null)
let stream: MediaStream | null = null
let detectTimer = 0

async function startCamera() {
  cameraError.value = false
  if (!navigator.mediaDevices?.getUserMedia) {
    cameraError.value = true
    return
  }
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
    if (videoRef.value) {
      videoRef.value.srcObject = stream
      await videoRef.value.play()
    }
    if ('BarcodeDetector' in window) {
      const detector = new (window as unknown as { BarcodeDetector: new (o: { formats: string[] }) => { detect: (v: HTMLVideoElement) => Promise<Array<{ rawValue: string }>> } }).BarcodeDetector({
        formats: ['qr_code'],
      })
      detectTimer = window.setInterval(async () => {
        if (!videoRef.value) return
        try {
          const codes = await detector.detect(videoRef.value)
          const hit = codes.find((c) => c.rawValue)
          if (hit?.rawValue) {
            stopCamera()
            emit('scanned', hit.rawValue.trim())
            emit('update:show', false)
          }
        } catch {
          /* 单帧失败忽略 */
        }
      }, 500)
    } else {
      cameraError.value = true
    }
  } catch {
    cameraError.value = true
  }
}

function stopCamera() {
  if (detectTimer) {
    clearInterval(detectTimer)
    detectTimer = 0
  }
  stream?.getTracks().forEach((t) => t.stop())
  stream = null
}

function onManualSearch() {
  const id = manualId.value.trim()
  if (!id) return
  emit('scanned', id)
  emit('update:show', false)
}

onMounted(() => {
  if (props.show) void startCamera()
})

onBeforeUnmount(() => stopCamera())

function onShowChange(open: boolean) {
  emit('update:show', open)
  if (!open) stopCamera()
  else void startCamera()
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    title="扫描物品二维码"
    style="width: 520px"
    @update:show="onShowChange"
  >
    <NAlert v-if="cameraError" type="warning" title="摄像头不可用" style="margin-bottom: 12px">
      请使用下方手动输入物品 ID，或在浏览器设置中允许摄像头权限。
    </NAlert>
    <video
      v-show="!cameraError"
      ref="videoRef"
      class="scan-video"
      playsinline
      muted
    />
    <NSpace vertical style="margin-top: 12px">
      <NInput
        v-model:value="manualId"
        placeholder="手动输入物品 ID"
        data-testid="scanner_manual_input"
      />
      <NButton type="primary" data-testid="btn_search" @click="onManualSearch">查找</NButton>
    </NSpace>
  </NModal>
</template>

<style scoped>
.scan-video {
  width: 100%;
  max-height: 280px;
  background: #111;
  border-radius: 8px;
}
</style>
