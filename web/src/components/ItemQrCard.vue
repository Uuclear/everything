<!-- 物品二维码卡片：payload 仅含物品 UUID -->
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { NButton, NCard, NSpace, NSpin } from 'naive-ui'
import { qrPayloadForItem, renderQrPng, renderQrSvg } from '../items/qrcode'

const props = defineProps<{
  itemId: string
}>()

const pngUrl = ref('')
const svgMarkup = ref('')
const loading = ref(true)

async function render() {
  loading.value = true
  try {
    const payload = qrPayloadForItem(props.itemId)
    pngUrl.value = await renderQrPng(payload, 256)
    svgMarkup.value = await renderQrSvg(payload)
  } finally {
    loading.value = false
  }
}

onMounted(() => void render())
watch(() => props.itemId, () => void render())

function downloadPng() {
  const a = document.createElement('a')
  a.href = pngUrl.value
  a.download = `item-${props.itemId.slice(0, 8)}.png`
  a.click()
}

function downloadSvg() {
  const blob = new Blob([svgMarkup.value], { type: 'image/svg+xml' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `item-${props.itemId.slice(0, 8)}.svg`
  a.click()
  URL.revokeObjectURL(url)
}

function printQr() {
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(`<img src="${pngUrl.value}" alt="qr" />`)
  w.document.close()
  w.print()
}
</script>

<template>
  <NCard title="物品二维码" size="small" data-testid="qr_card">
    <NSpin v-if="loading" />
    <template v-else>
      <div class="qr-wrap">
        <img v-if="pngUrl" :src="pngUrl" alt="物品二维码" width="256" height="256" />
      </div>
      <NSpace style="margin-top: 12px">
        <NButton data-testid="btn_download_png" @click="downloadPng">下载 PNG</NButton>
        <NButton data-testid="btn_download_svg" @click="downloadSvg">下载 SVG</NButton>
        <NButton data-testid="btn_print" @click="printQr">打印</NButton>
      </NSpace>
    </template>
  </NCard>
</template>

<style scoped>
.qr-wrap {
  display: flex;
  justify-content: center;
}
</style>
