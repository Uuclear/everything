<script setup lang="ts">
// ============================================================================
// 汇率设置视图（stage5-finance-v2 / B5 / FR-V2-C.1、FR-V2-C.2）
// ============================================================================
//
// 任务: stage5-finance-v2 / B5 批次（SA-3 UI 集成）
// 路径: web/src/views/SettingsRatesView.vue
// 作用: 默认币种选择 + 离线汇率包（JSON）手动导入 / 查看 / 移除。
//
// 设计要点:
//   1. 纯本地操作 —— 不接任何网络 API；汇率包仅支持手动文件导入；
//   2. 校验全部下沉 store（importRateTable / setDefaultCurrency），视图层
//      只负责把中文结果经 NMessage 反馈；
//   3. 文件选择跟随 FinancePolicyEditor 附件上传写法（NButton 触发隐藏
//      input[type=file] + FileReader.readAsText）；
//   4. 汇率键按字典序展示 "USD/CNY = 7.25"；生效日期按本地时区渲染
//      YYYY-MM-DD（与 aggregator 本地日历口径一致）；
//   5. 中文文案直接写模板（Web 端无 strings.xml 纪律）。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-C.1 / FR-V2-C.2
//   - web/src/stores/finance.ts（rateTable / defaultCurrency / 导入移除 API）
//   - web/src/finance/rateTable.ts（汇率包格式真理源）
// ============================================================================

import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NEmpty,
  NList,
  NListItem,
  NPopconfirm,
  NSelect,
  NSpace,
  NTag,
  NText,
  useMessage,
} from 'naive-ui'
import { useFinanceStore } from '../stores/finance'

const router = useRouter()
const message = useMessage()
const store = useFinanceStore()

// ========== 启动 hydration ==========
// 与 FinanceView 同节奏：进入设置页前确保持久化状态已还原，否则刚导入的
// 汇率包在直接刷新本路由时会显示为空态。
onMounted(() => {
  if (!store.hydrated) store.hydrate()
})

// ========== 默认币种 ==========
/** 预设 5 项（label = code + 中文名；spec FR-V2-C.1）。 */
const CURRENCY_OPTIONS = [
  { label: 'CNY 人民币', value: 'CNY' },
  { label: 'USD 美元', value: 'USD' },
  { label: 'EUR 欧元', value: 'EUR' },
  { label: 'JPY 日元', value: 'JPY' },
  { label: 'HKD 港币', value: 'HKD' },
]

/**
 * 选择器本地镜像值 —— 单向 :value 绑定；非法手填被 store 拒绝后，强制
 * 回滚为当前生效默认币种（避免输入框残留非法 tag）。
 */
const currencyValue = ref<string>(store.defaultCurrency)

/**
 * NSelect 更新回调（tag 模式允许手填任意 ISO 4217 代码）。
 * 仅接受 3 位大写字母；小写 / 长度不符给 NMessage 提示并回滚选择。
 */
function onCurrencyChange(value: string | null): void {
  if (value == null) return
  const ok = store.setDefaultCurrency(value)
  if (ok) {
    currencyValue.value = store.defaultCurrency
    message.success(`默认币种已设置为 ${store.defaultCurrency}`)
  } else {
    currencyValue.value = store.defaultCurrency
    message.error('币种代码必须为 3 位大写英文字母（例如 USD），已忽略本次修改')
  }
}

// ========== 离线汇率包 ==========
/** 隐藏 file input 的引用（点击"导入汇率包"按钮触发）。 */
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 已导入汇率包的全部货币对（按 key 字典序），未导入时为空数组。 */
const rateRows = computed(() => {
  const table = store.rateTable
  if (!table) return [] as Array<{ key: string; value: number }>
  return Object.keys(table.rates)
    .sort()
    .map((key) => ({ key, value: table.rates[key] }))
})

/** 生效时刻（effective_ts）→ 本地日期 YYYY-MM-DD。 */
function localDate(ts: number): string {
  // 与 aggregator TZ_OFFSET_MIN 同口径：CST（UTC+8）本地日历分量。
  const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()
  const d = new Date(ts + tzOffsetMin * 60_000)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 汇率展示值：保持 JSON 原始数字字面量（7.25 / 0.048 等）。 */
function formatRate(value: number): string {
  return String(value)
}

/** 触发隐藏 file input 点击（UI 隐藏原始控件，跟随附件上传写法）。 */
function onPickFile(): void {
  fileInputRef.value?.click()
}

/**
 * 处理文件选中：FileReader 读为文本 → store.importRateTable 校验导入。
 * 成功刷新列表（响应式 store 自动驱动）；失败弹中文原因。
 * 加密上行为 best-effort：synced=false（未解锁 / 离线）时额外提示本地已生效、
 * 待通道恢复后重新导入补推。每次处理后清空 input.value，允许重复选择同一文件。
 */
function onFileChange(event: Event): void {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file) return
  const reader = new FileReader()
  reader.onload = async () => {
    const text = typeof reader.result === 'string' ? reader.result : ''
    const result = await store.importRateTable(text)
    if (result.ok) {
      const pairCount = Object.keys(result.table.rates).length
      const syncNote = result.synced ? '，已加密同步' : '，本地已生效（通道不可用，稍后重新导入可补同步）'
      message.success(`汇率包导入成功：共 ${pairCount} 个货币对，生效日期 ${localDate(result.table.effectiveTs)}${syncNote}`)
    } else {
      message.error(result.error, { duration: 6000 })
    }
  }
  reader.onerror = () => {
    message.error('汇率包文件读取失败，请重试')
  }
  reader.readAsText(file)
}

/** 移除当前汇率包（NPopconfirm 二次确认后执行）。 */
function onClearRateTable(): void {
  store.clearRateTable()
  message.success('汇率包已移除，看板已恢复面值口径')
}

/** 返回财务看板。 */
function goBack(): void {
  router.push({ name: 'finance' })
}
</script>

<template>
  <div class="rates-settings">
    <div class="page-head">
      <h2>汇率设置</h2>
      <n-button size="small" @click="goBack">返回财务看板</n-button>
    </div>

    <!-- ========== 默认币种 ========== -->
    <n-card class="block" title="默认币种" size="small">
      <n-space align="center" :size="12" wrap>
        <n-select
          :value="currencyValue"
          :options="CURRENCY_OPTIONS"
          :tag="true"
          filterable
          placeholder="选择或输入 3 位大写币种代码"
          style="width: 260px;"
          @update:value="onCurrencyChange"
        />
        <n-text depth="3" style="font-size: 12px;">
          当前看板与月报金额按此币种折算展示；可手填任意 ISO 4217 三字母代码
        </n-text>
      </n-space>
    </n-card>

    <!-- ========== 离线汇率包 ========== -->
    <n-card class="block" size="small">
      <template #header>离线汇率包</template>
      <template #header-extra>
        <n-space :size="8">
          <n-button size="small" type="primary" @click="onPickFile">导入汇率包（JSON）</n-button>
          <n-popconfirm
            v-if="store.rateTable !== null"
            @positive-click="onClearRateTable"
          >
            <template #trigger>
              <n-button size="small" type="error" ghost>移除当前汇率包</n-button>
            </template>
            确定移除当前汇率包？移除后外币金额将按面值计入看板。
          </n-popconfirm>
        </n-space>
      </template>

      <!-- 隐藏的 file input（accept 限定 JSON，跟随附件上传写法） -->
      <input
        ref="fileInputRef"
        type="file"
        accept="application/json,.json"
        style="display: none;"
        data-testid="rates-file-input"
        @change="onFileChange"
      />

      <!-- 已导入：生效日期 + 货币对数量 + 全部汇率 -->
      <template v-if="store.rateTable">
        <n-space class="status-line" :size="12" align="center">
          <n-tag size="small" type="success" :bordered="false">已导入</n-tag>
          <n-text depth="2" style="font-size: 13px;">
            生效日期：{{ localDate(store.rateTable.effectiveTs) }}
          </n-text>
          <n-text depth="2" style="font-size: 13px;">
            货币对数量：{{ rateRows.length }}
          </n-text>
        </n-space>
        <n-list bordered class="rate-list">
          <n-list-item v-for="row in rateRows" :key="row.key">
            <span class="pair">{{ row.key }}</span>
            <span class="eq">=</span>
            <span class="rate-value">{{ formatRate(row.value) }}</span>
          </n-list-item>
        </n-list>
      </template>

      <!-- 未导入：空态 -->
      <n-empty
        v-else
        description="尚未导入汇率包，外币金额将按面值（1:1）计入看板"
        size="small"
        class="empty"
      />
    </n-card>

    <!-- ========== 说明 ========== -->
    <n-card class="block" title="说明" size="small">
      <ul class="notes">
        <li>
          汇率包仅支持手动导入（JSON 文件），请从央行官网等可信来源下载后导入；
          应用不会实时联网获取汇率。
        </li>
        <li>
          汇率包为零知识数据：汇率数据经加密通道上行同步，服务端仅存密文、无法解密查看。
        </li>
        <li>
          未导入汇率包或包内缺少某货币对时，对应外币金额按面值 1:1 计入，不丢条目。
        </li>
      </ul>
    </n-card>
  </div>
</template>

<style scoped>
.rates-settings {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}
.block {
  border-radius: 10px;
}
.status-line {
  margin-bottom: 10px;
}
.rate-list {
  max-height: 360px;
  overflow-y: auto;
}
.rate-list :deep(.n-list-item) {
  gap: 8px;
  font-variant-numeric: tabular-nums;
}
.pair {
  font-weight: 600;
  color: #374151;
  min-width: 120px;
}
.eq {
  color: #9ca3af;
}
.rate-value {
  color: #111827;
}
.empty {
  padding: 18px 0;
}
.notes {
  margin: 0;
  padding-left: 18px;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.9;
}
</style>
