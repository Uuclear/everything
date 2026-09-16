# 阶段 5 v2 — 财务二版增量 - 实施计划

> 需求来源：[spec.md](file:///d:/github/everything/everything/.trae/specs/stage5-finance-v2/spec.md)
> 任务按"模块 + 优先级"打包，分 8 个批次（B1~B8）推进。每批独立门禁 + 推送；
> 每批含子代理派发 + 主会话门禁复跑 + tasks.md 三段式回填（Pass Condition
> / Status / Completion Evidence）。
> **优先级标注**：P0 必做 / P1 建议 / P2 可选（详见 spec.md）。

## 批次划分总览

| 批次 | 优先级 | Task | 内容 | 前置 |
|---|---|---|---|---|
| B1 | P0 | T1 | 4 子类型纯函数 + Web 端 4 编辑器 + 列表 | v1 已落地 |
| B2 | P0 | T2 | Android 端 4 编辑器 + 列表 + 导航 | T1 |
| B3 | P0 | T3 | 附件完整闭环（块存储 + UI 集成） | T1 |
| B4 | P0 | T4 | 后 3 类提醒链路（订阅/保单/借款）+ aggregator loan 启用 | T1 + T2 |
| B5 | P1 | T5 | 多币种汇率 + aggregator 多币种折算 | v1 aggregator |
| B6 | P1 | T6 + T7 | 预算硬约束 + Web 端提醒 | v1 既有提醒 |
| B7 | P2 | T8 + T9 | 投资账户 + 手动行情 | v1 aggregator |
| B8 | P2 | T10 + T11 | AI 联动记账（OCR + 语音） + 文档 + 门禁 | T1 |

---

## Task 1: 4 子类型纯函数 + Web 端 4 编辑器（P0）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 `FinanceAggregator.kt` / `web/src/finance/types.ts`
- **Description**:
  - 修改 `docs/finance.md` 与 `docs/module-schemas.md` §9：v2 四类子类型
    schema 完整化（`subscription` / `policy` / `loan` / `contract` 字段定义）
  - 新建 `android/app/src/main/java/com/everything/eve/finance/SubscriptionRecord.kt` /
    `PolicyRecord.kt` / `LoanRecord.kt` / `ContractRecord.kt`（4 个 record
    数据类 + 反序列化 + 校验函数）
  - 新建 `web/src/finance/types.ts` 扩展：`Subscription` / `Policy` / `Loan`
    / `Contract` 接口 + 校验函数
  - 修改 `web/src/stores/finance.ts`：增加 `subscriptions / policies / loans /
    contracts` state + CRUD actions
  - 新建 `web/src/views/finance/SubscriptionList.vue` + `SubscriptionEditor.vue`
  - 新建 `web/src/views/finance/PolicyList.vue` + `PolicyEditor.vue`
  - 新建 `web/src/views/finance/LoanList.vue` + `LoanEditor.vue`
  - 新建 `web/src/views/finance/ContractList.vue` + `ContractEditor.vue`
  - 修改 `web/src/router/index.ts`：注册 `/finance/subscriptions` / 
    `/finance/policies` / `/finance/loans` / `/finance/contracts` + 4 个 
    editor 子路由
  - 修改 `web/src/views/finance/FinanceDashboard.vue`：增加 4 类子类型卡片
  - 修改 `web/src/views/AppShell.vue`：增加 4 类子类型菜单项
  - 新建 4 个 Web spec：≥4 + 4 + 4 + 4 = ≥16 用例
  - 修改 `web/src/finance/__tests__/types.spec.ts`：4 子类型 schema 校验
- **TR 列表**:
  - TR-1.1 4 子类型 schema 完整化（Android + Web + docs）
  - TR-1.2 Subscription Editor + List（Web）
  - TR-1.3 Policy Editor + List（Web）
  - TR-1.4 Loan Editor + List（Web）
  - TR-1.5 Contract Editor + List（Web）
  - TR-1.6 4 子类型 store CRUD（Web）
  - TR-1.7 路由 + 菜单 + Dashboard 卡片（Web）

---

## Task 2: Android 端 4 编辑器 + 列表 + 导航（P0）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/SubscriptionListScreen.kt`
    + `SubscriptionEditorScreen.kt`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/PolicyListScreen.kt`
    + `PolicyEditorScreen.kt`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/LoanListScreen.kt`
    + `LoanEditorScreen.kt`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/ContractListScreen.kt`
    + `ContractEditorScreen.kt`
  - 修改 `android/app/src/main/java/com/everything/eve/ui/AppNav.kt`：增加
    8 个 Routes 常量（4 list + 4 editor）
  - 修改 `android/app/src/main/java/com/everything/eve/ui/screens/VaultScreen.kt`：
    增加 4 类子类型卡片入口
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/FinanceDashboard.kt`：
    增加 4 类子类型卡片
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceViewModel.kt`：
    增加 4 类子类型 state + actions
  - 修改 `android/app/src/main/res/values/strings.xml`：增加 4 子类型文案
    （每类 ≥10 条 string resource）
  - 新建 `android/app/src/test/java/com/everything/eve/finance/SubscriptionRecordTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/PolicyRecordTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/LoanRecordTest.kt`：
    ≥4 用例（含 `include_in_net_assets`）
  - 新建 `android/app/src/test/java/com/everything/eve/finance/ContractRecordTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/ui/finance/FinanceViewModelV2Test.kt`：
    ≥4 用例
- **TR 列表**:
  - TR-2.1 SubscriptionListScreen + SubscriptionEditorScreen（Android）
  - TR-2.2 PolicyListScreen + PolicyEditorScreen（Android）
  - TR-2.3 LoanListScreen + LoanEditorScreen（Android）
  - TR-2.4 ContractListScreen + ContractEditorScreen（Android）
  - TR-2.5 路由 + VaultScreen + Dashboard 卡片（Android）
  - TR-2.6 FinanceViewModel v2 扩展
  - TR-2.7 strings.xml v2 文案 + 4 子类型 record 单测 ≥16 用例

---

## Task 3: 附件完整闭环（块存储 + UI 集成）（P0）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/AttachmentDao.kt`：
    Room 表 `finance_attachment`（`id / record_id / mime / size / 
    sha256 / encrypted_payload / schema_version / module`）+ 索引
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/AttachmentRepository.kt`：
    `upload(content: ByteArray, mime: String, recordId: String)` +
    `download(id: String): ByteArray` + `list(recordId: String): List<Attachment>`
    + `delete(id: String)`
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/AttachmentRecord.kt`：
    数据类 + 反序列化
  - 修改 `docs/module-schemas.md` §9：增加 `finance_attachment` 表 schema
  - 修改 `android/app/src/main/java/com/everything/eve/data/EveDatabase.kt`：
    `version = 7` + `MIGRATION_6_7`
  - 修改 `android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt`：
    `sealRecord/openRecord` 联动附件
  - 修改 `android/app/src/main/java/com/everything/eve/collector/CollectorWorker.kt`：
    附件块同步（pullAndDecrypt + pushChanges）
  - 新建 `web/src/finance/attachment.ts`：`uploadFile(file: File)` +
    `downloadFile(id: string)` + `listAttachments(recordId: string)` +
    `deleteAttachment(id: string)`
  - 新建 `web/src/views/finance/AttachmentViewer.vue`：PDF 走 PDF.js +
    图片走 `<img>`
  - 修改 Web 端 Policy / Contract / 任何带 attachments 的编辑器：增加附件
    上传按钮 + 列表
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/AttachmentUploader.kt`：
    Compose 上传组件（`ACTION_OPEN_DOCUMENT`）
  - 新建 `android/app/src/test/java/com/everything/eve/data/finance/AttachmentRepositoryTest.kt`：
    ≥6 用例（CRUD + 50MB 限制 + sha256 校验）
  - 新建 `android/app/src/test/java/com/everything/eve/data/finance/AttachmentPullDecryptTest.kt`：
    ≥4 用例
  - 新建 `web/src/finance/__tests__/attachment.spec.ts`：≥6 用例
- **TR 列表**:
  - TR-3.1 finance_attachment 表 + Room v6→v7 迁移
  - TR-3.2 AttachmentRepository + 附件块加密同步
  - TR-3.3 Web 端 attachment.ts + AttachmentViewer
  - TR-3.4 编辑器附件 UI 集成（双端）
  - TR-3.5 AttachmentRepositoryTest ≥6 + AttachmentPullDecryptTest ≥4 + 
    attachment.spec.ts ≥6 用例

---

## Task 4: 后 3 类提醒 + aggregator loan 启用（P0）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1 + Task 2
- **Description**:
  - 修改 `android/app/src/main/java/com/everything/eve/reminder/NextCardFiring.kt`：
    增加 `nextSubscriptionRenewal(...)` / `nextPolicyExpiry(...)` / 
    `nextLoanDue(...)` 三个纯函数（双锁定 fixture）
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt`：
    `monthlyReport(...)` 增加 `loans: List<LoanRecord>? = null` 入参
    `netWorth(...)` 启用应收借款纳入
  - 修改 `android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt`：
    `rebuildChain` 增加 3 类 finance 触发（订阅扣费 / 保单到期 / 借款到期）
  - 修改 `android/app/src/main/java/com/everything/eve/reminder/ReminderReceiver.kt`：
    `handleFinanceModule` 增加 3 类 kind 分支
  - 修改 `android/app/src/main/res/values/strings.xml`：增加 3 条 finance 
    提醒文案（`subscription_renewal_due_text` / `policy_expiry_due_text` /
    `loan_due_due_text`，**不渲染金额 / 续费 / 到期日期数字**）
  - 修改 `web/src/finance/nextCardFiring.ts`：镜像 3 类函数 + fixture
  - 修改 `web/src/finance/aggregator.ts`：镜像 aggregator 扩展
  - 修改 `web/src/stores/finance.ts`：scheduler 启用 3 类
  - 新建 `android/app/src/test/java/com/everything/eve/finance/NextSubscriptionRenewalTest.kt`：
    ≥6 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/NextPolicyExpiryTest.kt`：
    ≥6 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/NextLoanDueTest.kt`：
    ≥6 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/FinanceAggregatorV2Test.kt`：
    ≥6 用例（含 loan 应收借款纳入 + include_in_net_assets）
  - 修改 `android/app/src/test/java/com/everything/eve/reminder/ReminderSchedulerFinanceTest.kt`：
    +6 用例（3 类 kind + 链式调度验证）
  - 新建 `web/src/finance/__tests__/nextCardFiring-v2.spec.ts`：+6 用例
  - 新建 `web/src/finance/__tests__/aggregator-v2.spec.ts`：+6 用例
- **TR 列表**:
  - TR-4.1 nextSubscriptionRenewal + nextPolicyExpiry + nextLoanDue 纯函数
  - TR-4.2 aggregator loan 启用 + include_in_net_assets
  - TR-4.3 ReminderScheduler rebuildChain 扩展 3 类
  - TR-4.4 ReminderReceiver handleFinanceModule 3 类分支
  - TR-4.5 通知文案（3 条零知识）
  - TR-4.6 三端 fixture SHA-256 一致
  - TR-4.7 Android 单测 ≥30 + Web 单测 ≥12 用例

---

## Task 5: 多币种汇率 + aggregator 多币种折算（P1）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 `FinanceAggregator.monthlyReport(...)`
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/finance/RateTable.kt`：
    `data class RateTable(val effectiveTs: Long, val rates: Map<String, BigDecimal>)`
    + `convert(amount: BigDecimal, from: String, to: String): BigDecimal?`
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt`：
    `monthlyReport(...)` / `netWorth(...)` 增加 `targetCurrency: String` + 
    `rateTable: RateTable?` 入参 → 输出 `convertedAmount`
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/RateTableDao.kt`：
    Room 表 `finance_rate`（`id / currency_base / currency_quote / rate /
    effective_ts / encrypted_payload`）
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/RateTableRepository.kt`：
    `import(json: String, base: String)` + `latest(base: String): RateTable?`
  - 修改 `android/app/src/main/java/com/everything/eve/data/EveDatabase.kt`：
    `version = 8` + `MIGRATION_7_8`
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/FinanceViewModel.kt`：
    增加 rateTable state + import action
  - 新建 `android/app/src/main/java/com/everything/eve/ui/settings/RatesImportScreen.kt`：
    Compose 导入页（文件选择 + 解析预览 + 保存）
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/FinanceScreen.kt`：
    增加"默认币种"设置项
  - 修改 `android/app/src/main/res/values/strings.xml`：增加汇率相关文案
  - 修改 `android/app/src/main/AndroidManifest.xml`：无新增权限（汇率包
    走 SD 卡 / Document picker，与附件共用）
  - 镜像 Web 端：`web/src/finance/rateTable.ts` + `web/src/views/SettingsRatesView.vue`
    + `web/src/stores/finance.ts` 增加 rateTable state
  - 修改 `web/src/router/index.ts`：注册 `/settings/rates`
  - 新建 `android/app/src/test/java/com/everything/eve/finance/RateTableTest.kt`：
    ≥6 用例（convert / 缺失降级 / 自交叉）
  - 新建 `android/app/src/test/java/com/everything/eve/finance/FinanceAggregatorV2RateTest.kt`：
    ≥6 用例（USD 转 CNY / EUR 转 CNY / JPY 转 CNY / 缺失降级 / 默认币种 / 
    多账户跨币种）
  - 新建 `android/app/src/test/java/com/everything/eve/data/finance/RateTableRepositoryTest.kt`：
    ≥4 用例（导入 / 列表 / 删除 / 加密上行）
  - 新建 `web/src/finance/__tests__/rateTable.spec.ts`：≥6 用例
  - 新建 `web/src/finance/__tests__/aggregator-rate.spec.ts`：≥6 用例
- **TR 列表**:
  - TR-5.1 RateTable 纯函数 + convert
  - TR-5.2 aggregator 多币种折算
  - TR-5.3 finance_rate 表 + Room v7→v8 迁移
  - TR-5.4 RateTableRepository + 加密上行
  - TR-5.5 Android RatesImportScreen + 默认币种设置
  - TR-5.6 Web 端 rateTable + SettingsRatesView
  - TR-5.7 单测 ≥22 + Web 单测 ≥12 + 三端 fixture SHA-256

---

## Task 6: 预算硬约束 + 超支拦截（P1）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 `FinanceAggregator.monthlyReport(...)` + 任务编辑器
- **Description**:
  - 修改 `docs/finance.md`：增加"预算"章节（v2 spec FR-V2-F 详细化）
  - 修改 `docs/module-schemas.md` §9：增加 `type="budget"` 子类型 schema
  - 新建 `android/app/src/main/java/com/everything/eve/finance/BudgetRecord.kt`：
    数据类 + 反序列化 + 校验
  - 新建 `android/app/src/main/java/com/everything/eve/finance/BudgetEnforcer.kt`：
    纯函数 `check(tx: TxRecord, budgets: List<BudgetRecord>, monthlyTotal:
    Map<String, BigDecimal>): BudgetCheckResult`
    - `enum class BudgetCheckResult { OK, WARNING, BLOCK }`
    - 输出：`overspendPct / category / thresholdPct`
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceViewModel.kt`：
    保存 tx 前调用 `BudgetEnforcer.check` → BLOCK 弹确认对话框
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/BudgetConfirmDialog.kt`：
    Compose 对话框（"已用 105%，是否仍保存？"）
  - 新建 `web/src/views/finance/BudgetList.vue` + `BudgetEditor.vue`
  - 修改 `web/src/views/finance/FinanceTxList.vue`：保存 tx 前调用 enforcer
  - 新建 `web/src/views/finance/BudgetConfirmDialog.vue`：Web 端确认对话框
  - 修改 `web/src/stores/finance.ts`：增加 budgets state + enforcer 调用
  - 新建 `android/app/src/test/java/com/everything/eve/finance/BudgetEnforcerTest.kt`：
    ≥6 用例（OK / WARNING / BLOCK / 阈值边界 / 多分类并行 / 月初重置）
  - 新建 `android/app/src/test/java/com/everything/eve/ui/finance/BudgetConfirmDialogTest.kt`：
    ≥4 用例
  - 新建 `web/src/finance/__tests__/budgetEnforcer.spec.ts`：≥6 用例
- **TR 列表**:
  - TR-6.1 BudgetRecord + 校验
  - TR-6.2 BudgetEnforcer 纯函数（双端）
  - TR-6.3 Android 端 tx 保存拦截 + 确认对话框
  - TR-6.4 Web 端 Budget CRUD + tx 拦截
  - TR-6.5 单测 ≥16 + Web 单测 ≥6 用例

---

## Task 7: Web 端提醒（Web Notification API + Service Worker）（P1）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 4 + v1 既有 Service Worker
- **Description**:
  - 修改 `web/public/sw.js`：复用 v1 events 既有注册逻辑，增加 finance 
    通知通道（订阅扣费 / 保单到期 / 借款到期 3 类）
  - 新建 `web/src/notifications/financeNotifications.ts`：
    - `requestPermission()` / `scheduleNotification(record, dueTs)` / 
      `cancelNotification(recordId)`
    - IndexedDB 缓存本地 finance 明文 + 触发时刻（不上行）
  - 修改 `web/src/views/finance/FinanceView.vue`：增加"允许浏览器通知"开关
  - 修改 `web/src/stores/finance.ts`：scheduler 启用 3 类（订阅扣费 / 
    保单到期 / 借款到期）
  - 新建 `web/src/notifications/__tests__/financeNotifications.spec.ts`：
    ≥4 用例
  - 修改 `web/src/views/__tests__/FinanceView.spec.ts`：通知开关 UI ≥2 用例
- **TR 列表**:
  - TR-7.1 Service Worker finance 通道注册
  - TR-7.2 financeNotifications.ts（schedule / cancel / permission）
  - TR-7.3 FinanceView 通知开关 UI
  - TR-7.4 Web 单测 ≥6 用例

---

## Task 8: 投资账户 + 手动行情（P2）

- **Status**: `pending`
- **Priority**: medium
- **Depends On**: v1 `FinanceAggregator.netWorth(...)` + Task 5
- **Description**:
  - 修改 `android/app/src/main/java/com/everything/eve/finance/InvestmentAccountRecord.kt`：
    数据类 + holdings 字段
  - 新建 `android/app/src/main/java/com/everything/eve/finance/QuoteTable.kt`：
    `data class QuoteTable(val ts: Long, val quotes: Map<String, BigDecimal>)`
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt`：
    `netWorth(...)` / `accountBalance(...)` 增加 `quoteTable: QuoteTable?` 
    入参 → 投资账户 `value_minor = sum(shares * current_price_minor)`
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/QuoteTableDao.kt`：
    Room 表 `finance_quote` + 索引
  - 新建 `android/app/src/main/java/com/everything/eve/data/finance/QuoteTableRepository.kt`：
    `syncFromEndpoint(url: String, base: String)` + `latest(): QuoteTable?`
  - 修改 `android/app/src/main/java/com/everything/eve/data/EveDatabase.kt`：
    `version = 9` + `MIGRATION_8_9`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/settings/QuotesSyncScreen.kt`：
    Compose 同步页（端点配置 + 同步按钮 + 列表）
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/FinanceDashboard.kt`：
    新增"投资账户"卡片（市值 + 持仓 top 5 + 同步行情按钮）
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/FinanceTxList.kt`：
    月报页新增"投资账户市值变化"行
  - 镜像 Web 端：`web/src/finance/quoteTable.ts` + 
    `web/src/views/SettingsQuotesSyncView.vue` + Dashboard 投资卡片
  - 修改 `web/src/router/index.ts`：注册 `/settings/quotes`
  - 新建 `android/app/src/test/java/com/everything/eve/finance/QuoteTableTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/FinanceAggregatorV2QuoteTest.kt`：
    ≥4 用例（投资账户市值聚合）
  - 新建 `android/app/src/test/java/com/everything/eve/data/finance/QuoteTableRepositoryTest.kt`：
    ≥4 用例（HTTP 拉取 + 解析 + 加密上行）
  - 新建 `web/src/finance/__tests__/quoteTable.spec.ts`：≥4 用例
- **TR 列表**:
  - TR-8.1 InvestmentAccountRecord + QuoteTable 纯函数
  - TR-8.2 aggregator 投资账户市值聚合
  - TR-8.3 finance_quote 表 + Room v8→v9 迁移
  - TR-8.4 QuoteTableRepository + HTTP 同步
  - TR-8.5 Android QuotesSyncScreen + Dashboard 投资卡片 + TxList 月报行
  - TR-8.6 Web 端 quoteTable + SettingsQuotesSyncView
  - TR-8.7 单测 ≥12 + Web 单测 ≥4 用例

---

## Task 9: AI 联动记账（OCR + 语音）（P2）

- **Status**: `pending`
- **Priority**: medium
- **Depends On**: v1 tx 编辑器
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/finance/OcrRecognizer.kt`：
    on-device ML Kit Text Recognition（`com.google.mlkit:text-recognition`
    AAR，自包含零 GMS）+ 启发式解析（金额 / 日期 / 商家名）
    - 纯函数 `parseReceiptText(text: String): ReceiptHint?`
    - `data class ReceiptHint(val amountMinor: Long?, val ts: Long?, 
      val merchant: String?)`
  - 新建 `android/app/src/main/java/com/everything/eve/finance/SpeechRecognizer.kt`：
    Android SpeechRecognizer on-device 模式 + 启发式意图解析
    - 纯函数 `parseSpeechText(text: String): SpeechHint?`
    - `data class SpeechHint(val amountMinor: Long?, val category: String?, 
      val ts: Long = System.currentTimeMillis())`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerSheet.kt`：
    Compose CameraX 拍照 + OCR 预览
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderSheet.kt`：
    Compose 录音 + 识别预览
  - 修改 `android/app/src/main/java/com/everything/eve/ui/finance/TxEditorScreen.kt`：
    增加"扫描小票" / "语音记账"按钮 → 预填 Editor 字段
  - 修改 `android/app/src/main/java/com/everything/eve/finance/FinanceViewModel.kt`：
    增加 ocrHint / speechHint state + actions
  - 修改 `android/app/src/main/AndroidManifest.xml`：增加 `CAMERA` /
    `RECORD_AUDIO` 权限
  - 修改 `android/app/src/main/res/values/strings.xml`：增加 OCR / 语音
    权限说明文案 + "扫描小票" / "语音记账"按钮文案
  - 新建 `android/app/src/test/java/com/everything/eve/finance/OcrParserTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/finance/SpeechParserTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/ui/finance/OcrScannerSheetTest.kt`：
    ≥4 用例（Robolectric + ComposeTestRule）
  - 新建 `android/app/src/test/java/com/everything/eve/ui/finance/SpeechRecorderSheetTest.kt`：
    ≥4 用例
  - **Web 端不做 OCR / 语音**：仅文本输入 + iCal 导入（v1 既有）
  - **真机冒烟（v2 推荐执行，FU-7 同款）**：docs/smoke/finance-v2-ai-manual.md
    ≥6 场景
- **TR 列表**:
  - TR-9.1 OcrRecognizer + parseReceiptText 纯函数
  - TR-9.2 SpeechRecognizer + parseSpeechText 纯函数
  - TR-9.3 Android OCR Scanner Sheet + CameraX 集成
  - TR-9.4 Android Speech Recorder Sheet + SpeechRecognizer 集成
  - TR-9.5 TxEditor 预填按钮 + 权限申请 + 文案
  - TR-9.6 单测 ≥16 用例（含 Robolectric）
  - TR-9.7 真机冒烟手册（FU-7 同款 ≥6 场景）

---

## Task 10: 文档同步 + 门禁复跑（最终）（P0）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T1~T9
- **Description**:
  - 修改 `docs/finance.md`：v2 增量章节（4 子类型 / 附件 / 多币种汇率 /
    投资账户 / 预算 / Web 提醒 / AI 联动）
  - 修改 `docs/crypto.md`：附件 envelope 章节（v2 附件走 records 通道扩展
    + type="attachment" 子标识 + sha256 校验 + 50MB 限制）
  - 修改 `docs/android.md`：v2 增量（CameraX / SpeechRecognizer / ML Kit /
    OCR / 语音权限说明 + 真机冒烟指南）
  - 修改 `docs/module-schemas.md` §9：v2 字段扩展（4 子类型 + attachment 
    表 + rate 表 + quote 表 + budget 子类型 + include_in_net_assets）
  - 修改 `README.md`：v2 功能矩阵扩展 + 已知问题补充
  - 修改 `everything_plan.md`：阶段 5 v2 行勾选 ✅ + v3 候选列出
  - 门禁复跑：Android `testDebugUnitTest` + `assembleDebug` + Web `vitest` 
    + `vue-tsc` + 零知识 grep + 20 AC 映射
  - 新建 `docs/smoke/stage5-finance-v2-e2e.md`：≥15 场景冒烟
- **TR 列表**:
  - TR-10.1 docs/finance.md v2 增量
  - TR-10.2 docs/crypto.md + docs/android.md + module-schemas.md v2 增量
  - TR-10.3 README + everything_plan 同步
  - TR-10.4 端到端冒烟脚本 ≥15 场景
  - TR-10.5 三端门禁复跑全绿 + 20 AC 映射 + 零知识 grep

---

## 经验汇总（v1 → v2 关键决策）

1. **不重做 v1 架构**：v2 仅在 v1 既有组件上做增量；v1 单测零回归为硬约束。
2. **不引入 GMS / Firebase / 第三方 SDK**：OCR 用 ML Kit 自包含 AAR；语音用
   Android SpeechRecognizer on-device；行情走自托管 HTTP / RSS；汇率走手动导入。
3. **附件延后到 v2**：v1 占位字段 `attachments: [{id, mime, size, sha256}]` 在
   v2 启用，完整闭环。
4. **不引入实时 API**：汇率 / 行情均手动触发，避免服务端零知识约束破坏。
5. **P0/P1/P2 三档优先级**：P0 必做（4 子类型 + 附件 + 后 3 类提醒）；P1 建议
   （多币种 / 预算 / Web 提醒）；P2 可选（投资 / AI 联动）。
6. **服务端零增量**：所有 v2 能力走 v1 records 通道；服务端零聚合原则延续。
7. **Web 端不做 OCR / 语音**：避免跨设备照片 / 语音同步，符合零知识。

## 度量目标

- v1 零回归：22 Android suite / 190 tests / 20 Web files / 268 tests 全绿。
- v2 新增：≥ 120 Android test + ≥ 80 Web test + ≥ 40 fixture 用例（含
  OCR / 语音 instrumented）。
- APK 体积变化：release APK 增量 ≤ 8 MB（ML Kit + CameraX + Speech）。
- 三端门禁 + 20 AC 映射 + 零知识 grep + 文档同步全过。