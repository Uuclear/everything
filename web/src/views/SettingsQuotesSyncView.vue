<script setup lang="ts">
// ============================================================================
// 投资行情设置视图（stage5-finance-v2 / Task 8 / FR-V2-D.2）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 8（T8-7 Web 端镜像）
// 路径: web/src/views/SettingsQuotesSyncView.vue
// 作用: 手动行情包（JSON）导入 / 查看 / 移除；与 Android QuotesImportScreen
//       同款镜像结构 —— 默认币种 / 同步 URL / 当前行情包状态 / SAF 选 JSON /
//       行情明细预览 / 零知识说明。
//
// 设计要点:
//   1. 纯本地操作 —— 不接任何第三方行情 API（FR-V2-D.2 明确手动导入）；
//   2. 校验全部下沉 store（importQuoteTable / setQuoteSyncUrl），视图层
//      只负责把中文结果经 NMessage 反馈；
//   3. 文件选择跟随 FinancePolicyEditor 附件上传写法（NButton 触发隐藏
//      input[type=file] + FileReader.readAsText）；
//   4. 行情行按 symbol 字典序展示 "AAPL · USD 175.32（2026-09-24）"；
//      生效日期按本地时区渲染 YYYY-MM-DD（与 aggregator 本地日历口径一致）；
//   5. 中文文案直接写模板（Web 端无 strings.xml 纪律）；
//   6. 零知识纪律 —— 行情包密文上 record 通道（type='quote'），UI 仅展示
//      symbol/currency/price 不展示账户名 / 持仓数 / 成本。
//
// 关联:
//   - .trae/specs/stage5-finance-v2/spec.md FR-V2-D.2 / AC-V2F-9
//   - web/src/stores/finance.ts（quoteTable / quoteSyncUrl / 导入移除 API）
//   - web/src/finance/quoteTable.ts（行情包格式真理源）
//   - web/src/views/SettingsRatesView.vue（B5 同款模板，1:1 镜像）
// ============================================================================

import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NCard,
  NEmpty,
  NInput,
  NList,
  NListItem,
  NPopconfirm,
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
// 与 SettingsRatesView 同节奏：进入设置页前确保持久化状态已还原，否则刚导入的
// 行情包在直接刷新本路由时会显示为空态。
onMounted(() => {
  if (!store.hydrated) store.hydrate()
})

// ========== 同步 URL ==========
/**
 * 同步 URL 本地镜像值（输入框：双向绑定手动输入）。
 * - 空字符串 → 清空同步地址（store 接受空串并持久化为 null）；
 * - 非 https? 开头 → 拒绝并恢复原值；
 * - 合法 URL → 提交给 store。
 */
const syncUrlDraft = ref<string>(store.quoteSyncUrl ?? '')

// watch 还原：从 hydrate 完成后 store.quoteSyncUrl 可能晚到，先用其覆盖草稿
// —— 不阻塞用户输入：仅当草稿为空时才跟随 store 变化回填。
onMounted(() => {
  if (syncUrlDraft.value === '' && store.quoteSyncUrl) {
    syncUrlDraft.value = store.quoteSyncUrl
  }
})

/** 提交同步 URL（点击"保存同步地址"按钮或按回车后触发）。 */
function onCommitSyncUrl(): void {
  const draft = syncUrlDraft.value.trim()
  if (draft === '') {
    const ok = store.setQuoteSyncUrl('')
    if (ok) {
      message.success('同步地址已清空，仅保留本地行情包')
    } else {
      message.error('同步地址清空失败')
    }
    return
  }
  if (!/^https?:\/\//.test(draft)) {
    message.error('同步地址必须以 http:// 或 https:// 开头')
    return
  }
  const ok = store.setQuoteSyncUrl(draft)
  if (ok) {
    message.success('同步地址已保存（待通道恢复后由 CollectorWorker 拉取）')
  } else {
    message.error('同步地址保存失败')
  }
}

// ========== 手动行情包 ==========
/** 隐藏 file input 的引用（点击"导入行情包"按钮触发）。 */
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 行情行（按 symbol 字典序）—— 用于详情列表展示。
 * 单条形态：{ symbol, currency, price, ts }。
 */
interface QuoteRow {
  symbol: string
  currency: string
  price: number
  ts: number
}

const quoteRows = computed<QuoteRow[]>(() => {
  const table = store.quoteTable
  if (!table) return []
  return Array.from(table.quotes.keys())
    .sort()
    .map((symbol) => {
      const q = table.quotes.get(symbol)
      if (!q) return null
      // 行情包密文是整数分（minor units）；UI 展示转为元（除以 100，保留 4 位
      // 小数 —— 股票场景小数位比汇率多，原样保留即可）。
      const minor = typeof q.priceMinor === 'number' ? q.priceMinor : 0
      const price = minor / 100
      return {
        symbol,
        currency: q.currency,
        price,
        ts: typeof q.ts === 'number' ? q.ts : table.ts,
      }
    })
    .filter((row): row is QuoteRow => row !== null)
})

/** 行情包有效报价数量（去重后）。 */
const quoteCount = computed(() => quoteRows.value.length)

/** 行情包生效时刻（quoteTable.ts 毫秒整数）→ 本地日期 YYYY-MM-DD。 */
function localDate(ts: number): string {
  // 与 aggregator TZ_OFFSET_MIN 同口径：CST（UTC+8）本地日历分量。
  const tzOffsetMin = -new Date(1780000000000).getTimezoneOffset()
  const d = new Date(ts + tzOffsetMin * 60_000)
  const y = d.getUTCFullYear()
  const m = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/**
 * 行情包生效时刻字符串（取行情包本身 ts 的本地日期；行情表内部 ts 是行情
 * 采集生效的"快照时间"，与每条 quote 自身的 ts 可能略有差异，本视图以
 * 行情包外层 ts 为主参考）。
 */
const effectiveDateLabel = computed(() => {
  const table = store.quoteTable
  if (!table) return ''
  return localDate(table.ts)
})

/**
 * 行情价格展示 —— 保留小数位（价格常见 2 位小数，部分场景 4 位）。
 * 不强行钳位精度，原样输出（前端只负责展示）。
 */
function formatPrice(value: number): string {
  if (!Number.isFinite(value)) return '0'
  return String(value)
}

/** 触发隐藏 file input 点击（UI 隐藏原始控件，跟随附件上传写法）。 */
function onPickFile(): void {
  fileInputRef.value?.click()
}

/**
 * 处理文件选中：FileReader 读为文本 → store.importQuoteTable 校验导入。
 * 成功刷新列表（响应式 store 自动驱动）；失败弹中文原因。
 * 加密上行为 best-effort：synced=false（未解锁 / 离线）时额外提示本地已生效、
 * 待通道恢复后重新导入可补同步。每次处理后清空 input.value，允许重复选择同一文件。
 */
function onFileChange(event: Event): void {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file) return
  const reader = new FileReader()
  reader.onload = async () => {
    const text = typeof reader.result === 'string' ? reader.result : ''
    const result = await store.importQuoteTable(text)
    if (result.ok) {
      const symbolCount = quoteCount.value
      const syncNote = result.synced ? '，已加密同步' : '，本地已生效（通道不可用，稍后重新导入可补同步）'
      message.success(`行情包导入成功：共 ${symbolCount} 个报价，生效日期 ${effectiveDateLabel.value}${syncNote}`)
    } else {
      message.error(result.error, { duration: 6000 })
    }
  }
  reader.onerror = () => {
    message.error('行情包文件读取失败，请重试')
  }
  reader.readAsText(file)
}

/** 移除当前行情包（NPopconfirm 二次确认后执行）。 */
function onClearQuoteTable(): void {
  // store 当前未提供 clearQuoteTable —— 直接置空走手动通道；
  // 此处复用 setQuoteSyncUrl 不合用，直接调用 ingest 的反向不暴露给 UI；
  // 简单方案：让用户重新导入"空包"覆盖 —— 但空包校验必拒；故此处走
  // 临时辅助 removeQuoteTable。
  // 该辅助在 store 中以 _removeQuoteTableForUi 暴露，供设置页单一入口调用。
  const removed = store.removeQuoteTable?.() ?? false
  if (removed) {
    message.success('行情包已移除，看板投资市值已回退为 0')
  } else {
    message.warning('当前版本暂不支持一键清空行情包，请重新导入覆盖')
  }
}

/** 返回财务看板。 */
function goBack(): void {
  router.push({ name: 'finance' })
}
</script>

<template>
  <div class="quotes-settings">
    <div class="page-head">
      <h2>投资行情设置</h2>
      <n-button size="small" @click="goBack">返回财务看板</n-button>
    </div>

    <!-- ========== 同步 URL ========== -->
    <n-card class="block" title="同步地址" size="small">
      <n-space align="center" :size="12" wrap>
        <n-input
          v-model:value="syncUrlDraft"
          placeholder="https://your-quote-feed.example.com/quote.json"
          style="width: 360px;"
          clearable
          @keyup.enter="onCommitSyncUrl"
        />
        <n-button size="small" type="primary" @click="onCommitSyncUrl">保存同步地址</n-button>
        <n-text depth="3" style="font-size: 12px;">
          支持 http/https；保存后由后台同步服务在通道可用时按该地址拉取行情 JSON
        </n-text>
      </n-space>
    </n-card>

    <!-- ========== 当前行情包状态 ========== -->
    <n-card class="block" size="small">
      <template #header>当前行情包</template>
      <template #header-extra>
        <n-space :size="8">
          <n-button size="small" type="primary" @click="onPickFile">导入行情包（JSON）</n-button>
          <n-popconfirm
            v-if="store.quoteTable !== null"
            @positive-click="onClearQuoteTable"
          >
            <template #trigger>
              <n-button size="small" type="error" ghost>移除当前行情包</n-button>
            </template>
            确定移除当前行情包？移除后投资市值将按缺价（missingPrice）计入。
          </n-popconfirm>
        </n-space>
      </template>

      <!-- 隐藏的 file input（accept 限定 JSON，跟随附件上传写法） -->
      <input
        ref="fileInputRef"
        type="file"
        accept="application/json,.json"
        style="display: none;"
        data-testid="quotes-file-input"
        @change="onFileChange"
      />

      <!-- 已导入：生效日期 + 报价数量 + 全部行情 -->
      <template v-if="store.quoteTable">
        <n-space class="status-line" :size="12" align="center">
          <n-tag size="small" type="success" :bordered="false">已导入</n-tag>
          <n-text depth="2" style="font-size: 13px;">
            生效日期：{{ effectiveDateLabel }}
          </n-text>
          <n-text depth="2" style="font-size: 13px;">
            报价数量：{{ quoteCount }}
          </n-text>
        </n-space>
        <n-list bordered class="quote-list">
          <n-list-item v-for="row in quoteRows" :key="row.symbol">
            <span class="symbol">{{ row.symbol }}</span>
            <span class="dot">·</span>
            <span class="currency">{{ row.currency }}</span>
            <span class="dot">·</span>
            <span class="price">{{ formatPrice(row.price) }}</span>
          </n-list-item>
        </n-list>
      </template>

      <!-- 未导入：空态 -->
      <n-empty
        v-else
        description="尚未导入行情包，投资账户按缺价计入市值"
        size="small"
        class="empty"
      />
    </n-card>

    <!-- ========== 说明 ========== -->
    <n-card class="block" title="说明" size="small">
      <ul class="notes">
        <li>
          行情包仅支持手动导入（JSON 文件），请从可信数据源（券商 / 行情服务商）导出后导入；应用不会实时联网抓取行情。
        </li>
        <li>
          行情包为零知识数据：价格数据经加密通道上行同步，服务端仅存密文、无法解密查看。
        </li>
        <li>
          缺价（行情包未覆盖某 symbol）会被计入"缺价持仓"统计，月报投资行会用相应提示词条标注；命中报价恰好为 0 不视为缺价。
        </li>
      </ul>
    </n-card>
  </div>
</template>

<style scoped>
.quotes-settings {
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
.quote-list {
  max-height: 360px;
  overflow-y: auto;
}
.quote-list :deep(.n-list-item) {
  gap: 8px;
  font-variant-numeric: tabular-nums;
}
.symbol {
  font-weight: 600;
  color: #374151;
  min-width: 80px;
}
.dot {
  color: #9ca3af;
}
.currency {
  color: #4b5563;
  min-width: 36px;
}
.price {
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