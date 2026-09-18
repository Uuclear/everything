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
| B4 | P0 | T4 | 后 3 类提醒链路（订阅/保单/借款）+ aggregator loan 启用 | T1 + T2（含 TR-2.6） |
| B5 | P1 | T5 | 多币种汇率 + aggregator 多币种折算 | v1 aggregator |
| B6 | P1 | T6 + T7 | 预算硬约束 + Web 端提醒 | v1 既有提醒 |
| B7 | P2 | T8 + T9 | 投资账户 + 手动行情 | v1 aggregator |
| B8 | P2 | T9 | AI 联动记账（OCR + 语音） | v1 tx 编辑器 |
| 收尾 | P0 | T10 | 文档同步 + 端到端冒烟 + 门禁复跑 | T1~T9 |

---

## Task 1: 4 子类型纯函数 + Web 端 4 编辑器（P0）

- **Status**: `completed`
- **Priority**: high
- **Depends On**: v1 `FinanceAggregator.kt` / `web/src/finance/types.ts`
- **Completion Evidence**:
  - **Pass Condition**: 4 子类型 schema 完整化 + Android 4 record + Web 端 4 编辑器 + 4 列表 + 路由 + Dashboard + store CRUD + 4 list spec ≥16 用例；Web 端门禁全绿（vitest 461/461 + vue-tsc 0 error + vite build 0 error）。
  - **Status**: `completed`（2026-09-16 提交 `7680532`，commit 链 `ec11631..7680532` 推送 main 成功）。
  - **Completion Evidence**:
    - Android 端：`FinanceRecords.kt`（含 `SubscriptionRecord / PolicyRecord / LoanRecord / ContractRecord` 4 record + 4 `validate*` + 工具函数 `isValidDecimalString / isValidDecimalNonNegative / isValidCurrencyCode / isValidSha256Hex` + `ValidationResult` sealed + `AttachmentRef` + `FINANCE_V2_SCHEMA_VERSION=2` + `ATTACHMENT_MAX_SIZE_BYTES=50MB`）。
    - Web 端：`web/src/finance/types.ts` 扩展 4 子类型 + `web/src/stores/finance.ts` v2 扩展（12 CRUD + 4 list computed + byId 路由 + `assertValidV2` + `payloadTypeOf` + hydrate v1 兼容 + persist schemaVersion=2）+ 4 个 `.vue` List + 4 个 `.vue` Editor（`SubscriptionList.vue / SubscriptionEditor.vue / PolicyList.vue / PolicyEditor.vue / LoanList.vue / LoanEditor.vue / ContractList.vue / ContractEditor.vue`）+ `FinanceDashboard.vue` 4 卡片 + `AppShell.vue` 4 菜单项 + `router/index.ts` 注册 4 类 list + 4 editor 子路由。
    - 文档：`docs/finance.md` + `docs/module-schemas.md` §9 schema 完整化 + `web/src/finance/__tests__/types.spec.ts` 4 子类型 schema 校验。
    - 单测：`web/src/views/finance/__tests__/` 新建 `FinanceContractList.spec.ts`（16 用例）+ `FinanceSubscriptionList.spec.ts`（≥16）+ `FinancePolicyList.spec.ts`（≥16）+ `FinanceLoanList.spec.ts`（≥16）+ 编辑器 4 个 spec（含 schema/CRUD/列表渲染）。
    - 门禁：`vitest 461/461` + `vue-tsc 0 error` + `vite build 0 error`。
    - Diff：commit `7680532` 23 files / +4819 / -43。
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

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Completion Evidence**:
  - **Pass Condition**: 8 个 Compose Screen + VM v2 state + 8 CRUD actions + routes 4 tab + 4 editor + Dashboard 4 卡片 + strings 64 条 + 4 record test 64 用例 + VM v2 test 4 用例；Android 端门禁全绿（gradlew testDebugUnitTest 299/299 + 0 compile error）。
  - **Status**: `completed`（2026-09-16 提交 `f526800`，commit 链 `7680532..f526800` 推送 main 成功）。
  - **Completion Evidence**:
    - 8 个 Compose Screen：`SubscriptionListScreen.kt / SubscriptionEditorScreen.kt / PolicyListScreen.kt / PolicyEditorScreen.kt / LoanListScreen.kt / LoanEditorScreen.kt / ContractListScreen.kt / ContractEditorScreen.kt`，函数签名 `fun XxxListScreen(vm, onAdd, onEdit)` / `fun XxxEditorScreen(id, vm, onClose)`；零知识纪律：金额 `R.string.finance_dashboard_amount_mask`（`****`）+ 日期 `daysUntil(ts)` 相对天数；下拉菜单改用 `Box + DropdownMenu + OutlinedTextField(.clickable)` 锚定模式（ExposedDropdownMenuBox 在该项目 Material3 版本下编译失败）。
    - VM v2：`FinanceViewModel.kt` 扩展 4 state 字段（subscriptions / policies / loans / contracts 默认 `emptyList`）+ 8 CRUD（upsert/delete × 4 子类型，upsert 前调 `FinanceRecords.validate*`，失败返回 `Result.failure(IllegalArgumentException(reason))` + `_eventChannel.trySend(Error(...))`）+ 4 EditorBuffer（`SubscriptionEditorBuffer / PolicyEditorBuffer / LoanEditorBuffer / ContractEditorBuffer`）+ `combine` 改造为 vararg（11 Flow<Any?> + transform 保留 v1 7 流 index 0~6 顺序）；4 个 `MutableStateFlow<List<*>>` 内存数据源（标注 `TODO(B3)` 替换 Room Flow）。
    - 路由：`FinanceRoutes.kt` 新增 4 tab 常量（`TAB_SUBSCRIPTIONS / TAB_POLICIES / TAB_LOANS / TAB_CONTRACTS`）+ 4 editor 常量（`EDITOR_SUBSCRIPTION / EDITOR_POLICY / EDITOR_LOAN / EDITOR_CONTRACT`），v1 既有 9 项保持不动。
    - Dashboard：`FinanceDashboard.kt` 新增 4 v2 卡片（即将续费订阅 30 天内 / 即将到期保单 90 天内 / 待还借款未结清 / 即将结束合同 90 天内）+ `V2DashboardCard` Composable；testTag 命名 `dashboard_v2_<type>`。
    - 文案：`res/values/strings.xml` 新增 64 条 v2 string resource（finance_v2_sub_* ≥10 / finance_v2_policy_* ≥10 / finance_v2_loan_* ≥10 / finance_v2_contract_* ≥10 / finance_v2_dashboard_* 4 / finance_v2_*_hint_within_days 4）。
    - 单测：`SubscriptionRecordTest.kt / PolicyRecordTest.kt / LoanRecordTest.kt / ContractRecordTest.kt` 各 16 用例（4 describe × 4 it）+ `FinanceViewModelV2Test.kt` 4 用例（upsertSubscription 合法 / upsertLoan 非法 / deletePolicy 存在 / upsertContract 关联字段）；VM 测试用 JDK 反射 + `sun.misc.Unsafe` 桩 FinanceRepository 4 DAO + `TestApplication` 自引用 + `runBlocking { vm.state.first() }` 触发 collect。
    - 门禁：`gradlew testDebugUnitTest 299/299`（v1 295 零回归 + v2 4 record × 16 + VM v2 4 = 68 新增）+ `compileDebugKotlin 0 error`；web 端 `vitest 461/461` + `vue-tsc 0 error` + `vite build 0 error` 全绿。
    - Diff：commit `f526800` 17 files / +5013 / -4。
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

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1
- **Completion Evidence**:
  - **Pass Condition**: finance_attachment Room 表 + v6→v7 迁移 + AttachmentRepository（upload/download/delete/pull/push）+ records 通道 type=attachment 零新协议 + 双端附件 UI（Web PDF.js/图片预览 + Android SAF/系统查看器）+ 单测 ≥16 用例；五门禁全绿（Android 316/316 + compileDebugKotlin；web 488/488 + vue-tsc 0 + vite build）。
  - **Status**: `completed`（2026-09-17 提交 `04e1328`，commit 链 `4bd39c0..04e1328`，待推送 main）。
  - **Completion Evidence**:
    - TR-3.1：`AttachmentEntity.kt`（finance_attachment 表：id/record_id/mime/size/sha256/encrypted_payload/schema_version/module/created_at/updated_at/dirty/deleted；record_id 索引）+ `AttachmentDao.kt` 11 方法（upsert/getById/observeByRecordId/dirtyList/markDirty/markDeleted 等）；`EveDatabase.kt` version 7 + `MIGRATION_6_7`（CREATE TABLE + INDEX）。注：Entity 的 dirty/deleted 用 Int(0/1) 与既有 FinanceAccountEntity 的 Boolean 风格不同，Repository 内按 Int 口径处理。
    - TR-3.2：`AttachmentRepository.kt` upload（size>0 / ≤50MB / sha256 64hex 前置校验 → CryptoEnvelope.sealRecord 本地块密文 → AttachmentEntity 双写 → recordsRepository.upsertFinanceAttachment 元数据密文）/ download（openRecord + 端侧 sha256 再校验，不一致 SecurityException）/ delete（markDeleted + tombstone `{"id","deleted":true}`）/ pullAndDecrypt（墓碑优先；非墓碑解元数据 JSON 入 Room，dirty=0）/ pushChanges（dirtyList → records 通道 → markDirty 0）；**零依赖 envelope 扩展**——AAD 仍 `eve:v1:record:{id}:finance:BE(uint64 version)`，type=`attachment` 子标识；`RecordsRepository` 标 `open class` + `upsertFinanceAttachment`/`decryptFinanceAttachment` 标 open（仅供单测桩子类化，生产语义不变）；`FinanceModule.TYPE_ATTACHMENT` 常量；`CollectorWorker` 尾部 financeRepo.pull 后挂 attachment pull+push；`ServiceLocator.attachmentRepo` 懒初始化。
    - TR-3.3 Web：`web/src/finance/attachment.ts`（uploadFile/downloadFile/deleteFile/sha256Hex/白名单 MIME/50MB 上限；新增依赖 `pdfjs-dist@^4.0.0` 动态 import，worker 走 `new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).href`，首页 canvas 渲染）+ `AttachmentViewer.vue`（PDF/图片/其他三态，下载/删除，naive-ui）。
    - TR-3.4 双端 UI：Android `AttachmentList.kt`（LazyColumn + 点击系统 ACTION_VIEW 查看，cacheDir 副本 + FileProvider 豁免路径规避 FileUriExposedException）+ `AttachmentUploader.kt`（SAF `ACTION_OPEN_DOCUMENT` 零新 Manifest 权限，takePersistableUriPermission）+ VM `bindAttachmentRepository/addAttachment/removeAttachment/attachmentsByRecordId`（viewModelScope 异步上传，同步返回 pending AttachmentRef）+ Policy/Contract 编辑器附件区 + strings；Web store `attachments/attachmentsByRecordId/attachmentCipherCache` 三索引 + `uploadAttachment` 包装（uploadFile 的 onUploaded 回调回灌元数据）+ `getAttachmentsForRecord` cipher 缺失也返回 + ingest 明文 Uint8Array 三段类型收窄；Policy/Contract 编辑器挂 AttachmentViewer。
    - TR-3.5：Android `AttachmentRepositoryTest.kt` 8 用例（happy path + 50MB/0 字节/sha256 长度/sha256 非 hex + download 字节一致 + sha256 篡改 SecurityException + tombstone）+ `AttachmentPullDecryptTest.kt` 5 用例（空列表/有效记录入库/墓碑 markDeleted/非 attachment type 跳过/跨 module 跳过）+ `FinanceViewModelAttachmentTest.kt` 4 用例（addAttachment 合法字节/upload 失败转 Error 事件/removeAttachment/Flow 转发）= 17；Web `attachment.spec.ts`（上传/下载/删除/sha256/上限/MIME）+ `AttachmentViewer.spec.ts`（预览/上传集成/删除）。测试基建三处修复：①`testOptions.unitTests.isReturnDefaultValues=true` + `testImplementation(org.json:json:20240303)` 真实实现覆盖 android.jar JSONObject stub（否则链式 put 抛 RuntimeException/NPE）；②`kotlinx-coroutines-test:1.9.0` + 测试内 `Dispatchers.setMain(UnconfinedTestDispatcher())`/resetMain（viewModelScope 无 main looper）；③RecordsRepository 桩从 java.lang.reflect.Proxy（只能代理 interface）改为 open 子类 override。
    - 门禁：Android `gradlew testDebugUnitTest 316/316`（0 failure / 0 ignored，较 B2 的 299 净增 17 = 8+5+4 全部来自 B3 三个新测试类）+ `compileDebugKotlin 0 error`；Web `vitest 32 files/488 tests`（较 B2 的 461 净增 27）+ `vue-tsc --noEmit exit 0` + `vite build exit 0`（13.88s）。
    - Diff：commit `04e1328` 28 files / +5520 / -13（11 个 Android 新增文件 + 4 个 Web 新增文件 + 13 个修改文件；临时脚本 `.trae/parse-junit.ps1`、`tmp_calculate_times.ps1` 明确排除未提交）。
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
    `sealRecord/openRecord` 联动附件（附件块**复用** v1 records 通道，
    `type="attachment"` 子标识 + AAD `module="finance" + type +
    attachment_id`，**不新建独立协议通道**）
  - 修改 `android/app/src/main/java/com/everything/eve/collector/CollectorWorker.kt`：
    附件块同步（pullAndDecrypt + pushChanges，复用 records 通道加密上行）
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

- **Status**: `completed`
- **Priority**: high
- **Depends On**: Task 1 + Task 2
- **Completion Evidence**:
  - **Pass Condition**: 3 个 v2 提醒纯函数双端镜像 + aggregator loan 启用（include_in_net_assets）+ Scheduler/Receiver 3 类分支 + 3 条零知识文案 + 三端 fixture SHA-256 一致 + 单测 Android ≥30 / Web ≥12；五门禁全绿（Android 366/366 + compileDebugKotlin；web 513/513 + vue-tsc 0 + vite build）。
  - **Status**: `completed`（2026-09-18 提交 `67c18ef`，待推送 main）。
  - **Completion Evidence**:
    - TR-4.1：`NextCardFiring.kt` 增 `nextSubscriptionRenewal`（renewal≤now 按 monthly+1 月/quarterly+3 月/yearly+1 年/custom_days+N 天系统时区日历滚动，保留时分秒；未知 cycle 基准过期返 null；24 周期防御上限）/ `nextPolicyExpiry`（expiry 一次性不滚动）/ `nextLoanDue`（仅 status=paid 早退，未知 status 照常提醒）+ SubscriptionLike/PolicyLike/LoanLike DTO；候选统一 `base - r*60000`（r≥0、严格 >now 取最小）；web `nextCardFiring.ts` 字节级镜像 + 3 个 toXxxLike 适配器，语义零差异。
    - TR-4.2：双端 `netWorth(..., loans=[])` 新增末位默认参——`includeInNetAssets=false` 跳过；剩余本金 = principal−paid 钳位 ≥0；direction=lent 计资产、borrowed 计负债，净资产恒等式自然得 "net += lent − borrowed"；DashboardSnapshot 七字段结构不变（不新增 loanCount）；`monthlyReport(..., loans=[])` 加预留入参（本金非 income/expense 不累加，Task 5 多币种折算预留）。
    - TR-4.3：`ReminderScheduler.rebuildChain` 新增 v2 取数层 `loadV2Trigger`（records 表 getActiveByModuleType 三类 → decryptFinanceV2 → V2PayloadCodec.decode → Like DTO，逐条 runCatching 容错）+ 纯函数 `selectBestV2Trigger`（triggerTs/kindRank/id 字典序）；event/card/v2 三源统一 (ts, rank) 合并，tie-break 固定 event(0)>card(1)>subscription(2)>policy(3)>loan(4)，与旧双分支行为等价；胜出仍走 `scheduleNextFinance` 同一 requestCode 单闹钟，严禁新链路；全空走原 cancel 路径。
    - TR-4.4：`ReminderReceiver` 启用集合扩为 5 类，新增 `handleV2FinanceModule`——records.getById 取行校验 module=finance/deleted=0 → 解密 decode → 按 kind 二次校验（subscription/policy 须 active=true，loan 须 status≠paid）→ 抽象文案；记录删除/解密失败/状态失效静默忽略且链式 rebuildChain 仍触发。
    - TR-4.5：strings.xml 三条零知识文案——订阅续费临近/保单即将到期/借款到期临近，"点击查看"收尾；不含金额、续费/到期日期、对手方、保单号、名称任何业务字段。
    - 配套债务清理（recon 发现 B2 v2 仅内存 CRUD，重启后调度无数据源）：新建 `V2PayloadCodec.kt`（四类 camelCase↔snake_case 明文 JSON，字段与 web types.ts 逐一对齐，缺字段容错不抛）；`RecordsRepository` 增 `upsertFinanceV2`/`upsertFinanceV2Tombstone`/`decryptFinanceV2`（复用 v1 envelope，AAD `eve:v1:record:{id}:finance:BE(version)` 不变）；RecordDao 增 `getActiveByModuleType`；FinanceModule 增 4 个 TYPE 常量；VM 四 upsert/delete 还清 8 处 TODO（内存语义与返回值不变，viewModelScope best-effort 密封上行 + 墓碑，init hydrateV2 下行回填）；FinanceViewModelV2Test 补 Dispatchers.setMain 基建。
    - TR-4.6：双端共享 fixture 两份经 Get-FileHash 实测字节级一致——`next-v2-firing-cases.json` SHA-256=`c4ea61f7cc17c177ec93fc8cafc4b0cb616d8267ca79eb77bc17440f26edcb76`（15 case：sub 8/policy 4/loan 3，锚点 now=1782619200000 = 2026-06-28 12:00 CST）；`aggregator-v2-cases.json` SHA-256=`9e5fd8761458df070b7cd6b9e362cdd4b9869cdfb4cba6b4fa6ab9a5b291db88`（9 case）；Android 四测试类 companion 硬编码 hash + MessageDigest 断言，web 两 spec 用 node:crypto/Vite ?raw 双路径断言同常量。
    - TR-4.7：Android 新增 **50** 用例（NextSubscriptionRenewalTest 12 + NextPolicyExpiryTest 10 + NextLoanDueTest 11 + FinanceAggregatorV2Test 10 + ReminderSchedulerFinanceTest 追加 7，要求各 ≥6 全满足），全量 **366/366**（较 B3 的 316 净增 50）；Web 新增 **25** 用例（nextCardFiring-v2.spec 16 + aggregator-v2.spec 9），全量 **513/513 / 34 files**（较 B3 的 488 净增 25）。
    - 门禁：Android `testDebugUnitTest 366/366`（0 failure）+ `compileDebugKotlin 0 error`；Web `vitest 513/513` + `vue-tsc --noEmit exit 0` + `vite build exit 0`。
    - Diff：commit `67c18ef` 25 files / +4660 / −62（11 新增：1 codec + 4 Android 测试 + 2 Android fixture + 2 web fixture + 2 web spec；14 修改；临时脚本 `.trae/parse-junit.ps1`、`tmp_calculate_times.ps1` 明确排除）。
    - 已知边界（v3 候选）：hydrateV2 为 VM init 一次性拉取，存活期内多端同步下行需 VM 重建刷新；Web 端仅纯计算 upcomingV2Reminders 出口，真浏览器通知属 G-7；loan 分期 repayment_installment 按 spec 延后 v3。
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

- **Status**: `completed`
- **Priority**: high
- **Depends On**: v1 `FinanceAggregator.monthlyReport(...)`
- **Completion Evidence**:
  - **Pass Condition**: RateTable 纯函数（parse/convert/自交叉/缺失降级）双端镜像 + aggregator targetCurrency 折算 + finance_rate 表 Room v7→v8 + 加密离线汇率包导入（records 通道 type=rate）+ 双端设置页/默认币种 + 看板折算接入 + 单测 Android ≥22 / Web ≥12 + fixture SHA-256 一致；五门禁全绿（Android 400/400 + compileDebugKotlin；web 556/556 + vue-tsc 0 + vite build）。
  - **Status**: `completed`（2026-09-19 提交 `d260ce7`，待推送 main）。
  - **Completion Evidence**:
    - TR-5.1：Android 新建 `finance/RateTable.kt`——`RateTable(effectiveTs, rates: Map<String,Double>)` + `RateTables.parse(json)`（version 缺省接受/显式≠1 拒绝、effective_ts 必填非负、rates≥1、key 严格 `^[A-Z]{3}/[A-Z]{3}$` 且 from≠to、rate 有限且 >0，中文异常）+ `convert(amountMinor, from, to, table): Long?`（自交叉直通 → 正向乘 → 倒数除 → null；BigDecimal setScale HALF_UP）+ `convertOrIdentity`（null 面值 1:1 降级）；金额全程 minor Long；web `rateTable.ts` 字节级镜像（parseRateTable/convertMinor/convertMinorOrIdentity）。**负数舍入双端一致性**：绝对值先取整再恢复符号（HALF_UP ≡ Math.round 仅在非负成立；fixture 锁定 −2×7.25→−15、−101×1.25→−126.25→−126、−9300÷0.93→−10000 三例）。
    - TR-5.2：双端 `netWorth(..., loans=[], targetCurrency="CNY", rateTable=null)` / `monthlyReport(...)` 末位默认参（B4 loans 之后，既有位置/具名调用零回归）；私有 toTarget 统一折算账户（acc.currency）/卡 usedLimit（CardLike 增 currency 默认 CNY）/loan 剩余本金（lent 资产/borrowed 负债）/月报逐笔 tx（TxLike 增 currency 默认 CNY；FinanceTx schema 无 currency 字段，按 CNY）；缺汇率或 table=null → 面值 1:1；DashboardSnapshot 增 `targetCurrency`（Android data class 默认参恒携带；Web 条件性出键保既有严格 toEqual 快照），原 currency 字段语义不变。
    - TR-5.3：`FinanceRateEntity`（finance_rate 12 列：id/currency_base/currency_quote/rate REAL/effective_ts/encrypted_payload/schema_version/module/created_at/updated_at/dirty/deleted；确定性 id=`${pair}@${effectiveTs}`）+ `FinanceRateDao`（11 方法：upsert/upsertAll/getById/latestEffectiveTs/listByEffectiveTs/observeLatest/dirtyList/markDirty/markDeleted/markClean/deleteAll）；EveDatabase `version=8` + entities 注册 + `MIGRATION_7_8`（建表 + pair/effective_ts/dirty 三索引）；工程无 MigrationTestHelper 测试传统，按既有迁移测试惯例不新增。
    - TR-5.4：`RateTableRepository`——importPackage（parse 校验 → recordsRepository.upsertFinanceV2(type="rate", id=`rate@${ts}`) 整包密封 AAD 不变 → 按货币对拆行 upsertAll，encrypted_payload 空串占位避免二次封包双 nonce）/ latest 两步查拼表 / observeLatestTable / pullAndDecrypt（module+type 兜底过滤、墓碑跳过、逐条解密 parse 容错、下行写真实 ciphertext dirty=0）/ pushChanges（按 ts 分组取全行 TreeMap 重建稳定 JSON 兜底 upsert + 逐行 markDirty 0）；ServiceLocator.rateTableRepository 懒初始化；CollectorWorker 附件段之后挂 rate pull+push（异常吞掉不阻断）；FinanceModule.TYPE_RATE 常量。
    - TR-5.5：Android `FinanceSettings`（eve-finance SharedPreferences 非敏感：defaultCurrency 默认 CNY + 3 大写校验 + 5 预设 CNY/USD/EUR/JPY/HKD）；`RatesImportScreen`（ui/settings 包，Scaffold+TopAppBar 返回；默认币种 FilterChip 5 预设 + 手填 TextField 大写截断 3 字符；当前包生效日期/货币对数状态卡；SAF OpenDocument JSON 选择 IO 协程读文本；LazyColumn 汇率明细；修复一处 remember 误置 LazyListScope 编译错误——上移至组合函数体）；FinanceRoutes 常量 + FinanceScreen settingsMode 全屏承载（仿编辑器骨架）；VM 增 rateTableState/defaultCurrencyState（stateIn 大包扩到 13 流）+ importRateTable（viewModelScope best-effort）+ setDefaultCurrency + init hydrateRateTable（ServiceLocator.latest）；FinanceDashboard 顶部"默认币种▾/汇率包设置"两入口 + 6 参聚合（含 B4 LoanLike 适配）+ 目标币后缀；strings 16 条文案（零知识：汇率公开但提示仍走加密通道）；无新增 Manifest 权限（SAF 与附件共用）。
    - TR-5.6：Web 新建 `rateTable.ts`；`SettingsRatesView.vue`（默认币种 NSelect 5 预设+手填、当前包状态+货币对表、file accept JSON 导入 NMessage 反馈、Popconfirm 移除、离线手动导入说明卡）；路由 `/finance/settings/rates`（name finance-settings-rates，挂 /finance AppShell children）；store 增 rateTable/defaultCurrency/rateRecordVersions 三状态 + 持久化（PersistedFinanceState 三可选字段，**不升 schemaVersion 保持 2**，旧数据 restoreRateTable/restoreRateVersions 安全降级，reset 清空不跨账号）；Dashboard 6 参折算 + 顶部入口 + 无包"按面值计入"提示。**FR-V2-C.2 加密上行缺口闭合**：SA-3 初版仅 localStorage 本地持久化，主流程复核发现与 spec"加密上行 records 通道 type=rate"及 Android 实现不对等——补 importRateTable 异步密封 channel.seal→push（确定性 id rate@ts、version 按 rateRecordVersions 严格递增、skipped 版本竞争触发 pullAll(0) 全量补拉、通道异常 synced=false 但本地不回滚）+ ingest 顶部 type=rate 分支（open 解密→parseRateTable 校验→取最大 effectiveTs→版本表记录→persist；坏包静默跳过；墓碑忽略）；CryptoChannel.seal 入参放宽为 `FinancePayloadAll | Record<string, unknown>`（默认实现 JSON.stringify 零行为变化）；UI 按 synced 显示"已加密同步/本地已生效待补推"。
    - TR-5.7：Android 新增 **34** 用例（RateTableTest 18：fixture 11 convert 段全驱动 + parse 非法 8 边界 + 负数符号 + 大数；FinanceAggregatorV2RateTest 10：fixture 9 case + 月报折算；RateTableRepositoryTest 6：合法导入密封拆行/非法零写入/同 ts 幂等覆盖/pull 过滤解密/push 重建翻干净/空 dirty），全量 **400/400**（较 B4 的 366 净增 34）；Web 新增 **43** 用例（rateTable.spec 16 + aggregator-rate.spec 12 + finance-rates store spec 15：导入/失败/离线 synced=false/币种校验/hydrate 往返/旧数据三字段降级/版本表防篡改/清除/加密上行 id+version 递增/下行 ingest 成功与坏包跳过），全量 **556/556 / 37 files**（较 B4 的 513 净增 43）。
    - TR-4.6 式 fixture 核验：`rate-table-cases.json` 双端 Get-FileHash 实测一致 SHA-256=`7a1c77af553595423a3d0a338421e00eb7c0f59ca7b906d9768faf3abb2aea12`（21 case：11 convert + 8 aggregator + 2 monthlyReport，锚点 now=1782619200000）；Android MessageDigest + web node:crypto/Vite ?raw 双路径断言同常量；LF 无 BOM。
    - 门禁：Android `testDebugUnitTest 400/400`（0 failure）+ `compileDebugKotlin 0 error`；Web `vitest 556/556` + `vue-tsc --noEmit exit 0` + `vite build exit 0`（15.46s）。
    - Diff：commit `d260ce7` 32 files / +5654 / −47（17 新增：Android 6 主代码 + 3 测试 + 1 fixture，Web 1 主代码 + 1 视图 + 2 测试 + 1 fixture + 1 类型垫片 + 1 store 测试；15 修改；临时脚本与 android/.kotlin 构建缓存未提交）。
    - 已知边界：Web 端汇率包推送为导入时 best-effort（无后台 Worker，离线恢复后重新导入补推；Android 由 CollectorWorker 周期 pushChanges 兜底）；金额 minor×汇率按业务量级（千亿元分）在 2^53 内安全，极端溢出不防御；多账户跨币种已覆盖，投资账户行情属 Task（FR-V2-D）范围。
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
  - 新建 `android/app/src/main/java/com/everything/eve/finance/OcrParser.kt`：
    纯函数层（on-device ML Kit Text Recognition 调用方 → 输入文本 →
    输出 `ReceiptHint`）。UI 层另建 `OcrScannerEngine.kt` 调系统 CameraX
    + ML Kit 引擎，避免与 `android.speech.SpeechRecognizer` 同名 import 混淆。
    - 纯函数 `parseReceiptText(text: String): ReceiptHint?`
    - `data class ReceiptHint(val amountMinor: Long?, val ts: Long?, 
      val merchant: String?)`
  - 新建 `android/app/src/main/java/com/everything/eve/finance/SpeechParser.kt`：
    纯函数层（on-device 语音识别结果文本 → `SpeechHint`）。UI 层另建
    `SpeechRecorderEngine.kt` 调系统 `android.speech.SpeechRecognizer` API。
    - 纯函数 `parseSpeechText(text: String): SpeechHint?`
    - `data class SpeechHint(val amountMinor: Long?, val category: String?, 
      val ts: Long = System.currentTimeMillis())`
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/OcrScannerSheet.kt`：
    Compose CameraX 拍照 + OCR 预览（调用 `OcrScannerEngine`）
  - 新建 `android/app/src/main/java/com/everything/eve/ui/finance/SpeechRecorderSheet.kt`：
    Compose 录音 + 识别预览（调用 `SpeechRecorderEngine`）
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
  - TR-9.1 OcrParser + parseReceiptText 纯函数（+ OcrScannerEngine UI 层）
  - TR-9.2 SpeechParser + parseSpeechText 纯函数（+ SpeechRecorderEngine UI 层）
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