<script setup lang="ts">
// 恢复码备份卡片：注册成功 / 恢复重置后全屏展示。
// 恢复码是忘记主密码后唯一的数据取回途径，强制用户确认已离线保存。
import { ref } from 'vue'
import { NAlert, NButton, NCheckbox, NSpace, NTag, useMessage } from 'naive-ui'

const props = defineProps<{
  code: string // 已分组的恢复码（XXXX-...）
  /** 重置场景：提示旧码已作废。 */
  rotated?: boolean
}>()
const emit = defineEmits<{ confirm: []; cancel: [] }>()

const message = useMessage()
const acknowledged = ref(false)
const copied = ref(false)

async function copyCode() {
  const text = props.code
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    message.success('恢复码已复制，请立即粘贴到离线安全的位置')
    setTimeout(() => (copied.value = false), 3000)
  } catch {
    message.warning('浏览器不允许自动复制，请手动选择文本抄录')
  }
}

function printCode() {
  window.print()
}
</script>

<template>
  <div class="backup">
    <n-alert type="error" class="critical" :bordered="false">
      <div class="critical-title">
        {{ rotated ? '新的恢复码已生成，旧恢复码立即作废' : '请立即离线保存恢复码' }}
      </div>
      <div class="critical-body">
        忘记主密码且没有恢复码 = 你的全部数据将<b>永久丢失且无法找回</b>。
        服务器不存储恢复码，也无法帮你重置。请抄写或打印到离线安全的位置，
        不要截图、不要存网盘、不要发给任何人。
      </div>
    </n-alert>

    <div class="code-box" @click="copyCode">
      <span class="code-text">{{ code }}</span>
    </div>
    <n-space justify="center" class="actions">
      <n-button size="small" @click="copyCode">{{ copied ? '已复制' : '复制恢复码' }}</n-button>
      <n-button size="small" @click="printCode">打印</n-button>
    </n-space>

    <n-checkbox v-model:checked="acknowledged" class="confirm">
      我已将恢复码保存在离线、安全的位置，并理解丢失它将导致数据永久丢失
    </n-checkbox>

    <n-space justify="space-between" class="footer">
      <n-button quaternary type="error" @click="emit('cancel')">
        {{ rotated ? '取消（保持退出）' : '放弃并返回登录' }}
      </n-button>
      <n-button type="primary" :disabled="!acknowledged" @click="emit('confirm')">
        我已备份，进入资料库
      </n-button>
    </n-space>

    <n-tag size="small" :bordered="false" type="warning" class="tip">
      恢复码不区分大小写，输入时可省略连字符
    </n-tag>
  </div>
</template>

<style scoped>
.backup {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.critical {
  border-radius: 10px;
}
.critical-title {
  font-weight: 700;
  font-size: 15px;
  margin-bottom: 4px;
}
.critical-body {
  font-size: 13px;
  line-height: 1.7;
}
.code-box {
  background: #10131c;
  border: 1px dashed #3a4258;
  border-radius: 10px;
  padding: 20px 12px;
  text-align: center;
  cursor: pointer;
  user-select: all;
}
.code-text {
  font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
  font-size: 22px;
  letter-spacing: 2px;
  color: #5eead4;
  word-break: break-all;
  line-height: 1.6;
}
.actions {
  margin-top: -4px;
}
.confirm {
  margin: 2px 2px 0;
  line-height: 1.6;
}
.footer {
  margin-top: 4px;
}
.tip {
  align-self: center;
}
</style>
