# 阶段 5 v2 — 财务二版增量 - 产品需求文档（PRD）

## Overview

- **Summary**：在阶段 5 财务 v1 已完成的基础上，**选择性落地 v1 spec 中已
  登记但 v2 才实施的 10 项 Future Enhancements**。v2 不重做 v1 既有架构，
  沿用 v1 的"前端 Room 加密 → 服务端密文 → 浏览器解密"骨架与
  FinanceAggregator / NextCardFiring / 单闹钟链式调度等既有组件，本 spec
  的全部 FR 仅在 v1 既有组件上做增量（新增模块 / 新增 UI / 新增端侧能力），
  **不动** v1 envelope / AAD / records / Room 表 / 服务端 envelope / Pinia
  store 主结构。
- **Purpose**：把 v1 中"承诺但延后"的 10 项能力集中落地（含 4 类子类型编辑器
  + 银行 API 直连 + 多币种汇率 + 投资账户行情 + AI 联动记账 + 预算硬约束 +
  Web 端提醒），使财务模块达到"可订阅 / 可保单 / 可借款 / 可合同 / 可多币种
  / 可行情 / 可 OCR / 可拦截"的自托管完整盘，为阶段 6（AI Agent）提供
  "财务规划 / 现金流预测 / 预算拦截 / 卡片识别"四类高价值上下文。
- **Target Users**：自托管 Everything 服务、单 Android 主机 + 任意浏览器的
  唯一用户本人。本期仍维持**单用户自托管**，不引入多用户 / 共享 / 第三方
  服务商直连（银行 API 仅支持**离线导入** + **手动同步**，不直连银联/PLAID）。

## Goals

- **G-1**：v2 启用 4 类子类型编辑器（保单 / 订阅 / 应收借款 / 合同发票），
  复用 v1 `FinanceType` 钩子（含 `POLICY/SUBSCRIPTION/LOAN/CONTRACT` 4 个
  枚举值）。
- **G-2**：v2 启用后 3 类提醒（订阅扣费 / 保单到期 / 借款到期），复用 v1
  单闹钟链式调度 + 提醒枚举钩子（含 `subscription_renewal / policy_expiry
  / loan_due`）。
- **G-3**：v2 启用附件完整闭环（合同 / 发票 / 保单 PDF + 图片附件），
  实现阶段 3 已登记但延后到财务 v2 的附件能力。
- **G-4**：v2 启用多币种汇率换算（加密离线汇率包）+ 投资账户手动同步
  + 实时行情抓取（自托管 RSS / HTTP 拉取，不接第三方券商 API）。
- **G-5**：v2 启用 AI 联动记账（OCR 卡片识别 / 语音记账）— Android 端
  on-device ML Kit（零 GMS 依赖）。
- **G-6**：v2 启用预算硬约束 / 告警 / 超支拦截（端侧实时阻断 + 文案告警）。
- **G-7**：v2 启用 Web 端提醒（Web Notification API + Service Worker）。
- **G-8**：v2 启用 `include_in_net_assets` 字段（v1 钩子已留）+ 应收借款
  `loans?: Loan[]` 入参已在 v1 aggregator 接入，v2 补全编辑器。
- **G-9**：跨模块打通：阶段 6 AI Agent 工具消费点 + 阶段 7 健康模块联动点。
- **G-10**：v1 零回归（22 suite / 190 tests / 20 files / 268 tests 全绿）。

## Non-Goals

- **不做** 多用户 / 共享 / 第三方服务商直连（PLAID / 银联 SDK / 支付宝 /
  微信支付 / 各券商 API）：v2 银行 API 仅支持**离线导入**（OFX / QIF / CSV
  文件级）+ **手动同步**（用户在 App 内手动触发 "导入" 按钮），不直连。
- **不做** 服务端聚合 / 分析 / 统计 / OCR：零知识约束下服务端不解密，
  任何聚合 / 分类 / 识别 / 拦截全部在客户端完成。
- **不做** 投资账户自动同步 / 自动再平衡：v2 投资账户仅手动录入持仓 +
  手动触发"同步行情"按钮（拉取自托管 RSS / HTTP 端点，不直连券商）。
- **不做** 服务端富文本编辑器 / 服务端文件存储：附件走 v1 records 通道
  加密上行，服务端仅承载密文 + 二进制块哈希。
- **不做** iOS 端：v2 仍仅 Android + Web；iOS 列入未来阶段。
- **不做** 服务端推送通道（FCM / Web Push 唤醒链路）：v2 沿用 v1 "本地闹钟
  + Web Notification API" 双轨；服务端不推送通知触发时刻。
- **不做** AI Agent 联动（阶段 6）：v2 仅在 finance store / repository 预留
  工具暴露点；不实现 Agent 调度本身。
- **不做** 实时汇率抓取（央行 / Fixer.io / Open Exchange Rates）：v2 汇率
  包走**离线下载 + 手动导入**（用户从央行官网下载 JSON / CSV 后导入），
  不直连网络拉取。
- **不做** 预算多账户联表 / 多用户预算共享：v2 预算仍单用户 + 端侧计算。

## Future Enhancements (v3 候选)

> v2 spec 完成后，以下能力明确列入 v3，本期不做：

- 多用户 / 共享 / 第三方服务商直连（PLAID / 银联 / 各券商）。
- 服务端富文本编辑器 / 服务端 OCR / 服务端汇率 API。
- iOS 端财务模块（Apple Wallet / HealthKit 联动）。
- AI Agent 调度链路（阶段 6 主体，消费 v2 暴露的工具）。
- 跨设备预算同步（共享调度表加密上行）。
- 投资账户自动同步 / 自动再平衡（券商 API 直连）。

## Background & Context

### 财务 v1 已交付锚点

- **既有架构**：四表 schema（`finance_account` / `finance_card` /
  `finance_tx` / `finance_reminder_log`）+ Room v5→v6 显式迁移（MIGRATION_5_6
  嵌入 EveDatabase.companion object）+ Luhn 校验（Web TS + Android Kotlin
  + 三端 fixture SHA-256 一致）+ FinanceAggregator（5 纯函数）+
  NextCardFiring（账单日 T+0 09:00 / 还款日 T-1 09:00，单点日期当月+下月
  双候选取最小）+ 单闹钟链式调度（rebuildChain 合并 event + finance 触发）
  + ReminderReceiver 按 module 路由（event / finance）+ Android Compose
  UI 9 文件 + Web Vue3 13 文件。
- **既有加密**：复用 v1 envelope；AAD `eve:v1:record:{id}:{module}:{BE_UINT64
  (version)}`，module="finance"。
- **既有零知识**：通知文案不渲染金额 / 卡号后四位 / 具体日期数字。
- **既有墓碑**：deleteAccount 触发 txDao.updateAccountIdNull + accountDao.delete。
- **累计**：22 Android suite / 190 tests / 20 Web files / 268 tests / 
  0 failures。

### v1 已留 v2 钩子

- `FinanceType` 常量已含 7 个 type（v1 启用 3 个 + v2 占位 4 个）：
  - v1 启用：`account` / `card` / `tx`
  - v2 占位：`policy` / `subscription` / `loan` / `contract`
- 提醒 `kind` 枚举已含 5 类（v1 启用前 2 + v2 占位后 3）：
  - v1 启用：`card_statement_due` / `card_payment_due`
  - v2 占位：`subscription_renewal` / `policy_expiry` / `loan_due`
- `aggregator.monthlyReport(...)` 已接 `loans?: Loan[]` 入参，v2 启用 loan
  时纳入应收借款。
- `attachments: [{id, mime, size, sha256}]` 字段 v1 占位未下发；v2 启用。
- `include_in_net_assets` 字段 v1 钩子已留；v2 启用。
- `FinanceRepository` 内部已留扩展点（`sealRecord` / `openRecord` 联动
  + `pullAndDecrypt` + 墓碑处理）。

### v1 → v2 不动条款

v2 spec 的硬约束：

1. **不动** v1 既有 envelope / AAD / records / finance 四表 / Pinia store 主
   结构 / Vue Router 主结构 / Chi 路由主结构。
2. **不破** v1 单测：v1 既有 22 Android suite / 190 tests / 20 Web files /
   268 tests 必须零回归。
3. **不新造** 服务端明文存储能力：附件 / OCR 结果 / 投资账户持仓 / 汇率包
   全部加密上行走 records 通道，禁止明文入库。
4. **不破坏** v1 零知识纪律：通知文案 / 日志 / 崩溃消息 grep 模式必须保持
   v1 红线水准（不渲染金额 / 卡号 / 坐标 / 具体日期数字）。
5. **不引入** GMS / Firebase / 第三方券商 SDK / 央行实时 API 直连。
6. **不破坏** v1 单闹钟链式调度：v2 启用后 3 类提醒**复用** v1 链式调度
   通道，不新建第二个 receiver。

## Functional Requirements

> 10 项 v2 能力按"模块 + 优先级"打包为 7 个 FR 组。每组内含子能力列表 +
> 跨端约束 + 零知识约束 + 与 v1 的扩展点。**优先级标注**：
> - **P0 必做**：v2 spec 核心交付
> - **P1 建议**：v2 spec 推荐实施但可在 v3 顺延
> - **P2 可选**：v2 spec 列出但不强制实施

---

### FR-V2-A：4 类子类型编辑器 + 完整提醒链路（P0）

#### FR-V2-A.1 订阅（subscription）编辑器

- **数据模型**：复用 v1 records 通道，新增 `type="subscription"` 子类型；
  明文字段：
  ```json
  {
    "id": "uuid",
    "name": "≤200 字符",
    "provider": "≤200 字符",
    "amount_minor": 0,
    "currency": "CNY",
    "billing_cycle": "monthly|quarterly|yearly|custom_days",
    "custom_days": 30,
    "start_ts": 1735689600000,
    "next_renewal_ts": 1738281600000,
    "reminders": [0, 1440],
    "active": true,
    "category": "entertainment|productivity|utility|other"
  }
  ```
- **CRUD**：Web + Android 双端 `SubscriptionList` + `SubscriptionEditor`
  + `SubscriptionCard`。
- **复用 v1 提醒**：subscription 复用 v1 events 单闹钟链式调度；链路扩展点
  = 在 `Reminders` 通道增加 `kind="subscription_renewal"` 分支 + 
  `subscription_renewal_due_text` 文案（**不渲染金额 / 续费日期数字**）。
- **零知识**：subscription 走同款 envelope，AAD module="finance" + type=
  "subscription" 子标识。

#### FR-V2-A.2 保单（policy）编辑器

- **数据模型**：新增 `type="policy"` 子类型；明文字段：
  ```json
  {
    "id": "uuid",
    "name": "≤200 字符",
    "policy_number": "≤100 字符（保单号，可加密）",
    "policy_number_encrypted": true,
    "provider": "≤200 字符",
    "premium_minor": 0,
    "currency": "CNY",
    "billing_cycle": "monthly|quarterly|yearly|single",
    "start_ts": 1735689600000,
    "expiry_ts": 1767225600000,
    "reminders": [0, 10080, 43200],
    "coverage_minor": 0,
    "active": true,
    "linked_account_id": null | "uuid",
    "attachments": []
  }
  ```
- **CRUD**：Web + Android 双端 `PolicyList` + `PolicyEditor`。
- **复用 v1 提醒**：在 `Reminders` 通道增加 `kind="policy_expiry"` +
  `policy_expiry_due_text` 文案（**不渲染保单号 / 到期日期数字 / 金额**）。
- **附件钩子**：policy 通过 `attachments` 字段引用 v2 启用的附件块存储
  （见 FR-V2-C.1）。

#### FR-V2-A.3 应收借款（loan）编辑器

- **数据模型**：新增 `type="loan"` 子类型；明文字段：
  ```json
  {
    "id": "uuid",
    "counterparty": "≤200 字符",
    "principal_minor": 0,
    "currency": "CNY",
    "direction": "lent|borrowed",
    "issue_ts": 1735689600000,
    "due_ts": 1767225600000,
    "interest_rate_apy_bps": 0,
    "status": "active|partially_paid|paid|overdue",
    "paid_minor": 0,
    "reminders": [0, 10080, 43200],
    "linked_account_id": null | "uuid",
    "include_in_net_assets": true
  }
  ```
- **CRUD**：Web + Android 双端 `LoanList` + `LoanEditor`。
- **v1 aggregator 启用**：`loans?: Loan[]` 已接 aggregator；v2 启用时
  应收借款纳入资产看板（`net_assets` += lent - borrowed）。
- **复用 v1 提醒**：在 `Reminders` 通道增加 `kind="loan_due"` +
  `loan_due_due_text` 文案（**不渲染对手方 / 金额 / 到期日期数字**）。

#### FR-V2-A.4 合同发票（contract）编辑器

- **数据模型**：新增 `type="contract"` 子类型；明文字段：
  ```json
  {
    "id": "uuid",
    "title": "≤200 字符",
    "counterparty": "≤200 字符",
    "kind": "rental|service|purchase|loan|other",
    "amount_minor": 0,
    "currency": "CNY",
    "signed_ts": 1735689600000,
    "start_ts": 1735689600000,
    "end_ts": 1767225600000,
    "auto_renew": false,
    "notice_period_days": 30,
    "notice_deadline_ts": 1764633600000,
    "status": "active|expired|terminated|renewed",
    "linked_account_id": null | "uuid",
    "attachments": []
  }
  ```
- **CRUD**：Web + Android 双端 `ContractList` + `ContractEditor`。
- **不自动生成提醒**：contract 不接 `Reminders` 通道主流程；如需提醒
  `notice_deadline_ts`，v3 评估（本期 Non-Goal）。

---

### FR-V2-B：附件完整闭环（P0）

#### FR-V2-B.1 附件块存储（沿用 records 通道）

- **存储格式**：附件走 v1 records 通道扩展，新增 `type="attachment"` 子
  类型；密文二进制块（AAD module="finance" + type="attachment" 子标识 +
  `attachment_id`）。
- **CRUD**：Web + Android 双端 `AttachmentManager`（上传 / 下载 / 列表 /
  删除 / sha256 校验）。
- **附件元数据**：在引用方记录（policy / contract / 任何带 `attachments`
  字段的记录）下挂 `[{id, mime, size, sha256}]` 列表。
- **零知识**：附件二进制全程加密上行；服务端仅承载密文 + sha256。
- **大小限制**：单文件 ≤ 50 MB（端侧校验，超限拒收）。

#### FR-V2-B.2 UI 集成

- **编辑器**：policy / contract / 任何带 `attachments` 的编辑器增加"附件
  上传"按钮（Android `ACTION_OPEN_DOCUMENT` / Web `<input type="file">`）。
- **列表**：附件展示缩略图（图片）/ 文件名 + 大小（非图片）。
- **查看器**：Web 端 PDF 走 PDF.js（开源、零网络依赖）；图片走原生
  `<img>`；Android 端 PDF 走系统 Intent + ACTION_VIEW。

---

### FR-V2-C：多币种汇率（P1）

#### FR-V2-C.1 多币种字段

- **v1 既有**：account / card / tx 均已含 `currency: string` 字段（v1 默认
  CNY 单币种）。
- **v2 启用**：在 Web 设置页与 Android 设置页增加"默认币种"选择（CNY /
  USD / EUR / JPY / HKD + 用户可手填 ISO 4217 代码）。

#### FR-V2-C.2 加密离线汇率包

- **汇率包格式**：
  ```json
  {
    "version": 1,
    "effective_ts": 1735689600000,
    "rates": {
      "USD/CNY": 7.25,
      "EUR/CNY": 7.85,
      "JPY/CNY": 0.048,
      "HKD/CNY": 0.93
    }
  }
  ```
- **导入**：Web 端 `/settings/rates` 与 Android `SettingsRatesScreen` 支持
  JSON 文件导入 → 加密上行（records 通道 `type="rate"`）→ 客户端解密后
  内存中作为 `RateTable` 单例使用。
- **零知识**：汇率包加密上行；服务端不解密。
- **不直连 API**：v2 **不**实时拉取央行 / Fixer.io / Open Exchange Rates；
  用户从央行官网下载 JSON 后手动导入。

#### FR-V2-C.3 资产看板多币种折算

- **aggregator 扩展**：v1 `FinanceAggregator.monthlyReport(...)` / 
  `netWorth(...)` 增加 `targetCurrency: string` 入参 + `rateTable: RateTable`
  入参 → 输出折算结果。
- **跨端纯函数镜像**：Android `FinanceAggregator.kt` + Web 
  `web/src/finance/aggregator.ts` 双锁定。
- **fixture**：≥6 用例（USD 转 CNY / EUR 转 CNY / JPY 转 CNY / 缺失汇率
  降级 / 默认币种为基准 / 多账户跨币种折算）。

---

### FR-V2-D：投资账户 + 手动行情（P2）

#### FR-V2-D.1 投资账户子类型

- **数据模型**：v1 `finance_account` 表 `kind` 字段已含 "investment"
  枚举值；v2 启用后增加 `holdings` 字段：
  ```json
  "holdings": [
    {"symbol": "AAPL", "shares": 10, "cost_basis_minor": 150000, "currency": "USD"}
  ]
  ```
- **CRUD**：Web + Android 双端 `InvestmentAccountEditor` 支持新增 /
  编辑 / 删除 holdings。

#### FR-V2-D.2 手动行情同步

- **行情源**：v2 **不直连券商 API**；用户在设置页配置"行情 RSS 端点"
  （自托管 HTTP / RSS）；手动触发"同步行情"按钮 → HTTP GET 端点 → 解析
  JSON 或 RSS → 客户端解密后填入 `current_price_minor` 字段。
- **行情格式**（自托管推荐示例）：
  ```json
  {
    "version": 1,
    "quotes": [
      {"symbol": "AAPL", "price_minor": 18500, "currency": "USD", "ts": 1735689600000}
    ]
  }
  ```
- **零知识**：行情包加密上行；服务端不解密。
- **aggregator 扩展**：`netWorth(...)` + `accountBalance(...)` 增加
  `quoteTable: QuoteTable` 入参 → 投资账户 `value_minor = sum(shares * 
  current_price_minor)`。

#### FR-V2-D.3 UI 集成

- 资产看板新增"投资账户"卡片（市值 + 持仓 top 5 + 同步行情按钮）。
- 月报页新增"投资账户市值变化"行（对比上月）。

---

### FR-V2-E：AI 联动记账（P1）

#### FR-V2-E.1 OCR 卡片识别（Android on-device）

- **引擎**：Android ML Kit Text Recognition（on-device 模式，零 GMS 依赖
  — 使用 `com.google.mlkit:text-recognition` 自包含 AAR）+ 本地启发式解析
  （金额 / 日期 / 商家名）。
- **触发**：用户在 Android 编辑器点"扫描小票"按钮 → CameraX 拍照 →
  on-device OCR → 启发式解析 → 预填 Editor 字段（用户可改）。
- **零知识**：OCR 全程设备本地；OCR 结果不入 records；仅用户确认后
  手动保存的 tx 才走加密链路。
- **权限**：`CAMERA` 权限申请；权限说明 UI 化。

#### FR-V2-E.2 语音记账（Android on-device）

- **引擎**：Android SpeechRecognizer on-device 模式（API 31+）+
  本地意图解析（"我刚买了个汉堡花了 35 元" → amount=3500, category=
  "dining", ts=now）。
- **触发**：用户在 Android 编辑器点"语音记账"按钮 → 录音 → on-device
  识别 → 启发式解析 → 预填 Editor 字段（用户可改）。
- **零知识**：语音转文字全程设备本地；语音数据不入 records。
- **权限**：`RECORD_AUDIO` 权限申请；权限说明 UI 化。

#### FR-V2-E.3 Web 端不做 OCR / 语音

Web 端仅提供文本输入 + iCal 导入（v1 既有）；OCR / 语音仅 Android。

---

### FR-V2-F：预算硬约束 + 超支拦截（P1）

#### FR-V2-F.1 预算配置

- **数据模型**：新增 `type="budget"` 子类型（v2 启用）；明文字段：
  ```json
  {
    "id": "uuid",
    "scope": "monthly|weekly|yearly|custom",
    "category": "all|dining|transport|...（含 v1 默认分类列表）",
    "amount_minor": 0,
    "currency": "CNY",
    "start_ts": 1735689600000,
    "end_ts": 1767225600000,
    "warning_threshold_pct": 80,
    "block_threshold_pct": 100,
    "active": true
  }
  ```
- **CRUD**：Web + Android 双端 `BudgetList` + `BudgetEditor`。

#### FR-V2-F.2 实时拦截（端侧）

- **触发时机**：用户在 Web / Android 编辑器保存 tx 之前 → 端侧计算
  当月累计同分类支出 → 若 ≥ `block_threshold_pct` → 弹"超支确认"对话框
  （"已用 105%，是否仍保存？"）。
- **降级路径**：用户在对话框选"仍保存" → 不阻断，仅记录 log 字段
  `overspend_acknowledged: true`。
- **零知识**：拦截判断全程客户端；服务端不参与。

#### FR-V2-F.3 文案告警

- **触发时机**：tx 保存后立即计算当月累计 → 若 ≥ `warning_threshold_pct` →
  应用内 toast / banner 显示"本月 [分类] 已用 85%，接近预算上限"。
- **月报页**：v1 已有阈值提醒文案，v2 增强为"硬约束告警 + 趋势线"。

---

### FR-V2-G：Web 端提醒（P1）

#### FR-V2-G.1 Web Notification API

- **触发链路**：Web 端 `FinanceView` / `SubscriptionEditor` / `LoanEditor` 
  等页面增加"允许浏览器通知"开关 → 用户授权后启用 Notification API +
  Service Worker。
- **本地缓存**：IndexedDB 缓存本地 finance 明文 + 触发时刻（不上行，仅
  本地副本）。
- **触发**：setTimeout / Service Worker `showNotification` 触发。
- **降级路径**：用户拒绝通知权限 → 退回"页面打开时 toast"路径。
- **零知识**：触发时刻不进 records；IndexedDB 仅本地副本。

#### FR-V2-G.2 Service Worker 注册

- `web/public/sw.js` 复用 v1 events 既有注册逻辑（v1 已为 events 启用
  Web Notification API，v2 复用同款 Service Worker）。
- **新增通知通道**：finance 通道（订阅扣费 / 保单到期 / 借款到期 3 类）。

---

## Acceptance Criteria (AC 矩阵)

> 共 20 个 AC，每个 AC 对应一组子能力 + 单测 / e2e 验证。

| AC | 标题 | 对应 FR | 验证方式 |
|---|---|---|---|
| AC-V2F-1 | subscription CRUD + 提醒触发 | FR-V2-A.1 | 双端单测 + 手动冒烟 |
| AC-V2F-2 | policy CRUD + 提醒触发 + 附件引用 | FR-V2-A.2 | 双端单测 + 手动冒烟 |
| AC-V2F-3 | loan CRUD + aggregator 应收借款纳入 | FR-V2-A.3 | 双端单测 + 三端 fixture |
| AC-V2F-4 | contract CRUD + 附件引用 | FR-V2-A.4 | 双端单测 + 手动冒烟 |
| AC-V2F-5 | 附件上传/下载/列表/删除 | FR-V2-B.1 + B.2 | 双端单测 + 手动冒烟 |
| AC-V2F-6 | 加密离线汇率包导入 | FR-V2-C.1 + C.2 | Web / Android 单测 + fixture |
| AC-V2F-7 | 多币种折算正确 | FR-V2-C.3 | 三端 fixture ≥6 用例 |
| AC-V2F-8 | 投资账户 holdings CRUD | FR-V2-D.1 | 双端单测 |
| AC-V2F-9 | 手动行情同步 + 资产看板市值 | FR-V2-D.2 + D.3 | 双端单测 + 手动冒烟 |
| AC-V2F-10 | OCR 卡片识别 | FR-V2-E.1 | Android instrumented test + 真机冒烟 |
| AC-V2F-11 | 语音记账 | FR-V2-E.2 | Android instrumented test + 真机冒烟 |
| AC-V2F-12 | 预算 CRUD | FR-V2-F.1 | 双端单测 |
| AC-V2F-13 | 超支拦截 + 文案告警 | FR-V2-F.2 + F.3 | 双端单测 + 手动冒烟 |
| AC-V2F-14 | Web Notification API 触发 finance | FR-V2-G.1 | Web 单测 + 手动冒烟 |
| AC-V2F-15 | Service Worker finance 通道注册 | FR-V2-G.2 | Web 单测 |
| AC-V2F-16 | `include_in_net_assets` 字段启用 | FR-V2-A.3 联动 | 三端 fixture |
| AC-V2F-17 | v1 零回归 + 22 suite / 190 tests / 20 files / 268 tests | 硬约束 | 三端门禁复跑 |
| AC-V2F-18 | 4 类子类型 schema_version + 常量完整 | FR-V2-A | 代码审查 + 单测 |
| AC-V2F-19 | 零知识红线 grep（v2 子类型通知 / 附件 / OCR / 语音全链路） | 硬约束 | grep + 手动冒烟 |
| AC-V2F-20 | 文档同步 + 端到端冒烟 + 门禁 | 收尾 | docs + smoke + 门禁 |

## 技术风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| 1 | 10 个 FR 范围过大里程碑失控 | 任务书分 8 批次推进；P0 强制 / P1 建议 / P2 可选 |
| 2 | OCR / 语音权限合规评估 | 权限说明 UI 化；用户拒绝仅隐藏入口；不阻断核心记账功能 |
| 3 | 投资账户行情源稳定性 | 仅支持自托管 RSS / HTTP；用户自负配置责任；不推荐公共 API |
| 4 | 附件体积膨胀 | 单文件 ≤ 50 MB 端侧校验；总附件配额按账户数线性 |
| 5 | ML Kit AAR 增加 APK 体积 | 按需模块化（`ml-kit:text-recognition` + `camera-camera2` 仅 release 启用） |
| 6 | 多币种汇率包手动维护成本 | 提供"导入向导"提示用户从央行官网下载；不直连 API |
| 7 | 预算硬拦截与用户体验冲突 | 默认 `block_threshold_pct=100` 仅弹确认；超 100% 才弹；可一键"仍保存" |
| 8 | Web Notification API Safari 兼容性 | 仅 Chrome / Edge / Firefox 主干支持；Safari 降级到"页面打开时 toast" |
| 9 | 服务端新增 envelope 风险 | 全部走 v1 records 通道；服务端零聚合原则延续 |
| 10 | v2 子类型字段膨胀导致 records 体积 | 子类型字段 `omitempty`；稀疏化处理 |

## 度量 / 验收

- v1 零回归：22 Android suite / 190 tests / 20 Web files / 268 tests 全绿。
- v2 新增：≥ 120 Android test + ≥ 80 Web test + ≥ 40 fixture 用例（含
  OCR / 语音 instrumented）。
- 端到端冒烟：`docs/smoke/stage5-finance-v2-e2e.md` 含 ≥ 15 场景。
- 文档同步：`docs/finance.md` v2 增量章节 / `docs/crypto.md` 附件 envelope
  章节 / `docs/android.md` OCR / 语音 / 行情 / 附件 章节 / `module-schemas.md`
  §9 v2 字段扩展 / `everything_plan.md` 阶段 5 v2 行勾选 ✅。
- 零知识 grep：通知文案 / 日志 / 崩溃消息 grep 模式保持 v1 红线水准；
  4 类子类型通知 + 附件通知 + OCR 预览 + 语音预览全链路零命中金额 / 
  卡号 / 坐标 / 具体日期数字 / OCR 原文 / 语音原文。
- AC 映射：20 个 AC 全部映射到对应 TR 子任务。
- APK 体积变化：release APK 增量 ≤ 8 MB（ML Kit + CameraX + Speech）。

## 后续路线（v3 候选）

- 多用户 / 共享 / 第三方服务商直连（PLAID / 银联 / 各券商）。
- 服务端富文本编辑器 / 服务端 OCR / 服务端汇率 API。
- iOS 端财务模块（Apple Wallet / HealthKit 联动）。
- AI Agent 调度链路（阶段 6 主体，消费 v2 暴露的工具）。
- 跨设备预算同步 / 跨设备提醒同步。
- 投资账户自动同步 / 自动再平衡。
- 阶段 7 健康模块联动点：health metrics → budget 联动（医疗支出预算）。

## 实施优先级建议

> **P0 必做**（FR-V2-A + FR-V2-B）：4 子类型编辑器 + 附件完整闭环，
> 是 v1 已承诺的钩子兑现，里程碑价值最高。
>
> **P1 建议**（FR-V2-C + FR-V2-F + FR-V2-G）：多币种汇率 / 预算硬约束 /
> Web 提醒；属于"价值高但可顺延"。
>
> **P2 可选**（FR-V2-D + FR-V2-E）：投资账户 / AI 联动记账；技术风险大
> （行情源稳定性 / OCR 权限合规），建议 v3 实施。