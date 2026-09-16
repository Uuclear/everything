<script setup lang="ts">
// ============================================================================
// 财务附件查看弹层（stage5-finance-v2 / Task 3 / TR-3.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 3 / TR-3.4（Web 附件预览 UI）
// 路径: web/src/views/finance/AttachmentViewer.vue
// 作用: 在 NModal 内渲染附件二进制 —— PDF 走 pdfjs-dist 动态 import，
//       图片走 <img>，其它类型 NEmpty 兜底；blob URL 用完即 revoke。
//
// 设计要点：
//   1. **不依赖外部 channel 注入** —— 由父组件传入 attachmentId + mime，
//      内部直接走 stores/finance（与编辑器同款路径），不持有 sodium；
//   2. **pdfjs-dist 动态 import** —— 避免常驻 bundle；
//   3. **blob URL 生命周期管理** —— onUnmounted revoke；
//   4. **零知识纪律** —— 错误消息只给通用原因，不渲染文件大小 / sha256 / 文件名。
//
// 关联：
//   - tasks.md TR-3.4
//   - web/src/finance/attachment.ts（downloadFile）
//   - web/src/stores/finance.ts（attachments state + pullAll 接入）
// ============================================================================

import { computed, onUnmounted, ref, watch } from 'vue'
import { NButton, NCard, NEmpty, NModal, NSpace } from 'naive-ui'
import { useFinanceStore } from '../../stores/finance'
import { downloadFile } from '../../finance/attachment'

// ========== Props / Emits ==========

const props = defineProps<{
  /** 弹层显隐（v-model:show）。 */
  show: boolean
  /** 附件 UUID（来自 AttachmentRef.id）。 */
  attachmentId: string | null
  /** MIME（用于路由 PDF / image / 兜底）；可由父组件传入。 */
  mime: string | null
}>()

const emit = defineEmits<{
  /** 关闭弹层（v-model:show 同款）。 */
  (e: 'update:show', value: boolean): void
}>()

const store = useFinanceStore()

// ========== 内部状态 ==========

/** 当前下载得到的 Blob URL（用于 <img src=...> / pdfjs.getDocument）。 */
const blobUrl = ref<string | null>(null)
/** 加载中文案（避免空白期闪烁）；非空时 NEmpty 不显示。 */
const loading = ref(false)
/** 错误文案（人类可读）。 */
const errorText = ref<string | null>(null)
/** PDF 渲染 canvas 模板引用。 */
const pdfCanvas = ref<HTMLCanvasElement | null>(null)

// ========== 计算属性 ==========

/** PDF 类型判定（MIME 前缀 + 兜底 application/pdf）。 */
const isPdf = computed(
  () => props.mime === 'application/pdf' || props.mime?.startsWith('application/pdf') === true,
)
/** 图片类型判定。 */
const isImage = computed(() => props.mime?.startsWith('image/') === true)

// ========== 工具 ==========

/**
 * 关闭弹层（统一走 emit，便于父组件用 v-model:show）。
 */
function closeViewer(): void {
  emit('update:show', false)
}

/**
 * 清理 blob URL（避免内存泄漏）。
 */
function clearBlobUrl(): void {
  if (blobUrl.value) {
    try {
      URL.revokeObjectURL(blobUrl.value)
    } catch {
      // 静默降级：URL.revokeObjectURL 偶发 InvalidStateError（已被回收）。
    }
    blobUrl.value = null
  }
  errorText.value = null
}

/**
 * 触发 PDF.js 渲染（仅 isPdf 路径调用）。
 *
 * 实现要点：
 *   - 动态 import pdfjs-dist 避免常驻 bundle；
 *   - workerSrc 指向 npm 包内的 worker（Vite 默认能解析）；
 *   - 仅渲染第 1 页（v1 简化版；多页走 v2 评估）。
 */
async function renderPdf(): Promise<void> {
  if (!blobUrl.value) return
  if (!pdfCanvas.value) return
  loading.value = true
  try {
    // 动态 import —— 仅 PDF 路径加载，常驻 bundle 不含 pdfjs。
    const pdfjs = await import('pdfjs-dist')
    // worker 配置：Vite 通过 new URL(...import.meta.url) 自动打包 worker 资源。
    pdfjs.GlobalWorkerOptions.workerSrc = new URL(
      'pdfjs-dist/build/pdf.worker.min.mjs',
      import.meta.url,
    ).href
    const task = pdfjs.getDocument(blobUrl.value)
    const pdf = await task.promise
    const page = await pdf.getPage(1)
    const viewport = page.getViewport({ scale: 1.5 })
    const canvas = pdfCanvas.value
    canvas.width = viewport.width
    canvas.height = viewport.height
    const ctx = canvas.getContext('2d')
    if (!ctx) {
      errorText.value = 'PDF 渲染上下文不可用'
      return
    }
    await page.render({ canvasContext: ctx, viewport }).promise
  } catch {
    errorText.value = 'PDF 解析失败'
  } finally {
    loading.value = false
  }
}

/**
 * 拉附件 → 解密 → 构造 Blob URL → 按需触发 PDF 渲染。
 */
async function loadAttachment(): Promise<void> {
  if (!props.attachmentId || !props.mime) return
  loading.value = true
  errorText.value = null
  clearBlobUrl()
  try {
    // 通过 store 的 channel 注入（默认走 envelope.sealRecord/openRecord）。
    // store 当前没有暴露 channel —— 故直接用 envelope（与 attachment.ts defaultAttachmentChannel 同款）。
    const result = await downloadAttachmentFromStore(props.attachmentId)
    if (!result.ok) {
      errorText.value = result.error
      return
    }
    blobUrl.value = URL.createObjectURL(result.value)
    if (isPdf.value) {
      // 等下一个 tick 让 pdfCanvas 渲染到 DOM。
      await Promise.resolve()
      await renderPdf()
    }
  } catch {
    errorText.value = '加载附件失败'
  } finally {
    loading.value = false
  }
}

/**
 * 从 store-attachmentChannel 拉附件并解密 —— 内部走 envelope（同 defaultAttachmentChannel）。
 *
 * 此处复刻 attachment.ts downloadFile 行为但绕开 store 的 channel 注入层（store-finance.ts
 * 没有 attachment 子类型 channel）。生产路径由 store 维护 channel；这里走直接 envelope 链路。
 */
async function downloadAttachmentFromStore(
  attachmentId: string,
): Promise<{ ok: true; value: Blob } | { ok: false; error: string }> {
  // 取 store 中已缓存的 AttachmentRef（元数据）+ 由父组件注入 channel。
  // 此处走 store.getAttachmentMeta 暴露的元数据接口。
  const meta = store.getAttachmentMeta(attachmentId)
  if (!meta) {
    return { ok: false, error: '附件不存在' }
  }
  // 调 attachment.ts 的 downloadFile，channel 由 store 暴露（见 store-finance.ts 扩展）。
  const ch = store.getAttachmentChannel()
  if (!ch) {
    return { ok: false, error: '附件通道未初始化' }
  }
  return downloadFile(attachmentId, ch, meta.sha256)
}

// ========== watch ==========

/**
 * 监听 show + attachmentId —— 打开时拉附件，关闭时清理。
 *
 * 注意：watch 的回调可能是异步；Vue 3 watch(async) 行为是 fire-and-forget，
 * 错误由内部 try/catch 兜住（不冒泡到 vue warning）。
 */
watch(
  () => [props.show, props.attachmentId] as const,
  async ([show, id]) => {
    if (show && id) {
      await loadAttachment()
    } else {
      clearBlobUrl()
    }
  },
)

// ========== 生命周期 ==========

/** 组件销毁时回收 blob URL。 */
onUnmounted(() => {
  clearBlobUrl()
})
</script>

<template>
  <n-modal
    :show="show"
    @update:show="(v: boolean) => emit('update:show', v)"
    style="width: 80%; max-width: 900px;"
    preset="card"
    role="dialog"
    aria-modal="true"
    :bordered="false"
    title="附件预览"
    :on-close="closeViewer"
  >
    <n-card :bordered="false" content-style="padding: 12px;">
      <!-- PDF 渲染 canvas -->
      <canvas
        v-if="isPdf"
        ref="pdfCanvas"
        class="attachment-canvas"
        data-testid="attachment-pdf-canvas"
      />
      <!-- 图片渲染 -->
      <img
        v-else-if="isImage && blobUrl"
        :src="blobUrl"
        alt="attachment"
        class="attachment-image"
        data-testid="attachment-image"
      />
      <!-- 兜底：空状态 + 错误提示 -->
      <div v-else class="attachment-fallback" data-testid="attachment-fallback">
        <n-empty
          v-if="!loading"
          :description="errorText ?? '不支持的文件类型'"
        />
        <div v-else class="attachment-loading" data-testid="attachment-loading">
          加载中...
        </div>
      </div>
      <!-- 底部按钮 -->
      <n-space justify="end" style="margin-top: 12px;">
        <n-button @click="closeViewer" data-testid="attachment-close-btn">关闭</n-button>
      </n-space>
    </n-card>
  </n-modal>
</template>

<style scoped>
.attachment-canvas {
  display: block;
  max-width: 100%;
  margin: 0 auto;
  border: 1px solid #e5e7eb;
  background: #fff;
}
.attachment-image {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  margin: 0 auto;
  border: 1px solid #e5e7eb;
  background: #fff;
}
.attachment-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}
.attachment-loading {
  color: #6b7280;
  font-size: 14px;
}
</style>