# 财务模块（finance，阶段 5 v1）

> **For agentic workers:** 本文件为财务模块**独立文档**，与
> [`module-schemas.md`](module-schemas.md) 第 9 章配套引用；字段定义以
> [`docs/schemas/finance.schema.json`](schemas/finance.schema.json)
> （JSON Schema Draft 2020-12）为机器可读准绳，本文件为人类可读骨架。
>
> **零知识纪律**（继承 4a / 4b）：服务端只见密文与 records 元数据；明文账户名 /
> 卡号 / 金额 / 分类 / 备注仅驻留 Android Room 与 Web 浏览器内存，**不写**
> SharedPreferences / localStorage / IndexedDB / 日志 / 通知文案 / 崩溃消息。
> 通知文案**绝不**渲染金额数字 / 卡号后四位 / 具体日期数字。

---

## 目录

1. [模块定位与边界](#1-模块定位与边界)
2. [分类体系](#2-分类体系)
3. [月报聚合规则](#3-月报聚合规则)
4. [资产看板定义](#4-资产看板定义)
5. [Luhn 校验](#5-luhn-校验)
6. [提醒触发规则](#6-提醒触发规则)
7. [v2 钩子说明](#7-v2-钩子说明)
8. [跨端共享 fixture 命名规范](#8-跨端共享-fixture-命名规范)
9. [交叉引用](#9-交叉引用)

---

## 1. 模块定位与边界

### 1.1 module 与 type 子类型

财务作为**单一 module** = `"finance"`，所有子类型通过 `type` 字段区分。
完全复用阶段 1 records 信封（XChaCha20-Poly1305 + AAD
`eve:v1:record:{id}:{module}:{BE(uint64 version)}`，`module="finance"`），
与 4a `place` / 4b `event` **三端逐字节一致**——**不新造** envelope 参数
或服务端接口。

```
module = "finance"
type   ∈ { "account", "card", "tx", "policy", "subscription", "loan", "contract" }
```

**取舍说明**：选 A（单 module + type 子类型）而非 B（按子类型拆 module）：
财务是高度聚合场景——资产看板需要交叉查询（账户余额 + 信用卡已用额度 +
应收/借款），单一 module 下客户端聚合更直接；records envelope 与版本管理
按 module 维度升级更顺畅。

**v1 实际只下发三种 type**：`account` / `card` / `tx`；
`policy` / `subscription` / `loan` / `contract` 在 v2 启用，本期**仅占位常量
与 schema_version=1 钩子**，**不下发编辑器**。

### 1.2 服务端零知识边界

服务端 Chi `approved` 分组下 `/records/batch` 已承载批量幂等（阶段 0–4b 既有
实现）。财务模块**完全复用**既有链路：

- 服务端不解密 / 不校验 amount / 不缓存明文余额；
- 服务端不引入新表 / 新列 / 新接口；
- finance 与 place / event 同走 `/records/batch`；
- 服务端可见的元数据仅限 records 表已有列（`id` / `module` / `type`
  因投递校验入索引，但财务字段均处于密文中）。

### 1.3 客户端聚合（不上行）

净资产 / 总资产 / 总负债 / 分类饼图 / 月报预算阈值全部**在客户端
聚合**，**不上行服务端**。聚合算法抽为跨端共享纯函数（Web
`web/src/finance/aggregator.ts` + Android `FinanceAggregator.kt`），由 JVM 与
Vitest 单测覆盖，三端 fixture 镜像加载 + SHA-256 一致。

### 1.4 与既有架构的关系

| 模块 | module | type | 加密链路 | 服务端可见 |
|---|---|---|---|---|
| 阶段 1 `pass` | `pass` | `login` / `note` / `card` | records + AAD | 密文 + 元数据 |
| 阶段 4a `place` | `place` | `place` | records + AAD | 密文 + 元数据 |
| 阶段 4b `event` | `event` | `event` | records + AAD | 密文 + 元数据 |
| **阶段 5 `finance`** | **`finance`** | **`account` / `card` / `tx` (+ v2 占位 4 类)** | **records + AAD** | **密文 + 元数据** |

财务模块严格遵循 `docs/development.md` "新增业务模块不改 records 表"的约定
——仅定义模块 JSON Schema + 客户端表单即可。

---

## 2. 分类体系

财务记账默认分类（v1 内置常量），**不持久化分类字典到服务端**，仅客户端
内置 + 自定义自然扩展。

### 2.1 默认分类列表

| kind | 默认分类 |
|---|---|
| `expense`（支出） | 餐饮 / 交通 / 居家 / 购物 / 娱乐 / 医疗 / 教育 / 通讯 / 旅行 / 其他 |
| `income`（收入） | 工资 / 奖金 / 投资 / 兼职 / 红包 / 退款 / 其他 |
| `transfer`（转账） | 无分类（仅记录金额 + 双方账户 / 卡） |

### 2.2 用户自定义分类自然扩展机制

用户可在编辑器中输入自定义分类字符串（如"咖啡""健身"），作为新分类自然
扩展：

- 分类列表 = `default_categories ∪ {历史流水中出现过的 category 去重}`；
- 完全本地聚合，**不上行**；
- 自定义分类不持久化到服务端（避免泄漏用户语义习惯）。

### 2.3 字段约束

- `category` 长度 ≤20 字符（Android schema 与 fixture 锁定）；
- 前端表单校验非空；
- 服务端不解密故无二次校验。

### 2.4 `transfer` 的中性属性

`transfer`（账户间转账）**不计入收入 / 支出**——它属于账户间内部调动，
不构成真实收支。这一约定贯穿月报聚合（§3）、资产看板（§4）与预算阈值（§3.2）
三处口径。

---

## 3. 月报聚合规则

月报页（v1）展示当月收支汇总，**完全在客户端计算**，**不上行服务端**。
聚合入口为跨端共享纯函数
[`FinanceAggregator.monthlyReport(...)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt)，
其 Web 镜像为 `web/src/finance/aggregator.ts` 的 `monthlyReport` 函数。

### 3.1 汇总口径

`monthlyReport(yearMonth, txs, accounts)` 函数签名与字段：

```
yearMonth       = "YYYY-MM"           # 年月键（本地日历月）
income          = decimal-as-string   # 当月收入合计（仅 kind="income"）
expense         = decimal-as-string   # 当月支出合计（仅 kind="expense"）
net             = decimal-as-string   # = income - expense（cents 整数减法）
txCount         = int                 # 当月流水条数（含三类）
categoryBreakdown = Map<category, decimal-as-string>   # 仅 expense 分类
```

**算法骨架**（与 [FinanceAggregator.kt](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt) §monthlyReport 完全一致）：

1. 按 `tx.occurredAt` 的本地日历日拆出 `YYYY-MM` 分量（与 4b `Recurrence.kt` 同款 CST 口径）；
2. 仅命中 `yearMonth` 的流水参与；
3. `kind="income"` 累加进 `income`；
4. `kind="expense"` 累加进 `expense` + 计入 `categoryBreakdown`；
5. `kind="transfer"` **不计入** income / expense（仅计入 `txCount`）；
6. `net = income - expense`（cents 整数减法，负数 = 当月净流出）。

### 3.2 预算阈值（可选设置）

`FinanceAggregator.budgetThreshold(monthlyIncome, monthlyExpense, threshold)`
返回 `BudgetStatus` 三档枚举：

| 比值（expense / income） | 状态 | 文案 |
|---|---|---|
| `< 1.0` | `OK` | 支出未达月收入（绿） |
| `1.0 ≤ 比值 < 1.5` | `WARNING` | 超支预警（黄） |
| `≥ 1.5` | `EXCEEDED` | 严重超支（红） |

**特殊约定**：`monthlyIncome = 0`（零收入）时一律返回 `OK`，不抛错。
实现中通过 `ratioX10000 = (expenseCents * 10000) / incomeCents` 做 cents 整数
比较，避免 Long 截断误差。

**纪律**：

- **不阻断**保存：超支不拦截新流水录入；
- **不上行**服务端：预算值仅存 Android DataStore + Web 内存 +
  用户导出 JSON 备份，**不写** records；
- 阈值系数 `threshold` 默认 `0.8`（支出达收入 80% 预警）。

### 3.3 时区与月分桶

`occurred_at` 取设备本地时区语义（与 4b event 同款 `tz_mode=local`），按本地
日历日聚合当月汇总。`FinanceAggregator.yearMonthOf(ts)` 内部实现：

```
shifted = ts + TZ_OFFSET_MIN * 60_000
return "%04d-%02d".format(year, monthValue)   // 用 UTC 字段读
```

**不持久化月度快照**，按需即时计算（O(N) 单用户量级 < 30ms）。

---

## 4. 资产看板定义

资产看板**完全在客户端聚合**，**不上行服务端**。聚合入口为
[`FinanceAggregator.netWorth(...)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt)
对应 Web `aggregator.ts` 的 `aggregate` 函数。

### 4.1 公式

实现位于
[`FinanceAggregator.netWorth`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt)：

- **总资产（`totalAssetValue`）** = `Σ(非归档账户 balance)`
  - 仅 `archived=false` 的账户；
  - 信用卡"信用额度"非自有资产，**不计入**；
- **总负债（`totalLiability`）** = `Σ(非归档信用卡 usedLimit)`
  - 卡过滤：`archived=false` 且 `kind="credit"`；
  - 借记卡不计负债（schema 语义对齐）；
- **净资产（`totalAssets`）** = `totalAssetValue - totalLiability`
  - cents 整数运算天然避免浮点精度丢失；
  - 负数表示"资不抵债"，业务允许；
- **货币（`currency`）** = `accounts[0].currency ?? "CNY"`；
- **计数（`accountCount` / `cardCount` / `txCount`）** = 列表计数（含归档条目）。

### 4.2 边界条件

- `accounts` / `cards` / `txs` 任一为空 → 返回全零 `DashboardSnapshot`，不抛错；
- `usedLimit` / `balance` 为 null 或非数字 → 视为 `"0.00"`，跳过该项；
- 卡片兜底 currency 在 T5 当前实现仅做"账户优先 → DEFAULT_CURRENCY 二级降级"
  （CardLike 当前未承载 currency 字段；Web 镜像行为一致）。

### 4.3 DashboardSnapshot 字段

```
totalAssets          decimal-as-string   # 净资产（= 总资产 - 总负债）
totalAssetValue      decimal-as-string   # 总资产（仅账户余额）
totalLiability       decimal-as-string   # 总负债（信用卡已用）
accountCount         int                 # 列表计数（含归档）
cardCount            int                 # 列表计数（含归档）
txCount              int                 # 流水总数
currency             string (ISO 4217)   # 全空时 = "CNY"
```

### 4.4 单账户 / 单卡聚合

辅助函数供卡片详情页 / 编辑器校验用：

- `accountBalance(account, txs)`：起点 = `account.balance`，按 `tx.accountId` /
  `tx.transferToAccountId` 双向调整（`income` +、`expense` −、`transfer` 转出 −
  / 转入 +）。
- `cardUsedLimit(card, txs)`：起点 = `card.usedLimit ?? "0"`，仅 `tx.cardId
  == card.id` 参与；`expense` +、`income` 还款 −；**钳位到 0**（不会为负）。

### 4.5 看板为本地视图

- 仅在内存 + 浏览器 / Compose 渲染层展示；
- **不写入** records 表；
- **不参与同步**；
- 刷新策略：账户 / 卡 / 流水变更后即时重算（O(N) 单用户量级 < 50ms）。

---

## 5. Luhn 校验

UI 录入完整卡号（13–19 位数字）时做 **Luhn 校验**，校验通过后**仅保留后四位**
入信封（**完整卡号不持久化**）。

### 5.1 算法描述

Luhn 算法（mod 10）步骤：

1. 从右到左遍历卡号各位数字；
2. 偶数位（从右数第 2、4、6……位）数字 ×2，若结果 >9 则将十位与个位相加
   （等价于 `n - 9`）；
3. 所有位求和；
4. 总和 mod 10 == 0 即合法。

### 5.2 实现锚点

跨端纯函数镜像：

| 端 | 文件 | 关键常量 |
|---|---|---|
| Web | `web/src/finance/luhn.ts` | `MIN_PAN_LENGTH=13`、`MAX_PAN_LENGTH=19`、`SEPARATOR_CHARS=" -"`、`LAST4_LENGTH=4`、`MODULUS=10`、`DOUBLE_MULTIPLIER=2` |
| Android | [`Luhn.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/Luhn.kt) | 同上 |

**步骤**：

1. `luhnValidate(pan)`：剥离 `" "` / `"-"` 分隔符 → 长度 `13 ≤ len ≤ 19` →
   纯数字校验 → 模 10 累加；
2. `extractLast4(pan)`：Luhn 校验通过后取末 4 位数字字符串。

### 5.3 BIN 推断表（`brand` 字段）

按卡号前缀（BIN 段）推断发卡品牌，写入 `finance_card.brand` 字段：

| 品牌 | BIN 段 |
|---|---|
| `visa` | `4` |
| `master` | `51-55` / `2221-2720` |
| `unionpay` | `62` / `81` |
| `amex` | `34` / `37` |
| `jcb` | `3528-3589` |
| `discover` | `6011` / `65` / `644-649` / `622126-622925` |
| `unknown` | 未识别 |

### 5.4 仅后四位入库纪律

- **完整卡号不入库**（不进 Room / localStorage / IndexedDB / 服务端）；
- **后四位**（4 位数字字符串）入 `card.last4` 字段；
- UI 列表仅展示脱敏（如"•••• 1234"）；
- 校验失败弹错并清空输入框，**不持久化**任何位；
- 空串不触发校验（`last4` 必填校验由前端表单把关）。

### 5.5 跨端实现

| 端 | 文件 |
|---|---|
| Web | `web/src/finance/luhn.ts`（纯函数，Vitest 覆盖） |
| Android | [`Luhn.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/Luhn.kt)（纯函数镜像，JUnit 覆盖） |

---

## 6. 提醒触发规则

财务提醒**完全复用**阶段 4b `ReminderScheduler.nextTrigger / scheduleNext /
rebuildChain`，**不新建**第二个 Scheduler；通过"扩展模块分支"接入。
计算入口为跨端共享纯函数
[`NextCardFiring.nextTrigger(...)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt)
对应 Web `web/src/finance/nextCardFiring.ts`。

### 6.1 触发时机常量

| 触发点 | 常量 | 备注 |
|---|---|---|
| 信用卡账单日 `billing_day` **T+0** | `TRIGGER_HOUR=9`、`TRIGGER_MINUTE=0` | v1 启用 |
| 信用卡还款日 `billing_day + due_day` **T-1** | `PAYMENT_DUE_DAYS_BEFORE=1` | v1 启用（仅 `due_day` 非空时） |
| 订阅扣费日 **T-1** | （同 1 天） | v2 钩子（`subscription_renewal`） |
| 保单到期 **T-30** | （30 天） | v2 钩子（`policy_expiry`） |
| 应收借款到期 **T-7** | （7 天） | v2 钩子（`loan_due`） |

### 6.2 单点日期算法（双触发口径）

[`NextCardFiring.nextTrigger(card, nowMs)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt) 算法骨架：

1. **早退守卫**：`card.archived=true` 或 `card.billingDay=null` → 返回 `null`；
2. **拆 `nowMs` 本地日历分量**：用 CST UTC+8 口径（与 4b Recurrence.kt 同款）；
3. **账单日候选**（当月 + 下月，选 `> nowMs`）：
   - 当月 `billingDay` T+0 09:00 CST；
   - 下月 `billingDay` T+0 09:00 CST；
5. **还款日 T-1 候选**（仅 `card.dueDay` 非空时）：
   - 还款日 = `billingDay + dueDayOffset`；
   - 超过当月最大天数 → 钳位到当月最大日（31 在 2 月按 28/29 计）；
   - T-1 = 还款日 − 1 天（月初 1 日时 `minusDays` 退到上月最后一天）；
6. **取两类候选最小值**（最近一次未来触发）；
7. 全过期 → 返回 `null`（业务语义：近期无提醒）。

### 6.3 跨月滚动与月底裁剪

`due_day` 为 offset 模式：`billing_day + due_day_offset` 跨月滚动——

- 如 `billing_day=5, due_day=25` → 还款日 = 当月 30 日；
- 31 在 2 月按当月最大日裁剪（如 `billing_day=1, due_day=30` 在 2 月 = 28/29 日）；
- 当 `due_day=1` 时，T-1 通过 `LocalDate.minusDays(1)` 自动退到上月最后一天。

### 6.4 批量与链式调度

[`NextCardFiring.upcomingTriggers(cards, nowMs, limit=5)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt)：

- 遍历所有卡，收集非 null 候选；
- 按 ms 升序排序，取前 N 条（默认 N=5）；
- 与 [`ReminderScheduler.rebuildChain(ctx)`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt)
  的"全局最小触发点"语义对齐。

### 6.5 调度复用与 module 路由

财务提醒**复用**阶段 4b [`ReminderScheduler`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt)：

- **单闹钟 requestCode** `0x45564556` = "EVEEV" hex，event + finance 共用；
- `rebuildChain(ctx)` 阶段 5 扩展：合并事件 + 财务两类触发，取全局最小
  `nextTrigger` 写**单闹钟**（严禁新建第二条调度链路）；
- Intent extras 新增 `EXTRA_MODULE` / `EXTRA_REF_KIND` / `REF_ID=card.id` 三字段；
- 触发常量：
  - `MODULE_EVENT="event"` / `MODULE_FINANCE="finance"`；
  - `REF_KIND_CARD_STATEMENT_DUE="card_statement_due"`；
  - `REF_KIND_CARD_PAYMENT_DUE="card_payment_due"`；
- `LOOKAHEAD_MS` = 14 天、`MAX_MONTH_LOOKAHEAD` = 24 个月（与 4b 同款）。

### 6.6 模块分支路由（ReminderReceiver）

[`ReminderReceiver`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderReceiver.kt)
按 `module` 字段分支：

| module | DAO | 通知文案 |
|---|---|---|
| `"event"`（或缺失，4b 兼容） | `EventDao` | title + "即将开始 / N 分钟后开始" |
| `"finance"` | `FinanceCardDao` | 抽象文案（账单 / 还款），**不渲染金额 / 卡号后四位 / 具体日期** |

**零知识红线**：通知文案**绝不**渲染金额 / 卡号后四位 / 具体日期数字。
通知 id 用 `cardId.hashCode()`（同一卡片覆盖，不同卡片并行）。channelId =
`"events"`（与事件共用，不新建 channel）。

### 6.7 通知文案模板（v1 启用）

| `ref_kind` | 文案模板 |
|---|---|
| `card_statement_due` | "💳 信用卡账单已生成" |
| `card_payment_due` | "💳 信用卡还款临近" |

跳转路由携带 `record_id` + `record_kind`，路由至 `Routes.FINANCE` 编辑器。

### 6.8 提醒日志表（Room v6）

`finance_reminder_log`（独立于 4b `event_reminder_log`）字段（4 列）：

| 列 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | — | 自增 |
| `ref_id` | TEXT | 是 | finance 条目 id（v1 即 `card.id`） |
| `ref_kind` | TEXT | 是 | `card_statement_due` / `card_payment_due`（v2 启用后三类） |
| `fire_at` | INTEGER | 是 | Unix 毫秒（触发时刻） |
| `delivered` | INTEGER | 否 | 0/1；通知是否成功投递（POST_NOTIFICATIONS 拒绝时 = 0） |

v1 仅启用前两类写入；订阅 / 保单 / 借款三类留 v2。

---

## 7. v2 子类型（财务二版 stage5-finance-v2）

阶段 5 v2 在 v1 三类基础上扩展 4 子类型：**Subscription / Policy / Loan /
Contract**。本期 v2 已落地数据契约 + 校验函数（[TR-1.1](#) / [TR-2.7](#)），
编辑器 / 详情页 / 仪表盘卡片随后续批次展开。

### 7.1 类型常量（FinanceType）

| 常量 | v1 启用 | v2 启用 |
|---|---|---|
| `ACCOUNT` | ✅ | ✅ |
| `CARD` | ✅ | ✅ |
| `TX` | ✅ | ✅ |
| `POLICY`（保单） | ⏳ v1 占位 | ✅ v2 启用 |
| `SUBSCRIPTION`（订阅） | ⏳ v1 占位 | ✅ v2 启用 |
| `LOAN`（应收 / 借款） | ⏳ v1 占位 | ✅ v2 启用 |
| `CONTRACT`（合同 / 发票） | ⏳ v1 占位 | ✅ v2 启用 |
| `BUDGET`（预算，B6） | ⏳ v1 无 | ✅ v2 启用（records 通道 `type="budget"`，无独立 Room 表，详见 §7.7） |

v2 启用时，类型枚举常量无需新增；4 子类型即 `FinanceType` 常量后 4 位；
B6 预算复用 records 密文通道的子标识 `type="budget"`（见
[`FinanceModule.TYPE_BUDGET`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/data/finance/FinanceModule.kt)）。

### 7.2 schema_version

v1 条目（account / card / tx）明文 payload 顶部保留 `schema_version=1`
字段；v2 4 子类型明文 payload 顶部固定 `schema_version=2`。解码器按
version 路由扩展字段，向前兼容。

### 7.3 v2 子类型字段表（schema 真理源）

#### 7.3.1 Subscription（订阅）

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | — |
| `schema_version` | int (=2) | — |
| `name` | string (≤200) | 订阅名称 |
| `provider` | string (≤200) | 服务商 |
| `amount_minor` | string (decimal-as-string) | 续费金额 |
| `currency` | string (ISO 4217, 3 大写字母) | 默认 CNY |
| `billing_cycle` | `'monthly' \| 'quarterly' \| 'yearly' \| 'custom_days'` | — |
| `custom_days` | number \| null | 仅 custom_days 时必填 |
| `start_ts` / `next_renewal_ts` | number (Unix 毫秒) | 下次扣费由 `nextSubscriptionRenewal` 纯函数计算 |
| `reminders` | number[] (分钟偏移, 非负整数) | 走 v1 events 单闹钟链 |
| `active` | boolean | — |
| `category` | `'entertainment' \| 'productivity' \| 'utility' \| 'other'` | — |
| `created_at` / `updated_at` | number (Unix 毫秒) | — |

#### 7.3.2 Policy（保单）

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | — |
| `schema_version` | int (=2) | — |
| `name` | string (≤200) | 保单名称 |
| `policy_number` | string (≤100) | 保单号 |
| `policy_number_encrypted` | boolean (默认 true) | 显示时按需截取末 4 位 |
| `provider` | string (≤200) | 保险公司 |
| `premium_minor` / `coverage_minor` | string (decimal-as-string) | 保费 / 保额 |
| `currency` | string | — |
| `billing_cycle` | `'monthly' \| 'quarterly' \| 'yearly' \| 'single'` | — |
| `start_ts` / `expiry_ts` | number (Unix 毫秒) | expiry 必须 >= start |
| `reminders` | number[] | 到期提醒偏移（默认 `[0, 10080, 43200]`） |
| `active` | boolean | — |
| `linked_account_id` | string \| null | 关联账户 |
| `attachments` | `AttachmentRef[]` | v2 启用，详见 §7.4 |

#### 7.3.3 Loan（应收 / 借款）

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | — |
| `schema_version` | int (=2) | — |
| `counterparty` | string (≤200, 不渲染通知文案) | 对手方 |
| `principal_minor` / `paid_minor` | string (decimal-as-string) | 本金 / 已还（≤ principal） |
| `currency` | string | — |
| `direction` | `'lent' \| 'borrowed'` | 我借出 / 我借入 |
| `issue_ts` / `due_ts` | number (Unix 毫秒) | due >= issue |
| `interest_rate_apy_bps` | number (非负整数) | 10000 bps = 100% |
| `status` | `'active' \| 'partially_paid' \| 'paid' \| 'overdue'` | — |
| `reminders` | number[] | 到期提醒偏移 |
| `linked_account_id` | string \| null | — |
| `include_in_net_assets` | boolean | 净资产聚合开关（默认 true） |
| `created_at` / `updated_at` | number | — |

**资产看板聚合**：aggregator 入参新增 `loans: LoanLike[]`，净资产计算
`net_assets += lent - borrowed`（按主币种折算，v2 启用多币种时由 T5
汇率包介入）。

#### 7.3.4 Contract（合同 / 发票）

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | — |
| `schema_version` | int (=2) | — |
| `title` / `counterparty` | string (≤200) | — |
| `kind` | `'rental' \| 'service' \| 'purchase' \| 'loan' \| 'other'` | — |
| `amount_minor` | string (decimal-as-string) | 合同金额 |
| `currency` | string | — |
| `signed_ts` / `start_ts` / `end_ts` | number (Unix 毫秒) | end >= start |
| `auto_renew` | boolean | — |
| `notice_period_days` | number (非负整数) | 提前通知期（天） |
| `notice_deadline_ts` | number (Unix 毫秒) | **必须等于 `end_ts - notice_period_days * 86400000`** |
| `status` | `'active' \| 'expired' \| 'terminated' \| 'renewed'` | — |
| `linked_account_id` | string \| null | — |
| `attachments` | `AttachmentRef[]` | v2 启用，详见 §7.4 |

**提醒**：contract 不接入 v1 Reminders 通道主流程；`notice_deadline_ts`
触发评估列入 v3。

### 7.4 附件元数据（AttachmentRef）

v2 启用后，policy / contract 可挂附件。**二进制走 records 通道 type=
'attachment' 子标识 + AAD `module="finance" + type + attachment_id`**，
**复用** v1 records 通道，不新造独立协议。

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | 附件唯一 id |
| `mime` | string | MIME 类型 |
| `size` | number (字节) | 端侧校验 ≤ 50 MB；**超限直接拒收，不压缩 / 不分块** |
| `sha256` | string (64 hex) | 二进制 sha-256 |

### 7.5 校验函数（客户端纯函数层）

- **Web**：`web/src/finance/types.ts` 导出 `validateSubscription /
  validatePolicy / validateLoan / validateContract / validateV2Payload`；
- **Android**：`android/app/src/main/java/com/everything/eve/finance/
  FinanceRecords.kt` 导出 `FinanceRecords.validateSubscription / ...` +
  `ValidationResult` sealed class。
- 校验函数**不抛异常**，返回 `{ok: true} | {ok: false, reason}`；
  `reason` **不含敏感数据**（金额 / 日期 / 账号数字），仅给"字段级错
  误类别"，避免日志泄漏。
- **幂等性**：合法入参不会被修改（单测覆盖）。
- **三端契约**：同口径校验逻辑；待 finance.schema.json v2 草稿到位后
  以 schema 为真理源校核。

### 7.6 接入点（编辑器 / store / Room / 路由 / 仪表盘）

| 接入点 | v2 扩展方式 |
|---|---|
| Web 路由 | `Routes.FINANCE_POLICY / _SUBSCRIPTION / _LOAN / _CONTRACT` |
| Web store | `web/src/stores/finance.ts` 同款 `upsert / delete / list / byId` 链路 |
| Web 编辑器 | `PolicyEditorDialog.vue` / `SubscriptionEditorDialog.vue` / `LoanEditorDialog.vue` / `ContractEditorDialog.vue` |
| Android Room DAO | `FinancePolicyDao` / `FinanceSubscriptionDao` / `FinanceLoanDao` / `FinanceContractDao` |
| Android Room 表 | `finance_policy / _subscription / _loan / _contract`（Room 迁移 v6 → v7） |
| Android Repository | `FinanceRepository`（v2 启用后联动 records 通道 type='attachment'） |
| 提醒枚举 | 启用后三类（`subscription_renewal / policy_expiry / loan_due`），**预算告警不接入 Reminders 通道**（仅 toast/banner） |
| 预算（B6） | records 通道 `type="budget"`（无独立 Room 表）；Android `BudgetEnforcer` / `BudgetGate` / `BudgetConfirmDialog` / `BudgetListScreen` / `BudgetEditorScreen` + VM 硬闸门；Web `budgetEnforcer.ts` / `budgetGate.ts` / `BudgetConfirmDialog.vue` / `BudgetList.vue` / `BudgetEditor.vue` + store `precheckTx` 硬闸门；审计列触发 Room v8→v9，详见 §7.7 |

### 7.7 预算硬约束 + 超支拦截（B6，type="budget"）

预算是 v2 第 5 个 records 子类型（**不新建 Room 表**）：`module="finance"` +
`type="budget"`，复用既有密文通道、墓碑与 hydrate 链路；AAD 沿用
`eve:v1:record:{id}:finance:{BE(uint64 version)}`。预算明文 payload 固定
`schema_version=2`，共 13 字段：

| 字段 | 类型 | 备注 |
|---|---|---|
| `id` | string (UUID v4) | — |
| `schema_version` | int (=2) | — |
| `scope` | `'monthly' \| 'weekly' \| 'yearly' \| 'custom'` | 周期分桶口径 |
| `category` | string (1..20) | `'all'` 表示覆盖全部分类，否则为具体分类标签 |
| `amount_minor` | string (decimal-as-string) | 预算额度，必须 > 0，最多 2 位小数 |
| `currency` | string (ISO 4217) | 默认 CNY |
| `start_ts` | number (Unix 毫秒, >0) | 有效期起点 |
| `end_ts` | number (Unix 毫秒) | 有效期终点（含），必须 ≥ `start_ts` |
| `warning_threshold_pct` | number (int) | 预警阈值，默认 80；1 ≤ warning ≤ block ≤ 10000 |
| `block_threshold_pct` | number (int) | 硬拦截阈值，默认 100（允许 >100 表示弹性容忍） |
| `active` | boolean | 停用预算不参与判定 |
| `created_at` / `updated_at` | number (Unix 毫秒) | — |

#### 7.7.1 三档判定（BudgetEnforcer 纯函数，双端镜像）

- 纯函数 `checkTx(incoming, budgets, existing, rateTable, nowMs)` 返回
  `BudgetCheckResult`，档位 `OK / WARNING / BLOCK`，结果字段含
  `usedPct`（Long 整除向下取整）/ `category` / 阈值百分比，**不含任何金额**；
- 仅对 `kind="expense"` 支出做判定；收入 / 转账直接 `OK_EMPTY`；
- `inactive` 预算、与本笔流水 `occurred_at` 不同桶的预算均不参与；
- 异币支出经 B5 `RateTables.convert`（Android）/ `convertMinor`（Web）折算
  到预算币种；**缺汇率保守放行**（convert 返回 null 时跳过该预算，不误拦）；
- 多预算命中取最严重档位；同级取 `usedPct` 更大者；再相同取 `budgetId`
  字典序，保证双端判定结果唯一且一致；
- 编辑既有流水时，`existing` 中同 id 旧记录自动排除自身，避免重复累计；
- 金额全程整数分（Android `Long` / Web `bigint`），decimal 元字符串解析
  禁止浮点，正则 `^(\d+)(?:\.(\d{1,2}))?$`。

#### 7.7.2 周期分桶（CST，UTC+8）

- `monthly`：按 Asia/Shanghai（`ZoneOffset.ofHours(8)`）自然月切桶；
- `yearly`：CST 自然年；
- `weekly`：**非自然周**——以预算 `start_ts` 所在 CST 日期零点为 epoch，
  7 天滚动窗口；
- `custom`：桶即 `[start_ts, end_ts + 1ms)`；
- 分桶锚点是流水自身的 `occurred_at`（支持补录历史账），`nowMs` 仅作预留
  参数，不改变判定。

#### 7.7.3 双层拦截 + 审计位

1. **UI 预检查**：保存支出前同步跑 precheck——`BLOCK` 挂超支确认对话框，
   本次不保存、不退出；用户选"仍保存"后携带 `overspendAcknowledged=true`
   二次提交才落库；`WARNING` 不拦截，保存成功后 Toast 软提示。
2. **VM / store 硬闸门兜底**：`saveBuffer`（Android）/ `addTx、updateTx`
   （Web，返回 boolean）在 BLOCK 且未 ack 时直接中止，绕过 UI 也无法落库。
3. **审计字段**：`finance_tx` 新增 `overspend_acknowledged`（Android Room
   **v8 → v9**：`ALTER TABLE finance_tx ADD COLUMN overspend_acknowledged
   INTEGER NOT NULL DEFAULT 0`，旧流水升级后一律视为未经超支确认；Web 在
   `FinanceTx` 上以可选字段承载，**不升** tx 的 `schema_version`，仍为 1）。
   该位仅本地审计留痕，不参与预算判定与同步业务语义。

#### 7.7.4 零知识文案纪律（安全红线）

预算告警 / 拦截文案**只允许出现"已用百分比 + 分类名"**，严禁渲染金额、
币种数字、日期、卡号、对手方账户：

- WARNING Toast：`本月{分类}已用 X%，接近预算上限`（分类 `all` 降级为
  "全部支出"）；
- BLOCK 对话框正文：`预计已用 X%，超过预算阈值，是否仍保存？`；
- 预算列表行只展示周期、分类、预警 / 拦截百分比、启停态，**不渲染额度**；
- 文案集中在纯函数层（Android `BudgetGate.kt` / Web `budgetGate.ts`），
  便于 JVM / node 单测对稳定字符串断言；
- 预算告警**不接入 Reminders 闹钟 / 通知通道**，仅端侧实时 toast / 对话框。

### 7.8 v3 候选（非 v2 立即启用）

- loan 分期扣款场景（`repayment_installment`，总到期 `loan_due` v2 已覆盖）；
- 投资账户自动同步 / 自动再平衡（券商 API 直连）；
- 合同 `notice_deadline_ts` 提醒（走 v2 评估，v3 实施）；
- 银行 API / 银联开放平台直连同步；
- Web 端生物识别解锁（如 TouchID / FaceID）。

---

## 8. 跨端共享 fixture 命名规范

跨端共享 fixture 镜像加载 + SHA-256 一致，文件位置与命名约定：

### 8.1 Web 端

```
web/src/finance/__fixtures__/
  ├─ luhn-cases.json              # Luhn 校验 + 后四位提取（≥6 用例）
  ├─ aggregator-cases.json        # netWorth / accountBalance / cardUsedLimit
  │                                 / monthlyReport / budgetThreshold（≥16 用例）
  ├─ next-card-firing-cases.json  # nextTrigger / upcomingTriggers
  ├─ budget-enforcer-cases.json   # B6 预算三档判定（20 用例，与 Android 同 SHA-256）
  └─ account-cases.json / card-cases.json / tx-cases.json   # 三类条目输入
```

### 8.2 Android 端（test resources）

```
android/app/src/test/resources/finance/__fixtures__/
  ├─ luhn-cases.json              # Luhn 镜像（与 Web 同 SHA-256）
  ├─ aggregator-cases.json        # 五个聚合函数镜像（同 SHA-256）
  ├─ next-card-firing-cases.json  # 双触发候选镜像（同 SHA-256）
  └─ account-cases.json / card-cases.json / tx-cases.json   # 三类条目输入
```

### 8.3 Android 端（test java fixtures）

```
android/app/src/test/java/com/everything/eve/finance/__fixtures__/
  ├─ account-cases.json / card-cases.json / tx-cases.json   # 三类条目输入
  └─ budget-enforcer-cases.json   # B6 预算判定镜像（与 Web 逐字节同 SHA-256）
```

### 8.4 命名与字段规范

- 文件名：**复数 + 中横线 + cases.json**（如 `luhn-cases.json`）；
- 根字段：`cases` 数组，每条用例含 `name` / `input` / `expected`；
- 时间戳字段：所有 `ts_*` / `*_at` 字段一律 Unix 毫秒 int64；
- 金额字段：所有 `*_minor` 或 `balance` / `amount` 一律 **decimal-as-string**
  （避免 JS / Kotlin Double 精度丢失）；
- 字符串字段：UI 语义"必填 / 选填 / 默认"在每条用例的 `expected` 同步给出。

### 8.5 三端 fixture 哈希一致性

Web 与 Android 端对应 fixture 文件 SHA-256 **逐字节一致**，由 Task 2 / Task 3
/ Task 5 / Task 8 实施时执行 SHA-256 比对并写入 Evidence 段。

---

## 9. 交叉引用

| 引用对象 | 路径 / 章节 | 用途 |
|---|---|---|
| 模块明文 JSON Schema | [`module-schemas.md`](module-schemas.md) 第 9 章"finance 模块" | 字段定义的人类可读规范 |
| JSON Schema 机器可读 | [`docs/schemas/finance.schema.json`](schemas/finance.schema.json) | Draft 2020-12 校验 |
| 加密与 AAD 规则 | [`crypto.md`](crypto.md) §5.1 | AAD 沿用 `eve:v1:record:{id}:{module}:{BE(uint64 version)}`（module=`"finance"`） |
| ReminderScheduler 复用 | [`android.md`](android.md) "财务模块（阶段 5）" | 4b 链式 AlarmManager 财务复用路径 |
| Room v5→v6 迁移 | [`android.md`](android.md) "财务模块（阶段 5）" | finance_* 四表扩展 |
| Android 纯函数聚合 | [`FinanceAggregator.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt) | 五个聚合 API 实现 |
| Android B6 预算判定 | [`BudgetEnforcer.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/BudgetEnforcer.kt) / [`BudgetGate.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/ui/finance/BudgetGate.kt) | 预算三档纯函数 + 零知识文案闸门（Web 对应 `budgetEnforcer.ts` / `budgetGate.ts`，见 §7.7） |
| Android Room v8→v9 | [`EveDatabase.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/data/EveDatabase.kt) | B6 审计列 `overspend_acknowledged` 迁移（MIGRATION_8_9） |
| Android 触发计算 | [`NextCardFiring.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt) | nextTrigger / upcomingTriggers |
| Android Luhn 校验 | [`Luhn.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/Luhn.kt) | 卡号校验 + 后四位提取 |
| Android 链式调度 | [`ReminderScheduler.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderScheduler.kt) | 单闹钟 + module 路由 |
| Android 接收路由 | [`ReminderReceiver.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/reminder/ReminderReceiver.kt) | event / finance 分支 |
| Android Room v6 | [`EveDatabase.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/data/EveDatabase.kt) | MIGRATION_5_6 + 四表 schema |
| FR-1 字段详细定义 | `.trae/specs/stage5-finance/spec.md` §FR-1 | account / card / tx 三类字段源 |
| 实施任务分解 | `.trae/specs/stage5-finance/tasks.md` Task 1 ~ Task 13 | 批派发序列与子任务 |
| 4a 轨迹 place 模块 | [`module-schemas.md`](module-schemas.md) 第 7 章 | module=place 链路参考 |
| 4b 日程 event 模块 | [`module-schemas.md`](module-schemas.md) 第 8 章 | module=event 链路参考 |

---

## 备注

- 本文档为阶段 5 财务 v1 文档同步（T12）落地结果，与实际实现（Room v6 /
  FinanceAggregator / NextCardFiring / Luhn / ReminderScheduler /
  ReminderReceiver）逐字段一致；
- 字段如有变更：`module-schemas.md` 第 9 章 / `finance.schema.json` / Web
  `types.ts` / Android `Finance*Entity.kt` / 本文件**五端必须**同改 + 同步
  更新 fixture（保持 SHA-256 一致仍由 fixture 派生）。