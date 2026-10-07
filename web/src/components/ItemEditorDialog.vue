<!-- 物品编辑对话框：16 字段与 Android ItemEditorScreen 对齐 -->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NModal,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NButton,
  NSpace,
  NInputNumber,
  NDatePicker,
  useMessage,
} from 'naive-ui'
import { useItemsStore } from '../stores/items'
import type { Item } from '../items/types'
import {
  CATEGORY_OPTIONS,
  createBlankItemForm,
  formFromItem,
  itemFromForm,
  validateItemForm,
  warrantyUntilLabel,
} from '../items/editor'
import { warrantyUntilTs } from '../items/warranty'

const props = defineProps<{
  show: boolean
  item?: Item | null
}>()
const emit = defineEmits<{
  'update:show': [v: boolean]
  saved: [item: Item]
}>()

const store = useItemsStore()
const message = useMessage()
const form = ref(createBlankItemForm())
const saving = ref(false)

const warrantyPreview = computed(() =>
  warrantyUntilLabel(warrantyUntilTs(form.value.purchase_date, form.value.warranty_duration_days)),
)

const canSave = computed(() => !validateItemForm(form.value))

watch(
  () => props.show,
  (open) => {
    if (!open) return
    if (props.item) form.value = formFromItem(props.item)
    else form.value = createBlankItemForm()
  },
)

async function onSave() {
  const err = validateItemForm(form.value)
  if (err) {
    message.warning(err)
    return
  }
  saving.value = true
  try {
    const item = itemFromForm(form.value, props.item ?? undefined)
    await store.upsert(item)
    emit('saved', item)
    emit('update:show', false)
  } finally {
    saving.value = false
  }
}

async function onDelete() {
  if (!props.item) return
  saving.value = true
  try {
    await store.remove(props.item.id)
    emit('update:show', false)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="item ? '编辑物品' : '新建物品'"
    style="width: 640px; max-height: 90vh; overflow: auto"
    @update:show="(v) => emit('update:show', v)"
  >
    <NForm label-placement="left" label-width="100">
      <NFormItem label="名称" required>
        <NInput v-model:value="form.name" data-testid="input_name" />
      </NFormItem>
      <NFormItem label="分类" required>
        <NSelect
          v-model:value="form.category"
          :options="CATEGORY_OPTIONS"
          data-testid="dropdown_category"
        />
      </NFormItem>
      <NFormItem label="标签">
        <NInput
          v-model:value="form.tagsInput"
          placeholder="逗号分隔，最多 8 个"
          data-testid="tags_input"
        />
      </NFormItem>
      <NFormItem label="品牌">
        <NInput v-model:value="form.brand" />
      </NFormItem>
      <NFormItem label="型号">
        <NInput v-model:value="form.model" />
      </NFormItem>
      <NFormItem label="序列号">
        <NInput v-model:value="form.serial_no" />
      </NFormItem>
      <NFormItem label="购买日期">
        <NDatePicker v-model:value="form.purchase_date" type="date" />
      </NFormItem>
      <NFormItem label="价格（元）">
        <NInput v-model:value="form.purchase_price_yuan" />
      </NFormItem>
      <NFormItem label="币种">
        <NInput v-model:value="form.currency" />
      </NFormItem>
      <NFormItem label="保修天数">
        <NInputNumber v-model:value="form.warranty_duration_days" :min="0" />
      </NFormItem>
      <NFormItem label="保修截止">
        <span>{{ warrantyPreview }}</span>
      </NFormItem>
      <NFormItem label="发票链接">
        <NInput v-model:value="form.receipt_url" placeholder="https://..." />
      </NFormItem>
      <NFormItem label="备注">
        <NInput v-model:value="form.note" type="textarea" />
      </NFormItem>
      <NFormItem label="存放位置">
        <NInput v-model:value="form.location_text" />
      </NFormItem>
    </NForm>
    <NSpace justify="end">
      <NButton v-if="item" type="error" :loading="saving" data-testid="btn_delete" @click="onDelete">
        删除
      </NButton>
      <NButton @click="emit('update:show', false)">取消</NButton>
      <NButton
        type="primary"
        :loading="saving"
        :disabled="!canSave"
        data-testid="btn_save"
        @click="onSave"
      >
        保存
      </NButton>
    </NSpace>
  </NModal>
</template>
