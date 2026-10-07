<script setup lang="ts">
// 选图 → 端侧 OCR → 解析 → 可编辑确认 → apply
import { ref } from 'vue'
import { NButton, NModal, NForm, NFormItem, NInput, NSpace, useMessage } from 'naive-ui'
import { recognizeTextFromImage } from '../engine'

export type OcrFieldMap = Record<string, string>

const props = defineProps<{
  label: string
  /** 将 OCR 原文解析为待确认字段；空对象表示无结果 */
  buildFields: (text: string) => OcrFieldMap
  fieldLabels: Record<string, string>
}>()

const emit = defineEmits<{ apply: [fields: OcrFieldMap] }>()

const message = useMessage()
const fileInput = ref<HTMLInputElement | null>(null)
const busy = ref(false)
const showConfirm = ref(false)
const draft = ref<OcrFieldMap>({})

function openPicker() {
  fileInput.value?.click()
}

async function onFileChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  busy.value = true
  try {
    const text = await recognizeTextFromImage(file)
    const fields = props.buildFields(text)
    const keys = Object.keys(fields).filter((k) => fields[k]?.trim())
    if (keys.length === 0) {
      message.warning('未识别到有效信息，请重试或手动输入')
      return
    }
    draft.value = { ...fields }
    showConfirm.value = true
  } catch {
    message.error('识别失败，请重试或手动输入')
  } finally {
    busy.value = false
  }
}

function confirmApply() {
  emit('apply', { ...draft.value })
  showConfirm.value = false
}
</script>

<template>
  <input ref="fileInput" type="file" accept="image/*" capture="environment" hidden @change="onFileChange" />
  <n-button quaternary :loading="busy" @click="openPicker">{{ label }}</n-button>
  <n-modal v-model:show="showConfirm" preset="card" title="确认识别结果" style="max-width: 520px">
    <n-form label-placement="left" label-width="96">
      <n-form-item v-for="(_value, key) in draft" :key="key" :label="fieldLabels[key] ?? key">
        <n-input v-model:value="draft[key]" />
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="showConfirm = false">取消</n-button>
        <n-button type="primary" @click="confirmApply">填入表单</n-button>
      </n-space>
    </template>
  </n-modal>
</template>
