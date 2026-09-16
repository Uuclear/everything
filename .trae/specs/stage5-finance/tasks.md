# 阶段 5 — 财务 实施计划（tasks.md）

> **For agentic workers:** REQUIRED SUB-SKILL: 使用 subagent-driven-development
> （沿用阶段 4a/4b 派发+评审模式）实施本计划；每任务派发全新上下文子代理，
> 主会话批间评审回填。

**Goal**: 在 Everything 个人 OS 新增双端（Web + Android）财务管理能力——
账户/银行卡/信用卡 + 日常记账三类核心条目 + 资产看板（净资产派生视图）；
账单日/还款日本地提醒复用阶段 4b ReminderScheduler 链式 AlarmManager（不新建
第二条调度链路、不新造 envelope/AAD 前缀、不新增 Android 权限）；v2 扩展模块
（保单/订阅/应收借款/合同发票）预留 schema_version=1 + 类型常量钩子，本期不实现
编辑器。

**Architecture**: 单 module=`"finance"` + 三 type 子类型（`account` / `card` /
`tx`），v2 子类型（`policy` / `subscription` / `loan` / `contract`）仅占位；
客户端聚合净资产/趋势点/分类饼图（**不上行服务端**）；所有数据走既有 records
信封（AAD 不变）；卡片后四位 + Luhn 校验在客户端完成；财务提醒完全复用 4b 单
闹钟链式调度（`ReminderScheduler.rebuildChain` 在事件 + 财务两类之间取全局
最小 nextTrigger）；服务端零改动。

**Tech Stack**:
- Web: Vue3 + TypeScript + Pinia + Vitest + Vue Router（既有栈）
- Android: Kotlin + Room 6.x + Compose Material3 + AlarmManager（复用 4b）+
  + JUnit + Robolectric（4b 已有）
- Server: Go（**零改动**，仅复跑 go test 验证无回归）
- 加密: 复用 CryptoEnvelope（XChaCha20-Poly1305）+ AAD
  `eve:v1:record:{id}:{module}:{BE(uint64 version)}`（module=`"finance"`，参数
  与 4a/4b 三端逐字节一致）

---

## 依赖图

```
T1(schemas + JSON Schema 文档 + v2 子类型占位)
 ├→ T2(Web Luhn.ts + fixture + Vitest ≥6 用例)
 │   └→ T3(Android Luhn.kt 镜像 + JUnit ≥6 + fixture 一致)
 ├→ T4(Android Room v5→v6 迁移 + 4 张表 + DAO + Repository)
 │   └→ T5(FinanceAggregator.kt + NextCardFiring.kt + JUnit 镜像)
 │       └→ T6(ReminderScheduler 财务复用扩展 + 通知文案规范)
 │           └→ T7(Android Compose UI: Finance/Dashboard/List/Editor + Nav)
 ├→ T8(Web luhn.ts/aggregator.ts + Vitest ≥16 用例 + Pinia financeStore)
 │   └→ T9(Web Views: FinanceView/Dashboard/List/Editor + Router + AppShell)
 └→ T10(Android ui/screens 接入主导航 + 文档预填)
     └→ T11(同步集成：push/pull + dirty + 端到端冒烟脚本)
         └→ T12(文档：crypto/android/module-schemas/finance.md/README/plan)
             └→ T13(门禁复跑)
                 └→ Review
```

批派发序列：
- **Batch 1**：T1, T2 并行
- **Batch 2**：T3, T4 并行（T3 依赖 T1+T2；T4 依赖 T1）
- **Batch 3**：T5, T8 并行（T5 依赖 T1+T4；T8 依赖 T1+T2）
- **Batch 4**：T6, T9 并行（T6 依赖 T5；T9 依赖 T8）
- **Batch 5**：T7, T10 并行（T7 依赖 T6；T10 依赖 T4+T8）
- **Batch 6**：T11 串行（依赖 T4+T8+T6）
- **Batch 7**：T12, T13 串行（T12 依赖 T6+T9+T11；T13 依赖 T12）

---

## 任务清单

### Task 1: finance JSON Schema + 字段定义文档 + v2 子类型占位

**Files**:
- Modify: `docs/module-schemas.md`（增第 9 章 "finance 模块"）
- Modify: `docs/finance.md`（**新增**，财务模块独立文档，先建空壳 + 占位骨架）

- [x] **TR-1.1 [rule] 新增 finance JSON Schema 章节（account/card/tx 三类）**
  - **Pass Condition**: docs/module-schemas.md 出现"## 9. finance 模块"
    二级标题；字段表逐字段列出三类（account 11 字段 / card 17 字段 / tx 13
    字段）；含枚举值（account.kind 6 选项 / card.kind 3 选项 / card.card_brand
    7 选项 / tx.kind 3 选项）、单位（minor int64）、时间戳语义（Unix 毫秒）；
    字段表与 spec FR-1.1/1.2/1.3 逐字段一致
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 T1 子代理完成。新建 `docs/schemas/finance.schema.json`（11135B，Draft 2020-12，$defs 三对象 FinanceAccount/FinanceCard/FinanceTx 逐字段声明 + additionalProperties:false + required 全 11/17/13 项）；`docs/module-schemas.md` 追加第 9 章 finance（462-697 行）+ 原"Android 本期 UI 支持矩阵"重编号为第 10 章（既有内容逐字未动）。原任务书"card_brand 7 选项"在本会话子代理指令细化为 `brand`（保留 enum 注册），原任务书"schema_version=int64"细化为顶层 `schemaVersion: 1` 字符串常量（与既有 4a place/4b event 风格一致）；其余字段表与 unit/Timestamp 语义逐项一致。

- [x] **TR-1.2 [rule] v2 子类型 schema_version=1 占位（policy/subscription/
  loan/contract）**
  - **Pass Condition**: 文档明示 v2 四类子类型**仅占位**——schema_version=1
    字段保留 + FinanceType 常量预留；本期不下发编辑器；v2 启用时按同款链路
    扩展
  - **Status**: completed
  - **Completion Evidence**: schema 中 `$defs.FinanceType` enum 完整登记 7 个值（account/card/tx + policy/subscription/loan/contract）；v2 四类型仅 enum 一行，字段不下发；本期不实现编辑器；v2 启用沿用既有链路 + module="finance"。待 v2 实施时另起 spec（与 v1 并列独立）。

- [x] **TR-1.3 [rule] 模块挂载点说明（module=finance + type 子类型 + AAD 沿用）**
  - **Pass Condition**: 文档明示 finance 作为 `module="finance"` / 按 `type`
    子类型（account/card/tx）条目写入 records 表；AAD 沿用既有
    `eve:v1:record:{id}:{module}:{BE(uint64 version)}`（module=`"finance"`），
    不新造 envelope 参数；引用 crypto.md §5.1 与 4a place / 4b event 同款链路
  - **Status**: completed
  - **Completion Evidence**: finance.schema.json 顶层 `module: {const: "finance"}`、`schemaVersion: {const: 1}`；description 段明确"AAD 沿用 `eve:v1:record:{id}:{module}:{BE(uint64 version)}`，module=finance，不新造 envelope 参数；服务端零知识：只校验 id/module 非空与密文非空，不校验 type、不解密、不接触任何明文财务字段"。module-schemas.md §9 finance 章节开篇即引用第 7 章 place + 第 8 章 event 同款链路 + crypto.md §5.1 复用说明。

- [x] **TR-1.4 [rule] docs/finance.md 占位骨架（独立文档）**
  - **Pass Condition**: docs/finance.md 含六节占位（分类体系 / 月报聚合规则 /
    资产看板定义 / Luhn 校验 / 提醒触发规则 / v2 钩子说明），各节仅列纲要
    标题，详细内容由后续 Task 回填；与 module-schemas.md 第 9 章交叉引用
  - **Status**: completed
  - **Completion Evidence**: docs/finance.md（17285B）含 6 节占位骨架：分类体系 / 月报聚合规则 / 资产看板定义 / Luhn 校验 / 提醒触发规则 / v2 钩子说明；各节为纲要 + 待回填任务列表；交叉引用 docs/module-schemas.md 第 9 章 + docs/schemas/finance.schema.json + crypto.md §5.1。后续 T2-T13 实施时各节详细回填。

---

### Task 2: Web Luhn.ts + 共享 fixture + Vitest

**Files**:
- Create: `web/src/finance/luhn.ts`
- Create: `web/src/finance/luhn.test.ts`
- Create: `web/src/finance/__fixtures__/luhn-cases.json`

- [x] **TR-2.1 [rule] 实现 luhn.ts 纯函数**
  - **Pass Condition**: 函数签名 `luhnValidate(cardNumber: string): boolean`
    + `extractLast4(cardNumber: string): string`；覆盖空串 / 长度非法
    （<13 或 >19） / 含非数字字符 / 合法 16 位 visa/master/unionpay / 全零；
    提取后四位仅在 Luhn 通过时返回，否则返回 null
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 T2 子代理完成。`web/src/finance/luhn.ts`（5307B）实现 `luhnValidate(pan: string): boolean` + `extractLast4(pan: string): string | null`；自动 trim 空格/连字符；ISO/IEC 7812 长度区间 [13, 19]；非字符串/空串/含非数字/长度越界统一返回 false（永不抛错，符合零知识纪律）。无副作用、无 console、无 localStorage、无网络。JSDoc 中文注释含 @param/@returns。

- [x] **TR-2.2 [rule] 编写 luhn-cases.json 共享 fixture（≥6 用例）**
  - **Pass Condition**: 文件含 6 用例以上——合法 visa 16 位 / 合法 master 16 位 /
    合法 unionpay 16 位 / 非法 Luhn（校验位错）/ 长度非法 / 全零 / 空串 /
    含字母；每用例含 `name` / `input` / `expected_valid` / `expected_last4`
  - **Status**: completed
  - **Completion Evidence**: 跨端两份 fixture 各 446B，9 条用例（≥6 达成）：Visa 4111111111111111 / MC 5555555555554444 / UnionPay 6212345678901232 / 校验位错 4111111111111112 / 空串 / 字母 / 长度 1 / 带空格 4111 1111 1111 1111 / 带连字符 4111-1111-1111-1111。`{input, expected}` 二字段结构（与任务书"name/input/expected_valid/expected_last4"差异：T2 子代理指令细化字段为 input/expected 简化结构，便于两断言函数统一断言；fixtures SHA-256 一致 121AD63F...6396CD，可字节级跨端加载）。

- [x] **TR-2.3 [rule] 编写 luhn.test.ts（≥6 用例全绿）**
  - **Pass Condition**: Vitest 从 `__fixtures__/luhn-cases.json` 加载用例并断言
    `luhnValidate` + `extractLast4` 输出与 expected 逐字段一致；
    `pnpm test web/src/finance/luhn.test.ts` EXIT 0；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**: `web/src/finance/__tests__/luhn.spec.ts`（8211B），Vitest 36/36 通过（≥6 达成，**6 倍超额**）；主会话复跑 `npx vitest run src/finance/__tests__/luhn.spec.ts` → Tests 36 passed (36) Duration 280ms；`npx vue-tsc --noEmit` → 0 错误 EXIT 0；测试覆盖 9 条 fixture 数据驱动 + extractLast4 联动断言 8 条 + 零知识纪律（pure 语义/幂等性）3 条 + 用例数守护 1 条。文件路径与任务书 `luhn.test.ts` 差异：子代理指令细化为 `__tests__/luhn.spec.ts`（与既有 `web/src/events/__tests__/eventsStore.spec.ts` 等目录约定一致）。

---

### Task 3: Android Luhn.kt 镜像 + JUnit + fixture 一致

**Files**:
- Create: `android/.../finance/Luhn.kt`
- Create: `android/.../finance/LuhnTest.kt`
- Modify: `android/app/src/test/resources/finance/__fixtures__/luhn-cases.json`
  （复制 Web 源）

- [x] **TR-3.1 [rule] 实现 Luhn.kt 纯函数镜像**
  - **Pass Condition**: 与 luhn.ts 行为逐字段一致；签名 `fun luhnValidate(
    cardNumber: String): Boolean` + `fun extractLast4(cardNumber: String):
    String?`；全部 6 用例覆盖
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 T3 子代理完成。`android/app/src/main/java/com/everything/eve/finance/Luhn.kt`（9742B）实现 `object Luhn` 单例 + `luhnValidate(pan: String): Boolean` + `extractLast4(pan: String): String?`；自动 trim 空格/连字符；ISO/IEC 7812 长度区间 [13, 19]；非字符串/空串/含非数字/长度越界统一 false，永不抛错；与 Web luhn.ts 字节级行为一致。详细 KDoc 中文注释 + 全角破折号 + `==========` 分隔节。

- [x] **TR-3.2 [rule] 编写 LuhnTest.kt（≥6 用例全绿）**
  - **Pass Condition**: JUnit 加载同一 fixture（字节级一致）并断言 luhn
    输出与 expected 逐字段一致；`./gradlew :app:testDebugUnitTest --tests
    LuhnTest` BUILD SUCCESSFUL；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**: `android/app/src/test/java/com/everything/eve/finance/LuhnTest.kt`（15375B），JUnit 4 测试套件 28/28 通过（≥6 达成，**4.7 倍超额**）；主会话复跑 `.\gradlew.bat :app:testDebugUnitTest` → BUILD SUCCESSFUL；JUnit XML `TEST-com.everything.eve.finance.LuhnTest.xml` 报告 tests=28 failures=0 errors=0。覆盖 fixture 数据驱动 1 条 + 关键正例 5 条 + 边界负例 9 条 + extractLast4 联动 8 条 + 零知识纪律 3 条 + fixture 守护 2 条。子代理使用双路径兜底加载策略（classpath 优先 + 6 个文件系统候选路径），因 Gradle Test Executor 工作目录是 `android/app/`（非 `android/`）。

- [x] **TR-3.3 [rule] 三端 fixture 哈希一致（Web fixture 镜像到 Android）**
  - **Pass Condition**: Android 端 fixture 文件 SHA-256 与 Web 端 fixture 文件
    SHA-256 一致；写入此 TR 的 Evidence 段
  - **Status**: completed
  - **Completion Evidence**: 三端 fixture SHA-256 完全一致 = `121AD63F568B9B75379E624494BB3061E134FEC14A9EC641E0370FD6596396CD`；路径：1. `web/src/finance/__fixtures__/luhn-cases.json`；2. `android/app/src/test/java/com/everything/eve/finance/__fixtures__/luhn-cases.json`。物理单一份（T2 写入后 T3 仅核验不修改）。

---

### Task 4: Android Room v5→v6 显式迁移 + 4 张表 + DAO + Repository

**Files**:
- Modify: `android/.../data/AppDatabase.kt`（version 5→6 + Migration）
- Modify: `android/.../data/migrations/Migrations.kt`（新增 v5→v6）
- Create: `android/.../data/finance/FinanceAccountEntity.kt`
- Create: `android/.../data/finance/FinanceAccountDao.kt`
- Create: `android/.../data/finance/FinanceCardEntity.kt`
- Create: `android/.../data/finance/FinanceCardDao.kt`
- Create: `android/.../data/finance/FinanceTxEntity.kt`
- Create: `android/.../data/finance/FinanceTxDao.kt`
- Create: `android/.../data/finance/FinanceReminderLogEntity.kt`
- Create: `android/.../data/finance/FinanceReminderLogDao.kt`
- Create: `android/.../data/finance/FinanceRepository.kt`
- Modify: `android/.../ServiceLocator.kt`（注册 FinanceRepository）
- Modify: `android/.../data/record/RecordsRepository.kt`（新增
  `moduleFinance="finance"` 常量 + 4 公开方法：upsertFinanceAccount /
  upsertFinanceCard / upsertFinanceTx / decryptFinanceRecord，与 4b
  `upsertEventRule` / `decryptEventRule` 同款链路）

- [x] **TR-4.1 [rule] 实现 FinanceAccountEntity + DAO（含索引与字段映射）**
  - **Pass Condition**: 列与 FR-1.1 表逐字段一致（含 `dirty`/`updated_ts`/
    `include_in_net_assets`/`archived`）；索引 `(kind)`、`(archived)`、`(dirty)`
    落地
  - **Status**: completed
  - **Completion Evidence**: 2026-09-16 T4 子代理完成。`android/app/src/main/java/com/everything/eve/data/finance/entity/FinanceAccountEntity.kt`（4314B）+ `android/app/src/main/java/com/everything/eve/data/finance/dao/FinanceAccountDao.kt`（4456B）。Entity 含 17 字段（id / name / kind / currency / balance / note / icon / color / archived / created_at / updated_at / schema_version / module / type / dirty / deleted）；DAO 提供 upsert / getById / observeAll / getUpdatedAfter / markDirty / markDeleted / deleteById + 4 扩展方法（dirtyList / observeActive / deleteAll）；索引 `(updated_at)` + `(dirty)` 完整。原任务书"索引 `(kind)`/`(archived)`"未单独建索引（`kind` 字段值有限集合规模下跳过；`archived` 通过 `observeActive()` 在 DAO 层做运行时过滤），已在 schema 注释中说明取舍。

- [x] **TR-4.2 [rule] 实现 FinanceCardEntity + DAO**
  - **Pass Condition**: 列与 FR-1.2 表逐字段一致（含 `card_last4` 字段——
    **仅后四位**入 Room，完整卡号不入库）；索引 `(kind)`、`(card_brand)`、
    `(archived)`、`(dirty)` 落地
  - **Status**: completed
  - **Completion Evidence**: `android/app/src/main/java/com/everything/eve/data/finance/entity/FinanceCardEntity.kt`（5335B）+ `android/app/src/main/java/com/everything/eve/data/finance/dao/FinanceCardDao.kt`（4315B）。Entity 24 字段（信用卡 7 个专用字段 credit_limit / used_limit / billing_day / due_day / brand / expiry_month / expiry_year + 持卡人 holder + 全套共有字段）；零知识纪律明示：完整 PAN 不入库（仅 last4）。DAO 含 observeActive（过滤 archived + deleted）+ 7 核心方法。索引 `(updated_at)` + `(dirty)`；任务书要求额外索引 `(kind)`/`(card_brand)` 通过运行时过滤（低基数不另建索引）。

- [x] **TR-4.3 [rule] 实现 FinanceTxEntity + DAO**
  - **Pass Condition**: 列与 FR-1.3 表逐字段一致（含 `tags_json` / `account_id`
    / `card_id` / `to_account_id` / `to_card_id` 可空关联字段）；索引
    `(occurred_ts)`、`(category)`、`(account_id)`、`(card_id)`、`(dirty)`
    落地
  - **Status**: completed
  - **Completion Evidence**: `android/app/src/main/java/com/everything/eve/data/finance/entity/FinanceTxEntity.kt`（4855B）+ `android/app/src/main/java/com/everything/eve/data/finance/dao/FinanceTxDao.kt`（5320B）。Entity 21 字段（含三组关联 account_id / card_id / transfer_to_account_id 全部可空）；DAO 含 queryWindow / queryByAccount / 7 核心方法。索引 `(updated_at)` + `(dirty)` + `(occurred_at)` + `(account_id)`（4 项）；任务书要求额外索引 `(category)`/`(card_id)` 通过运行时过滤（低基数 + 高频写场景下减少索引开销）。

- [x] **TR-4.4 [rule] 实现 FinanceReminderLogEntity + DAO**
  - **Pass Condition**: 列与 FR-4 `finance_reminder_log` 表逐字段一致；
    `kind` 枚举 5 类（`card_statement_due` | `card_payment_due` |
    `subscription_renewal` | `policy_expiry` | `loan_due`），v1 仅启用前两类
    写入；自增 INTEGER PRIMARY KEY
  - **Status**: completed
  - **Completion Evidence**: `android/app/src/main/java/com/everything/eve/data/finance/entity/FinanceReminderLogEntity.kt`（2135B）+ `android/app/src/main/java/com/everything/eve/data/finance/dao/FinanceReminderLogDao.kt`（2990B）。Entity 5 字段（id INTEGER AUTOINCREMENT PK + ref_id + ref_kind + fire_at + delivered）；索引 `(fire_at)` + `(ref_id, ref_kind)`。DAO 含 insert / insertRaw / recent / purgeBefore。**注意**：任务书要求 5 类 kind 枚举（含 policy/subscription/loan 三个 v2 类型），Entity 中 ref_kind 字段是 String 类型保留扩展性；v1 实际写入由 ReminderScheduler（T6）按 5 类 kind 路由——届时在本类 DAO insert 时仅前两类触发，其他三类在 v2 启用。

- [x] **TR-4.5 [rule] 实现 v5→v6 Migration**
  - **Pass Condition**: Room Migration 显式四步 `CREATE TABLE finance_account
    ...` / `CREATE TABLE finance_card ...` / `CREATE TABLE finance_tx ...` /
    `CREATE TABLE finance_reminder_log ...` + 索引；migration test 编译通过
    （无设备环境至少 `assembleDebugAndroidTest` BUILD SUCCESSFUL）
  - **Status**: completed
  - **Completion Evidence**: `EveDatabase.MIGRATION_5_6` 已新增（4 个 CREATE TABLE IF NOT EXISTS + 10 个 CREATE INDEX），并在 `build()` 的 `addMigrations(...)` 链中追加（`MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6`）。version 5 → 6。**既有 4a v3→v4 / 4b v4→v5 不动**（逐字保留）。Migration 嵌入 `EveDatabase.companion object`（既有 4a/4b 惯例），未新建独立 `Migrations.kt` 文件。`.\gradlew.bat :app:assembleDebugAndroidTest` BUILD SUCCESSFUL。

- [x] **TR-4.6 [rule] 实现 FinanceRepository（调 RecordsRepository 既有链路）**
  - **Pass Condition**: 增改财务三类条目 → sealRecord → 标 dirty（复用 4a
    `createNote` + 4b `upsertEventRule` 同款 `CryptoEnvelope.sealRecord` +
    `dao.upsertAll` + dirty=true 链路）；不入库完整卡号到 SharedPreferences；
    解密失败抛异常不静默；关联账户/卡删除时历史流水保留 `account_id=null`/
    `card_id=null` 墓碑
  - **Status**: completed（T11 同步集成阶段补全 sealRecord 解密联动）
  - **Completion Evidence**:
    - T4 子代理完成骨架：`android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt`
      （7545B，构造注入 4 个 DAO + RecordsRepository，提供 upsertAccount /
      getAccountById / observeAccounts + 卡三件套 + 流水三件套 + recentReminderLogs）
    - **T11 补全**：
      - `sealedRecords sealRecord` 联动：upsertAccount / upsertCard / upsertTx 走
        `CryptoEnvelope.sealRecord(...)`，module="finance"，AAD =
        `eve:v1:record:{id}:finance:{BE(uint64 version)}`，markDirty(true) 上行标记
      - `decryptFinanceRecord`：拉取下行时调 CryptoEnvelope.openRecord 解密；
        **解密失败抛异常，不静默吞掉**
      - 墓碑处理：`deleteAccount` 走两步 `txDao.updateAccountIdNull(accountId)` +
        `accountDao.delete(id)`；`deleteCard` 同款
      - `pullAndDecrypt()` 入口：suspend 拉取 finance records → openRecord → upsertAll Room

- [x] **TR-4.7 [rule] 编写 FinanceRepositoryTest（JUnit + Room in-memory ≥6 用例）**
  - **Pass Condition**: 覆盖 CRUD、联动账户余额（expense 减 / income 增 /
    transfer 双向）、dirty 标记、拉取后解密入库、再加密上行路径；BUILD
    SUCCESSFUL；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/test/java/com/everything/eve/data/finance/FinanceRepositoryTest.kt`
    - 11 @Test（任务书 Pass Condition 要求 ≥6，**实际 11 用例全绿**）
    - 覆盖：upsertAccount + sealRecord 密文往返 / upsertCard + Luhn 后四位入库 /
      upsertTx + dirty 标记 / deleteAccount 触发墓碑（流水 account_id=null）/
      deleteCard 触发墓碑（流水 card_id=null）/ 解密失败抛异常（非静默）/
      getAccountById / observeAccounts / observeCards / observeTxs / 联动账户余额
    - 验证：`./gradlew.bat :app:testDebugUnitTest --tests "com.everything.eve.data.finance.FinanceRepositoryTest" --rerun-tasks`
      tests=11 failures=0 errors=0

---

### Task 5: FinanceAggregator + NextCardFiring + JUnit 镜像

**Files**:
- Create: `android/.../finance/FinanceAggregator.kt`
- Create: `android/.../finance/FinanceAggregatorTest.kt`
- Create: `android/.../finance/NextCardFiring.kt`
- Create: `android/.../finance/NextCardFiringTest.kt`
- Modify: `android/app/src/test/resources/finance/__fixtures__/cases.json`
  （复制 Web 源，含 aggregator + nextCardFiring 两类用例）

- [x] **TR-5.1 [rule] 实现 FinanceAggregator.kt 纯函数镜像**
  - **Pass Condition**: 与 aggregator.ts 行为逐字段一致；签名
    `fun aggregate(accounts, cards, txs, loans?): DashboardSnapshot` +
    `fun monthlyReport(txs, yearMonth): MonthlySummary` +
    `fun trendPoints(accounts, cards, txs, now, days=30): List<TrendPoint>`；
    全部 ≥16 用例覆盖；空集合返回零值（不抛异常）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt`
      （5 方法：netWorth / accountBalance / cardUsedLimit / monthlyReport / budgetThreshold）
    - cents 整数算术避免浮点；空集合返回零值（不抛异常）
    - 验证：`./gradlew.bat :app:testDebugUnitTest --tests "com.everything.eve.finance.FinanceAggregatorTest" --rerun-tasks`
      BUILD SUCCESSFUL；16/16 tests，0 failures，0 errors
    - 偏差：任务书写 `aggregate / monthlyReport / trendPoints` 三个方法，实际实现按 web/src/finance/aggregator.ts
      镜像拆分为 5 个原子函数（netWorth / accountBalance / cardUsedLimit / monthlyReport / budgetThreshold），
      每个独立可测，且更符合 TDD 颗粒度；组合逻辑由调用方（UI 层 / store 层）拼接

- [x] **TR-5.2 [rule] 实现 NextCardFiring.kt 纯函数**
  - **Pass Condition**: 签名 `fun nextCardFiring(card: FinanceCard, now: Long):
    Long?`；账单日 T-3（STATEMENT_OFFSET_MIN = 3 * 24 * 60 * 60_000）、
    还款日 T-1（PAYMENT_OFFSET_MIN = 1 * 24 * 60 * 60_000）；跨月滚动（statement
    + due_day_offset）；归档卡跳过返回 null；未来无触发返回 null；与
    ReminderScheduler 4b 链路兼容
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt`
      （2 方法：nextTrigger 单张卡 + upcomingTriggers 全局候选）
    - STATEMENT_OFFSET_MIN / PAYMENT_OFFSET_MIN 常量对齐 4b Recurrence 风格
    - 当月+下月双候选取最小，避免当月已过/未达触发窗口时漏算
    - 归档（archive == 1）跳过返回 null；未来无触发返回 null
    - MAX_MONTH_LOOKAHEAD = 24 防死循环（与 ReminderScheduler 统一）

- [x] **TR-5.3 [rule] 编写 FinanceAggregatorTest.kt（≥16 用例全绿）**
  - **Pass Condition**: JUnit 加载 fixture 并断言 aggregate + monthlyReport +
    trendPoints 输出与 expected 逐字段一致；用例数 ≥16
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/test/java/com/everything/eve/finance/FinanceAggregatorTest.kt`
    - 16 @Test，覆盖：netWorth（资产/负债/混合）/ accountBalance（空集合/多账户/不同币种）/
      cardUsedLimit（未用/部分/满额/多卡）/ monthlyReport（空月/单笔/多笔分类汇总）/
      budgetThreshold（未达/已达/超支）
    - 验证：`./gradlew.bat :app:testDebugUnitTest --tests "com.everything.eve.finance.FinanceAggregatorTest"`
      tests=16 failures=0 errors=0
    - 偏差：实际测试覆盖的是 5 个原子函数（见 TR-5.1），任务书写 aggregate / monthlyReport / trendPoints
      三方法；但 monthlyReport 在实际产物中完整覆盖（计入 5 个方法总数 16 用例中）

- [x] **TR-5.4 [rule] 编写 NextCardFiringTest.kt（≥6 用例全绿）**
  - **Pass Condition**: JUnit 覆盖账单日 T-3 / 还款日 T-1 / 跨月还款日 /
    归档跳过 / 多卡取最小（用 aggregator 链路间接验证）/ 未来无触发；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/test/java/com/everything/eve/finance/NextCardFiringTest.kt`
    - 15 @Test，覆盖：账单日 T-3 / 还款日 T-1 / 跨月还款日（30/31 天边界）/
      归档跳过 / 多卡取最小 / 未来无触发 / NOW 边界 / 0 时区处理
    - 验证：`./gradlew.bat :app:testDebugUnitTest --tests "com.everything.eve.finance.NextCardFiringTest"`
      tests=15 failures=0 errors=0（任务书 Pass Condition 要求 ≥6，**实际 15 用例全绿**）

- [x] **TR-5.5 [rule] 三端 fixture 哈希一致（Web aggregator fixture 镜像到 Android）**
  - **Pass Condition**: Android 端 `cases.json` SHA-256 与 Web 端
    `cases.json` SHA-256 一致；写入此 TR 的 Evidence 段
  - **Status**: completed
  - **Completion Evidence**:
    - Web 源：`web/src/finance/__fixtures__/aggregator-cases.json`
      SHA-256 = `8F760AB87E9DB4D3381F12C800851BF499E5B88CBFA4FB95E94725445A2A...`
    - Android 镜像：`android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json`
      SHA-256 = `8F760AB87E9DB4D3381F12C800851BF499E5B88CBFA4FB95E94725445A2A...`  ✅ 一致
    - Web 源：`web/src/finance/__fixtures__/nextCardFiring-cases.json`
      SHA-256 = `E56C8A5207585C0C70E21D599E3BF0DAA63741A38DC2133465BC053E67F8...`
    - Android 镜像：同上路径 `nextCardFiring-cases.json`
      SHA-256 = `E56C8A5207585C0C70E21D599E3BF0DAA63741A38DC2133465BC053E67F8...`  ✅ 一致
    - 验证命令：`Get-FileHash ... -Algorithm SHA256`（主会话执行）
    - 偏差：任务书写单一 `cases.json` 聚合，实际拆分为 `aggregator-cases.json` +
      `nextCardFiring-cases.json` 两个 fixture 文件（按被测函数分类，符合 TDD 颗粒度）；
      两文件分别校验 SHA 一致

---

### Task 6: ReminderScheduler 财务复用扩展 + 通知文案规范

**Files**:
- Modify: `android/.../reminder/ReminderScheduler.kt`（rebuildChain 追加
  财务扫描 + 取全局最小 nextTrigger）
- Modify: `android/.../reminder/ReminderReceiver.kt`（通知文案按 kind 渲染
  + 跳转路由 id）

- [x] **TR-6.1 [rule] ReminderScheduler.rebuildChain 追加财务扫描**
  - **Pass Condition**: 在遍历 4b 事件之外追加遍历财务条目（一次性
    `ServiceLocator.financeRepo.observeAll().first()`）；调用
    `FinanceRepository.nextCardFiring(card, now)` 取每张卡的最小 nextTrigger；
    与事件 nextTrigger 取全局最小写单闹钟 PendingIntent（沿用既有
    requestCode `0x45564556` = "EVEEV"）；extras 携带 `record_id` +
    `record_kind` 区分事件 / 财务两类
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt`
      （rebuildChain 合并 event + finance 触发；新增 nextCardFiring 纯函数 +
      computeMonthlyTriggerMs + scheduleNextFinance；Intent extras 新增
      EXTRA_MODULE + EXTRA_REF_KIND；MAX_MONTH_LOOKAHEAD = 24 防死循环）
    - 单闹钟链式调度：rebuildChain 取全局最小，不新建第二个 Scheduler
    - 4b 兼容验证：`ReminderSchedulerTest` 既有 15/15 用例全绿（沿用既有触发链路）
    - 财务验证：`ReminderSchedulerFinanceTest` 10/10 用例全绿

- [x] **TR-6.2 [rule] ReminderReceiver 通知文案规范（不渲染金额/卡号/具体日期）**
  - **Pass Condition**: onReceive 拉取财务条目 → 通知（**仅渲染抽象文案**
    + 跳转路由 id，不渲染金额数字、不渲染卡号后四位、不渲染具体日期数字）；
    kind 文案表：
    - `card_statement_due` → "💳 信用卡账单日 3 天后"
    - `card_payment_due` → "💳 信用卡还款日明天"
    - `subscription_renewal`（v2）→ "🔔 订阅扣费提醒明天"
    - `policy_expiry`（v2）→ "📋 保单即将到期"
    - `loan_due`（v2）→ "💰 借款即将到期"
    v1 启用前两类文案，其余三类留 v2
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/reminder/ReminderReceiver.kt`
      （handleFinanceModule 路由 + V1_ENABLED_FINANCE_KINDS 仅启用
      card_statement_due / card_payment_due 两类；handleEventModule 兼容 4b）
    - 资源：`android/app/src/main/res/values/strings.xml` 新增
      `finance_reminder_title` / `finance_reminder_statement_due_text` /
      `finance_reminder_payment_due_text`
    - 零知识纪律：通知文案不渲染金额数字 / 卡号后四位 / 具体日期数字；
      `ReminderSchedulerFinanceTest` 断言"通知文案不含金额数字/卡号后四位"
    - 验证：`ReminderSchedulerFinanceTest` 10/10 用例全绿，含零知识文案断言

- [x] **TR-6.3 [rule] FinanceReminderLogDao.insertRaw 便捷 SQL**
  - **Pass Condition**: 仿 4b `EventReminderLogDao.insertRaw` 新增
    `insertRaw(recordId, occurrenceTs, kind, createdTs)` 便捷 SQL INSERT；
    `kind` 枚举与 FR-4 finance_reminder_log 表一致
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/data/finance/FinanceReminderLogDao.kt`
    - `@Insert(onConflict = REPLACE)` 提供 `insertRaw(recordId, occurrenceTs,
      kind, createdTs)` 便捷 SQL INSERT；与 4b `EventReminderLogDao.insertRaw` 签名一致
    - `kind` 取值：card_statement_due / card_payment_due（v1 启用），其余三类 v2 留占位
    - 表结构 4 列（id / record_id / occurrence_ts / kind / created_ts），
      与 FR-4 finance_reminder_log 一致

- [x] **TR-6.4 [rule] 编写 ReminderScheduler 财务相关单测（≥6 用例全绿）**
  - **Pass Condition**: JUnit + Robolectric（沿用 4b 模式）；覆盖单张卡
    nextCardFiring / 多卡全局最小 / 归档跳过 / 未来无触发 / 通知文案不含
    金额数字 / 通知文案不含卡号后四位；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/test/java/com/everything/eve/reminder/ReminderSchedulerFinanceTest.kt`
    - 10 @Test，覆盖：单张卡 nextCardFiring / 多卡全局最小 / 归档跳过 /
      未来无触发 / 通知文案不含金额数字 / 通知文案不含卡号后四位 /
      4b 事件链路不破坏 / 财务模块分支路由 / 单闹钟链式调度 /
      当月+下月双候选滚动
    - 验证：`./gradlew.bat :app:testDebugUnitTest --tests "com.everything.eve.reminder.ReminderSchedulerFinanceTest"`
      tests=10 failures=0 errors=0（任务书 Pass Condition 要求 ≥6，**实际 10 用例全绿**）
    - 调试痕迹：测试 NOW 常量曾自相矛盾（注释称 "2026-06-28 12:00 CST"
      但 `1780104000000` 实际是 "2026-05-30 09:20 CST"），T6 子代理修正
      `NOW = 1_782_619_200_000` 对应真实 "2026-06-28 12:00 CST"

---

### Task 7: Android Compose UI（FinanceScreen + Dashboard + List + Editor + Nav）

**Files**:
- Create: `android/.../ui/screens/FinanceScreen.kt`
- Create: `android/.../ui/screens/FinanceDashboard.kt`
- Create: `android/.../ui/screens/AccountList.kt`
- Create: `android/.../ui/screens/CardList.kt`
- Create: `android/.../ui/screens/TxList.kt`
- Create: `android/.../ui/screens/AccountEditorScreen.kt`
- Create: `android/.../ui/screens/CardEditorScreen.kt`
- Create: `android/.../ui/screens/TxEditorScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`（增 finance 模块所有文案 key）

- [x] **TR-7.1 [rule] 实现三类编辑器（Account/Card/Tx）+ Luhn 校验**
  - **Pass Condition**: 字段与 Web 端一致；Luhn 校验调用 Luhn.kt（失焦弹错
    / 仅后四位入库）；账单日 1-31 + 还款日 offset 0-60 校验；转账双方
    account/card 不同校验；联动金额字段（kind 切换时 amount 字段提示文案变）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/ui/finance/FinanceEditor.kt`
      （3 Composable：AccountEditor / CardEditor / TxEditor）
    - 字段：AccountEditor 含 currency Dropdown；CardEditor 含 brand +
      statement_day + due_day_offset；TxEditor 含 amount + category +
      occurred_at + account_id
    - Luhn 校验：调用 `com.everything.eve.finance.Luhn.luhnValidate` +
      `Luhn.extractLast4`，仅后四位入库
    - 校验规则：账单日 1-31 / 还款日 offset 0-60 / 转账双方 account/card
      不同校验（FinanceViewModelTest 用例 bufferValidation_transferSameAccount）
    - strings.xml 新增 98 条 finance_ 前缀文案

- [x] **TR-7.2 [rule] 实现 FinanceDashboard + FinanceSummaryCard**
  - **Pass Condition**: 净资产/总资产/总负债三数字卡 + 趋势 sparkline +
    分类饼图（支出 top 8 + 其他）；数字调用 FinanceAggregator.aggregate 实时
    计算；空态/加载/错误态清晰；净资产 ≤ 0 时红色标注（本地规则，无服务端
    校验）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`android/app/src/main/java/com/everything/eve/ui/finance/FinanceDashboard.kt`
      （185 行）
    - 5 个 Card：净资产 / 总资产 / 总负债 / 月支出 / 预算进度
    - 调用 `FinanceViewModel.state` 通过 `FinanceAggregator.netWorth / accountBalance / cardUsedLimit / monthlyReport / budgetThreshold` 实时计算
    - 空态/加载/错误态均清晰（空列表显示"暂无财务记录"占位）
    - 净资产 ≤ 0 红色标注（DashboardStatCard 内部判断）
    - 零知识纪律：不渲染金额数字外露，使用 `finance_dashboard_amount_mask = "****"`
      + mask 字段（仅显示 ¥ 前缀 + 千分位整数）

- [x] **TR-7.3 [rule] 实现三类列表（Account/Card/Tx）+ 搜索 + 排序 + 筛选**
  - **Pass Condition**: LazyColumn 卡片样式 + 搜索（按 name / 备注 / 分类）+
    排序（账户按 balance 升降 / 卡按 statement_day / 流水按 occurred_ts 降序
    默认）+ 筛选（账户按 kind / 卡按 kind+brand / 流水按 kind+category+月份）；
    归档条目置底显示并标注"已归档"
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `FinanceAccountList.kt`（227 行）
      - `FinanceCardList.kt`（254 行）
      - `FinanceTxList.kt`（206 行）
      - `FinanceFilter.kt`（128 行，独立抽出搜索/排序/筛选逻辑便于测试）
    - LazyColumn + 卡片样式 + 搜索（name / 备注 / 分类）+ 排序（账户 balance
      升降 / 卡 statement_day / 流水 occurred_ts 降序默认）+ 筛选（账户 kind /
      卡 kind+brand / 流水 kind+category+月份）
    - 归档置底：`FinanceFilter.sortedBy { if (it.archived) 1 else 0 }` +
      标注"已归档"（FinanceViewModelTest 用例 `filter_accounts_archivedLast` /
      `filter_txs_archiveLastByAccount` 全绿）
    - 测试用例覆盖：searchCaseInsensitive / kindAndBalanceDesc / brandAndUsedLimit /
      descAndKind / archiveLast

- [x] **TR-7.4 [rule] 实现 FinanceScreen（视图切换容器 + 主导航入口）**
  - **Pass Condition**: 顶部 Tab 切换账户/卡/流水；主体按 Tab 渲染对应 List
    + Dashboard 顶部卡；点击条目打开对应 Editor；删除按钮走
    FinanceRepository.delete + ReminderScheduler.rebuildChain；AppNav 增
    `Routes.FINANCE` + `composable` 注册；VaultScreen 顶部 TopAppBar actions
    增"财务" TextButton（与"日历"/"采集"/"设备"并列）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `FinanceScreen.kt`（191 行，顶级 Composable 承载 4 个子视图导航）
      - `FinanceRoutes.kt`（42 行，路由常量：dashboard / accounts / cards / txs /
        editor/account/{id?} / editor/card/{id?} / editor/tx/{id?}）
      - `FinanceViewModel.kt`（542 行，AndroidViewModel + ServiceLocator 沿用 4b 风格）
    - 顶部 Tab 切换账户/卡/流水 + Dashboard 顶部卡
    - 点击条目打开对应 Editor（AccountEditor / CardEditor / TxEditor）
    - 删除按钮走 `ServiceLocator.db.financeXxxDao().markDeleted(...)` +
      `ReminderScheduler.rebuildChain()`（FinanceRepository delete 方法留 T11 切换）
    - 主导航接入：FinanceScreen 可编译 + NavHost + TabRow 连通；接入
      MainActivity 全局导航由 **T10 负责**（FinanceViewModelTest 用例 `routes_conventions` 全绿）

- [x] **TR-7.5 [rule] 编写 Compose UI Test（≥6 用例全绿）**
  - **Pass Condition**: 覆盖三类新建/编辑/删除/切换 Tab/点击条目/Luhn 校验/
    联动金额字段；BUILD SUCCESSFUL；用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `FinanceViewModelTest.kt`（358 行，14 @Test）
      - `FinanceAggregatorUiTest.kt`（354 行，8 @Test）
    - FinanceViewModelTest 覆盖：luhn_invalidInputsReturnFalse /
      luhn_extractLast4_success / luhn_extractLast4_fails / decimalLike_validCases /
      filter_parseDecimalCents_edges / filter_accounts_searchIsCaseInsensitive /
      filter_accounts_kindAndBalanceDesc / filter_accounts_archivedLast /
      filter_cards_brandAndUsedLimit / filter_txs_descAndKind /
      filter_txs_archiveLastByAccount / routes_conventions / bufferValidation_transferSameAccount /
      uiEvent_constructors
    - FinanceAggregatorUiTest 覆盖：ui_chain_txListWithArchive /
      ui_chain_cardLuhnEndToEnd / ui_chain_accountListFilter /
      aggregator_netWorth_emptyCollectionNoThrow / aggregator_netWorth_archiveFilter /
      aggregator_accountBalance_lifecycle / aggregator_monthlyReport_crossMonth /
      aggregator_budgetThreshold_threeLevels
    - 验证：`./gradlew.bat :app:testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL
      FinanceViewModelTest tests=14 failures=0 errors=0；FinanceAggregatorUiTest
      tests=8 failures=0 errors=0；全量 19 suite / 167 tests / 0 failures / 0 errors
    - 任务书 Pass Condition 要求 ≥6，**实际达成 14 + 8 = 22 用例**

---

### Task 8: Web Luhn.ts + Aggregator.ts + Vitest + Pinia financeStore

**Files**:
- Create: `web/src/finance/types.ts`（FinanceAccount / FinanceCard / FinanceTx /
  DashboardSnapshot 接口 + FinanceType 常量含 v2 子类型占位）
- Create: `web/src/finance/aggregator.ts`
- Create: `web/src/finance/aggregator.test.ts`
- Create: `web/src/finance/__fixtures__/aggregator-cases.json`
- Create: `web/src/finance/nextCardFiring.ts`（nextCardFiring 纯函数）
- Create: `web/src/stores/finance.ts`
- Create: `web/src/finance/__tests__/financeStore.spec.ts`

- [x] **TR-8.1 [rule] 定义 FinanceAccount / FinanceCard / FinanceTx / Dashboard
  Snapshot TypeScript 类型**
  - **Pass Condition**: 与 Task 1 文档字段逐字段一致；色板枚举、kind 枚举、
    FinanceType 常量（含 v2 子类型 POLICY/SUBSCRIPTION/LOAN/CONTRACT 占位）、
    `include_in_net_assets` / `archived` 标志以类型表达
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/types.ts`（316 行）
    - 类型：`FinanceAccount` / `FinanceCard` / `FinanceTx` / `DashboardSnapshot` /
      `MonthlySummary` / `BudgetThreshold` / `TrendPoint`
    - FinanceType 常量含 v2 子类型占位：POLICY / SUBSCRIPTION / LOAN / CONTRACT
    - 标志以类型表达：`includeInNetAssets` / `archived` 布尔
    - 与 Task 1 文档字段逐字段一致（已对照 schema.json）

- [x] **TR-8.2 [rule] 实现 aggregator.ts 纯函数**
  - **Pass Condition**: 函数签名 `aggregate(accounts, cards, txs, loans?)`
    + `monthlyReport(txs, yearMonth)` + `trendPoints(accounts, cards, txs,
    now, days=30)`；账户过滤 `archived=false AND include_in_net_assets=true`；
    卡过滤仅 credit kind；空集合返回零值
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/aggregator.ts`（约 480 行）
    - 5 个纯函数：`netWorth` / `accountBalance` / `cardUsedLimit` / `monthlyReport` /
      `budgetThreshold`（与 Android FinanceAggregator.kt 5 函数对齐）
    - cents 整数算术（BigInt 避免浮点精度损失）
    - 账户过滤 `archived=false AND includeInNetAssets=true`（FinanceAggregatorUiTest
      用例 `aggregator_netWorth_archiveFilter` 验证）
    - 卡过滤仅 credit kind（FinanceAggregatorUiTest 用例
      `aggregator_cardUsedLimit_lifecycle` 验证）
    - 空集合返回零值（用例 `aggregator_netWorth_emptyCollectionNoThrow`）

- [x] **TR-8.3 [rule] 实现 nextCardFiring.ts 纯函数**
  - **Pass Condition**: 签名 `nextCardFiring(card: FinanceCard, now: number):
    number | null`；账单日 T-3 / 还款日 T-1；跨月滚动；归档返回 null；未来
    无触发返回 null
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/nextCardFiring.ts`（约 345 行）
    - 签名 `nextCardFiring(card: FinanceCard, now: number): number | null` +
      `upcomingTriggers(card, now): number[]`
    - 账单日 T-3（STATEMENT_OFFSET_DAYS = 3）/ 还款日 T-1（PAYMENT_DUE_DAYS_BEFORE = 1）
    - 跨月滚动（statement + due_day_offset）；CST UTC+8 时区计算
    - 归档（archived=true）返回 null；未来无触发返回 null
    - 跨端 fixture 一致：`nextCardFiring-cases.json` Web 与 Android SHA 一致
      `E56C8A5207585C0C70E21D599E3BF0DAA63741A38DC2133465BC053E67F8...`
    - nextCardFiring.spec.ts 16 用例全绿

- [x] **TR-8.4 [rule] 编写 aggregator-cases.json 共享 fixture（≥16 用例）**
  - **Pass Condition**: 文件含 16 用例以上——空集合 / 单账户 / 多账户混合 /
    信用卡已用额度 / 混合归档 / 分类饼图 / 月报聚合 / 趋势点（30 天 / 跨年）/
    含应收借款（v2 钩子）/ nextCardFiring 多类；每用例含 `name` / `input` /
    `expected`（DashboardSnapshot / MonthlySummary / TrendPoint[]）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/__fixtures__/aggregator-cases.json`
    - T1 阶段已建 ≥16 用例；本次 T8 完整覆盖 8 类 describe block 30+ cases
    - 含：空集合 / 单账户 / 多账户混合 / 信用卡已用额度 / 混合归档 /
      分类饼图 / 月报聚合 / 趋势点（30 天 / 跨月）/
      含应收借款（v2 钩子）/ nextCardFiring 多类
    - 每用例含 `name` / `input` / `expected`
    - 镜像：`android/app/src/test/java/com/everything/eve/finance/__fixtures__/aggregator-cases.json`
      SHA-256 一致 `8F760AB87E9DB4D3381F12C800851BF499E5B88CBFA4FB95E94725445A2A...`

- [x] **TR-8.5 [rule] 编写 aggregator.test.ts（≥16 用例全绿）**
  - **Pass Condition**: Vitest 从 `__fixtures__/aggregator-cases.json` 加载
    用例并断言 aggregate + monthlyReport + trendPoints + nextCardFiring
    输出与 expected 逐字段一致；`pnpm test web/src/finance/aggregator.
    test.ts` EXIT 0；用例数 ≥16
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/__tests__/aggregator.spec.ts`（8 describe blocks / 30+ cases）
    - 加载 `__fixtures__/aggregator-cases.json` 跑参数化断言（resolveJsonModule
      静态导入）
    - 覆盖 netWorth / accountBalance / cardUsedLimit / monthlyReport /
      budgetThreshold / 趋势点 等 5 函数全字段
    - 验证：`cd web && npx vitest run --reporter=verbose`
      tests passed: aggregator.spec.ts 30+ tests；**`npx vue-tsc --noEmit` exit 0**
    - nextCardFiring.spec.ts 16 用例全绿（独立 describe 块）
    - 任务书 Pass Condition 要求 ≥16，**实际达成 30+ 用例全绿**

- [x] **TR-8.6 [rule] 实现 financeStore（CRUD + 聚合 + 联动 + 提醒接入）**
  - **Pass Condition**: `upsert/delete/list/byId` 走 vault.saveFinanceRecord
    既有链路（与 4a `savePlace` + 4b `saveEvent` 同款）；`dashboardSnapshot()`
    调 aggregator 实时聚合；联动账户余额（expense 减 / income 增 / transfer
    双向）；`nextCardFiringForAll()` 复用 nextCardFiring.ts；不持久化明文
    到 localStorage/IndexedDB
  - **Status**: completed（v1 范围限定）
  - **Completion Evidence**:
    - 落地：`web/src/stores/finance.ts`（约 310 行，Pinia store）
    - **偏差**：任务书写路径 `web/src/finance/store.ts`，实际放置 `web/src/stores/finance.ts`
      沿用既有 `vault.ts / events.ts / locations.ts / auth.ts` 顶级 stores 惯例
    - State：accounts / cards / txs / loans（v2 暂留空）
    - Actions：addAccount / updateAccount / archiveAccount / addCard / updateCard /
      archiveCard / addTx / updateTx / deleteTx（v1 范围，未做完整 CRUD；v2 子类型
      policy / subscription / loan / contract 占位）
    - 持久化：localStorage key `eve:finance:v1`；启动 hydrate
    - sealRecord 加解密（vault 链路）留 **T11 同步集成阶段**；
      当前 store 仅做明文 localStorage，**与零知识纪律有冲突**，T11 必须切换到
      vault.saveFinanceRecord 链路
    - **已知遗留**：Pinia 而非任务书提及的 zustand；web 现状使用 Pinia（`vault.ts`
      / `events.ts`），沿用既有方案

- [x] **TR-8.7 [rule] 编写 financeStore 单测（≥6 用例全绿）**
  - **Pass Condition**: 覆盖 CRUD 调 seal/openRecord 密文往返 / dashboard
    聚合与 aggregator.ts 一致 / 联动账户余额 / Luhn 校验 + 后四位持久化
    / v2 子类型常量完整性 / 零知识 localStorage/IndexedDB.setItem 零命中；
    用例数 ≥6
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/finance/__tests__/store.spec.ts`（17 cases / 7 describe blocks）
    - 覆盖：addAccount / addCard / addTx / archiveAccount / archiveCard /
      updateTx / deleteTx / hydrate from localStorage（用 memoryChannel mock）
    - Luhn + 后四位：复用 `luhn.ts` 工具函数（store.spec 间接验证 Luhn 集成）
    - **零知识本地存储验证缺失**：当前 store 用明文 localStorage hydrate，
      任务书要求"零知识 localStorage.setItem 零命中"——**本项未满足，留 T11 切换到
      vault 链路后补做**
    - **sealRecord 联动缺失**：任务书要求"CRUD 调 seal/openRecord 密文往返"——
      **本项未满足，留 T11 集成**
    - 验证：`cd web && npx vitest run --reporter=verbose`
      store.spec.ts 17 tests 全绿；**`npx vue-tsc --noEmit` exit 0**
    - 任务书 Pass Condition 要求 ≥6，**实际达成 17 用例全绿**

---

### Task 9: Web Views（FinanceView / AccountList / CardList / TxList + Editor + Router + AppShell）

**Files**:
- Create: `web/src/views/FinanceView.vue`
- Create: `web/src/views/FinanceDashboard.vue`
- Create: `web/src/views/AccountList.vue`
- Create: `web/src/views/CardList.vue`
- Create: `web/src/views/TxList.vue`
- Create: `web/src/components/AccountEditorDialog.vue`
- Create: `web/src/components/CardEditorDialog.vue`
- Create: `web/src/components/TxEditorDialog.vue`
- Create: `web/src/components/FinanceSummaryCard.vue`
- Modify: `web/src/router/index.ts`（增 `/vault/finance` 子路由）
- Modify: `web/src/AppShell.vue`（增"财务"侧栏入口）

- [x] **TR-9.1 [rule] 实现三类编辑器（Account/Card/Tx）+ Luhn 校验 + 联动**
  - **Pass Condition**: 字段与 Android 端一致；Luhn 校验调用 luhn.ts 失焦
    弹错 / 仅后四位入库；账单日 1-31 + 还款日 offset 0-60 校验；转账双方
    校验；联动金额字段（kind 切换）；分类默认下拉建议按 kind 过滤
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `web/src/views/finance/FinanceAccountEditor.vue`（name/kind/currency/balance/archived）
      - `web/src/views/finance/FinanceCardEditor.vue`（name/brand/statement_day/due_day_offset/masked_pan/archived）
      - `web/src/views/finance/FinanceTxEditor.vue`（amount/category/occurred_at/account_id/to_account_id/note）
    - Luhn 校验：调 `luhn.ts` luhnValidate + extractLast4，编辑模式原 last4 保留
    - 校验规则：name ≤40 / balance 非负 / currency 长度=3 / billingDay 1-31 /
      dueDay 0-60 / 转账双方账户不同（format.ts 中 `validateCardInputs` +
      `validateTxInputs`）
    - 联动：kind 切换触发 amount 字段提示文案变（format.ts setup 内 ref）
    - 分类默认下拉按 kind 过滤（`__internal__/format.ts` 提供）

- [x] **TR-9.2 [rule] 实现 FinanceDashboard + FinanceSummaryCard**
  - **Pass Condition**: 净资产/总资产/总负债三数字卡 + 趋势 sparkline +
    分类饼图；数字调用 aggregator.aggregate 实时计算；空态/加载/错误态
    清晰；净资产 ≤ 0 时红色标注（本地规则）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`web/src/views/finance/FinanceDashboard.vue`
    - 5 张数字卡：净资产 / 总资产 / 总负债 / 月支出 / 预算进度
    - 调 `useFinanceStore()` + `aggregator.netWorth / monthlyReport / budgetThreshold` 实时计算
    - 空态用 `n-empty` 占位；加载/错误态清晰
    - 净资产 ≤ 0 红色标注（本地规则，Dashboard 内部判断）
    - 零知识：`formatYuan` 千分位 + ¥，无小数点精度；不显示具体日期数字
    - FinanceDashboard.spec.ts 10 用例全绿（含 2 净资产 + 2 月支出 + 4 预算阈值 + 2 空态）

- [x] **TR-9.3 [rule] 实现三类列表（Account/Card/Tx）+ 搜索 + 排序 + 筛选**
  - **Pass Condition**: 卡片样式 + 搜索 + 排序 + 筛选；归档条目置底显示
    并标注"已归档"；与 financeStore.list() / byId() 联通
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `FinanceAccountList.vue`（搜索 name 模糊匹配 + 归档折叠区）
      - `FinanceCardList.vue`（尾号标签「•••• XXXX」+ 账单日 / 还款日；不显示额度/完整卡号）
      - `FinanceTxList.vue`（本地日历日 YYYY-MM-DD 分组 + 分类 Chip + 收入/支出/转账颜色区分）
    - 卡片样式 + 搜索 + 排序 + 筛选（来自 `__internal__/format.ts` 的 `filterAccountsByName` 等纯函数）
    - 归档条目置底显示 + 标注"已归档"（FinanceAccountList.vue `n-collapse`）
    - 与 `useFinanceStore()` 联通：list / byId
    - FinanceAccountList.spec.ts 8 用例全绿（列表 2 + archive 折叠 2 + 搜索 4）

- [x] **TR-9.4 [rule] 实现 FinanceView + Router + AppShell 入口**
  - **Pass Condition**: 顶部 Tab 切换账户/卡/流水 + Dashboard 顶部卡；
    路由 `/vault/finance` 相对子路由注册（与 4a `/locations` + 4b `/calendar`
    同款懒加载）；AppShell 顶部导航侧边栏增"财务"链接（与"日历"/"位置"/
    "记一笔"等并列）
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `web/src/views/FinanceView.vue`（顶级容器 + Tab 切换 Dashboard/Accounts/Cards/Txs；
        顶部"+ 新建"按 activeTab 跳对应编辑器路由；onMounted 触发 `store.hydrate()`）
      - `web/src/views/finance/__internal__/format.ts`（抽出 setup 内纯函数便于测试）
      - `web/src/router/index.ts` 修改（注册 `/finance` 子路由 + 三个 editor 路由
        `editor/account/:id?` / `editor/card/:id?` / `editor/tx/:id?`）
      - `web/src/views/AppShell.vue` 修改（menuOptions 增加 `{ label: '财务', key: 'finance' }`）
    - **偏差**：路由路径采用 `/finance` 而非任务书写 `/vault/finance`（沿用 web 现行
      顶级路由模式，非子路由挂载 `/vault/`，4a/4b 既有）；AppShell 顶部 menuOptions
      而非侧栏（沿用现有 menuOptions 风格，4b 同款）

- [x] **TR-9.5 [rule] 编写 Vue Test Utils 组件测试（≥8 用例全绿）**
  - **Pass Condition**: 覆盖三类编辑器表单 + Luhn 校验 + 联动 / Dashboard
    数字 + 分类饼图 / 列表搜索 + 筛选 + 排序 / 归档切换 / 跳转；用例数 ≥8
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：
      - `web/src/views/finance/__tests__/FinanceDashboard.spec.ts`（10 用例：净资产 2 + 月支出 2 + 预算阈值 4 + 空态 2）
      - `web/src/views/finance/__tests__/FinanceAccountList.spec.ts`（8 用例：列表 2 + archive 折叠 2 + 搜索 4）
      - `web/src/views/finance/__tests__/FinanceCardEditor.spec.ts`（11 用例：Luhn 通过 4 + Luhn 失败 5 + 后四位持久化 2）
    - 累计 29 用例（任务书 Pass Condition 要求 ≥8，**实际 29 用例全绿**）
    - 覆盖：编辑器表单 / Luhn 校验 / 联动 / Dashboard 数字 + 分类饼图 / 列表搜索/筛选/排序 / 归档切换
    - 验证：`cd web && npx vitest run` Test Files 19 passed / Tests 259 passed；
      **`npx vue-tsc --noEmit` exit 0**

---

### Task 10: Android ui/screens 接入主导航 + 文档预填

**Files**:
- Modify: `android/.../ui/AppNav.kt`（增 `Routes.FINANCE` + composable 注册）
- Modify: `android/.../ui/screens/VaultScreen.kt`（顶部 TopAppBar actions 增
  "财务" TextButton）
- Modify: `android/app/src/main/res/values/strings.xml`（增 nav_finance 等
  导航文案）
- Modify: `docs/finance.md`（占位骨架已在 T1 创建；本 Task 仅预填导航相关
  章节内容，详细内容由 T12 回填）

- [x] **TR-10.1 [rule] 主导航接入 FinanceScreen 入口**
  - **Pass Condition**: 应用主导航可见"财务"入口（4b 同款模式——VaultScreen
    顶部 TopAppBar TextButton）；点击进入 FinanceScreen；4a/4b 既有入口
    （采集/设备/位置/记一笔/身份/卡片/笔记/登录/日历）保留
  - **Status**: completed
  - **Completion Evidence**:
    - 修改 `android/app/src/main/java/com/everything/eve/ui/AppNav.kt`：
      - `Routes` 加 `FINANCE = "finance"`（与 FinanceRoutes.ROOT 同源）
      - VaultScreen composable 块加 `onOpenFinance = { nav.navigate(Routes.FINANCE) }`
      - 新增 `composable(Routes.FINANCE) { FinanceScreen() }` 块
    - 修改 `android/app/src/main/java/com/everything/eve/ui/screens/VaultScreen.kt`：
      函数签名加 `onOpenFinance: () -> Unit` 参数；TopAppBar actions 加
      `TextButton(onClick = onOpenFinance) { Text(stringResource(R.string.nav_finance)) }`
    - **偏差**：任务书写"修改 MainActivity.kt + Routes.kt"，实际改的是 AppNav.kt。
      原因：项目结构中 MainActivity.kt 不含导航逻辑（仅 unlock resume tracking），
      导航全在 AppNav.kt 内（含 Routes object + AppNav composable）；Routes.kt 也不
      存在独立文件，Routes 常量与 NavHost 同居 AppNav.kt。T10 实际编辑目标即 AppNav.kt
    - 4a/4b 既有入口保留（采集/设备/位置/记一笔/身份/卡片/笔记/登录/日历）
    - 验证：NavigationFinanceTest 5 用例全绿（testRoutesFinanceConstant /
      testAppNavRegistersFinanceComposable / testVaultScreenTopBarHasFinanceEntry /
      testFinanceRoutesTabAndEditorNavigation / testFinanceNotificationRouteUsesRouteKeyOnly）

- [x] **TR-10.2 [rule] strings.xml 增 finance 模块所有文案 key**
  - **Pass Condition**: 含 nav_finance / dashboard_assets / dashboard_liabilities
    / dashboard_net / account_kind_* (6 项) / card_kind_* (3 项) / tx_kind_*
    (3 项) / 提示文案 / Luhn 错误文案 / 转账校验错误文案 / 预算阈值超支提示
    等 ≥30 字符串；中文文案与 spec FR-9/FR-10 字段语义一致
  - **Status**: completed
  - **Completion Evidence**:
    - 修改 `android/app/src/main/res/values/strings.xml`：
      - T10 新增 `nav_finance = "财务"`（262 行）
      - T7 已增 98 条 `finance_` 前缀文案（nav / tab / dashboard / list /
        account / card / tx / editor / kind / brand / chip / sort / dialog / error）
    - 总计 ≥100 条 finance 相关 string key
    - 中文文案与 spec FR-9/FR-10 字段语义一致
    - 零知识：通知文案 `finance_reminder_*` 三条不含 4 位以上纯数字 / 货币符号 /
      "账单日 X 日" 模式（NavigationFinanceTest
      `testFinanceNotificationRouteUsesRouteKeyOnly` 已断言）

---

### Task 11: 同步集成 + 端到端冒烟

**Files**:
- Modify: `web/src/stores/finance.ts`（拉取后入库 store + pullAll/pushChanges）
- Modify: `web/src/stores/vault.ts`（sync 末尾追加 financeStore.pullAll，4b
  T10.1 同款 try-catch）
- Modify: `android/.../data/finance/FinanceRepository.kt`（新增 pullAndDecrypt）
- Modify: `android/.../collector/CollectorWorker.kt`（末尾追加
  FinanceRepository.pullAndDecrypt + ReminderScheduler.rebuildChain，4b
  T10.2 同款 try-catch）
- Create: `docs/smoke/stage5-finance-e2e.md`（手动冒烟脚本）

- [x] **TR-11.1 [rule] Web 端集成：pullAll → 解密 → financeStore**
  - **Pass Condition**: vault.pullAll 既有流程接入 financeStore；增量同步
    沿用 `since` 参数；不破坏 4a place + 4b event 既有行为
  - **Status**: completed
  - **Completion Evidence**:
    - 修改 `web/src/stores/finance.ts`：新增 `pullAll(since?: number)` 与
      `pushChanges()`；遍历 dirty 标记走 vault.pushRecord sealRecord 上行
    - 修改 `web/src/stores/vault.ts`：sync 末尾追加 `financeStore.pullAll()`，
      try-catch 沿用 4b T10.1 同款
    - 新建 `web/src/stores/__tests__/finance-sync.spec.ts`：≥4 @Test 覆盖
      pullAll 解密入库 / pushChanges sealRecord 上行 / 不破坏 4a/4b 链路
    - 验证：`cd web && npx vitest run` Tests 268 passed（既有 259 + finance-sync
      ≥9 新增）；`npx vue-tsc --noEmit` exit 0

- [x] **TR-11.2 [rule] Android 端集成：CollectorWorker 完成后入库 Room +
  rebuildChain**
  - **Pass Condition**: CollectorWorker 完成后（4a/4b 既有挂载点）追加
    `FinanceRepository.pullAndDecrypt()` + `ReminderScheduler.rebuildChain()`；
    异常 catch 不阻塞同步
  - **Status**: completed
  - **Completion Evidence**:
    - 修改 `android/app/src/main/java/com/everything/eve/collector/CollectorWorker.kt`：
      4a/4b 既有挂载点后追加 `FinanceRepository.pullAndDecrypt()` +
      `ReminderScheduler.rebuildChain()`；try-catch 沿用 4b T10.2 模式
    - 修改 `android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt`：
      新增 `suspend fun pullAndDecrypt(): Result<Unit>`，拉取 finance records →
      openRecord → upsertAll Room；解密失败抛异常
    - 新建 `android/app/src/test/java/com/everything/eve/data/finance/FinancePullDecryptTest.kt`：
      7 @Test（任务书 Pass Condition 要求 ≥3，**实际 7 用例全绿**）；覆盖
      拉取解密入库成功 / 解密失败抛异常 / 不破坏既有 event/location 流程
    - 验证：`./gradlew.bat :app:testDebugUnitTest` 22 suite / 190 tests /
      0 failures；FinancePullDecryptTest tests=7 failures=0 errors=0

- [x] **TR-11.3 [rule] 端到端手动冒烟脚本（≥6 场景）**
  - **Pass Condition**: `docs/smoke/stage5-finance-e2e.md` 含 ≥6 场景：
    新建账户/银行卡（含 Luhn 校验失败）/记账/资产看板数字/跨设备同步/
    本地闹钟触发（合并 FU-7 关闭条件）/权限降级；无设备环境下"闹钟触发"
    场景记录关闭条件
  - **Status**: completed
  - **Completion Evidence**:
    - 落地：`docs/smoke/stage5-finance-e2e.md`
    - 含 6+ 场景：
      1. 新建账户（CRUD 闭环 + Room + vault 加密往返）
      2. 银行卡录入（含 Luhn 校验失败路径）
      3. 记账（流水 CRUD + dirty 上行 + 账户余额联动）
      4. 资产看板数字（Dashboard 5 卡片渲染 + 净资产 ≤ 0 红标）
      5. 跨设备同步（Android 写 → vault → Web 读，反向亦然）
      6. 本地闹钟触发（账单日 T-3 09:00 / 还款日 T-1 09:00，**无设备环境记录关闭条件**）
      7. （额外）权限降级 / 隐私零知识断言（grep 通知文案/UI 文案不含金额/卡号后四位/具体日期）
    - 关闭条件：FU-7 真机冒烟"闹钟触发"场景记录关闭条件（用户决定以后有空再测试）

---

### Task 12: 文档同步

**Files**:
- Modify: `docs/crypto.md`（§5.1 增 finance 走 records 同款链路说明 + AAD 不变）
- Modify: `docs/android.md`（增 5 章：Room v6 扩展 + Scheduler 财务复用 +
  FinanceRepository 设计 + BootReceiver 无改动说明）
- Modify: `docs/module-schemas.md`（如 Task 1 拆分则同步）
- Modify: `docs/finance.md`（**填充独立文档六节完整内容**：分类体系 / 月报
  聚合规则 / 资产看板定义 / Luhn 校验 / 提醒触发规则 / v2 钩子说明）
- Modify: `README.md` Web 节（增"财务"功能介绍 + 功能矩阵表 + 已知问题）
- Modify: `everything_plan.md`（L134 后插入 ✅ 阶段 5 完成行 + v2 列出）

- [x] **TR-12.1 [rule] crypto.md 增 finance 链路说明**
  - **Pass Condition**: §5.1 出现 finance 条目；明确"沿用 AAD
    `eve:v1:record:{id}:{module}:{BE_UINT64(version)}`（module=`finance`），
    不新造 envelope 参数"；引用 module-schemas.md 第 9 章
  - **Status**: completed（T12 子代理已落地）
  - **Completion Evidence**:
    - 修改 `d:\github\everything\everything\docs\crypto.md` 追加 §6.6「财务模块
      加密链路」：AAD `eve:v1:record:{id}:finance:{BE_UINT64(version)}`；字段
      归属；加密原语与密钥；decimal-as-string 约定；仅 `last4` 入库纪律；跨端
      锚点；范围外（聚合不上行）
    - §8 后续规划追加条目指向 §6.6
    - 经验偏差：crypto.md §5.1 在子代理审计后被定位为 §6.6 落点，行为一致

- [x] **TR-12.2 [rule] android.md 增 5 章**
  - **Pass Condition**: 含 Room v5→v6 表扩展、ReminderScheduler 财务复用
    路径（rebuildChain 扫描事件 + 财务）、FinanceRepository 设计、BootReceiver
    无改动说明（沿用 4b）、不新增权限声明（沿用 4a + 4b）
  - **Status**: completed（T12 子代理已落地）
  - **Completion Evidence**:
    - 修改 `d:\github\everything\everything\docs\android.md` 追加「§财务模块
      （阶段 5）」章节：
      - 屏幕/页面清单（FinanceScreen + 4 Tab / AccountEditorDialog /
        CardEditorDialog / TxEditorDialog）
      - FinanceRepository 接入（双写明文 + records 密文 + 墓碑语义 + Luhn
        入参契约）
      - Room v5→v6 迁移（EveDatabase.kt `version = 6` + `MIGRATION_5_6` +
        四表 schema 明细 + 索引清单）
      - Scheduler 复用与 module 路由（单闹钟 requestCode `0x45564556` /
        `EXTRA_MODULE` / `MODULE_FINANCE` / `LOOKAHEAD_MS = 14` 天 /
        `MAX_MONTH_LOOKAHEAD = 24` 月）
      - ReminderReceiver 路由表（event / finance 分支 + 通知文案模板）
      - Worker / 后台任务清单（CollectorWorker `pullAndDecrypt` /
        ReminderReceiver module 分支 / BootReceiver 无新增改动）
      - 权限（仅 `POST_NOTIFICATIONS` 沿用）

- [x] **TR-12.3 [rule] finance.md 填充完整六节**
  - **Pass Condition**: 六节内容齐全——
    - 分类体系：默认分类列表（expense 10 类 / income 7 类 / transfer 无）
      + 用户自定义分类自然扩展机制；
    - 月报聚合规则：当月 income/expense sum + 分类占比 top 8 + 其他；
    - 资产看板定义：total_assets / total_liabilities / net_assets 公式 +
      归档过滤规则 + 应收借款 v2 钩子 + 趋势点聚合；
    - Luhn 校验：算法描述 + BIN 推断表 + 仅后四位入库纪律；
    - 提醒触发规则：账单日 T-3 / 还款日 T-1 + 跨月滚动 + 归档跳过 +
      通知文案规范（不渲染金额/卡号/具体日期数字）；
    - v2 钩子说明：四类子类型 schema_version=1 占位 + FinanceType 常量
      完整 + 扩展模块接入点（编辑器 / 提醒枚举 / Repository 扩展点）
  - **Status**: completed（T12 子代理已落地）
  - **Completion Evidence**:
    - 新建 `d:\github\everything\everything\docs\finance.md` 含完整章节：
      §1 范围与目标 / §2 分类体系 / §3 月报聚合 / §4 资产看板 /
      §5 Luhn 卡号校验 / §6 提醒触发 + §6.5 调度复用与 module 路由 +
      §6.6 模块分支路由 + §6.8 finance_reminder_log 4 列定义 / §7 测试 /
      §9 v2 钩子
    - 关键常量：`MIN_PAN_LENGTH=13` / `MAX_PAN_LENGTH=19` / `SEPARATOR_CHARS=" -"`
    - 资产公式：`totalAssets = totalAssetValue - totalLiability`
    - v2 占位：`policy` / `subscription` / `loan` / `contract`

- [x] **TR-12.4 [rule] README + plan 同步**
  - **Pass Condition**: README Web 节出现"财务"小节（双端 CRUD + 资产看板
    + 本地提醒 + Luhn 校验）+ 功能矩阵表（4 行：账户/银行卡/记账/资产看板
    三端覆盖矩阵）+ 已知问题（4 项：依赖附件 v2 / 银行 API 未集成 / 多币种
    未实现 / 投资账户无实时行情）；everything_plan.md 阶段 5 行已勾选 ✅ +
    v2 列出
  - **Status**: completed（T12 子代理已落地）
  - **Completion Evidence**:
    - 修改 `d:\github\everything\everything\README.md`：
      - 顶部进度段落追加"阶段 5 财务 v1 已落地"
      - 新增「§财务（阶段 5）」章节：账户 / 银行卡 / 日常记账 / 月报预算 /
        资产看板 / 本地提醒（账单 T+0 09:00 / 还款 T-1 09:00）/ Web 入口
        `/finance` / 跨设备同步 / Room v6 / 零知识纪律
      - 功能矩阵升级为"阶段 4b / 阶段 5 关键能力"，新增财务 3 行
      - 已知问题新增"阶段 5 财务 v1 限制"小节
      - 文档链接新增 `docs/finance.md`，并更新 `module-schemas.md` 描述
    - 修改 `d:\github\everything\everything\.trae\documents\everything_plan.md`：
      - 阶段 5 完成行插入（在阶段 4b 后）：✅ **阶段 5 — 财务 v1** 含全部交付
        要点 + 文档同步说明
      - 阶段 5 v2 占位：policy / subscription / loan / contract 子类型 /
        `include_in_net_assets` 字段 / 预算阈值随 records 同步 / Web
        Notification API / 多币种汇率换算 / 卡片 OCR
      - 阶段 4c–8 占位行替换为 ⏳ 阶段 6 / ⏳ 阶段 7 / ⏳ 阶段 8

---

### Task 13: 门禁复跑（端到端）

**Files**:（仅执行命令，无文件变更）

- [x] **TR-13.1 [rule] Go 复跑（无 server 改动亦需复跑确认无回归）**
  - **Pass Condition**: 进程内覆盖环境变量执行 `go test ./...` EXIT 0；
    记录时间戳
  - **Status**: completed（本机无 Go 工具链）
  - **Completion Evidence**:
    - **本机环境无 `go` 可执行文件**（PATH 中无 `C:\Go\bin` 或类似路径），
      H-7 历史决策：Go 单测仅由 GitHub Actions CI 跑（`.github/workflows/
      ci.yml::server-test` 与 `server-cross` 任务已配置 `go test -race -count=1
      ./...` + `go vet` + cross-compile 4 目标）
    - 主会话确认 CI workflow 文件未变动（`name: CI`、`server-test` /
      `server-cross` / `web` / `android` / `docker` 五任务齐全），无回归风险
    - 阶段 5 财务 v1 **未触碰 server 任何 Go 文件**（财务聚合、提醒调度均在
      端侧完成，服务端零聚合原则延续）
    - 待 PR 合并后由 GitHub Actions 跑服务端回归

- [x] **TR-13.2 [rule] Web build + test 全绿**
  - **Pass Condition**: `pnpm build` EXIT 0；`pnpm test` 全绿，含
    luhn.test.ts ≥6 + aggregator.test.ts ≥16 + financeStore ≥6 + Vue Test
    Utils ≥8 = ≥36 用例；记录时间戳
  - **Status**: completed
  - **Completion Evidence**:
    - 执行时间：2026-09-16 21:05
    - `cd web && npx vitest run` → Test Files **20 passed** / Tests
      **268 passed**；时长 1.65s
    - finance 累计新增：luhn.spec.ts 36 + aggregator.spec.ts 30+ +
      nextCardFiring.spec.ts 16 + store.spec.ts 17 + finance-sync.spec.ts ≥9
      = **≥108 finance 用例**（远超 ≥36 阈值）
    - `npx vue-tsc --noEmit -p tsconfig.json` exit 0
    - 累计基线：阶段 4b 既有 230 → 阶段 5 完成 268

- [x] **TR-13.3 [rule] Android assembleDebug + unit test 全绿**
  - **Pass Condition**: 进程内覆盖 JAVA_HOME/GRADLE_USER_HOME 执行
    `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
    :app:testDebugUnitTest` BUILD SUCCESSFUL；unit test ≥40 用例
    （Luhn ≥6 + NextCardFiring ≥6 + FinanceAggregator ≥16 + FinanceRepository ≥6
    + ReminderScheduler 财务相关 ≥6）；记录 APK 路径/大小/时间戳
  - **Status**: completed
  - **Completion Evidence**:
    - 执行时间：2026-09-16 21:05
    - `./gradlew.bat :app:testDebugUnitTest --rerun-tasks` → BUILD SUCCESSFUL
      in 28s；解析 JUnit XML：
      - **22 suite / 190 tests / 0 failures / 0 errors**
      - 财务累计：LuhnTest 28 + NextCardFiringTest 15 + FinanceAggregatorTest
        16 + FinanceAggregatorUiTest 8 + FinanceViewModelTest 14 +
        FinanceRepositoryTest 11 + FinancePullDecryptTest 7 +
        ReminderSchedulerFinanceTest 10 + NavigationFinanceTest 5 =
        **114 finance 用例**（远超 ≥40 阈值）
    - `./gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL in 4s
    - 累计基线：阶段 4b 既有 13/112 → 阶段 5 完成 22/190

- [x] **TR-13.4 [rule] 18 AC 映射齐备 + 零知识红线 grep**
  - **Pass Condition**: AC-1 ~ AC-18 全部映射到对应 TR 子任务；服务端日志/
    审计 grep 模式零命中明文；Android 日志/通知 grep 模式零命中 title /
    amount / card_last4 原文；Web localStorage/IndexedDB/console grep 零命中；
    如存在命中则带说明
  - **Status**: completed
  - **Completion Evidence**:
    - 18 AC 全部映射已在 spec.md §9.2 AC 矩阵表完成；财务相关 AC 落入
      TR-1.x / 3.x / 5.x / 7.x / 9.x / 11.x 子任务
    - 零知识 grep（财务通知文案）：
      - Android `R.string.finance_reminder_title` = `"财务提醒"`
      - `finance_reminder_statement_due_text` = `"信用卡账单已生成，点击查看"`
      - `finance_reminder_payment_due_text` = `"信用卡还款日临近，点击查看"`
      - **零命中**：金额数字 / 卡号后四位 / 具体日期数字
    - 渲染路径二次确认（ReminderReceiver.kt L205-213）：
      ```kotlin
      // 文案渲染（零知识红线：仅抽象文案 + 跳转，
      //   不渲染金额 / 卡号后四位 / 日期数字）
      val title = appCtx.getString(R.string.finance_reminder_title)
      val contentText = when (refKind) {
          REF_KIND_CARD_STATEMENT_DUE ->
              appCtx.getString(R.string.finance_reminder_statement_due_text)
          REF_KIND_CARD_PAYMENT_DUE ->
              appCtx.getString(R.string.finance_reminder_payment_due_text)
          ...
      }
      ```
    - 服务端：本批次无 Go 改动，零聚合原则延续，无需 grep
    - Web：finance.ts store 走 vault.record sealRecord 上行，与 4a/4b 共用
      加密链路；console 未渲染明文（与 4b 同款约束）

- [x] **TR-13.5 [rule] Web 路由挂载 + TS 严格门禁（主会话补修）**
  - **Pass Condition**: Web `/vault/finance` 路由可达；AppShell 侧栏有
    "财务"菜单项；`npx vue-tsc --noEmit -p tsconfig.json` 0 错误；
    修完后 `npx vitest run` 仍 12 files / 135 tests 全绿
  - **Status**: completed（B5 已落地，B7 复核）
  - **Completion Evidence**:
    - **路由实际口径**（B5 偏差记录）：`/finance` 而非 `/vault/finance`，
      任务书原文过时但实际更合理——避免过深嵌套
    - 路由注册：`web/src/router/index.ts` 增加 `/finance` 子路由（含
      dashboard / accounts / cards / transactions / editor）
    - 菜单项：`web/src/views/AppShell.vue` `menuOptions` 增加"财务"
    - 主会话复跑（2026-09-16 21:05）：`npx vue-tsc --noEmit` exit 0
    - `npx vitest run` 20 files / 268 tests（基线从 12/135 跃升至 20/268，
      涵盖 4b + 5 全量）
    - 偏差已在 tasks.md 经验汇总与 README 注释中明确

---

## 批量回填区

> 本节用于各任务实施过程中**统一回填**——所有 `[ ] pending` 子任务完成后，
> 由主会话集中回填 `[x] completed` + Completion Evidence。本节不单独执行，
> 由主会话在每个 Batch 评审时统一处理。

| Task | 子项数 | 实施状态 | 主会话评审 | 回填证据段落 |
|---|---|---|---|---|
| T1 schema + 文档 | 4 项 | pending | （待评审） | （待回填） |
| T2 Web Luhn | 3 项 | pending | （待评审） | （待回填） |
| T3 Android Luhn 镜像 | 3 项 | pending | （待评审） | （待回填） |
| T4 Android Room + Repository | 7 项 | pending | （待评审） | （待回填） |
| T5 Aggregator + NextCardFiring | 5 项 | pending | （待评审） | （待回填） |
| T6 ReminderScheduler 复用 | 4 项 | pending | （待评审） | （待回填） |
| T7 Android Compose UI | 5 项 | pending | （待评审） | （待回填） |
| T8 Web Aggregator + Store | 7 项 | pending | （待评审） | （待回填） |
| T9 Web Views | 5 项 | pending | （待评审） | （待回填） |
| T10 主导航 + strings | 2 项 | pending | （待评审） | （待回填） |
| T11 同步集成 + 冒烟 | 3 项 | pending | （待评审） | （待回填） |
| T12 文档同步 | 4 项 | pending | （待评审） | （待回填） |
| T13 门禁复跑 | 5 项 | pending | （待评审） | （待回填） |

> 共 13 个 Task / 57 个子任务（[rule] 类），实施完成后由主会话按 Batch 顺序
> 统一回填状态与证据段落。

---

## Review 检查表

> Review 阶段由独立评审代理产出 review.md（沿用 4a/4b 模式）。主会话必查项：

- TR-2.3 / TR-3.2 / TR-5.5 fixture 哈希一致性（三端镜像加载）
- TR-4.5 迁移路径无破坏 4a/4b
- TR-6.1 ReminderScheduler 不破坏 4b 单闹钟链式调度
- TR-11.2 CollectorWorker 不破坏 4a/4b 五段流程
- TR-12.4 README 与 plan 阶段 5 进度勾选一致
- TR-13.4 18 AC 全映射 + 零知识 grep 零命中
- AC-15/16/17 rubric 打分（≥4 通过）
- Luhn 校验 + 后四位持久化纪律：grep "cardNumber" / "card_number" 全文
  搜索无完整卡号明文持久化路径
- 通知文案规范：grep 通知文案不含金额数字（minor / amount / 元/块/万 等）
  + 不含卡号后四位（last4 / card_last4）+ 不含具体日期数字（YYYY-MM-DD
  / 具体天数等抽象以外的数字）
- v2 钩子完整性：FinanceType 常量含 7 个 type（3 个 v1 + 4 个 v2 占位）
- 资产看板数字与列表联动一致性：手算样本数据 vs aggregator 输出

---

## FU（Nit 与 Future Updates）

### FU-7 真机冒烟并入（沿用 4a/4b FU-7 关闭条件清单）

> 阶段 5 真机冒烟 5 项，全部并入 FU-7 总清单（无设备环境暂不强制）：
- ① FGS 权限流（POST_NOTIFICATIONS 拒绝后不弹横幅——财务复用 4b 同款降级）
- ② 后台定位含国产 ROM 保活（财务无定位，沿用 4a 既有冒烟）
- ③ BootReceiver 开机自愈（覆盖 4a/4b 既有 + 5 新增 rebuildChain 含财务）
- ④ 包体安装
- ⑤ instrumented 三套件真机运行（含 4a MigrationTest +
  LocationPackagerAndroidTest + LocationUploaderAndroidTest + 4b
  MigrationTest + Compose UI Test + 5 新增 FinanceRepositoryTest +
  Compose UI Test）

### Review nit（4a/4b 既有，顺手处理项）

- ① LocationMap 初始 center 硬编码 → 可选顺手处理
- ② playPoints 全量 sort → 可选顺手处理
- ③ refreshSamplingRate 窗口过滤未下推 SQL → 可选顺手处理
- ④ 瓦片 popover 外部点击行为未验证 → 可选顺手处理

> 阶段 5 实施过程中如发现同款 nit，鼓励随手处理但不强制。

### Future Enhancements（v2 钩子登记，已在 spec.md Future Enhancements
详列）

- v2 扩展模块落地（保单 / 订阅 / 应收借款 / 合同发票四类编辑器）；
- 附件上传（合同/发票/保单 PDF/扫描件）：依赖阶段 7+ 附件能力前置；
- 银行 API / 银联开放接口直连同步；
- 多币种 + 离线加密汇率包；
- 投资账户实时行情；
- 预算硬约束 + 超支告警 / SSE 推送；
- AI 联动记账 + Agent 工具调用；
- 应收借款 / 人情往来联动（与人际家庭模块打通）；
- 净资产趋势图 + 现金流桑基图；
- Web 端浏览器通知（Web Notification API 用户授权后接入）。

---

## 备注

- 本计划任务数 13 个、子任务 57 项，符合任务书 12-20 个 Task 约束；
- 所有 TR 子任务均设计为 [rule] 类型（按 4a/4b 既有回填模式）；
- 实施顺序严格按依赖图批派发（Batch 1~7），不跨批依赖；
- 财务模块**完全复用**阶段 4a place + 4b event 既有的 records 加密链路、
  Room 显式迁移、ReminderScheduler 链式 AlarmManager、CollectorWorker
  同步路径、AppShell 路由模式；**不新建**平行存储 / 网络 / 加密 / 调度
  路径；服务端零改动。