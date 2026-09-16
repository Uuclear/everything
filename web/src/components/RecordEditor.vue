<script setup lang="ts">
// 通用记录编辑器（modal）：按 kind 渲染 login/note/card/identity 四套表单。
// 明文仅在本组件内存中暂存，保存时经 sealRecord 端到端加密后才离开浏览器。
import { reactive, ref, watch } from 'vue'
import {
  NModal,
  NForm,
  NFormItem,
  NInput,
  NInputGroup,
  NSelect,
  NButton,
  NSpace,
  NCheckbox,
  NSwitch,
  NSlider,
  NDivider,
  NDynamicInput,
  NDatePicker,
  useMessage,
} from 'naive-ui'
import { useVaultStore } from '../stores/vault'
import {
  IDENTITY_KIND_LABELS,
  type DecryptedRecord,
  type IdentityKind,
  type RecordKind,
  type TotpConfig,
} from '../types/vault'
import { generatePassword, DEFAULT_GENERATOR_OPTIONS, type GeneratorOptions } from '../crypto/generator'
import { parseOtpauth } from '../crypto/totp'

const props = defineProps<{
  show: boolean
  kind: RecordKind
  record?: DecryptedRecord | null
}>()
const emit = defineEmits<{ 'update:show': [v: boolean]; saved: [] }>()

const vault = useVaultStore()
const message = useMessage()
const saving = ref(false)

const KIND_TITLES: Record<RecordKind, string> = {
  login: '登录项',
  note: '安全笔记',
  card: '银行卡',
  identity: '证件',
}

// ---- 表单草稿（各类型字段都放一个对象，按 kind 取用）----
const form = reactive({
  title: '',
  username: '',
  password: '',
  urls: [] as string[],
  notes: '',
  body: '',
  cardholder: '',
  number: '',
  exp_month: null as number | null,
  exp_year: null as number | null,
  cvv: '',
  identityKind: 'generic' as IdentityKind,
  name: '',
  idNumber: '',
  issuer: '',
  issued_ts: null as number | null,
  expires_ts: null as number | null,
  totpEnabled: false,
  otpauthUri: '',
  totpSecret: '',
  totpIssuer: '',
})

// ---- 密码生成器 ----
const genOpts = reactive<GeneratorOptions>({ ...DEFAULT_GENERATOR_OPTIONS })
const showGenerator = ref(false)
function regenerate() {
  try {
    form.password = generatePassword(genOpts)
  } catch (e) {
    message.error((e as Error).message)
  }
}

function applyOtpauth() {
  try {
    const p = parseOtpauth(form.otpauthUri)
    form.totpSecret = p.secret
    form.totpIssuer = p.issuer
    message.success('已解析 otpauth 链接')
  } catch (e) {
    message.error((e as Error).message)
  }
}

function isoDate(ts: number | null): string | undefined {
  if (!ts) return undefined
  return new Date(ts).toISOString().slice(0, 10)
}
function tsOf(iso?: string): number | null {
  if (!iso) return null
  return new Date(`${iso}T00:00:00`).getTime()
}

/** 打开时按记录初始化草稿（深拷贝，取消编辑不影响 store 中的明文）。 */
watch(
  () => props.show,
  (open) => {
    if (!open) return
    const d = props.record?.data as Record<string, any> | undefined
    Object.assign(form, {
      title: d?.title ?? '',
      username: d?.username ?? '',
      password: d?.password ?? '',
      urls: Array.isArray(d?.urls) ? [...d.urls] : [],
      notes: d?.notes ?? '',
      body: d?.body ?? '',
      cardholder: d?.cardholder ?? '',
      number: d?.number ?? '',
      exp_month: d?.exp_month ?? null,
      exp_year: d?.exp_year ?? null,
      cvv: d?.cvv ?? '',
      identityKind: (d?.kind as IdentityKind) ?? (props.record?.type as IdentityKind) ?? 'generic',
      name: d?.name ?? '',
      idNumber: d?.idNumber ?? '',
      issuer: d?.issuer ?? '',
      issued_ts: tsOf(d?.issued_on),
      expires_ts: tsOf(d?.expires_on),
      totpEnabled: Boolean(d?.totp?.secret),
      otpauthUri: '',
      totpSecret: d?.totp?.secret ?? '',
      totpIssuer: d?.totp?.issuer ?? '',
    })
  },
)

function close() {
  emit('update:show', false)
}

async function submit() {
  if (!form.title.trim()) {
    message.warning('请填写标题')
    return
  }
  // 按类型组装明文（空字段不下发，减小密文体积与暴露面）。
  let data: Record<string, unknown>
  if (props.kind === 'login') {
    data = { title: form.title.trim() }
    if (form.username) data.username = form.username
    if (form.password) data.password = form.password
    const urls = form.urls.map((u) => u.trim()).filter(Boolean)
    if (urls.length) data.urls = urls
    if (form.notes) data.notes = form.notes
    if (form.totpEnabled && form.totpSecret.trim()) {
      const totp: TotpConfig = { secret: form.totpSecret.replace(/\s/g, '').toUpperCase() }
      if (form.totpIssuer) totp.issuer = form.totpIssuer
      data.totp = totp
    }
  } else if (props.kind === 'note') {
    data = { title: form.title.trim() }
    if (form.body) data.body = form.body
  } else if (props.kind === 'card') {
    data = { title: form.title.trim() }
    if (form.cardholder) data.cardholder = form.cardholder
    if (form.number) data.number = form.number.replace(/\s/g, '')
    if (form.exp_month) data.exp_month = form.exp_month
    if (form.exp_year) data.exp_year = form.exp_year
    if (form.cvv) data.cvv = form.cvv
    if (form.notes) data.notes = form.notes
  } else {
    data = { title: form.title.trim(), kind: form.identityKind }
    if (form.name) data.name = form.name
    if (form.idNumber) data.number = form.idNumber
    if (form.issuer) data.issuer = form.issuer
    const issued = isoDate(form.issued_ts)
    const expires = isoDate(form.expires_ts)
    if (issued) data.issued_on = issued
    if (expires) data.expires_on = expires
    if (form.notes) data.notes = form.notes
  }

  saving.value = true
  try {
    await vault.save(props.kind, data as any, props.record ?? undefined)
    message.success('已加密保存并同步')
    emit('saved')
    close()
  } catch (e) {
    message.error((e as Error).message || '保存失败')
  } finally {
    saving.value = false
  }
}

const identityOptions = (Object.keys(IDENTITY_KIND_LABELS) as IdentityKind[]).map((k) => ({
  label: IDENTITY_KIND_LABELS[k],
  value: k,
}))
const monthOptions = Array.from({ length: 12 }, (_, i) => ({
  label: String(i + 1).padStart(2, '0'),
  value: i + 1,
}))
const yearOptions = Array.from({ length: 21 }, (_, i) => {
  const y = new Date().getFullYear() - 5 + i
  return { label: String(y), value: y }
})
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="record ? `编辑${KIND_TITLES[kind]}` : `新建${KIND_TITLES[kind]}`"
    class="editor-modal"
    @update:show="emit('update:show', $event)"
  >
    <n-form label-placement="top">
      <!-- ===== 登录项 ===== -->
      <template v-if="kind === 'login'">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" placeholder="例如 GitHub" />
        </n-form-item>
        <n-form-item label="用户名 / 邮箱">
          <n-input v-model:value="form.username" autocomplete="off" />
        </n-form-item>
        <n-form-item label="密码">
          <n-input-group>
            <n-input v-model:value="form.password" type="password" show-password-on="click"
              autocomplete="new-password" />
            <n-button @click="showGenerator = !showGenerator">生成器</n-button>
          </n-input-group>
        </n-form-item>
        <div v-if="showGenerator" class="generator">
          <div class="gen-row">
            <span>长度 {{ genOpts.length }}</span>
            <n-slider v-model:value="genOpts.length" :min="8" :max="64" :step="1" class="gen-slider" />
            <n-button size="tiny" @click="regenerate">换一个</n-button>
          </div>
          <n-space size="small">
            <n-checkbox v-model:checked="genOpts.lower">小写 a-z</n-checkbox>
            <n-checkbox v-model:checked="genOpts.upper">大写 A-Z</n-checkbox>
            <n-checkbox v-model:checked="genOpts.digit">数字 0-9</n-checkbox>
            <n-checkbox v-model:checked="genOpts.symbol">符号</n-checkbox>
            <n-checkbox v-model:checked="genOpts.excludeAmbiguous">排除易混淆</n-checkbox>
          </n-space>
        </div>
        <n-form-item label="网址">
          <n-dynamic-input v-model:value="form.urls" placeholder="https://example.com" />
        </n-form-item>
        <n-form-item label="备注">
          <n-input v-model:value="form.notes" type="textarea" :rows="2" />
        </n-form-item>
        <n-divider style="margin: 4px 0 12px">TOTP 两步验证（可选）</n-divider>
        <n-form-item label="为该登录项启用 TOTP">
          <n-switch v-model:checked="form.totpEnabled" />
        </n-form-item>
        <template v-if="form.totpEnabled">
          <n-form-item label="粘贴 otpauth 链接自动填充">
            <n-input-group>
              <n-input v-model:value="form.otpauthUri" placeholder="otpauth://totp/..." />
              <n-button @click="applyOtpauth">解析</n-button>
            </n-input-group>
          </n-form-item>
          <n-form-item label="TOTP 密钥（Base32）">
            <n-input v-model:value="form.totpSecret" placeholder="JBSWY3DPEHPK3PXP" />
          </n-form-item>
          <n-form-item label="发行方">
            <n-input v-model:value="form.totpIssuer" placeholder="GitHub（可选）" />
          </n-form-item>
        </template>
      </template>

      <!-- ===== 安全笔记 ===== -->
      <template v-else-if="kind === 'note'">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" />
        </n-form-item>
        <n-form-item label="正文">
          <n-input v-model:value="form.body" type="textarea" :rows="8" />
        </n-form-item>
      </template>

      <!-- ===== 银行卡 ===== -->
      <template v-else-if="kind === 'card'">
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" placeholder="例如 招行储蓄卡" />
        </n-form-item>
        <n-form-item label="持卡人">
          <n-input v-model:value="form.cardholder" />
        </n-form-item>
        <n-form-item label="卡号">
          <n-input v-model:value="form.number" placeholder="6225 **** **** 1234"
            @input="(v: string) => (form.number = v.replace(/[^\d]/g, ''))" />
        </n-form-item>
        <n-space>
          <n-form-item label="有效期月">
            <n-select v-model:value="form.exp_month" :options="monthOptions" class="month-select" />
          </n-form-item>
          <n-form-item label="年">
            <n-select v-model:value="form.exp_year" :options="yearOptions" />
          </n-form-item>
          <n-form-item label="CVC">
            <n-input v-model:value="form.cvv" maxlength="4" class="cvv-input" />
          </n-form-item>
        </n-space>
        <n-form-item label="备注">
          <n-input v-model:value="form.notes" type="textarea" :rows="2" />
        </n-form-item>
      </template>

      <!-- ===== 证件 ===== -->
      <template v-else>
        <n-form-item label="证件类型">
          <n-select v-model:value="form.identityKind" :options="identityOptions" />
        </n-form-item>
        <n-form-item label="标题" required>
          <n-input v-model:value="form.title" placeholder="例如 我的身份证" />
        </n-form-item>
        <n-form-item label="姓名">
          <n-input v-model:value="form.name" />
        </n-form-item>
        <n-form-item label="证件号码">
          <n-input v-model:value="form.idNumber" />
        </n-form-item>
        <n-form-item label="签发机构">
          <n-input v-model:value="form.issuer" />
        </n-form-item>
        <n-space>
          <n-form-item label="签发日期">
            <n-date-picker v-model:value="form.issued_ts" type="date" class="date-picker" />
          </n-form-item>
          <n-form-item label="到期日期">
            <n-date-picker v-model:value="form.expires_ts" type="date" class="date-picker" />
          </n-form-item>
        </n-space>
        <n-form-item label="备注">
          <n-input v-model:value="form.notes" type="textarea" :rows="2" />
        </n-form-item>
      </template>
    </n-form>

    <template #footer>
      <n-space justify="end">
        <n-button @click="close">取消</n-button>
        <n-button type="primary" :loading="saving" @click="submit">加密保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<style scoped>
.generator {
  border: 1px solid #e3e6ee;
  border-radius: 8px;
  padding: 10px 12px;
  margin: -4px 0 12px;
}
.gen-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 13px;
}
.gen-slider {
  flex: 1;
}
.month-select {
  width: 90px;
}
.cvv-input {
  width: 100px;
}
.date-picker {
  width: 160px;
}
</style>
<style>
/* modal 被 teleport 到 body，scoped 不生效，需全局样式。 */
.editor-modal {
  width: 600px;
  max-width: 94vw;
  max-height: 88vh;
}
</style>
