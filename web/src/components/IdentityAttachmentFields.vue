<script setup lang="ts">
// 证件影像：选图/拍照、缩略图预览、删除；仅使用内存 blob URL，不落 localStorage。
import { onUnmounted, ref, watch, withDefaults } from 'vue'
import { NButton, NSpace, useMessage } from 'naive-ui'
import { useVaultStore } from '../stores/vault'
import { downloadFile } from '../vault/attachment'
import type { IdentityKind } from '../types/vault'
import AttachmentViewer from '../views/finance/AttachmentViewer.vue'

const props = withDefaults(
  defineProps<{
    recordId: string
    identityKind: IdentityKind
    frontId: string | null
    backId: string | null
    scanIds: string[]
    /** 只读：仅预览缩略图，不显示上传/删除。 */
    readonly?: boolean
  }>(),
  { readonly: false },
)

const emit = defineEmits<{
  'update:frontId': [v: string | null]
  'update:backId': [v: string | null]
  'update:scanIds': [v: string[]]
}>()

const vault = useVaultStore()
const message = useMessage()
const parentModule = 'identity'

const thumbUrls = ref<Record<string, string>>({})
const uploading = ref(false)

const previewId = ref<string | null>(null)
const previewMime = ref<string | null>(null)
const showViewer = ref(false)

function revokeThumbs() {
  for (const url of Object.values(thumbUrls.value)) {
    try {
      URL.revokeObjectURL(url)
    } catch {
      /* ignore */
    }
  }
  thumbUrls.value = {}
}

async function refreshThumb(attachmentId: string) {
  const meta = vault.getAttachmentMeta(attachmentId)
  if (!meta) return
  const ch = vault.getAttachmentChannel(parentModule)
  const down = await downloadFile(attachmentId, ch, meta.sha256)
  if (!down.ok) return
  if (thumbUrls.value[attachmentId]) {
    URL.revokeObjectURL(thumbUrls.value[attachmentId])
  }
  thumbUrls.value[attachmentId] = URL.createObjectURL(down.value)
}

async function refreshAllThumbs() {
  revokeThumbs()
  const ids = [
    props.frontId,
    props.backId,
    ...props.scanIds,
  ].filter((x): x is string => Boolean(x))
  for (const id of ids) {
    await refreshThumb(id)
  }
}

watch(
  () => [props.frontId, props.backId, props.scanIds.join(',')] as const,
  () => {
    void refreshAllThumbs()
  },
  { immediate: true },
)

onUnmounted(revokeThumbs)

async function onPickFile(
  event: Event,
  slot: 'front' | 'back' | 'scan',
): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !props.recordId) return
  uploading.value = true
  try {
    const result = await vault.uploadVaultAttachment(parentModule, props.recordId, file)
    if (!result.ok) {
      message.error(result.error)
      return
    }
    const id = result.value.id
    if (slot === 'front') emit('update:frontId', id)
    else if (slot === 'back') emit('update:backId', id)
    else emit('update:scanIds', [...props.scanIds, id])
    await refreshThumb(id)
    message.success('已上传影像')
  } catch {
    message.error('上传失败')
  } finally {
    uploading.value = false
  }
}

async function removeSlot(slot: 'front' | 'back', id: string | null) {
  if (!id) return
  const result = await vault.deleteVaultAttachment(parentModule, props.recordId, id)
  if (!result.ok) {
    message.error(result.error)
    return
  }
  if (thumbUrls.value[id]) {
    URL.revokeObjectURL(thumbUrls.value[id])
    delete thumbUrls.value[id]
  }
  if (slot === 'front') emit('update:frontId', null)
  else emit('update:backId', null)
}

async function removeScan(id: string) {
  const result = await vault.deleteVaultAttachment(parentModule, props.recordId, id)
  if (!result.ok) {
    message.error(result.error)
    return
  }
  if (thumbUrls.value[id]) {
    URL.revokeObjectURL(thumbUrls.value[id])
    delete thumbUrls.value[id]
  }
  emit(
    'update:scanIds',
    props.scanIds.filter((x) => x !== id),
  )
}

function openPreview(id: string) {
  const meta = vault.getAttachmentMeta(id)
  previewId.value = id
  previewMime.value = meta?.mime ?? 'image/jpeg'
  showViewer.value = true
}
</script>

<template>
  <div class="id-attachments">
    <template v-if="identityKind === 'id_card'">
      <div class="slot">
        <span class="label">正面</span>
        <div v-if="frontId && thumbUrls[frontId]" class="thumb-wrap">
          <img
            :src="thumbUrls[frontId]"
            alt="证件正面"
            class="thumb"
            @click="openPreview(frontId!)"
          />
          <n-space v-if="!readonly" size="small">
            <n-button size="tiny" :disabled="uploading" @click="removeSlot('front', frontId)">
              删除
            </n-button>
          </n-space>
        </div>
        <label v-else-if="!readonly" class="pick">
          <input
            type="file"
            accept="image/*"
            capture="environment"
            :disabled="uploading"
            @change="(e) => onPickFile(e, 'front')"
          />
          拍照 / 选图
        </label>
      </div>
      <div class="slot">
        <span class="label">反面</span>
        <div v-if="backId && thumbUrls[backId]" class="thumb-wrap">
          <img
            :src="thumbUrls[backId]"
            alt="证件反面"
            class="thumb"
            @click="openPreview(backId!)"
          />
          <n-space v-if="!readonly" size="small">
            <n-button size="tiny" :disabled="uploading" @click="removeSlot('back', backId)">
              删除
            </n-button>
          </n-space>
        </div>
        <label v-else-if="!readonly" class="pick">
          <input
            type="file"
            accept="image/*"
            capture="environment"
            :disabled="uploading"
            @change="(e) => onPickFile(e, 'back')"
          />
          拍照 / 选图
        </label>
      </div>
    </template>
    <template v-else>
      <div v-for="id in scanIds" :key="id" class="slot">
        <div v-if="thumbUrls[id]" class="thumb-wrap">
          <img :src="thumbUrls[id]" alt="扫描页" class="thumb" @click="openPreview(id)" />
          <n-button v-if="!readonly" size="tiny" :disabled="uploading" @click="removeScan(id)">
            删除
          </n-button>
        </div>
      </div>
      <label v-if="!readonly" class="pick">
        <input
          type="file"
          accept="image/*"
          capture="environment"
          :disabled="uploading"
          @change="(e) => onPickFile(e, 'scan')"
        />
        添加扫描页
      </label>
    </template>
    <AttachmentViewer
      v-model:show="showViewer"
      source="vault"
      :attachment-id="previewId"
      :mime="previewMime"
    />
  </div>
</template>

<style scoped>
.id-attachments {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 8px;
}
.slot {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.label {
  font-size: 13px;
  color: #4b5563;
}
.thumb-wrap {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}
.thumb {
  max-width: 160px;
  max-height: 100px;
  object-fit: contain;
  border-radius: 6px;
  border: 1px solid #e5e7eb;
  cursor: pointer;
}
.pick input {
  display: none;
}
.pick {
  display: inline-block;
  padding: 6px 12px;
  border: 1px dashed #c4c8d4;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  color: #374151;
}
.pick:hover {
  border-color: #6366f1;
  color: #4f46e5;
}
</style>
