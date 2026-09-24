# 阶段 5 v2 — 财务扩展端到端手动冒烟脚本

> 本文档对应 `.trae/specs/stage5-finance-v2/tasks.md` 中 **Task 10 / TR-10.7**
> 的 Pass Condition：覆盖 ≥15 场景——订阅扣费提醒、保单到期提醒、借款到期提醒、
> 合同发票到期 + 附件 envelope、投资账户 + 手动行情、加密离线汇率包、
> 预算硬约束超支拦截、OCR / 语音记账、Web Notification API + Service Worker、
> Room v9→v10 迁移容错、v1 零回归。其中"真机闹钟触发 + ML Kit 端侧 OCR + 离线
> ASR"在当前无真机 / 无 OCR 训练集环境下并入 **FU-7 关闭条件**（沿用 4a / 4b /
> 5 v1 FU-7 总清单，本冒烟脚本给出可执行步骤 + 期望输出 + 失败降级路径）。
>
> 路径约定：
> - **Web** 仓库根 `web/`（Vue3 + Pinia + Vue Router + libsodium-wrappers
>   + Service Worker）；
> - **Android** 仓库根 `android/`（Kotlin Compose + Room v10 + AlarmManager
>   + XChaCha20-Poly1305 + ML Kit Text Recognition v2）；
> - **Go 服务端** 仓库根 `server/`（records 通道零改动，仅 envelope 字节存在性
>   校验）。
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
> 零知识纪律（v2 强化——在 v1 红线基础上新增保单号 / 合同号 / 附件块 envelope）：
> - 服务端 Go 日志 / 审计 grep 不出现 `policy_number` / `contract_number`
>   / `principal_minor` / `attachment_id` / `rate_minor` 等明文；
> - Android 日志 / 通知文案 / SharedPreferences 不出现上述明文；
> - 通知文案仅渲染抽象类别（"您有一项订阅即将扣费" / "您的保单即将到期"）
>   + 时间（YYYY-MM 粒度），**不渲染**金额 / 卡号后四位 / 保单号后四位 /
>   合同号后四位 / 具体日期数字；
> - Web `localStorage` / `IndexedDB` / `console.log` 不出现上述明文；
> - 完整保单号 → 入 `policy_number`（≤100 字符）+ `policy_number_encrypted=true`
  决定是否走密文通道（默认 true，与 `card.last4` 铁律同款截取末四位仅用于 UI 显示）；
> - 附件块 envelope `attachment_block.cipher` 仅 envelope 内，服务端永不可见
>   明文（XChaCha20-Poly1305 + AAD `eve:v1:attachment-block:{id}:{offset}`）；
> - 离线汇率包 envelope `rate.cipher` 仅 envelope 内，服务端永不可见
>   `rate_minor` 明文。
>
> 同步链路总览（v2 在 v1 基础上扩展）：
> - **Web → Server**：8 类 v2 子类型（`subscription` / `policy` / `loan`
>   / `contract` / `investment_account` / `attachment`）走既有 records 通道
>   + §5 AAD 不变；`quote` / `rate` / `holding` 不上行 records（仅端侧缓存）；
> - **Server → Web**：同上；
> - **Server → Android**：同上；`ReminderScheduler.rebuildChain(ctx)` 重注册
>   订阅扣费 / 保单到期 / 借款到期 / 合同发票到期的闹钟链头；
> - **冲突策略**：LWW（last-write-wins），`version` 单调递增；
> - **删除语义**：软删除——本地 `markDeleted(true)` + `version+1` +
>   `deleted=true`，远端收到墓碑覆盖明文；列表过滤 `deleted=false` 才可见；
> - **附件删除**：删附件 → 删除 attachment + 删除所有 attachment_block +
>   cascade_v2_contract.attachment_id = NULL（不级联删合同）。
>
> 引用：
> - `web/src/stores/finance.ts`（v2 扩展 `addSubscription` / `addPolicy` /
>   `addLoan` / `addContract` / `addInvestment` / `addAttachment` / `addRate`
>   + `quoteImport` 工具，CRUD 内部自动 pushChanges）
> - `web/src/stores/finance.quote.ts`（v2 新增，CSV 导入 + Room 缓存）
> - `web/src/stores/finance.rate.ts`（v2 新增，CSV 导入 + envelope 密文）
> - `web/src/stores/finance.attachment.ts`（v2 新增，sha256 客户端验签 +
>   256 KiB 切片上传）
> - `web/src/sw.ts`（v2 新增 Service Worker + Notification API 路由）
> - `android/app/src/main/java/com/everything/eve/data/finance/FinanceRepository.kt`
>   （v2 扩展 8 类子类型 + `BudgetEnforcer` + `OcrCaptureSheet` +
>   `VoiceCaptureSheet`）
> - `android/app/src/main/java/com/everything/eve/data/finance/QuoteImporter.kt`
>   （v2 新增，CSV 导入 ≤10000 行 + 同 `(symbol, as_of_ts)` 去重保留最高价源）
> - `android/app/src/main/java/com/everything/eve/data/finance/BudgetEnforcer.kt`
>   （v2 新增，CST 月度桶聚合 + 超支拦截 + `overspend_acknowledged` 审计列）

---

## §A 通用前置

执行所有场景前先确认：

1. **三端基线**：
   - 服务端 `go test ./...` 通过（无 v2 后端逻辑变更，仅 envelope 字节存在性
     校验保留）；
   - Web `npx vitest run` 通过（含 v2 单元测试 ≥47 个用例）；
   - Android `.\gradlew.bat :app:testDebugUnitTest --rerun-tasks` 通过
     （含 v2 单元测试 ≥558 个用例 + Room v10 schema 校验）。
2. **密文通道**：`module="finance"` + `type∈{"subscription","policy","loan",
   "contract","investment_account","attachment"}` 走 §5 AAD；
   `quote` / `rate` / `holding` 三类**不上行** records（仅 Room 缓存或
   envelope 密文）。
3. **零知识 grep**（贯穿所有场景）：
   - 服务端：`go test ./...` 后 `grep -ri "policy_number\|contract_number\|
     principal_minor\|attachment_id\|rate_minor" server/` 应为空
     （仅注释 / 测试 fixture 中允许）；
   - Android：`grep -ri "完整卡号\|完整保单号\|完整合同号" android/app/src/main/`
     应为空；
   - 通知文案：`grep -ri "renderAmount\|renderCardLast4\|renderPolicyLast4" android/app/src/main/java/com/everything/eve/ui/finance/` 应仅匹配通知文案的
     **禁止列表**（不得调用），不得匹配实际渲染。

---

## §B 场景列表（≥15）

### 场景 1：订阅扣费提醒 T-3 触发（Android 端本地闹钟链）

- **前置**：录入订阅"Netflix 标准版"，`cost_minor=3000`（30 元）、
  `next_bill_ts=2026-10-21 09:00 CST`、`billing_cycle="monthly"`、`auto_renew=true`、
  `payment_method="alipay"`、`card_id=NULL`。
- **步骤**：
  1. 提交订阅 → `financeSubscriptionRepo.upsert` → `recordsRepository.put(record)`
     → 上行 records；
  2. `ReminderScheduler.rebuildChain(ctx)` 重注册闹钟链头：当前最近 firing
     = `next_bill_ts - T-3 = 2026-10-18 09:00 CST`；
  3. 模拟时间至 2026-10-18 09:00 → `ReminderReceiver` 接收 →
     `module="finance"` + `kind="subscription"` → 拉取订阅详情（envelope
     解密）→ 通知文案 `"您的订阅即将扣费（3 天后）"` + 时间 `2026-10-21 09:00`
     （年月粒度，不渲染金额）；
  4. UI 端 `SubscriptionScreen` 列表项高亮"即将扣费"。
- **期望**：
  - 闹钟按 `next_bill_ts - T-3` 触发；误差 ±1 分钟；
  - 通知文案不出现 `3000` / `30.00` / `30 元` / `Netflix` 等明文；
  - 通知点击 → 跳订阅详情页（`/finance/subscriptions/:id`）。
- **失败降级**：
  - `SCHEDULE_EXACT_ALARM` 拒绝 → `setAndAllowWhileIdle` inexact + 写
    `finance_reminder_log.kind = "alarm_killed"` 留痕；
  - `POST_NOTIFICATIONS` 拒绝 → 仅系统通知（无应用内横幅，仍 OK）。

### 场景 2：保单到期提醒 T-30 + T-7 双档触发（Android 端本地闹钟链）

- **前置**：录入保单"平安车险"，`kind="auto"`、`policy_number="PINGAN-2026-AUTO-12345678"`
  + `policy_number_encrypted=true`（默认走密文通道）、`expiry_ts=2026-11-21`、
  `premium_minor=500000`（5000 元）。
- **步骤**：
  1. 提交保单 → 同上 records 通道（`policy_number` 走密文通道 +
     `policy_number_encrypted=true`）；
  2. `ReminderScheduler.rebuildChain(ctx)` 重注册：`expiry_ts - T-30 =
     2026-10-22 09:00` + `expiry_ts - T-7 = 2026-11-14 09:00` 双闹钟（链式
     `requestCode` 同款单链头 `0x45564556`）；
  3. 模拟至 2026-10-22 09:00 → T-30 触发 → 通知文案 `"您的保单即将到期（30 天后）"`
     + 时间 `2026-11-21`；模拟至 2026-11-14 09:00 → T-7 触发 → 通知文案
     `"您的保单即将到期（7 天后）"`。
- **期望**：
  - 两档闹钟按时触发；通知文案**不渲染**完整保单号 / `5000`（保费）；
  - 通知点击 → 跳保单详情页（`/finance/policies/:id`），详情页显示
    `(issuer, product_code)` 联合指纹 + UI 列表仅显示 `last4()` 末四位。
- **失败降级**：同场景 1。

### 场景 3：借款到期提醒 + 双向记账（lend_out / lend_in）

- **前置**：录入两笔借款——
  - A：`direction="lend_out"`（我借出）`principal_minor=100000`（1000 元）、
    `counterparty="张三"`、`due_ts=2026-10-25`、`interest_rate_pct=5.0`、
    `repaid_minor=20000`；
  - B：`direction="lend_in"`（我借入）`principal_minor=50000`（500 元）、
    `counterparty="李四"`、`due_ts=2026-10-26`、`interest_rate_pct=3.5`、
    `repaid_minor=0`。
- **步骤**：
  1. 提交 A + B → `financeLoanRepo.upsert` → records 通道；
  2. `ReminderScheduler.rebuildChain(ctx)` 注册 `T-3 = 2026-10-22`（A）+
     `T-1 = 2026-10-25`（A / 同日）双档；
  3. UI `LoanScreen` 列表显示：剩余应付（A）`= 100000 - 20000 = 80000 minor`
     = 800 元（**客户端纯函数**计算，不依赖服务端）；
  4. 模拟至 2026-10-22 → A T-3 通知文案 `"您有笔借出即将到期（3 天后）"`
     + 时间（不渲染金额 / 姓名）；模拟至 2026-10-25 → A T-1 通知文案
     `"您有笔借出明天到期"`。
- **期望**：
  - 双向记账正确（A 列入资产侧 / B 列入负债侧）；
  - 剩余应付 800 元计算正确；
  - 通知文案**不渲染**金额 / 张三 / 李四 姓名。
- **失败降级**：同场景 1。

### 场景 4：合同发票到期 + 附件 envelope（256 KiB 切片 + sha256 验签）

- **前置**：录入合同"租房合同"，`kind="rental"`、`due_ts=2026-10-31`、
  `attachment_id=NULL`；上传附件 `contract.pdf`（30 MiB < 50 MiB）。
- **步骤**：
  1. `attachmentRepo.upload(file)` → 计算 sha256（客户端）→ 256 KiB 切片
     → 每个块用 XChaCha20-Poly1305 加密（AAD = `eve:v1:attachment-block:
     {attachment_id}:{offset}`）→ 上行 records（块密文）；
  2. 附件元数据 L1 走 §5 AAD；块密文 L2 走专用前缀 envelope；
  3. `attachmentRepo.bindToContract(contractId, attachmentId)` →
     `finance_contract.attachment_id = attachmentId`；
  4. `ReminderScheduler.rebuildChain(ctx)` 注册 `T-30 + T-7` 双闹钟（合同发票
     到期）；
  5. 模拟至 T-7 → 通知文案 `"您的签合同即将到期（7 天后）"` + 时间
     （不渲染合同号 / 金额 / 附件名）。
- **期望**：
  - 30 MiB 附件切片 = 122880 块（30 × 1024 × 1024 / 256 KiB = 120 × 8 = 122880，
     实际 `ceil(30 MiB / 256 KiB) = 121` 块）；
  - sha256 客户端验签：下载所有块 → 拼接 → 计算 sha256 → 与附件元数据
     `sha256` 字段比对 → 一致则明文拼接 / 不一致则阻断（"该标的不完整，请重传"）；
  - 缺块时 UI 标红（"该标的不完整"）但不抛异常；
  - 通知文案零渲染。
- **失败降级**：
  - sha256 验签失败 → 阻断落库 + 提示用户重传；
  - 缺块 → UI 标红 + 服务端 records 校验发现 + 提示重传；
  - 上传 > 50 MiB → 客户端校验拒绝（"该标过大，仅支持 ≤ 50 MiB"）。

### 场景 5：投资账户 + 手动行情同步（Room 缓存 + 端侧纯函数聚合）

- **前置**：录入投资账户"招商白酒"，
  `kind="fund"`、`include_in_net_assets=true`、`last_synced_ts=2026-09-24`；
  录入持仓 `2026-09-24 161000 share=1000 cost_minor=100000`；
  手动行情 `quote(symbol="161725", price=150000 minor = 1.5 元, as_of_ts=2026-09-24 150000,
  source="manual")`。
- **步骤**：
  1. 提交账户 + 持仓 → records 通道（持仓表 `finance_investment_holding`
     **不入** records，仅 Room）；
  2. 手动行情录入 → `financeQuoteRepo.upsert` → **仅 Room**（quote 表不入
     records，不入 envelope）；
  3. UI `InvestmentScreen` 聚合：当前市值 = `1000 × 150000 = 150000000 minor`
     = 150 万元；累计收益 = `150000000 - 100000000 = 50000000 minor` = 50 万元；
  4. CSV 导入行情 `quoteImport.csv`（10000 行 ≤ 上限）：
     `symbol,price_minor,as_of_ts,source`；同 `(symbol, as_of_ts)` 仅保留
     最高价源。
- **期望**：
  - 聚合端侧纯函数计算正确（150 万 / 50 万）；
  - 持仓表 `finance_investment_holding` 不上行服务端
     （grep `holding` 在 records 通道中应为空）；
  - quote 表 `finance_quote` 不上行服务端（grep 同上）；
  - CSV 导入去重：同 `(symbol, as_of_ts)` 仅 1 条入库。
- **失败降级**：
  - quote 缺价 → 当前市值显示"暂未报价"（不抛错）；
  - 多设备同步 → quote 需各自重新录入（v2 不上行 records，v3 候选）。

### 场景 6：加密离线汇率包 + 多币种折算（envelope 密文 + 缺汇率保守放行）

- **前置**：录入汇率包 `rate.csv`：
  - `base=CNY, quote=USD, rate_minor=1407, effective_ts=2026-09-24`
    （1 CNY = 0.1407 USD）；
  - `base=CNY, quote=EUR, rate_minor=1300, effective_ts=2026-09-24`；
  - **未录入** `CNY→JPY` 汇率（模拟缺汇率场景）。
- **步骤**：
  1. `rateImport(csv)` → `financeRateRepo.upsert` → envelope 密文入
     `finance_rate.cipher`（明文 `rate_minor` 仅 envelope 内）；
  2. UI 多币种折算：人民币 1000 元 → 美元 140.7 元（按 envelope 内
     `rate_minor=1407` 折算）；人民币 1000 元 → 欧元 130 元；
  3. 模拟订阅聚合：人民币 30 元订阅 + 美元 5.99 元订阅 → 折算后总额
     = 30 + 5.99 × 7.107（CNY/USD 反向 = 1/0.1407 ≈ 7.107）= 30 + 42.57
     ≈ 72.57 元（**有 USD 汇率时折算**）；
  4. 模拟另一订阅：人民币 30 元 + 日元 600 円 → 缺 JPY 汇率 → **保守放行**
     = 30 + 30 = 60 元（按原币种累加，跳过 JPY 折算，不报错）；
  5. 模拟预算超支拦截：分类预算 100 元 + 实际 80 元（含日元的折算失败跳过）
     → 不触发超支（**保守放行不误拦**）。
- **期望**：
  - envelope 密文入库（grep `rate_minor` 在 `finance_rate` 表应为空，仅
    `cipher` 列有密文）；
  - 多币种折算正确；
  - 缺 JPY 汇率 → 不抛错、不误拦；
  - 通知文案不渲染折算金额。
- **失败降级**：
  - envelope 解密失败 → 视为缺汇率 → 保守放行；
  - 服务端 records 不接触 `rate_minor` 明文（grep 服务端应为空）。

### 场景 7：预算硬约束 B6 + 超支确认拦截（CST 月度桶聚合）

- **前置**：分类预算阈值 `category="餐饮", monthly_limit_minor=50000`（500 元）；
  录入流水：
  - 2026-09-15 餐饮 +200 元（`overspend_acknowledged=false`）；
  - 2026-09-20 餐饮 +400 元（累计 600 元 > 500 元，触发超支拦截）。
- **步骤**：
  1. 提交流水 → `BudgetEnforcer.check(tx)` → CST 月度桶（YYYY-MM）聚合
    = 200 + 400 = 500 元 + 400 元 = **超支 100 元**；
  2. UI 弹窗"超支确认：本月累计需支出 600 元，超过预算 100 元，是否确认？"
  3. 用户点击"确认" → `finance_tx.overspend_acknowledged=true` + 流水
     入库 + 写 `finance_reminder_log.kind="overspend_acknowledged"`；
  4. 用户点击"取消" → 流水不落库 + UI 提示"请调整金额"。
- **期望**：
  - CST 月度桶聚合正确（按 Asia/Shanghai 时区，不按 UTC）；
  - 超支拦截弹窗触发；
  - `overspend_acknowledged=true` 后流水入库；
  - 通知文案不渲染金额（"您本月餐饮分类已超出预算"）。
- **失败降级**：
  - 用户取消 → 流水不入库；
  - 预算阈值未录入 → 不触发拦截（视为端侧偏好）。

### 场景 8：OCR 记账落地准确度（端侧 ML Kit Text Recognition v2）

- **前置**：Android 端打开 `OcrCaptureSheet` → 拍摄 / 选择截图（含清晰
  金额 + 商家 + 时间）→ 端侧 ML Kit 识别（不离开端侧）。
- **步骤**：
  1. ML Kit 识别 → 候选字段：
     - 金额："30.59"（置信度 0.92）；
     - 商家："美团外卖"（置信度 0.88）；
     - 时间："2026-09-24 12:30"（置信度 0.85）；
  2. UI 弹窗"识别结果"，用户校对 → 点击"导入为流水"；
  3. 流水入库 `amount_minor=3059, merchant="美团外卖", occurred_at=2026-09-24 12:30`。
- **期望**：
  - 端侧 ML Kit 识别准确度 ≥80%（金额 / 商家 / 时间三字段均可手动改）；
  - 图像不上行服务端（验证）。
- **失败降级**：
  - 识别准确度 < 80% → 用户手动改；
  - 端侧 ML Kit 模型未下载 → 提示用户下载（约 10 MiB）；
  - 浏览器不支持（Web 端粘贴截图 OCR）→ 仅手动录入。

### 场景 9：语音记账（Web Speech API / Android 离线 ASR）

- **前置**：Android 端打开 `VoiceCaptureSheet` → 说出"今天中午打车花了三十五元"。
- **步骤**：
  1. Android 离线 ASR（vosk）识别 → "今天中午打车花了三十五元"；
  2. UI 解析：金额 3500 minor（35 元）、分类 = 交通、occurred_at = 今日中午；
  3. UI 弹窗"识别结果"，用户校对 → 点击"导入为流水"；
  4. Web 端浏览器 Web Speech API 同款流程（仅 Chrome / Edge 支持）。
- **期望**：
  - 端侧 ASR 识别准确度 ≥80%；
  - 金额 / 分类 / 时间字段均可手动改；
  - 音频不上行服务端。
- **失败降级**：同场景 8。

### 场景 10：Web 端 Service Worker + Notification API

- **前置**：Web 端 HTTPS（或 localhost）部署 → 浏览器打开 `/finance` →
  Service Worker 注册成功（DevTools → Application → Service Workers）。
- **步骤**：
  1. Service Worker 注册：`navigator.serviceWorker.register('/sw.ts')`；
  2. 用户授权 Notification 权限：`Notification.requestPermission()` → granted；
  3. 模拟订阅扣费 T-3 → Service Worker 接收 push → `self.registration.showNotification(...)`
     → 浏览器系统通知 `"您的订阅即将扣费（3 天后）"` + 时间；
  4. 点击通知 → 打开 PWA / 跳订阅详情页。
- **期望**：
  - Service Worker 注册成功（status: activated）；
  - 浏览器系统通知触发；
  - 通知文案零渲染（金额 / 卡号后四位 / 商家 / 具体日期数字）。
- **失败降级**：
  - Notification 权限 denied → 降级为站内横幅（不抛错、不阻断功能）；
  - Service Worker 注册失败 → 仅站内横幅；
  - Safari iOS 限制较多 → 仅站内横幅。

### 场景 11：Room v9 → v10 迁移容错（缺列默认 NULL + 缺表自动建）

- **前置**：模拟从 v9 升级到 v10（数据库版本号 `10`），库中 v9 表结构
  `finance_subscription` 缺 `auto_renew` 列、`finance_contract` 缺
  `attachment_id` 列。
- **步骤**：
  1. 启动 App → Room 检测版本 `10` → 触发 `MIGRATION_9_10`：
     - `ALTER TABLE finance_subscription ADD COLUMN auto_renew INTEGER NOT NULL DEFAULT 1`；
     - `ALTER TABLE finance_contract ADD COLUMN attachment_id TEXT`；
     - 新增 `CREATE TABLE IF NOT EXISTS finance_investment_account (...)`、
       `finance_investment_holding (...)`、`finance_quote (...)`、
       `finance_rate (...)`、`finance_attachment (...)`、
       `finance_attachment_block (...)`；
  2. 迁移失败 → 不抛错阻断同步 → UI 在下次启动自动重试；
  3. 迁移成功后，v9 旧数据保留（`auto_renew` 默认 `1` / `attachment_id` 默认 `NULL`）。
- **期望**：
  - 迁移成功 → 旧数据完整保留 + 新列默认值正确；
  - 迁移失败 → 不抛错、UI 自动重试；
  - 严禁 DROP / DROP COLUMN / 重命名（grep Room schema 应为空）。
- **失败降级**：
  - 迁移失败 → UI 显示"数据库迁移失败，请手动备份后重试"；
  - 严重错误 → 触发 `fallbackToDestructiveMigration()` 仅清空（**最严重**，
    须立即修复 schema 缺口）。

### 场景 12：v1 零回归（账户 / 卡 / 流水 + 预算 + 资产看板）

- **前置**：复用 v1 端到端冒烟脚本 `docs/smoke/stage5-finance-e2e.md` 的
  全部 8 场景。
- **步骤**：
  1. 跑 v1 全部 8 场景：Web 端账户 / 卡 / 流水的上行与零知识、Android 端
     `FinanceRepository.pullAndDecrypt` 拉取入 Room、墓碑跨设备、解密失败
     不静默、闹钟链重建；
  2. v2 升级后 v1 三类条目（account / card / tx）正常工作；
  3. v1 资产看板聚合（净资产 / 资产 / 负债 / 月报预算阈值 / 分类饼图 / 趋势点）
     不受 v2 升级影响。
- **期望**：
  - v1 全部 8 场景通过；
  - v2 schema_version=2 的子类型不影响 v1 schema_version=1 的子类型；
  - v1 `FinanceRecorder.income_in_net_assets` 字段（v1 以 `archived` 兜底）
     + v2 `include_in_net_assets` 字段（v2 明文）并存。
- **失败降级**：
  - v1 失败 → 立即回滚 v2 schema（手动 `fallbackToDestructiveMigration`）。

### 场景 13：保单号截取铁律（完整保单号不入任何持久层）

- **前置**：录入保单"平安车险"，完整保单号 `PINGAN-2026-AUTO-12345678`。
- **步骤**：
  1. `policyRepo.add(...)` → 客户端入 `policy_number="PINGAN-2026-AUTO-12345678"`
     + `policy_number_encrypted=true` → 完整保单号走密文通道 + Room
     `policy_number` 列（≤100 字符，密文信封内）；
  2. 完整保单号不入任何明文持久层：
     - grep `PINGAN-2026-AUTO-12345678` 在 Android Room / SharedPreferences /
       日志 / 通知 / 异常消息中应**全部为空**；
     - grep `PINGAN-2026-AUTO-12345678` 在 Web localStorage / IndexedDB /
       console.log 中应**全部为空**；
     - grep `PINGAN-2026-AUTO-12345678` 在服务端 records / 审计中应**全部为空**。
- **期望**：
  - 仅 `policy_number`（密文）+ `policy_number_encrypted=true` 入库；
  - 同保单识别依赖 `(issuer, product_code)` 联合指纹（同 card.last4 款）；
  - 通知文案不渲染完整保单号。
- **失败降级**：发现完整保单号入库 → 立即修复 `policyRepo` + 清库重建。

### 场景 14：附件 envelope 缺块容错（UI 标红 + 不抛错）

- **前置**：上传附件 `contract.pdf`（30 MiB，121 块）→ 删除中间第 60 块
  （模拟服务端丢块 / 同步缺失）。
- **步骤**：
  1. 下载附件 → 拼接所有块 → 计算 sha256 → 与 `finance_attachment.sha256`
     比对 → 不一致 → UI 标红"该标的不完整，请重传"；
  2. 不抛异常、不阻断其他功能（订阅 / 保单 / 借款 / 投资账户可正常使用）；
  3. 服务端 records 校验发现缺块 → 提示用户重传。
- **期望**：
  - sha256 验签失败阻断落库；
  - UI 标红友好提示；
  - 服务端 records 校验发现缺块。
- **失败降级**：同场景 4。

### 场景 15：手动行情 quote.id 确定性派生（`{symbol}:{as_of_ts}`）

- **前置**：手动录入行情：
  - `quote(symbol="161725", price=150000, as_of_ts=2026-09-24 150000,
    source="manual")`；
  - 同一 `(symbol, as_of_ts)` 重复录入（价格不同）。
- **步骤**：
  1. 客户端派生 `quote.id = "{symbol}:{as_of_ts}" = "161725:2026-09-24 150000"`；
  2. 同 `(symbol, as_of_ts)` 仅保留最后一条（覆盖） / CSV 导入仅保留最高价源；
  3. UI 列表显示唯一一条行情记录。
- **期望**：
  - quote.id 确定性派生（无需服务端分配）；
  - 重复录入去重；
  - quote 表**仅 Room 缓存**，不入 records（grep `finance_quote` 在 records
    通道中应为空）。
- **失败降级**：
  - quote.id 冲突（极端时区漂移）→ 客户端抛错 → 用户手动调整时区。

### 场景 16（额外）：OCR / 语音 / 附件 / 投资 / 预算 / 通知全链路零知识 grep

- **前置**：完成场景 1~15 全部通过。
- **步骤**：
  1. **服务端 grep**：
     ```bash
     grep -ri "完整保单号\|完整合同号\|principal_minor\|rate_minor\|attachment_id\|auto_renew" server/ | grep -v "_test.go\|//.*\s*说明"
     ```
     应为空（仅注释 / 测试 fixture 中允许出现变量名，不出现明文）；
  2. **Android grep**：
     ```bash
     grep -ri "完整卡号\|完整保单号\|完整合同号" android/app/src/main/
     grep -ri "renderAmount\|renderCardLast4\|renderPolicyLast4" android/app/src/main/java/com/everything/eve/ui/finance/
     ```
     前者应为空；后者应仅匹配通知文案的**禁止列表**（不得调用）；
  3. **Web grep**：
     ```bash
     grep -ri "完整保单号\|完整合同号\|principal_minor" web/src/
     ```
     应为空；
  4. **附件块 envelope grep**：
     ```bash
     grep -ri "eve:v1:attachment-block" android/app/src/main/ web/src/
     ```
     应仅匹配专用 AAD 前缀常量（`AttachmentCrypto.kt` / `attachment-crypto.ts`）；
  5. **离线汇率包 envelope grep**：
     ```bash
     grep -ri "rate_minor" android/app/src/main/ web/src/
     ```
     应仅匹配 envelope 密文构造 + 解密代码（`RateEnvelope.kt` / `rate-envelope.ts`）。
- **期望**：全部 grep 通过（无明文泄漏）。
- **失败降级**：发现明文泄漏 → 立即修复 + 重跑门禁 + 重新 build。

---

## §C 关闭条件清单（FU-7 v2 套件，沿用 4a / 4b / 5 v1 总清单）

> 当前无真机 / 无 OCR 训练集环境下，下列场景**不强制**在 CI 中跑通：
>
> 1. **真机闹钟触发**：场景 1 / 2 / 3 / 4 的实际闹钟触发（模拟时间至 firing
>    时刻）需真机验证；
> 2. **ML Kit 端侧 OCR**：场景 8 的端侧模型下载（约 10 MiB）+ 实际识别准确度
>    需真机验证；
> 3. **离线 ASR**：场景 9 的 vosk 模型（约 50 MiB）+ 实际识别准确度需真机验证；
> 4. **Web Notification API 权限授权**：场景 10 的浏览器权限需真实浏览器手动
>    授权验证；
> 5. **Service Worker PWA 安装**：场景 10 的 Service Worker 完整生命周期需
>    Chrome DevTools 验证；
> 6. **iOS Safari Notification API**：iOS Safari 限制较多，本批仅 Chrome /
>    Edge / Firefox 桌面验证。
>
> 上述场景给出**可执行步骤 + 期望输出 + 失败降级路径**，真机就绪后逐条勾选。