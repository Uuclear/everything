# 阶段 5 — 财务端到端手动冒烟脚本

> 本文档对应 `.trae/specs/stage5-finance/tasks.md` 中 **Task 11 / TR-11.3** 的
> Pass Condition：覆盖 8 场景——Web 端账户/卡/流水的上行与零知识、Android 端
> `FinanceRepository.pullAndDecrypt` 拉取入 Room、墓碑跨设备、解密失败不静默、
> 闹钟链重建。其中"真机闹钟触发"在当前无真机环境下并入 **FU-7 关闭条件**（沿用
> 4a/4b FU-7 总清单，不在本批冒烟范围内强制）。
>
> 路径约定：
> - **Web** 仓库根 `web/`（Vue3 + Pinia + Vue Router + libsodium-wrappers）；
> - **Android** 仓库根 `android/`（Kotlin Compose + Room + AlarmManager + XChaCha20-Poly1305）。
>
> 复跑命令（与 docs 中其它冒烟脚本同款）：
> ```bash
> # Web 端（主会话复跑 gate）
> cd web && npx vitest run
> cd web && npx vue-tsc --noEmit
>
> # Android 端（主会话复跑 gate；无真机则仅跑 unit test + instrumented 编译）
> cd android && .\gradlew.bat :app:testDebugUnitTest --rerun-tasks
> cd android && .\gradlew.bat :app:compileDebugUnitTestKotlin :app:assembleDebugAndroidTest
>
> # Go 服务端无回归复跑
> cd server && go test ./...
> ```
>
> 零知识纪律（贯穿所有场景，比 4a/4b 更严——财务字段含余额/PAN/last4）：
> - 服务端 Go 日志 / 审计 grep 不出现 `balance` / `last4` / `pan` / `cardholder` / `amount`
>   / `merchant` 等明文；
> - Android 日志 / 通知文案 / SharedPreferences 不出现上述明文；
>   通知文案仅渲染抽象类别（如"您有一笔待记账项"）+ 时间；
> - Web `localStorage` / `IndexedDB` / `console.log` 不出现上述明文；
> - 完整 PAN 永不入库（仅保留 `last4` + `pan_first6` 可选 + `pan_last4` 必填）；
> - `localStorage` / `IndexedDB` 仅保留密文；明文仅驻 Pinia store 内存。
>
> 同步链路总览（TR-11.1 / TR-11.2 装配后）：
> - **Web → Server**：`financeStore.addAccount / addCard / addTx` 内部自动
>   `pushChanges([data])` → `crypto/envelope.sealRecord` → `api.pushRecords` →
>   `POST /api/v1/records/batch`，模块常量 `module="finance"` /
>   `type∈{"account","card","tx"}`；
> - **Server → Web**：`vault.sync()` 末尾追加 `financeStore.pullAll(since)`
>   → `crypto/envelope.openRecord` → 反序列化明文 → upsert 到对应子表 store；
> - **Server → Android**：`CollectorWorker.doWork` 末尾追加
>   `recordsRepository.listRecordsAfter(sinceMs)` 过滤 `module="finance"` →
>   `financeRepo.pullAndDecrypt(moduleRecords)` → `CryptoEnvelope.openRecord`
>   → upsert 到 `finance_account` / `finance_card` / `finance_tx` 表；
>   随后 `ReminderScheduler.rebuildChain(ctx)` 重注册还款/账单日闹钟链头；
> - **冲突策略**：LWW（last-write-wins），与既有 records 一致（按 `version`
>   单调递增，远程 version 更大则覆盖本地）；
> - **删除语义**：软删除——本地 `markDeleted(true)` + `version+1` +
>   `deleted=true`，远端收到墓碑覆盖明文；列表过滤 `deleted=false` 才可见；
> - **关联解绑**：删除账户/卡时，仅本地与远端标记墓碑；历史流水
>   `account_id` / `card_id` 置 `NULL`（保留金额事实），不级联删除。

> 引用：
> - `web/src/stores/finance.ts`（T8 落盘，T11 暴露 `pullAll` / `pushChanges`
>   / `CryptoChannel` 注入点；CRUD 内部自动 pushChanges）
> - `web/src/stores/vault.ts`（4a 已有 sync，本批在末尾追加 `financeStore.pullAll`
>   try-catch 挂载点）
> - `android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt`
>   （T4 骨架 + T11 完整重写：双签名 `pullAndDecrypt` + 4 DAO 联动 + 墓碑）
> - `android/app/src/main/java/com/everything/eve/data/RecordsRepository.kt`
>   （4a/4b 既有；T11 追加 4 个 finance 公开方法 + `listRecordsAfter`）
> - `android/app/src/main/java/com/everything/eve/data/RecordDao.kt`
>   （T11 追加 `getUpdatedAfter(sinceMs)`）
> - `android/app/src/main/java/com/everything/eve/data/finance/FinanceModule.kt`
>   （T11 新建——module / type 常量集中处）
> - `android/app/src/main/java/com/everything/eve/crypto/CryptoEnvelope.kt`
>   （AAD 格式 `eve:v1:record:{id}:{module}:{BE(uint64 version)}`）

---

## 场景 1：Web 新建账户 → 检查 Network → 看到 `module=finance/type=account` 密文上行

**目标**：验证 Web 端通过 `financeStore.addAccount` 走 `crypto/envelope.sealRecord`
+ `api.pushRecords` 链路，把一条账户以密文上行到服务端 `/records/batch`，
且 `balance` / `currency` / `archived` 等敏感字段仅出现在 `ciphertext` 之内。

**前置条件**：
- Web 已登录且资料库已解锁（master key 在内存）。
- 浏览器开发者工具 Network + Console 已打开。
- 后端 Go 服务在 `localhost:8080`（或用户配置的服务端地址）。

**步骤**：
1. 在 Web 端访问 `/finance` 路由（顶部导航"财务"入口）。
2. 点击"账户"标签页 → 点击"新建账户"，在 `AccountEditorDialog.vue` 表单填写：
   - 名称：`日常活期`（脱敏用文案，非必填）。
   - 类型：`cash`；币种：`CNY`；余额：`5000.00`。
   - 备注：`工行尾号 1234`（脱敏；**注意：完整账号不入库——这是零知识纪律硬约束**）。
   - 归档：off。
3. 点击"保存"。
4. 在浏览器开发者工具 Network 面板过滤 `records/batch`，找到刚才保存时的 POST 请求。

**期望**：
- 请求 path = `POST /api/v1/records/batch`。
- 请求 body 含一条记录且 `module="finance"`、`type="account"`、`id` 为
  UUID v4 字符串、`version=1`、`ciphertext` 是 Base64 密文（不可肉眼读出原文）。
- 请求 body 的 `balance` / `currency` / `name` / `note` 等明文字段**不在**
  任何服务端可观测位置（不出现于 `ciphertext` 之外）。
- 响应 `server_time` 字段非 0；UI 账户列表立即可见该账户（version 严格
  递增即时写本地缓存，与 `vault.savePlace` / `eventsStore.upsert` 同款即时性）。
- Network → Preview 面板看到的是 `module` / `type` / `id` / `version` /
  `ciphertext` / `created_at` / `updated_at` 这 7 个字段，无 `balance` /
  `currency` / `name` 等明文字段。

**失败排查**：
- `module` 不等于 `"finance"` → 检查 `FinanceModule.MODULE` 常量；
- `type` 不等于 `"account"` → 检查 `FinanceModule.TYPE_ACCOUNT` 常量；
- `ciphertext` 为空 → `sealRecord` 失败；多半是 `auth.sodium` / `auth.masterKey`
  在未解锁时为 null（应先 unlock 再 open Editor）。
- Network Preview 看到 `balance` / `currency` 等明文字段 → 立即回退——
  这是零知识纪律硬破，**不能上线**；检查 `defaultCryptoChannel()` 是否真
  调到 `sealRecord`，或检查 `addAccount` 是否误传明文而非 `payload`。
- 不见 records/batch 请求 → `financeStore.addAccount` 未被调用；
  检查 `AccountEditorDialog.vue` 的 save handler 是否真调到 store。
- Network 面板出现 401 → 检查 access token 刷新链路（4a 已修过的；
  与本批无关，跳过本次排障）。

---

## 场景 2：Web 新建信用卡 → 检查 last4 入库 / 完整 PAN 不入密文（零知识纪律）

**目标**：验证 Web 端通过 `financeStore.addCard` 走同款密文上行链路，但
**完整 PAN（卡号）永不入库**——仅 `last4` + 可选 `pan_first6` 入密文
明文；任何位置（Network / IndexedDB / localStorage / 通知）都搜不到
完整 16 位 PAN。

**前置条件**：
- Web 已登录且资料库已解锁（同场景 1）。
- 浏览器开发者工具 Network + Application（IndexedDB / localStorage）面板已打开。

**步骤**：
1. 在 Web 端 `/finance` 路由 → "卡片"标签页 → 点击"新建卡片"。
2. 在 `CardEditorDialog.vue` 表单填写：
   - 名称：`招行白金`。
   - 类型：`credit`；发卡行：`招商银行`；币种：`CNY`。
   - 卡号：`6225760099887766`（测试用 Luhn 合法的 16 位卡号——
     注意：完整 PAN 仅用于触发前端 last4 抽取，**不应被任何持久化层捕获**）。
   - 有效期：`12/29`；CVV：`123`（仅前端校验 Luhn，不入库）。
   - 账单日：`5`；还款日：`25`；信用额度：`50000.00`；APR：`0.18`。
   - 持卡人：`张三`（脱敏）。
3. 点击"保存"。
4. 检查 Network `records/batch` 请求；检查 Application → localStorage；
   检查 Application → IndexedDB → `finance-card`（如存在）。

**期望**：
- Network → Preview 看到 `module="finance"`、`type="card"`、`ciphertext`
  非空；**Preview 面板整页搜索 "6225760099887766" / "622576" / "9887766" /
  "123"（CVV）均无结果**。
- localStorage → 选中 `everything-vault` / 类似键 → 整页搜索上述卡号 / CVV
  均无结果（仅密文）。
- IndexedDB → 选中所有库 → 整页搜索同样无结果。
- 通知文案（如有"卡片已添加"toast）仅出现卡片名称 `招行白金`，不出现卡号
  任何片段。
- 解密后 store 内存中能看到 `last4="7766"`、`panFirst6="622576"`（如填写）、
  `cardholder="张三"`，但**完整 PAN 字符串本身不出现于 store 任何字段**。

**失败排查**：
- Network Preview / localStorage / IndexedDB 任一处能搜到完整 PAN 或 CVV
  → **零知识硬破，立即停手排查**：
  - 检查 `CardEditorDialog.vue` 的提交 handler 是否仅传 `last4` /
    `panFirst6` 而非完整 `pan`；
  - 检查 `Card` 类型（`web/src/finance/types.ts`）是否含 `pan` /
    `cvv` 字段（**不应有**）；
  - 检查 `defaultCryptoChannel().sealRecord` 输入是否含完整 PAN（应已被
    对话框层剥除）。
- `last4` 字段为空 / 长度 ≠ 4 → `CardEditorDialog.vue` 的 last4 抽取逻辑
  有 bug；回退到 `extractLast4()` 工具函数（沿用 4a Luhn 工具，不在此处
  强制重写）。
- `ciphertext` 为空 → 同场景 1。
- 通知文案出现卡号片段 → 检查 `useToast()` 调用是否误把 `pan` 传进
  message 参数。

---

## 场景 3：Web 新建流水 → 检查 transfer 字段 + account_id 路由正确

**目标**：验证 Web 端 `financeStore.addTx` 上行链路：流水含 `kind`
（expense / income / transfer）、`account_id` 关联账户、可选 `card_id`
关联卡、`amount`（正数 + `direction` 区分入出账）、`occurred_at`、
可选 `transfer_to_account_id`（转账场景）。

**前置条件**：
- 已完成场景 1（至少 1 个账户存在）。
- 已完成场景 2（至少 1 张卡存在；如不测卡消费可跳过）。
- Web 已登录且资料库已解锁。

**步骤**：
1. 在 Web 端 `/finance` 路由 → "流水"标签页 → 点击"新建流水"。
2. 在 `TxEditorDialog.vue` 表单填写：
   - 类型：`expense`（支出）。
   - 账户：选中场景 1 的 `日常活期`。
   - 卡：选中场景 2 的 `招行白金`（**注意：选了卡不等于覆盖账户——account_id
     必填，card_id 可选**）。
   - 金额：`88.50`；币种：`CNY`（自动取账户币种）。
   - 类别：`餐饮`；商家：`测试咖啡店`；备注：`脱敏备注`。
   - 时间：今天 12:30。
3. 点击"保存"。
4. 检查 Network `records/batch` 请求；解密后检查 store。

**期望**：
- Network → Preview：`module="finance"`、`type="tx"`、`version=1`、
  `ciphertext` 非空。
- 解密后 store 内存中看到：
  - `kind="expense"`；`amount=88.50`；`direction="out"`（与 `kind="expense"`
    派生一致）。
  - `accountId` 等于场景 1 账户的 id（**不等于 cardId**——账户是必填路由，
    卡是可选附加）。
  - `cardId` 等于场景 2 卡的 id。
  - `occurredAt` 是 ISO 字符串，与表单填写一致。
  - `category="餐饮"`；`merchant="测试咖啡店"`；`note="脱敏备注"`。
- Network Preview / localStorage / IndexedDB 整页搜索 `88.50` / `测试咖啡店`
  / `脱敏备注` 均无结果（仅 ciphertext 内部）。

**失败排查**：
- `accountId` 为 null 但填了账户 → `TxEditorDialog.vue` 的 account picker
  未正确传 id；检查 picker 的 `v-model` 绑定。
- `cardId` 等于 `accountId`（误把卡当账户） → 同上——picker 路由反了。
- 解密后 store 看到明文 PAN 残留 → 同场景 2 零知识排查。
- Network Preview 看到 `amount` / `merchant` 等明文 → 同场景 1 排查。

---

## 场景 4：Web 删除流水 → 检查墓碑（deleted=true + ciphertext=""）上行 + 列表过滤

**目标**：验证 Web 端 `financeStore.deleteTx` 走墓碑策略：本地软删除
（store 内存中标记 `deleted=true`）+ `version+1` + 上行密文中 `deleted=true`。
**列表过滤 `deleted=false` 才可见**。

**前置条件**：
- 已完成场景 3（至少 1 条流水存在）。

**步骤**：
1. 在 Web 端 `/finance` 路由 → "流水"标签页。
2. 找到刚才的场景 3 流水，点击行尾删除按钮 → 确认弹窗"确定删除？"→ 确认。
3. 检查 Network `records/batch` 请求；刷新页面再拉一次。

**期望**：
- Network → Preview：上行体含一条 `module="finance"`、`type="tx"`、
  `version=2`（比场景 3 的 version=1 大 1）、`ciphertext` 仍非空
  （解密后明文是 `{"id":"<tx-id>","deleted":true,...}`）。
- UI 流水列表立即不显示该条（store 内存过滤 `deleted=false`）。
- 重新刷新页面 / 触发 `vault.sync()` → 远端 `pullAll` → 服务端
  `listRecords` 返回该条 → 解密后仍是 `deleted=true` → 列表仍不显示
  （**墓碑不复活**——这是删除语义的核心契约）。
- Network → Preview 搜索原始 `amount` / `merchant` 字段不应出现
  （上行明文仅 `id + deleted=true + version+1`，不含业务字段）。

**失败排查**：
- 列表仍显示已删除流水 → `financeStore` 的 list getter 未过滤
  `deleted=false`；检查 `computed` / getter。
- 刷新后墓碑"复活"显示出来 → `pullAll` 路径未走墓碑处理（应调
  `deleteAccount/Card/Tx` 而非单纯 `upsert`）；参考 `event-rules.ts`
  的墓碑处理（4b 既有模式）。
- version 未递增 → `deleteTx` 内部未走 `version+1`；检查 payload 构造。
- 上行密文含完整业务字段 → 墓碑 payload 构造错误（应仅含
  `id + deleted=true` 而非整条流水）；检查 `deleteTx` 实现。

---

## 场景 5：Web vault.sync 触发 financeStore.pullAll → 拉取远端密文 → 解密入 store

**目标**：验证 Web 端 `vault.sync()` 在 4a 闭环完成后，末尾追加调
`financeStore.pullAll(since)` → 服务端 `listRecords` → `crypto/envelope.openRecord`
→ 反序列化明文 → upsert 到对应子表 store。**失败被 try-catch 吞掉不破坏
vault 闭环**（与 4a event-rules store 同策略）。

**前置条件**：
- 已在另一设备 / 另一浏览器窗口完成场景 1/2/3/4 中的任意一条上行
  （制造远端 records 增量）。
- 当前窗口已登录但**未触发过** `financeStore.pullAll`。

**步骤**：
1. 在当前 Web 窗口打开 `/finance` 路由 → 列表为空（或仅本窗口旧数据）。
2. 点击顶部导航的"同步"按钮 → 触发 `vault.sync()`。
3. 等待同步完成 toast。
4. 检查 Network 面板：应看到 `records/batch`（push）+ `records/list`
  （pull 各模块两次以上——places + events + finance 各一次）。
5. 检查 `/finance` 列表应出现另一设备刚才上行的账户/卡/流水。

**期望**：
- Network 面板出现至少一次 `GET /api/v1/records/list?since=<ms>&module=finance`。
- 响应体 `records[]` 含远端上行记录；解密后 UI 立即可见。
- 同步完成 toast 正常出现，无错误红条。
- 若 `financeStore.pullAll` 内部抛异常（解密失败 / 网络抖动），同步
  toast 仍正常出现，但 console.error 有 finance 模块相关 warning——
  **vault 闭环不被破坏**。

**失败排查**：
- Network 不见 `module=finance` 的 list 请求 → 检查 `vault.ts` 的
  `sync()` 末尾是否真追加 `financeStore.pullAll(...)` 块。
- 同步后 `/finance` 仍为空 → 解密失败但被静默吞掉；检查 console
  是否有 `AEADBadTagException` / `openRecord failed` 等错误日志。
- 同步 toast 红条出错 → finance 模块异常**穿透**到 vault（违反隔离
  契约）；检查 try-catch 是否真包裹 `financeStore.pullAll(...)` 整块。
- `pullAll` 把 `module=event` 记录错误 upsert 到 finance store →
  检查 `pullAll` 是否真过滤 `module === FinanceModule.MODULE`（常量
  对比，而非字符串字面量）。

---

## 场景 6：跨设备：Android CollectorWorker 触发 FinanceRepository.pullAndDecrypt → 拉远端 records → 解密入 Room account/card/tx 表

**目标**：验证 Android 端 `CollectorWorker.doWork` 在 4a location 采集 +
4b events 拉取后，末尾追加 `recordsRepository.listRecordsAfter(sinceMs)` 过滤
`module="finance"` → `financeRepo.pullAndDecrypt(moduleRecords)` →
`CryptoEnvelope.openRecord` → upsert 到 `finance_account` / `finance_card`
/ `finance_tx` 表。

**前置条件**：
- Android 真机或模拟器一台，已配对且解锁到主界面（Master Key 已就位）。
- 已在 Web 端完成场景 1/2/3（制造至少 1 个账户 + 1 张卡 + 1 条流水的远端 records）。
- Android 已配对同一账号。

**步骤**：
1. 在 Android 端打开主界面，进入"财务"标签页 → 此时 Room 库为空（或仅本地旧数据）。
2. 触发一次前台同步（顶部菜单"立即同步"按钮）或等待 `CollectorWorker`
   被 WorkManager 调度（默认约 15 分钟）。
3. 等待同步完成 toast。
4. 打开 `adb shell`（或 Android Studio Database Inspector）连接设备 →
  查看 `eve.db`：
   - `SELECT * FROM finance_account;`
   - `SELECT * FROM finance_card;`
   - `SELECT * FROM finance_tx;`
5. 核对行数与字段。

**期望**：
- `finance_account` 表出现 Web 端场景 1 的账户（`id`、`name`、`type="cash"`、
  `currency="CNY"`、`balance=5000.00`、`archived=false`、`deleted=false`、
  `version=1`、`updated_at` 为最近）。
- `finance_card` 表出现 Web 端场景 2 的卡（`id`、`name`、`type="credit"`、
  `issuer="招商银行"`、`last4="7766"`、`pan_first6="622576"`（如填写）、
  `cardholder="张三"`、`credit_limit=50000.00`、`apr=0.18`、
  `statement_day=5`、`due_day=25`、`deleted=false`、`version=1`）。
  **完整 PAN / CVV 字段不应存在**（表 schema 不含 pan / cvv 列）。
- `finance_tx` 表出现 Web 端场景 3 的流水（`id`、`kind="expense"`、
  `amount=88.50`、`direction="out"`、`account_id=账户 id`、
  `card_id=卡 id`、`category="餐饮"`、`merchant="测试咖啡店"`、
  `occurred_at=今天 12:30 ISO`、`deleted=false`、`version=1`）。
- Android 日志（`adb logcat -s SyncWorker`）出现 "finance sync ok，
  upserted=3" 类成功日志，无异常 stacktrace。
- SharedPreferences / 通知文案 整页搜索 `88.50` / `测试咖啡店` / `6225760099887766`
  均无结果。

**失败排查**：
- Room 三表均为空 → `CollectorWorker` 的 finance try-catch 块静默吞了
  异常；检查 `adb logcat -s SyncWorker` 是否有 `finance sync failed` +
  stacktrace。
- `finance_account` 有行但 `balance` 为 null → `pullAndDecrypt` 解析时
  漏字段；检查 `FinanceRepository.toJson(FinanceAccountEntity)` / `fromJson`
  双向覆盖（24+ 字段）。
- `finance_card.last4` 缺失 → `FinanceCardEntity` 的字段名映射错（camelCase
  ↔ snake_case JSON 双向转换有 bug）；参考 `FinancePullDecryptTest.kt`
  已有 roundtrip 用例。
- 完整 PAN / CVV 出现在 Room 表 → **零知识硬破**——schema 层就应无
  `pan` / `cvv` 列；立即停手排查（多半是测试时手动塞了伪造字段）。
- `SyncWorker` 主流程被 finance 异常打断 → 4a/4b 闭环被破坏；检查
  CollectorWorker 的 try-catch 块是否真在 events try-catch 之后独立
  包裹（不是合并到 events 块内）。

---

## 场景 7：墓碑跨设备：远端删除账户 → Android 收到墓碑 → 本地对应行 markDeleted

**目标**：验证跨设备墓碑语义：Web 端 `financeStore.archiveAccount` /
`deleteTx` 推墓碑 → Android `pullAndDecrypt` 收到墓碑 → 本地 Room 表
对应行 `deleted=true` + `version` 递增 → UI 列表过滤不显示。

**前置条件**：
- 已完成场景 6（Android 端 Room 三表有数据）。

**步骤**：
1. 在 Web 端 `/finance` 路由 → "账户"标签页 → 找到场景 1 上行的账户
  → 点击"归档"按钮 → 确认。
2. 触发 Android 端前台同步（或等待 WorkManager 调度）。
3. 等待同步完成。
4. Android 端 `/finance` 路由 → "账户"标签页 → 该账户应不显示。
5. `adb shell` 查 `SELECT id, name, deleted, version FROM finance_account WHERE id='<id>';`
  → `deleted=1`、`version=2`。

**期望**：
- Android 端 UI 列表立即不显示该账户。
- Room 表对应行 `deleted=true`、`version=2`（比原 version=1 大 1）。
- 历史流水（`finance_tx` 表）的 `account_id` 字段被置 `NULL`（**保留
  金额事实**），流水本身仍可见（不级联删除）。
- Android 日志 "finance sync ok, upserted=1（墓碑覆盖）" 类日志。

**失败排查**：
- 列表仍显示已归档账户 → `pullAndDecrypt` 未走墓碑处理；检查
  `FinanceRepository.pullAndDecrypt` 的墓碑分支（应调 `markDeleted`
  而非纯 `upsert`）。
- Room 表 `deleted=0` / `version=1` 未变 → 墓碑 payload 未上行；检查
  Web 端 `archiveAccount` 是否真调 `pushChanges([tombstonePayload])`。
- 历史流水的 `account_id` 仍指向已删除账户（未置 null）→ 墓碑应用
  时未级联清字段；检查 `FinanceRepository.pullAndDecrypt` 墓碑分支
  的 SQL：`UPDATE finance_tx SET account_id=NULL WHERE account_id=:id`。
- 历史流水被级联删除 → 与"墓碑不复活 + 关联解绑不级联"契约矛盾；
  检查 SQL 不应带 `DELETE FROM finance_tx WHERE account_id=:id`。

---

## 场景 8：解密失败：远端密文被改 / AAD 不匹配 → pullAndDecrypt 抛 AEADBadTagException（不静默）

**目标**：验证 Android 端解密失败的 fail-fast 语义：远端 records 被中间人篡改
或 AAD 不匹配（id / module / version 任一字段与密文绑定对不上）→
`CryptoEnvelope.openRecord` 抛 `javax.crypto.AEADBadTagException` →
`pullAndDecrypt` **不静默吞掉**，异常向上抛到 `CollectorWorker` try-catch
被记日志 + 不污染 Room 表。

**前置条件**：
- Android 真机/模拟器，Room 有数据（沿用场景 6）。
- 已能用 `adb shell` 或 Database Inspector 直接改 Room `records` 表。

**步骤**：
1. `adb shell` 进入设备 → 打开 Database Inspector → 找到 `eve.db` 的
  `records` 表 → 选中**任意一条** `module="finance"` 的行。
2. 把 `ciphertext` 字段改成 Base64 形式的随机字节（如 `"AAAAAAAAAAAAAA=="`）
  → 保存。
3. 触发 Android 端前台同步。
4. 观察 `adb logcat -s SyncWorker`。

**期望**：
- `adb logcat -s SyncWorker` 出现 `finance sync failed` + stacktrace 含
  `javax.crypto.AEADBadTagException` / `AEADBadTagException` 关键字。
- Room `finance_account` / `finance_card` / `finance_tx` 表**未被污染**——
  本批同步涉及的若干行要么原样保留，要么原样墓碑；**绝不存在"半解半坏"**
  的部分写入。
- `SyncWorker` 主流程未被中断（4a location + 4b events 闭环正常完成）。
- 重新触发同步前，把 `ciphertext` 改回正确密文 → 下次同步成功恢复。

**失败排查**：
- logcat 无 `finance sync failed` 日志 → 异常被静默吞掉（违反
  fail-fast 契约）；检查 `pullAndDecrypt` 是否真让 `AEADBadTagException`
  向上抛（不是 `try { ... } catch (e: Exception) { Log.w(...) }` 吞掉）。
- Room 表出现脏数据（部分字段被部分写入）→ `pullAndDecrypt` 缺
  事务边界；检查 `@Transaction` 注解是否真打在 `pullAndDecrypt` 上
  （单条失败应整体回滚）。
- 同步主流程被中断 → 4a/4b 闭环被破坏；检查 `CollectorWorker` 的
  try-catch 是否真在 events 之后**独立**包裹 finance 块（不是合并
  到 events 块内）。
- 重新触发同步仍失败 → 密文改回正确值后仍失败，多半是 AAD 计算错；
  检查 `CryptoEnvelope.recordAAD(id, module, version)` 的 BE(uint64)
  序列化（高位在前 8 字节）。

---

## 场景 9（可选扩展）：账单日/还款日闹钟：拉取后 ReminderScheduler.rebuildChain 重新对齐闹钟链头

**目标**：验证 Android 端 `pullAndDecrypt` 完成后，`ReminderScheduler.rebuildChain(ctx)`
被调用 → 扫描 `finance_card` 表所有 `due_day>0 && deleted=false` 的卡 →
对齐下一个闹钟触发时间（账单日前 1 天 / 还款日前 1 天）→ 调度 AlarmManager。

**前置条件**：
- 已完成场景 6/7（Room 有至少 1 张卡含 `due_day`）。
- Android 真机/模拟器，可观察闹钟列表（`adb shell dumpsys alarm | grep com.everything.eve`）。

**步骤**：
1. 触发前台同步。
2. 同步完成后立即 `adb shell dumpsys alarm | grep com.everything.eve`。
3. 核对闹钟触发时间是否对应该卡的 `due_day - 1` 天 09:00。

**期望**：
- `dumpsys alarm` 输出含至少 1 条 `com.everything.eve` 相关闹钟，
  触发时间在 `due_day - 1` 天 09:00 ± 5 分钟（业务自定义容差）。
- `finance_reminder_log` 表（如果走了"已提醒过"路径）出现一条
  `card_id=<id>` + `fired_at=<ms>` 的记录。

**失败排查**：
- `dumpsys alarm` 无 `com.everything.eve` 条目 → `rebuildChain` 未被
  调用；检查 `CollectorWorker` 的 finance try-catch 块末尾是否真调
  `ReminderScheduler.rebuildChain(ctx)`。
- 闹钟触发时间不对 → `ReminderScheduler.rebuildChain` 的 next-fire-time
  计算错；回退到 4b events 模块的 `rebuildChain` 实现做差分。
- 重复触发同一闹钟（闹钟链头没对齐）→ `rebuildChain` 未先 cancel
  旧闹钟再 schedule 新闹钟；检查 `rebuildChain` 入口是否先
  `cancelAllChainHeads(ctx)`。

---

## 附录 A：失败排查速查表（贯穿所有场景）

| 症状 | 可能根因 | 第一动作 |
| --- | --- | --- |
| 上行密文含完整 PAN / CVV | 对话框层未剥除 pan / cvv | 检查 `CardEditorDialog.vue` 提交 handler |
| `module` 不等于 `"finance"` | 字符串字面量错 | 检查 `FinanceModule.MODULE` 常量 |
| `pullAll` 拉到 module=event 记录 | 未过滤 module | 加 `module === FinanceModule.MODULE` 守卫 |
| Room 表被部分写入 | 缺事务边界 | `pullAndDecrypt` 加 `@Transaction` 注解 |
| 墓碑未上行 / version 未递增 | `deleteTx` / `archiveAccount` 未调 pushChanges | 加 `await pushChanges([tombstone])` |
| 历史流水被级联删除 | 墓碑 SQL 错带 DELETE | 改为 `UPDATE ... SET account_id=NULL` |
| 同步主流程被 finance 异常打断 | try-catch 块合并到 events | 拆成独立 try-catch 块 |
| 解密失败被静默吞掉 | 异常被 catch 吞 | 让 `AEADBadTagException` 向上抛 |
| 闹钟链头未对齐 | 未先 cancelAllChainHeads | `rebuildChain` 入口先 cancel |
| `vue-tsc --noEmit` 报错 | Pinia store 类型推错 | 检查 `useFinanceStore()` 返回类型 |

## 附录 B：参考门禁数（与 tasks.md TR-11.3 Pass Condition 对齐）

- Web：`npx vitest run` 应至少新增 `finance-sync.spec.ts` 10 个用例全绿；
  既有 `store.spec.ts` 17 个、`aggregator.spec.ts` / `luhn.spec.ts` /
  `nextCardFiring.spec.ts` 不破坏。
- Web：`npx vue-tsc --noEmit` exit 0。
- Android：`./gradlew.bat :app:testDebugUnitTest --rerun-tasks` 应至少新增
  `FinanceRepositoryTest` 10 个 + `FinancePullDecryptTest` 5 个 = 15 个用例
  全绿；既有 ≥172 + 新增 ≥9 = ≥181 tests。
- 服务端：`go test ./...` 无回归（财务 records 模块不破坏既有批上行链路）。

## 附录 C：与既有 4a/4b 的契约差异

| 项 | 4a places | 4b events | 5 finance |
| --- | --- | --- | --- |
| 模块常量 | `place` | `event` | `finance`（含 3 子类型） |
| 删除语义 | 物理删除（不进 records） | 墓碑 `deleted=true` | 墓碑 `deleted=true` + 关联解绑 |
| 闹钟 | 无 | `rebuildChain` | `rebuildChain`（沿用 4b） |
| 字段密度 | 低（~10） | 中（~30） | 高（card 24 / tx 21 / account 17） |
| 零知识敏感度 | 中（标题） | 中（标题/规则） | **高**（PAN/CVV/余额/金额/商家） |
| 跨设备冲突 | LWW（按 updated_at） | LWW（按 version） | LWW（按 version，与 4b 对齐） |

> **本批冒烟脚本与 4a/4b 同款——仅在"字段密度 + 零知识敏感度"两个维度更严**。
> 其余装配（加密链路、AAD 格式、墓碑协议、LWW、rebuildChain）沿用既有
> 模式，本文档不重复展开。