# 阶段 5 — 财务 - 产品需求文档（PRD）

## Overview

- **Summary**：在"人生操作系统"新增个人财务管理能力——双端（Web Vue3+TS 与 Android
  Kotlin Compose）均提供账户、银行卡/信用卡、日常记账三类核心条目与资产看板
  （净资产派生视图）；账单日/还款日/订阅扣费日等本地提醒复用阶段 4b 的
  ReminderScheduler / AlarmManager 链式调度（不新造通道、不依赖 FCM）；财务作为
  `module="finance"` 条目经 records 信封（XChaCha20-Poly1305 + AAD
  `eve:v1:record:{id}:{module}:{BE(uint64 version)}`）同步，**服务端零改动**，
  净资产/总资产/总负债全部在客户端聚合，不上行。保单/订阅/应收借款/合同发票四类
  扩展模块列入 v2，本期仅完成 v1 核心 + MVP 资产看板。
- **Purpose**：把个人财务纳入加密个人库——资产看得清、账单还得上、收支记得住，
  形成可被未来 AI Agent 助理查询的财务档案（"本月信用卡该还多少""保单什么时候
  到期"），同时维持服务端零知识边界（服务端只见密文与元数据），提醒走端侧本地
  闹钟（不依赖服务端可见的触发时刻）。
- **Target Users**：自托管 Everything 服务、使用 Android 主机的单一用户本人
  （不合规场景：他人代为记账；产品内不做服务端推送通道）。

## Goals

- 双端 CRUD：Web 与 Android 均可新建/编辑/删除账户、银行卡/信用卡、记账条目，
  经 records 通道加密同步；冲突策略 LWW（last-write-wins）。
- 资产看板（净资产）：客户端聚合派生视图——总资产 = 账户余额合计、总负债 =
  信用卡已用额度合计 + 应收/应付净值、净资产 = 总资产 - 总负债；看板为本地
  实时计算，不上行服务端。
- 银行卡/信用卡：支持卡号后四位录入与 Luhn 校验、账单日（每月 day_of_month）
  与还款日配置、额度（仅元数据字段，不入日志）、到期提醒。
- 日常记账：支持收入/支出/转账三类流水 + 分类体系（默认 + 自定义）；按日分组
  时间线、按月聚合；月报预算阈值提示（仅本地，不上行）。
- 本地提醒：复用阶段 4b ReminderScheduler 链式 AlarmManager；触发时机为
  账单日 T-3 / 还款日 T-1 / 订阅扣费日 T-1 / 保单到期 T-30 / 应收借款到期 T-7
  （v1 仅触发前两类，v2 扩展）；通知文案不渲染金额数字（零知识纪律）。
- 零知识：服务端只见密文 records；明文标题/金额/卡号/账户名仅存 Android Room
  与 Web 浏览器内存，不进 SharedPreferences / localStorage / IndexedDB / 日志 /
  通知文案 / 崩溃消息。
- 可验证：核心算法（净资产聚合、Luhn 校验、账期/还款日计算、月度汇总）抽为
  跨端共享纯函数；三端 fixture 镜像加载 + JVM + Vitest 双锁。
- 不破既有架构：完全复用阶段 4a `place` 与阶段 4b `event` 的 records 加密链路
  与 SyncWorker / PullAndDecrypt 模式；不新造平行存储/网络/加密路径。

## Non-Goals

- **不做**附件/扫描件上传（合同发票扫描件、保单 PDF）：阶段 3 spec 已登记附件
  顺延；财务模块依赖附件前置——v1 不含附件，合同/发票/保单仅支持外部链接字段
  （`external_url` 字符串）；附件能力待阶段 7+ 一并回填。
- **不做**银行 API 直连 / 自动同步流水（PLAID / 银联 SDK）：本期仅手动录入；
  v2 评估安全与隐私风险后再决定是否引入。
- **不做**服务端聚合/分析/统计：净资产、月度预算、收支分类全部在客户端计算；
  服务端不解密、不解析、不索引。
- **不做**多币种汇率换算：v1 仅单币种（CNY 默认，可在设置中改默认币种字符串
  标识，不做实时汇率）；v2 评估引入加密的离线汇率包。
- **不做**投资账户实时行情：账户类型含"股票/基金"分类但**仅手动录入持仓与
  市值**，不做实时行情抓取。
- **不做**v2 扩展模块（本期划在 Non-Goals，但保留 schema 钩子）：
  - 保单管理（policy）：险种/保费/到期提醒；
  - 订阅服务（subscription）：服务名/金额/周期/下次扣费日；
  - 应收/借款（loan）：谁欠我/我欠谁 + 到期提醒；
  - 合同与发票（contract）：合同标题/到期日/外部链接。
- **不做**Web 端提醒（仅 Android 走本地闹钟）；未来如做需 Web Notification API
  用户授权。
- **不做**AI 联动记账（"我刚买了什么"语音识别/OCR）：v2 评估。
- **不做**预算硬约束/告警/超支拦截：v1 仅在月报页显示阈值提醒文案，不阻断
  记账保存。
- **不做**单次实例覆盖编辑：转账流水视作两条对向记录（出账 + 入账），不引用
  对方 ID；如需"取消转账对账"等价于删两条 + 重录。

## Future Enhancements

> 以下能力**并非明确不做**，本期因范围控制先记录在案，后续阶段逐一完善。
> 各项均须继承本期零知识纪律（服务端只见密文，分析/调度在客户端或经用户明示
> 的本地能力完成）。

- v2 扩展模块落地（保单/订阅/应收借款/合同发票四类条目 + 编辑器 + 提醒接入）；
- 附件上传（合同/发票/保单 PDF/扫描件）：依赖阶段 7+ 附件能力前置；
- 银行 API / 银联开放接口直连同步（评估合规与隐私边界）；
- 多币种 + 离线加密汇率包（按月快照，密文入库）；
- 投资账户实时行情（加密的离线行情包推送，客户端解密渲染）；
- 预算硬约束 + 超支告警 / SSE 推送（评估对服务端可见的元数据暴露）；
- AI 联动记账（语音/OCR 自动建账）+ Agent 工具调用（"本月信用卡该还多少"
  /"自动建一笔转账"）；
- 应收借款 / 人情往来联动（与人际家庭模块打通）；
- 净资产趋势图 + 现金流桑基图；
- Web 端浏览器通知（Web Notification API 用户授权后接入）。

## Background & Context

- 现有加密通道：阶段 0–4b 已落地 `CryptoEnvelope.sealRecord/openRecord`
  （XChaCha20-Poly1305，AAD `eve:v1:record:{id}:{module}:{BE(uint64 version)}`）；
  财务模块**直接复用**该信封，不新造原语（AAD 见 spec FR-1 与 FR-3）。
- 现有同步链路：Android `RecordsRepository`（Room 5.x）+ 15 分钟周期
  `CollectorWorker`（4a 既有 SyncWorker 语义对等）+ `pullAndDecrypt` 模式
  （4b T10.2 已落地）；Web `vault.ts` 的 `sealRecord/pushRecords` +
  `pullRecords/openRecord` + `pullAll/pushChanges` store action（4b T10.1 已落地）；
  服务端 Chi `approved` 分组下 `/records/batch` 已承载批量幂等。财务模块
  **完全复用**既有链路，不新造。
- 现有 manifest 权限：4a 已声明 `POST_NOTIFICATIONS`、4b 已声明
  `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM`；财务提醒**完全复用** 4b 既有的
  `ReminderScheduler` + `ReminderReceiver`，**不新增权限**。
- 现有调度链路：阶段 4b `ReminderScheduler.nextTrigger/scheduleNext/rebuildChain`
  + 单闹钟链式 AlarmManager + `event_reminder_log` 降级日志表已落地；财务仅
  需提供财务条目的"下一次提醒时刻"纯函数即可接入。**不新建**第二个 Scheduler。
- 现有 manifest 通道：阶段 4a `BootReceiver` 已处理开机拉起 SyncWorker；
  财务不新增 receiver，仅复用既有链路。
- 现有 Android 存储模式：阶段 4b Room v5（`event` + `event_reminder_log` 两表）；
  财务 Room v5→v6 显式迁移（新增 `finance_account` / `finance_card` /
  `finance_tx` 三张主表 + `finance_reminder_log` 降级日志表）。
- 模块扩展约定（`docs/development.md`）：新增业务模块**不改 records 表**——
  定义模块 JSON Schema + 客户端表单即可；财务模块严格遵循。
- 全库代码现状：全库无 finance/account/card/transaction 实现，本期为
  **绿地构建**，但锚定既有 envelope/records/CollectorWorker/Room/Compose/
  Pinia/Chi/audit 既有模式。

## Functional Requirements

### FR-1 财务数据模型（schema 拆分）

财务作为**单一 module** = `"finance"`，所有子类型通过 `type` 字段区分，共用一条
records 链路：

```
module = "finance"
type   ∈ { "account", "card", "tx", "policy", "subscription", "loan", "contract" }
```

> **取舍说明**：选 A（单 module + type 子类型）而非 B（按子类型拆 module）：
> 财务是高度聚合场景——资产看板需要交叉查询（账户余额 + 信用卡已用额度 + 应收/借款），
> 单一 module 下客户端聚合更直接；records envelope 与版本管理按 module 维度
> 升级更顺畅。**MVP（v1）实际只下发三种 type**：`account` / `card` / `tx`；
> `policy` / `subscription` / `loan` / `contract` 在 v2 启用，本期预留常量与
> schema 钩子即可。

明文 JSON Schema 进 `docs/module-schemas.md` 第 9 章（财务模块）：

#### 1.1 账户（type=account）

```json
{
  "id": "uuid-string",
  "schema_version": 1,
  "name": "字符串（≤64 字符，必填）",
  "kind": "cash|bank|deposit|investment|wallet|other",
  "currency": "CNY",
  "balance_minor": 0,
  "balance_updated_ts": 1735689600000,
  "note": "纯文本，可选",
  "color": "blue|green|red|amber|violet|pink|cyan|slate",
  "include_in_net_assets": true,
  "archived": false
}
```

字段口径：

- `kind`：账户类型枚举——现金 / 银行借记卡 / 定期存款 / 投资账户 / 电子钱包 / 其他；
- `balance_minor`：余额最小货币单位（CNY = 分，int64；不为负——支持透支的卡走
  `type=card` 而非 account）；
- `balance_updated_ts`：余额更新时间戳（Unix 毫秒）；客户端维护，服务端不解密；
- `include_in_net_assets`：是否计入净资产看板（默认 true；归档账户自动 false）；
- `archived`：归档标志（软删除）；归档后不计入看板、不参与聚合。

#### 1.2 银行卡/信用卡（type=card）

```json
{
  "id": "uuid-string",
  "schema_version": 1,
  "name": "字符串（≤64 字符，必填）",
  "kind": "credit|debit|prepaid",
  "bank_name": "字符串（≤64 字符）",
  "card_last4": "字符串（4 位数字，必填）",
  "card_brand": "visa|master|unionpay|amex|jcb|discover|unknown",
  "card_holder": "字符串（≤64 字符，可选）",
  "currency": "CNY",
  "credit_limit_minor": 0,
  "used_minor": 0,
  "used_updated_ts": 1735689600000,
  "statement_day": 5,
  "due_day_offset": 25,
  "note": "纯文本，可选",
  "color": "blue|green|red|amber|violet|pink|cyan|slate",
  "include_in_net_assets": true,
  "archived": false
}
```

字段口径：

- `card_last4`：卡号后四位数字字符串（4 字符，0-9）；UI 输入完整卡号时做
  Luhn 校验后**仅保留后四位**入信封（前端校验为主，**不向后端传完整卡号**）；
- `card_brand`：根据卡号前缀推断（BIN 段），未识别为 `unknown`；
- `credit_limit_minor`：信用额度（信用卡必填，借记卡/预付卡为 0）；
- `used_minor`：当前已用额度（仅信用卡有效；非负；为 0 时按"未用"看板标注）；
- `statement_day`：账单日（1-31 的日；> 月底最大日取月底，如 31 在 2 月按 28/29）；
- `due_day_offset`：还款日距账单日天数（offset 模式：statement_day + offset，
  跨月滚动）；v2 可扩展"具体日"模式；
- `include_in_net_assets`：是否计入净资产看板（默认 true；archived 自动 false）；
- **Luhn 校验**：UI 录入完整卡号（16-19 位）→ 校验通过后**仅持久化后四位**；
  校验失败弹错并清空，不入库。

#### 1.3 日常记账（type=tx）

```json
{
  "id": "uuid-string",
  "schema_version": 1,
  "kind": "expense|income|transfer",
  "amount_minor": 0,
  "currency": "CNY",
  "occurred_ts": 1735689600000,
  "category": "字符串（≤32 字符，必填）",
  "account_id": "uuid-string | null",
  "card_id": "uuid-string | null",
  "to_account_id": "uuid-string | null",
  "to_card_id": "uuid-string | null",
  "note": "纯文本，可选",
  "tags": ["string"]
}
```

字段口径：

- `kind`：expense = 支出、income = 收入、transfer = 转账；
- `amount_minor`：金额最小货币单位（CNY = 分，int64；非负）；
- `occurred_ts`：发生时刻（Unix 毫秒；用户可改回历史日期补录）；
- `category`：分类字符串（默认见 FR-6 分类体系；用户可自定义）；
- `account_id` / `card_id`：出账方（expense/income 必填其一，transfer 必填）；
- `to_account_id` / `to_card_id`：转账入账方（仅 transfer 必填，且不能等于
  出账方）；
- `tags`：标签数组（用于多维筛选；可选）；
- **联动账户余额**：`expense` 触发 `account.balance_minor -= amount_minor`
  或 `card.used_minor += amount_minor`；`income` 反向；`transfer` 双向调整；
  调整时机为保存即生效（客户端内存中）；账户/卡被删除时其历史流水保留
  `account_id=null`/`card_id=null` 墓碑，避免历史断裂。

### FR-2 资产看板（净资产派生视图）

资产看板**完全在客户端聚合**，不上行服务端：

- **总资产（total_assets）** = sum(active_accounts.balance_minor)（仅账户，
 不含卡的"信用额度"——额度是负债而非资产）；
- **总负债（total_liabilities）** = sum(active_credit_cards.used_minor)；
- **应收净值** = sum(active_loans_i_own_to_others) - sum(active_loans_others_own_to_me)
 （v2 启用 loan 时纳入；v1 默认为 0）；
- **净资产（net_assets）** = 总资产 - 总负债 + 应收净值；
- **趋势点（trend_point）** = 按日聚合 {date, total_assets, total_liabilities,
 net_assets}；取自历史月度快照（v1 不持久化快照，按需即时计算）；
- **看板为本地视图**：仅在内存 + 浏览器/Compose 渲染层展示，**不写入** records
 表；不参与同步；刷新策略——账户/卡/流水变更后即时重算（O(N) 单用户量级 < 50ms）。

### FR-3 同步链路（沿用 4a/4b 既有 records 通道）

| 端 | 入库路径 | 触发 | 出库路径 |
|---|---|---|---|
| Web | `vault.saveFinanceRecord` → `sealRecord` → `pushRecords` | 编辑器保存即 dirty | `pullAll(sinceMs)` → `openRecord` → 内存态 |
| Android | `FinanceRepository.upsert` → `RecordsRepository` dirty | 编辑器保存即 dirty | `CollectorWorker.doWork()` 末尾追加（4b T10.2 模式） |
| Server | — | — | 仅做认证 + 中转 + 元数据索引（**零改动**） |

- 冲突策略：LWW（last-write-wins，与现有 records 一致）；
- 删除语义：软删除墓碑 `deleted=true` + payload 仍加密；客户端拉取后从内存
  移除对应条目；硬删除不提供（保留审计追溯）；
- 增量同步：`since` 参数沿用 4a/4b 既有 `pullAndDecrypt(sinceMs)` 接口，**不新增**；
- 模块挂载点：所有财务条目走 `module="finance"`，按 `type` 区分（`account` /
  `card` / `tx` / 后续 v2 子类型）；
- 加密信封：完全复用既有 `CryptoEnvelope.sealRecord/openRecord`，参数与 AAD
  不变（`eve:v1:record:{id}:{module}:{BE(uint64 version)}`，4a/4b 已三端验证
  字节级一致）。

### FR-4 Android 端 Room v5→v6 显式迁移

Room 5 → 6 显式迁移（沿用 4a v3→v4 / 4b v4→v5 既有模式）：

- 新增 `finance_account` 表（明文字段，Vault DB 容器内加密）：
  - 列与 FR-1.1 表逐字段一致（含 `id / name / kind / currency / balance_minor /
    balance_updated_ts / note / color / include_in_net_assets / archived / dirty /
    updated_ts`）；
  - 索引 `(kind)`、`(archived)`、`(dirty)`；
- 新增 `finance_card` 表（明文字段）：
  - 列与 FR-1.2 表逐字段一致（含 `id / name / kind / bank_name / card_last4 /
    card_brand / card_holder / currency / credit_limit_minor / used_minor /
    used_updated_ts / statement_day / due_day_offset / note / color /
    include_in_net_assets / archived / dirty / updated_ts`）；
  - 索引 `(kind)`、`(card_brand)`、`(archived)`、`(dirty)`；
- 新增 `finance_tx` 表（明文字段）：
  - 列与 FR-1.3 表逐字段一致（含 `id / kind / amount_minor / currency /
    occurred_ts / category / account_id / card_id / to_account_id / to_card_id /
    note / tags_json / dirty / updated_ts`）；
  - 索引 `(occurred_ts)`、`(category)`、`(account_id)`、`(card_id)`、`(dirty)`；
- 新增 `finance_reminder_log` 表（降级事件记录，复用 4b `event_reminder_log`
  模式但独立表——财务与日程共享枚举）：
  - `id INTEGER PRIMARY KEY AUTOINCREMENT`
  - `record_id TEXT NOT NULL`（finance 条目 id）
  - `kind TEXT NOT NULL`（`card_statement_due` | `card_payment_due` |
    `subscription_renewal` | `policy_expiry` | `loan_due`）
  - `occurrence_ts INTEGER NOT NULL`
  - `created_ts INTEGER NOT NULL`
  - 索引 `(record_id)`、`(kind)`；
- 明文驻留纪律：仅 Android Room 与 Web 内存；不进 SharedPreferences / 日志 /
  崩溃消息 / 通知文案（通知文案仅渲染抽象文案 + 跳转路由 id，不渲染金额数字）。

### FR-5 Android 端本地提醒（复用 4b ReminderScheduler）

财务提醒**完全复用**阶段 4b `ReminderScheduler.nextTrigger / scheduleNext /
rebuildChain`，**不新建**第二个 Scheduler；通过"扩展事件类型"接入：

- 财务条目在 `FinanceRepository` 内部实现 `nextReminder(rule)` 纯函数，输出
  `Long?`（下一触发时刻的 Unix 毫秒；无则 null）；
- `ReminderScheduler.rebuildChain()` 在遍历 4b 事件之外追加遍历财务条目（一次
  全局扫描取最小 nextReminder），统一写入单闹钟 PendingIntent；
- 触发时机常量（财务专属，在 spec/代码中以 `FiringOffset` 枚举表达）：
  - 信用卡账单日 `statement_day` **前 3 天**（`STATEMENT_OFFSET_MIN = 3 * 24 * 60`）；
  - 信用卡还款日（`statement_day + due_day_offset`，跨月滚动）**前 1 天**
    （`PAYMENT_OFFSET_MIN = 1 * 24 * 60`）；
  - 订阅扣费日（v2）**前 1 天**；
  - 保单到期（v2）**前 30 天**；
  - 应收借款到期（v2）**前 7 天**；
- 通知文案**不渲染金额数字**（零知识纪律），仅渲染抽象文案 + 跳转路由：
  - 例："💳 信用卡账单日 3 天后" + 跳转财务卡片编辑器；
  - 例："💳 信用卡还款日明天" + 跳转财务卡片编辑器；
  - 例："🔔 订阅扣费提醒明天" + 跳转订阅条目编辑器（v2）；
- v1 实现：仅触发前两类（账单日 T-3 / 还款日 T-1）；后三类（订阅/保单/借款）
  在 v2 启用时接入对应枚举与文案。

### FR-6 分类体系（记账默认分类）

财务记账默认分类（v1 内置常量，可在 Web 设置页与 Android 设置页查看/导出，
**不持久化分类字典到服务端**，仅客户端内置 + 自定义）：

- 支出（expense）：餐饮 / 交通 / 居家 / 购物 / 娱乐 / 医疗 / 教育 / 通讯 /
 旅行 / 其他；
- 收入（income）：工资 / 奖金 / 投资 / 兼职 / 红包 / 退款 / 其他；
- 转账（transfer）：无分类（仅记录金额 + 双方账户/卡）；

用户可在编辑器中输入自定义分类字符串（如"咖啡""健身"），作为新分类自然扩展；
分类列表 = `default_categories ∪ {历史流水中出现过的 category 去重}`，完全本地
聚合，不上行。

### FR-7 月报与预算阈值

月报页（v1）展示当月收支汇总：

- 总收入 = sum(income_tx.amount_minor where month(occurred_ts) = current)；
- 总支出 = sum(expense_tx.amount_minor where month(occurred_ts) = current)；
- 分类占比 = 按 category 分组聚合 top N + 其他；
- 预算阈值（可选设置）：用户输入"月度支出预算"（minor int64），超过阈值时
  在月报页顶部展示一次性提示横幅（**不阻断**保存、不上行服务端）；
- 预算阈值仅存 Android DataStore（沿用既有习惯）+ Web 内存 + 用户导出 JSON
  备份；**不写** records。

### FR-8 Web 端 UI（Vue3 + Pinia + Vue Router）

- 新增路由 `/vault/finance`（AppShell children 内，与 4a/4b 同款相对子路由）；
- AppShell 侧栏增"财务"入口（在"日历"之后）；
- 组件树：
  - `views/FinanceView.vue`：财务主入口（侧栏切换账户/卡/流水三 Tab + 资产
    看板顶部卡）；
  - `views/FinanceDashboard.vue`：资产看板（净资产 + 总资产 + 总负债 + 趋势点
    + 分类饼图）；
  - `views/AccountList.vue` + `views/CardList.vue` + `views/TxList.vue`：
    三类条目列表（卡片网格 + 搜索 + 排序 + 筛选）；
  - `components/AccountEditorDialog.vue` + `CardEditorDialog.vue` +
    `TxEditorDialog.vue`：三类编辑器（含校验 + Luhn 校验）；
  - `components/FinanceSummaryCard.vue`：净资产/负债/资产三数字卡 + 趋势 sparkline；
  - `stores/finance.ts`：Pinia store（CRUD + 聚合 + 选择窗口）；
  - `finance/aggregator.ts`：净资产/趋势/分类聚合纯函数（跨端共享 fixture）；
  - `finance/luhn.ts`：Luhn 校验纯函数（跨端共享）。

### FR-9 Web 端编辑器校验

`AccountEditorDialog.vue` 表单字段：
- 名称（必填，≤64 字符）
- 类型（kind 下拉，6 选项）
- 货币（默认 CNY，可改）
- 余额（数字输入，minor 单位）
- 备注（纯文本，可选）
- 颜色（8 色板）
- 计入净资产（默认 true）
- 归档（默认 false）

`CardEditorDialog.vue` 表单字段：
- 名称（必填，≤64 字符）
- 类型（credit / debit / prepaid 三选一）
- 银行名称（可选，≤64 字符）
- 卡号（输入完整卡号时仅显示后四位；Luhn 校验失败弹错；**不持久化完整卡号**）
- 卡品牌（按 BIN 自动推断）
- 信用额度（信用卡必填，minor 单位）
- 已用额度（可选，minor 单位；非负）
- 账单日（1-31 数字输入；超出当月最大日按月底处理）
- 还款日偏移（offset 数字输入，0-60）
- 备注（纯文本，可选）
- 颜色（8 色板）
- 计入净资产（默认 true）

`TxEditorDialog.vue` 表单字段：
- 类型（expense / income / transfer 三选一）
- 金额（数字输入，minor 单位；非负）
- 货币（默认 CNY）
- 发生时间（日期 + 时间；可改回历史日期补录）
- 分类（输入框 + 默认下拉建议）
- 出账账户/卡（下拉二选一必填）
- 入账账户/卡（仅 transfer 必填；不能等于出账方）
- 备注（纯文本，可选）
- 标签（chip 数组追加）

校验：
- 必填字段非空；
- 卡号 Luhn 校验（前端表单防住）；
- 账单日 1-31、还款日偏移 0-60；
- 转账双方账户/卡不同；
- 金额 > 0；
- 分类字符串 ≤32 字符；
- 备注 ≤500 字符（防滥用）。

保存：调 `financeStore.upsert(type, rule)` → `vault.saveFinanceRecord` →
`sealRecord` → `pushRecords`（4a `savePlace` + 4b `saveEvent` 同款链路）。

### FR-10 Android 端 UI（Kotlin Compose）

- `FinanceScreen`：财务主入口（顶部 Tab 切换账户/卡/流水 + 资产看板顶部卡）；
- `FinanceDashboard.kt`：资产看板（净资产 + 总资产 + 总负债 + 趋势 sparkline +
  分类饼图）；
- `AccountList.kt` + `CardList.kt` + `TxList.kt`：三类条目列表（LazyColumn +
  卡片样式 + 搜索 + 排序 + 筛选）；
- `AccountEditorScreen` + `CardEditorScreen` + `TxEditorScreen`：编辑器（与
  Web 端字段语义一致）；
- 入口：阶段 4b 已落地 `AppNav.kt` 的"日历"路由，本期追加 `Routes.FINANCE`
  + `composable(Routes.FINANCE) { FinanceScreen() }` 注册；
- 主导航入口：VaultScreen 顶部 TopAppBar actions 增"财务" TextButton（与
  "日历"/"采集"/"设备"并列）。

### FR-11 跨端纯函数 + 共享 fixture

`web/src/finance/aggregator.ts` 与 `android/.../finance/FinanceAggregator.kt`
同源：

```ts
function aggregate(accounts: Account[], cards: Card[], txs: Tx[], loans?: Loan[]): DashboardSnapshot
function monthlyReport(txs: Tx[], yearMonth: string): MonthlySummary
function nextCardFiring(card: Card, now: number): Long | null  // 4b ReminderScheduler 复用入口
```

`DashboardSnapshot` 字段：

- `total_assets_minor`（int64）、`total_liabilities_minor`（int64）、
  `net_assets_minor`（int64）、`accounts_count`、`cards_count`、`txs_count`；
- `category_pie`：分类聚合 [{ category, total_minor, ratio }]，top 8 + 其他；
- `trend_points`：按日聚合 [{ date: 'YYYY-MM-DD', total_assets_minor,
  total_liabilities_minor, net_assets_minor }]（v1 取最近 30 天）。

聚合算法要点：

- 账户过滤：`archived = false AND include_in_net_assets = true`；
- 卡过滤：仅 `kind = 'credit'` 且未归档且计入净资产 → 纳入总负债；
- 应收/借款：v2 启用（`loans?: Loan[]` 可选入参；v1 不传）；
- 分类饼图：expense 与 income 分别聚合（默认饼图展示支出 top N）；
- 趋势点：按本地日历日聚合，每日取最新账户余额快照——v1 不持久化快照，按需
  实时计算（O(N) 单用户量级 < 50ms）。

跨端测试向量（`finance/__fixtures__/cases.json`）：

- `aggregator`：空集合 / 单账户 / 多账户混合 / 信用卡已用额度 / 混合归档 /
  含应收借款（v2）/ 分类饼图 / 月报聚合 / 趋势点（30 天 / 跨年），≥16 用例；
- `luhn`：合法 16 位 visa / 合法 16 位 master / 合法 16 位 unionpay / 非法
  Luhn / 长度非法 / 全零 / 空串，≥6 用例；
- `nextCardFiring`：账单日 3 天内未触发 / 还款日 1 天内未触发 / 跨月还款日 /
  归档卡跳过 / 多卡取最小 / 未来无触发，≥6 用例。

三端测试断言：Web Vitest 与 Android JUnit 加载同一 fixture 文件（哈希一致），
输出 `DashboardSnapshot / LuhnResult / Long?` 逐字段一致。

### FR-12 文档

- `docs/module-schemas.md` 第 9 章：finance JSON Schema 三类（account/card/tx）
  + v2 子类型 schema_version=1 占位；
- `docs/crypto.md` §5.1 增 finance 走 records 同款链路说明 + AAD 不变说明 +
  `module="finance"` 与 `type` 子类型约定；
- `docs/android.md` 增 5 章：Room v6 扩展、ReminderScheduler 财务复用路径、
  FinanceRepository 设计、BootReceiver 无改动说明；
- `docs/finance.md`（**新增**，财务模块独立文档）：分类体系、月报聚合规则、
  资产看板定义、Luhn 校验、提醒触发规则、v2 钩子说明；
- `README.md` Web 节：增"财务"功能介绍（净资产/银行卡提醒/记账）+ 功能矩阵表；
- `everything_plan.md` L134 后：勾选阶段 5 完成（含 8 项要点 + v2 列出）。

## Non-Functional Requirements

- **NFR-1 零知识红线**：服务端持久化与日志中不得出现财务明文
  （账户名/卡号后四位/完整卡号/余额/金额/分类/备注/标签/银行名）；Android
  明文仅存 Room 且有界（与 4a/4b 一致）；Web 明文仅驻留浏览器内存，**不写**
  localStorage / IndexedDB / 日志 / 崩溃消息 / 通知文案；通知文案仅渲染
  抽象提示（如"信用卡账单日 3 天后"），不渲染金额数字或具体日期数字。
- **NFR-2 权限最小化**：**不新增**任何 Android 权限（4a `POST_NOTIFICATIONS`
  + 4b `SCHEDULE_EXACT_ALARM` + `USE_EXACT_ALARM` 三项已覆盖财务提醒需求）；
  不新增 receiver / service / provider；仅复用 4b `ReminderScheduler` +
  `ReminderReceiver` + `BootReceiver`。
- **NFR-3 可测性**：聚合算法（净资产/月报/分类/Luhn/nextCardFiring）抽为
  不依赖 Android Framework / 浏览器 API 的纯函数，由 JVM（Kotlin）与 Vitest
  （TS）单测覆盖；跨端共享 fixture 双锁定（Web `__fixtures__/cases.json` 与
  Android `test/resources/finance/__fixtures__/cases.json` 字节级一致）；
  Room v5→v6 走显式迁移并有 instrumented 迁移测试（无设备环境至少编译通过）。
- **NFR-4 闹钟配额**：财务提醒**完全复用** 4b 单闹钟链式调度（`ReminderScheduler.
  rebuildChain` 在事件 + 财务两类之间取全局最小），**不新增**任何 PendingIntent
  或闹钟注册；规避国产 ROM AlarmManager 配额与滥用检测。
- **NFR-5 兼容性**：minSdk 26 / targetSdk 35；零 GMS 依赖；国产 ROM 杀后台/
  权限收紧场景降级不崩溃（4b 既有降级路径覆盖财务）。
- **NFR-6 一致性**：Android 沿用 ServiceLocator 手动注入 / Room 显式迁移 /
  Compose Material3 / 中文详细注释；服务端零改动；Web 沿用 stores / crypto
  (envelope) / router 分层；不新造平行链路。
- **NFR-7 性能**：单用户量级（百级账户/卡 + 千级流水）下：
  - 资产看板聚合 < 50ms（O(N)）；
  - 月报聚合 < 30ms；
  - 趋势点聚合 < 80ms（30 天 × 千级流水）；
  - rebuildChain 含财务条目后全局扫描 < 200ms（百级条目量级）。

## Constraints

- **Technical**：
  - 加密复用 CryptoEnvelope 同参数（Argon2id / XChaCha20-Poly1305 与 AAD 规则
    **不得新造**）；财务 AAD 沿用既有 `eve:v1:record:{id}:{module}:{BE(uint64
    version)}`（module=`"finance"`），与 4a place / 4b event 三端逐字节一致；
  - 服务端零知识：不解密 / 不校验 amount / 不缓存明文余额；不引入新表 / 列 /
    接口；finance 与 place / event 同走 `/records/batch`；
  - 时间戳一律 Unix 毫秒 int64；金额一律最小货币单位 int64（CNY = 分）；ID
    一律客户端 UUID 字符串；
  - 模块扩展：新增业务模块不改 records 表——定义模块 JSON Schema + 客户端
    表单即可；财务模块严格遵循；
  - **不引入**附件（阶段 3 spec 已登记顺延）；v1 不做附件上传，v2 评估。
- **Business**：仅本人自托管场景；应用内不做财务数据滥用检测；通知文案避免
  渲染金额数字、卡号后四位、具体日期数字。
- **Dependencies**：Android 不新增第三方库；Web 无新增依赖（Pinia / Vue
  Router / Vitest 既有）；服务端预计无新增依赖；Android 已有 Robolectric
  复用（4b 已引入）。

## Assumptions

- 单用户单账号；多设备间财务条目经 records 通道 LWW 同步；
- 设备系统时间大致准确（流水 `occurred_ts` 取系统本地时区语义）；
- 单用户财务条目量级假设：百级账户/卡 + 千级流水 + 百级历史月度快照；
- 用户主动维护账户余额/卡已用额度（v1 无银行 API 直连，手动录入）；
- 卡号完整录入仅用于 Luhn 校验 + BIN 推断，**不持久化**；仅保留后四位；
- 4b `ReminderScheduler` + `ReminderReceiver` + `BootReceiver` 链路稳定可用，
  财务直接复用不新建。

## Acceptance Criteria

### AC-1: 财务数据模型三类 schema 与 type 子类型约定

- **Type**: `rule`
- **Given**: 任意前端表单输入
- **When**: 提交账户/银行卡/信用卡/记账条目
- **Then**: 落 records 表的密文对应明文符合 FR-1（account/card/tx 三类）；
  v2 子类型（policy/subscription/loan/contract）仅占位常量与 schema_version=1
  钩子，不下发；服务端不解密故无法校验（文档明示）
- **Pass Condition**: 三端字段定义逐字段一致（Grep module-schemas.md 第 9 章 +
  Android FinanceEntity + Web types）
- **Evidence**: 文档 diff + 代码行

### AC-2: 卡号 Luhn 校验 + 后四位持久化

- **Type**: `rule`
- **Given**: 用户在编辑器输入完整卡号（16-19 位数字）
- **When**: 失焦时做 Luhn 校验
- **Then**: 合法 → 仅保留后四位入信封（**完整卡号不持久化**）；非法 →
  弹错并清空；空串 → 不入库（card_last4 必填）
- **Pass Condition**: Web luhn.test.ts ≥6 用例全绿（含合法 visa/master/unionpay
  + 非法 Luhn + 长度非法 + 全零 + 空串）；Android LuhnTest ≥6 用例全绿；
  fixture 哈希三端一致
- **Evidence**: 单测输出 + fixture 文件哈希

### AC-3: 同步链路复用 records 通道（财务 module=finance）

- **Type**: `rule`
- **Given**: Web 端新建一个账户
- **When**: 保存 + 触发 pullAll（Android CollectorWorker 周期窗口）
- **Then**: 服务端 `/records/batch` 收到密文（无明文可验）；Android 解密后
  Room `finance_account` 表出现该记录；编辑另一端再保存后 pullAll 可见更新
- **Pass Condition**: 服务端 Go 测试无回归；Android CollectorWorker 测试通过；
  Web pushRecords/pullRecords 代码审查确认复用 4a/4b 链路
- **Evidence**: go test 输出 + Android 测试 + 代码行

### AC-4: Android Room v5→v6 显式迁移

- **Type**: `rule`
- **Given**: 4b 后的 Room v5 数据库
- **When**: 应用升级到 v6（新增 finance_account / finance_card / finance_tx
  / finance_reminder_log 四表）
- **Then**: 迁移脚本执行成功，旧数据保留；新增表索引符合 FR-4
- **Pass Condition**: MigrationTest instrumented 编译通过（无设备环境至少编译）；
  Grep Room Migration 调用链
- **Evidence**: MigrationTest 代码 + Gradle 输出

### AC-5: 净资产聚合 + 趋势点 + 分类饼图（客户端聚合）

- **Type**: `rule`
- **Given**: 混合账户 + 信用卡 + 流水的本地数据集
- **When**: 调 aggregator.aggregate(accounts, cards, txs) 或
  aggregator.monthlyReport(txs, yearMonth) 或 aggregator.trendPoints(...)
- **Then**: 输出 DashboardSnapshot 字段（total_assets / total_liabilities /
  net_assets / category_pie / trend_points）逐字段正确；归档账户/卡不计入；
  应收借款 v2 不传
- **Pass Condition**: Web aggregator.test.ts ≥16 用例全绿；Android
  FinanceAggregatorTest ≥16 用例全绿；fixture 文件哈希三端一致
- **Evidence**: 单测输出 + fixture 文件哈希

### AC-6: 财务提醒复用 4b ReminderScheduler

- **Type**: `rule`
- **Given**: 多个信用卡（含不同账单日 / 还款日 / 归档状态）
- **When**: 调 nextCardFiring(card, now) 或全链路 ReminderScheduler.rebuildChain
- **Then**: 返回 now 之后最近触发时刻（账单日 T-3 或 还款日 T-1）；归档卡
  跳过；多卡取全局最小；未来无触发返回 null；通知文案仅渲染抽象文案
  （"信用卡账单日 3 天后"）+ 跳转路由 id，不渲染金额/具体日期数字
- **Pass Condition**: ReminderSchedulerTest 财务相关用例 ≥6 全绿；代码审查
  确认复用 4b 链路（不新建 Scheduler）；通知文案 grep 验证不含金额/卡号
- **Evidence**: 单测输出 + Scheduler 代码 + grep 验证

### AC-7: 权限降级路径（财务复用 4b）

- **Type**: `rule`
- **Given**: Android 13+ 设备拒绝 SCHEDULE_EXACT_ALARM 或 POST_NOTIFICATIONS
- **When**: 触发一次财务闹钟
- **Then**: 调度降级为 setAndAllowWhileIdle + 不弹横幅；finance_reminder_log
  写入对应 kind；事件保存/查看不阻塞（与 4b 同款）
- **Pass Condition**: 代码审查确认降级分支（复用 4b 代码路径）；reminder_log
  表索引查询通过
- **Evidence**: Scheduler 代码 + log 表 Schema

### AC-8: 联动账户余额（流水保存即生效）

- **Type**: `rule`
- **Given**: 用户编辑一条 expense 流水关联某账户
- **When**: 保存流水
- **Then**: 该账户 `balance_minor -= amount_minor` 在客户端内存中即时生效；
  看板数字自动刷新；同步上行后另一端拉取可看到账户余额与流水一致；
  income / transfer 同样联动
- **Pass Condition**: Web financeStore.spec.ts 用例覆盖三类联动 ≥6；Android
  FinanceRepositoryTest ≥6；端到端冒烟用例 1-2 项
- **Evidence**: 单测输出 + store/repository 代码

### AC-9: Web 资产看板 + 三类条目列表 + 编辑器

- **Type**: `rule`
- **Given**: Room/Store 中有若干账户/卡/流水（含归档/混合类型/转账）
- **When**: 切换资产看板 / 列表 / 编辑器，新建/编辑/删除/归档闭环
- **Then**: 资产看板数字正确（净资产 = 总资产 - 总负债）；列表按类型筛选 +
  搜索 + 排序；编辑器 Luhn 校验 + 联动字段（账单日 + 还款日偏移）正确；
  归档条目不计入看板但列表可显示
- **Pass Condition**: Vitest 组件测试 + 代码审查
- **Evidence**: 单测输出 + 组件代码

### AC-10: Web 财务编辑器与分类体系

- **Type**: `rule`
- **When**: 输入名称/类型/币种/余额/卡号/账单日/还款日/分类/账户/卡/标签等
- **Then**: 字段校验正确；分类默认下拉建议正确（按 kind 过滤）；转账双方
  账户校验；保存即 dirty + push
- **Pass Condition**: Vitest + Vue Test Utils ≥8 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-11: Android Compose 编辑器与 Luhn + 联动

- **Type**: `rule`
- **When**: 输入同上（Web）
- **Then**: 同 AC-10（字段语义一致）+ Luhn 校验弹错 + 联动金额字段
- **Pass Condition**: Compose UI Test ≥6 用例全绿
- **Evidence**: 单测输出 + 组件代码

### AC-12: 月报 + 预算阈值（仅本地）

- **Type**: `rule`
- **Given**: 当月流水数据集 + 预算阈值（用户设置）
- **When**: 渲染月报页
- **Then**: 总收入/总支出/分类占比正确；预算超支展示一次性提示横幅（不阻断
  保存、不上行服务端）；预算值仅存 Android DataStore + Web 内存，**不写**
  records
- **Pass Condition**: 代码审查 + Web 组件测试 + Android UI Test ≥4 用例
- **Evidence**: 单测输出 + 组件代码 + DataStore 配置

### AC-13: 零知识红线全链路

- **Type**: `rule`
- **Given**: 任意场景下财务明文流转
- **When**: 检查服务端日志/审计、Android 日志/通知/SharedPreferences/Web
  localStorage/IndexedDB/控制台
- **Then**: 服务端仅见密文与 records 元数据（module="finance" / type 子类型）；
  Android 日志/通知无余额/金额/卡号后四位明文（仅"信用卡账单日 3 天后"类
  抽象文案）；Web 无明文持久化路径
- **Pass Condition**: grep 模式（Log[.dwiev]、putString、通知文案、localStorage、
  sessionStorage、IndexedDB、document.cookie）零命中或带说明
- **Evidence**: grep 检查记录

### AC-14: 文档与计划同步

- **Type**: `rule`
- **When**: 实现完成
- **Then**: module-schemas.md 第 9 章含 finance JSON Schema 三类 + v2 子类型
  占位；crypto.md §5.1 增 finance 链路说明；android.md 增 5 章含权限沿用 /
  Room v6 扩展 / Scheduler 财务复用路径；docs/finance.md（**新增**）含分类
  体系 + 月报规则 + 资产看板 + Luhn + 提醒规则 + v2 钩子；README Web 节增
  "财务"功能介绍；plan 阶段 5 进度同步
- **Pass Condition**: 文档评审通过且字段与代码/DTO 一致
- **Evidence**: 文档 diff

### AC-15: Web 财务页体验质量（rubric）

- **Type**: `rubric`
- **Dimension**: 财务页（资产看板 + 三类列表 + 编辑器 + 切换 + 同步闭环）的
  用户体验质量
- **Scale**: 1-5
- **Anchors**: 1 = 视图错乱/聚合失败无提示；3 = 功能可用但空态/加载/错误态
  粗略；5 = 资产看板数字与列表联动顺畅，编辑-保存-同步闭环自然，归档/筛选/
  搜索/排序交互清晰，预算阈值提示不干扰
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对财务页走查（含空数据/大量数据/混合类型/归档状态四态）

### AC-16: 架构一致性与可测性（rubric）

- **Type**: `rubric`
- **Dimension**: 新增代码与既有架构的契合度及纯函数可测性
- **Scale**: 1-5
- **Anchors**: 1 = 新造平行存储/网络/加密路径，逻辑耦合在 Activity/组件不可测；
  3 = 复用主通道但聚合/Luhn/nextCardFiring 散落、部分单测；5 = 纯函数核心 +
  薄平台适配层，完全复用信封/Room/Worker/Chi 分组/audit/ReminderScheduler，
  单测覆盖全部平台无关分支
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对模块边界与单测覆盖的走查

### AC-17: 闹钟可靠性（财务复用 4b 链路）（rubric）

- **Type**: `rubric`
- **Dimension**: Android 端财务提醒在国产 ROM 与权限收紧下的可靠性
- **Scale**: 1-5
- **Anchors**: 1 = 频繁漏触发/无降级；3 = 主线可达但杀后台后失效率高；
  5 = 链式调度 + BootReceiver 重建 + 权限降级 + 降级日志全链路闭环（4b 同款），
  真机冒烟通过
- **Pass Threshold**: >= 4
- **Evidence**: 独立评审对 ReminderScheduler/BootReceiver/finance_reminder_log
  的走查 + 真机冒烟记录（无设备则并入 FU-7 关闭条件）

### AC-18: v2 钩子完备性（schema_version + 常量预留）

- **Type**: `rule`
- **Given**: v1 上线后 v2 模块（保单/订阅/应收借款/合同发票）待扩展
- **When**: 检查代码与 schema 钩子
- **Then**: `FinanceType` 常量含 `POLICY/SUBSCRIPTION/LOAN/CONTRACT` 4 个
  v2 子类型；schema_version=1 字段保留；编辑器入口可灰度开关（v1 隐藏）；提醒
  枚举含 5 类 kind（v1 仅启用前两类）；`FinanceRepository` 内部已留扩展点
  （不强制运行）
- **Pass Condition**: 代码审查 + 单元测试覆盖 v1 + v2 类型枚举完整性
- **Evidence**: 代码行 + 单测输出

## 交付物清单

### 代码层

- `docs/module-schemas.md` 第 9 章（新增，财务三类 schema + v2 子类型占位）
- `docs/crypto.md` §5.1（更新交叉引用 + `module="finance"` 链路说明）
- `docs/android.md` 5 章（新增，Room v6 + Scheduler 复用 + Repository 设计）
- `docs/finance.md`（**新增**，财务模块独立文档）
- `web/src/finance/luhn.ts` + `luhn.test.ts`（新增）
- `web/src/finance/aggregator.ts` + `aggregator.test.ts`（新增）
- `web/src/finance/__fixtures__/cases.json`（新增，Android 镜像加载）
- `web/src/finance/types.ts`（FinanceAccount / FinanceCard / FinanceTx 接口）
- `web/src/stores/finance.ts`（Pinia setup store + 聚合 + 联动 + 提醒接入）
- `web/src/components/AccountEditorDialog.vue` + `CardEditorDialog.vue` +
  `TxEditorDialog.vue`（新增）
- `web/src/components/FinanceSummaryCard.vue`（净资产 sparkline 卡）
- `web/src/views/FinanceView.vue` + `FinanceDashboard.vue` + `AccountList.vue` +
  `CardList.vue` + `TxList.vue`（新增）
- `web/src/router/index.ts`（更新：增 `/vault/finance` 路由）
- `web/src/AppShell.vue`（更新：增"财务"侧栏入口）
- `android/.../data/finance/FinanceAccountEntity.kt` + `FinanceCardEntity.kt` +
  `FinanceTxEntity.kt` + `FinanceReminderLogEntity.kt`（新增）
- `android/.../data/finance/FinanceAccountDao.kt` + `FinanceCardDao.kt` +
  `FinanceTxDao.kt` + `FinanceReminderLogDao.kt`（新增）
- `android/.../data/finance/FinanceRepository.kt`（新增，CRUD + 联动 +
  nextReminder + aggregator 镜像）
- `android/.../finance/FinanceAggregator.kt` + `FinanceAggregatorTest.kt`（新增）
- `android/.../finance/Luhn.kt` + `LuhnTest.kt`（新增）
- `android/.../finance/NextCardFiring.kt` + `NextCardFiringTest.kt`（新增）
- `android/.../data/AppDatabase.kt`（更新：version 5→6 + Migration）
- `android/.../data/migrations/Migrations.kt`（新增 v5→v6）
- `android/.../reminder/ReminderScheduler.kt`（更新：rebuildChain 追加财务扫描）
- `android/.../collector/CollectorWorker.kt`（更新：末尾追加 FinanceRepository.
  pullAndDecrypt，4b T10.2 模式）
- `android/.../ServiceLocator.kt`（更新：注册 FinanceRepository）
- `android/.../ui/AppNav.kt`（更新：增 `Routes.FINANCE` + composable 注册）
- `android/.../ui/screens/VaultScreen.kt`（更新：顶部 TopAppBar actions 增
  "财务" TextButton）
- `android/.../ui/screens/FinanceScreen.kt` + `FinanceDashboard.kt` +
  `AccountList.kt` + `CardList.kt` + `TxList.kt` + `AccountEditorScreen.kt` +
  `CardEditorScreen.kt` + `TxEditorScreen.kt`（新增）
- `app/src/main/res/values/strings.xml`（更新：增 finance 模块所有文案 key）

### 文档/约定层

- `README.md` Web 节（增"财务"功能介绍 + 功能矩阵 + 已知问题）
- `everything_plan.md` L134 后（勾选阶段 5 完成 + v2 列出）
- `.trae/specs/stage5-finance/spec.md`（本文件）
- `.trae/specs/stage5-finance/tasks.md`（writing-plans 产出）
- `.trae/specs/stage5-finance/review.md`（独立评审产出）

### 不交付（明确划界）

- 不动 server/任何 Go 代码
- 不动 records 表 schema / SyncWorker / CollectorWorker 接口
- 不动 4a/4b 既有测试、Android Manifest 已有权限（仅调度器扩展）
- 不引入新 receiver / service / provider
- 不产出附件上传（合同/发票/保单 PDF 扫描件）
- 不产出银行 API 直连 / 自动流水同步
- 不产出多币种汇率换算
- 不产出投资账户实时行情
- 不产出 Web 端浏览器通知
- 不产出 v2 扩展模块的编辑器与详情页（仅 schema_version=1 + 类型常量预留）
- 不产出预算硬约束/超支告警
- 不产出 AI Agent 联动记账

## 门禁（不可跳过）

- Go `go test ./...` 全 0（无 server 改动亦需复跑）
- Web `pnpm build` + `pnpm test` 全 0（含 luhn.test.ts ≥6 + aggregator.test.ts
  ≥16 + financeStore ≥6 + Vue Test Utils ≥8 = ≥36 用例）
- Android `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` BUILD
  SUCCESSFUL
- Android unit test 全绿（Luhn ≥6 + NextCardFiring ≥6 + FinanceAggregator ≥16 +
  FinanceRepository ≥6 + ReminderScheduler 财务相关 ≥6 = ≥40 用例）
- Android instrumented 编译通过（无设备环境仅编译）
- Web `finance/__fixtures__/cases.json` 与 Android 镜像文件 SHA-256 哈希一致
- 通知文案不渲染金额/卡号/具体日期数字（grep 模式零命中或带说明）