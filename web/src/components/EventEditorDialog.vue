<!--
  阶段 4b — 事件编辑器对话框（tasks.md Task 8 / TR-8.1）。

  角色定位（关键纪律：薄壳 + 类型严格）：
    - 本组件是表单 UI 壳，所有状态机 / 校验 / EventRule 构造全部抽到
      `events/editor.ts` 纯函数层。组件只负责：
        1) 接收 props: show / rule?；
        2) 用 createBlankForm / formFromRule 初始化草稿；
        3) 渲染表单（n-modal + n-form + n-input/select/switch/date-picker）；
        4) 调 validateForm 校验；通过后用 ruleFromForm 构造 EventRule 调
           useEventRulesStore().upsert(rule)，然后 emit('update:show', false)；
        5) 编辑模式（rule 存在）暴露"删除"按钮调 store.remove(id)。

  零知识纪律：标题/地点/备注仅 UI 文本与返回值出现；不写日志。
-->
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  NModal,
  NForm,
  NFormItem,
  NInput,
  NSelect,
  NSwitch,
  NButton,
  NSpace,
  NCheckbox,
  NCheckboxGroup,
  NDatePicker,
  NInputNumber,
  NDivider,
  NDynamicInput,
  useMessage,
} from 'naive-ui'
import { useEventRulesStore } from '../stores/event-rules'
import type { EventRule } from '../events/types'
import {
  REMINDER_OPTIONS,
  COLOR_OPTIONS,
  FREQ_OPTIONS,
  WEEKDAY_OPTIONS,
  END_KIND_OPTIONS,
  type EndKind,
  type Frequency,
  type Weekday,
} from '../events/editor'
import {
  createBlankForm,
  formFromRule,
  validateForm,
  ruleFromForm,
} from '../events/editor'

// =============================================================================
// props / emit
// =============================================================================

const props = defineProps<{
  /** 显示开关（v-model:show 模式）。 */
  show: boolean
  /** 编辑模式传入；null/undefined = 新建模式。 */
  rule?: EventRule | null
  /** 时间戳初值（新建模式预填；默认 Date.now）。null = 沿用默认。 */
  presetStartTs?: number | null
}>()
const emit = defineEmits<{
  'update:show': [v: boolean]
  'saved': [rule: EventRule]
}>()

const store = useEventRulesStore()
const message = useMessage()

// =============================================================================
// 草稿状态（独立响应式对象；切换 show 时按规则重置）
// =============================================================================

/** 当前编辑的草稿。 */
const form = ref(createBlankForm(props.presetStartTs ?? Date.now()))
const saving = ref(false)
/** 例外日期输入缓冲（NDynamicInput 是 string[] 形态；保存前转 exdates）。 */
const exdateInput = ref<string[]>([])

// 显示打开时初始化草稿（含 exdate 字符串数组同步）。
watch(
  () => props.show,
  (open) => {
    if (!open) return
    if (props.rule) {
      form.value = formFromRule(props.rule)
      exdateInput.value = [...props.rule.exdates]
    } else {
      form.value = createBlankForm(props.presetStartTs ?? Date.now())
      exdateInput.value = []
    }
  },
)

// endKind / freq 用计算器代理（n-select v-model 不接受泛型 ref 直接双向）。
const endKindValue = computed<EndKind>({
  get: () => form.value.endKind,
  set: (v) => (form.value.endKind = v),
})
const freqValue = computed<'NONE' | Frequency>({
  get: () => form.value.freq,
  set: (v) => (form.value.freq = v),
})

const byweekdaySelected = computed<Weekday[]>({
  get: () => form.value.byweekday,
  set: (v) => (form.value.byweekday = v),
})

// UI 形态选项（naive-ui n-select 接受 {label,value}[]）。
const remindersOpt = REMINDER_OPTIONS.map((o) => ({ label: o.label, value: o.value }))
const colorOpt = COLOR_OPTIONS.map((o) => ({ label: o.label, value: o.value }))
const freqOpt = [
  { label: '不重复', value: 'NONE' as const },
  ...FREQ_OPTIONS.map((o) => ({ label: o.label, value: o.value })),
]
const weekdayOpt = WEEKDAY_OPTIONS.map((o) => ({ label: o.label, value: o.value }))
const endKindOpt = END_KIND_OPTIONS.map((o) => ({ label: o.label, value: o.value }))

// endKind='date' 时 n-date-picker 的值（ms）；转 YYYY-MM-DD。
const endUntilPicker = computed<number | null>({
  get: () => (form.value.endUntil ? Date.parse(form.value.endUntil + 'T00:00:00') : null),
  set: (v) => {
    if (v == null) {
      form.value.endUntil = ''
      return
    }
    const d = new Date(v)
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    form.value.endUntil = `${y}-${m}-${dd}`
  },
})

// =============================================================================
// 校验 + 保存
// =============================================================================

const errors = computed(() => {
  // 同步 exdateInput → form.exdates（保存前最后一次），UI 触发。
  form.value.exdates = exdateInput.value.filter((s) => /^\d{4}-\d{2}-\d{2}$/.test(s))
  return validateForm(form.value)
})
const canSave = computed(() => errors.value.length === 0 && !saving.value)

/** 生成规则 id（UUID v4）；编辑模式复用 props.rule.id。 */
function genId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return (
    '00000000-0000-4000-8000-' +
    Math.random().toString(16).slice(2, 14).padStart(12, '0')
  )
}

async function save() {
  if (!canSave.value) return
  saving.value = true
  try {
    const id = props.rule?.id ?? genId()
    const rule = ruleFromForm(form.value, id)
    await store.upsert(rule)
    message.success(props.rule ? '事件已更新' : '事件已创建')
    emit('saved', rule)
    emit('update:show', false)
  } catch (e) {
    message.error((e as Error).message ?? '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!props.rule) return
  saving.value = true
  try {
    await store.remove(props.rule.id)
    message.success('事件已删除')
    emit('update:show', false)
  } catch (e) {
    message.error((e as Error).message ?? '删除失败')
  } finally {
    saving.value = false
  }
}

function close() {
  emit('update:show', false)
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="props.rule ? '编辑事件' : '新建事件'"
    class="event-editor-modal"
    :mask-closable="!saving"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <n-form label-placement="top" size="small">
      <!-- 标题必填 -->
      <n-form-item label="标题" required :feedback="errors.includes('标题不能为空') ? '标题不能为空' : ''">
        <n-input v-model:value="form.title" placeholder="例如：与 Andy 通话" maxlength="200" show-count />
      </n-form-item>

      <!-- 起止：n-date-picker datetime 模式 -->
      <n-form-item label="开始">
        <n-date-picker
          v-model:value="form.start_ts"
          type="datetime"
          clearable
          format="yyyy-MM-dd HH:mm"
          style="width: 100%"
        />
      </n-form-item>
      <n-form-item
        label="结束"
        :feedback="form.start_ts > form.end_ts ? '结束必须 ≥ 开始' : ''"
      >
        <n-date-picker
          v-model:value="form.end_ts"
          type="datetime"
          clearable
          format="yyyy-MM-dd HH:mm"
          style="width: 100%"
        />
      </n-form-item>

      <n-form-item label="全天">
        <n-switch v-model:value="form.all_day" />
      </n-form-item>

      <n-form-item label="地点">
        <n-input v-model:value="form.location_text" placeholder="可选" />
      </n-form-item>

      <n-form-item label="备注">
        <n-input v-model:value="form.note" type="textarea" :rows="2" placeholder="可选" />
      </n-form-item>

      <n-form-item label="颜色">
        <n-select v-model:value="form.color" :options="colorOpt" />
      </n-form-item>

      <n-form-item :label="`提醒（≤3 个；当前 ${form.reminders.length}）`">
        <n-select
          v-model:value="form.reminders"
          multiple
          :options="remindersOpt"
          placeholder="选择提前分钟档"
        />
      </n-form-item>

      <n-divider style="margin: 4px 0 12px">重复</n-divider>

      <n-form-item label="频率">
        <n-select v-model:value="freqValue" :options="freqOpt" />
      </n-form-item>

      <n-form-item v-if="form.freq !== 'NONE'" label="间隔">
        <n-input-number v-model:value="form.interval" :min="1" :max="999" />
      </n-form-item>

      <n-form-item v-if="form.freq === 'WEEKLY'" label="工作日（可多选）">
        <n-checkbox-group v-model:value="byweekdaySelected">
          <n-space>
            <n-checkbox v-for="o in weekdayOpt" :key="o.value" :value="o.value">
              {{ o.label }}
            </n-checkbox>
          </n-space>
        </n-checkbox-group>
      </n-form-item>

      <n-form-item v-if="form.freq === 'MONTHLY'" label="单 weekday（spec FR-2 仅支持一个）">
        <n-select
          :value="form.byweekday[0] ?? null"
          :options="weekdayOpt"
          placeholder="选择 weekday"
          @update:value="(v: Weekday) => (form.byweekday = [v])"
        />
      </n-form-item>

      <n-form-item v-if="form.freq !== 'NONE'" label="结束条件">
        <n-select v-model:value="endKindValue" :options="endKindOpt" />
      </n-form-item>

      <n-form-item v-if="form.freq !== 'NONE' && form.endKind === 'date'" label="截止日期（本地日）">
        <n-date-picker
          v-model:value="endUntilPicker"
          type="date"
          format="yyyy-MM-dd"
          style="width: 100%"
        />
      </n-form-item>

      <n-form-item v-if="form.freq !== 'NONE' && form.endKind === 'count'" label="次数">
        <n-input-number v-model:value="form.endCount" :min="1" :max="9999" />
      </n-form-item>

      <n-form-item label="例外日期（YYYY-MM-DD）">
        <n-dynamic-input v-model:value="exdateInput" placeholder="YYYY-MM-DD" />
      </n-form-item>
    </n-form>

    <!-- 错误列表 -->
    <div v-if="errors.length" class="form-errors">
      <ul>
        <li v-for="(e, i) in errors" :key="i">{{ e }}</li>
      </ul>
    </div>

    <template #footer>
      <n-space justify="space-between">
        <n-button v-if="props.rule" :loading="saving" type="error" secondary @click="remove">
          删除
        </n-button>
        <n-space>
          <n-button :disabled="saving" @click="close">取消</n-button>
          <n-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
            保存
          </n-button>
        </n-space>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.event-editor-modal {
  max-width: 560px;
}
.form-errors {
  margin: 6px 0 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fff5f5;
  color: #d03050;
  font-size: 12px;
}
.form-errors ul {
  margin: 0;
  padding-left: 18px;
}
</style>
