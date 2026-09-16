# Everything — 你的人生操作系统

把证件、密码、通讯录、短信、位置轨迹、日程、财产、银行卡、物品、健康等**关于你的一切**
汇聚到自己掌控的服务端，网页与安卓端随时访问，并由 AI Agent 助理查询、提醒与主动洞察。

- **自托管 / 零知识**：数据在客户端加密，服务端只存密文；主密码与主密钥永不明文传输。
- **单二进制**：Go 服务端内嵌 SQLite 与网页端，一个文件即可运行；同时提供多架构 Docker 镜像。
- **跨平台**：Windows / macOS / Linux（amd64 + arm64）/ Docker / 树莓派类 ARM 设备。
- **三端**：Go 服务端 · Vue3 网页（内嵌）· 原生 Kotlin 安卓（Compose）。
- **可插拔 AI**：统一 OpenAI 兼容接口，本地 Ollama 或云端模型（豆包/DeepSeek/OpenAI 等）。

> 当前进度：**阶段 0 + 阶段 1 已完成**（认证、零知识信封、增量同步、恢复密钥、设备审批、
> TOTP 二次验证、SSE 实时通道、Android 显式迁移），**阶段 2 密码库核心已落地**
> （登录/笔记/卡片 + 密码生成器 + TOTP 动态口令 + 证件到期提醒，Web 端完整 UI），
> **阶段 3 安卓采集器已完成**（通讯录/短信/通话记录只读采集 + 权限向导 + 采集状态页），
> **阶段 4a 位置轨迹已落地**（Android 前台定位采集封块加密 + 服务端零知识月表存储 +
> 网页轨迹页地图/回放/地点命名），
> **阶段 4b 日程/日历已落地**（双端事件 + 重复规则 + 本地闹钟；沿用 records 加密通道，
> 服务端零改动；详见下文"日历（阶段 4b）"小节），
> **阶段 5 财务 v1 已落地**（账户/银行卡/记账 + 月报预算 + 资产看板 + 账单/还款提醒；
> 沿用 records 加密通道 + 单闹钟链式调度复用；详见下文"财务（阶段 5）"小节）。
> 完整路线与模块全景见 [.trae/documents/everything_plan.md](.trae/documents/everything_plan.md)。

## 快速开始

### Docker

```bash
cd deploy
docker compose up -d            # 访问 http://localhost:8787
docker compose --profile ai up  # 同时启动本地 Ollama（阶段 6 启用）
```

### 二进制

从 Release 下载对应平台产物，或自行构建：

```bash
make server      # 先构建网页并内嵌，再产出 server/eve
./server/eve     # 默认监听 :8787，数据在 ./data
```

浏览器打开 `http://localhost:8787`，创建账户（首个账户注册后自动关闭注册）。

### 安卓

```bash
cd android
./gradlew :app:assembleDebug    # 需要 JDK 17 与 Android SDK 35
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

模拟器访问宿主机服务端使用默认地址 `http://10.0.2.2:8787`；真机改为局域网 IP。

## 开发

```bash
# 服务端（:8787）
cd server && go run ./cmd/eve

# 网页开发服务器（:5173，/api 自动代理到 :8787）
cd web && npm install && npm run dev
```

## 网页端轨迹页（阶段 4a）

轨迹页展示 Android 端采集并端到端加密上行的位置轨迹（Android 侧开启方式见
[docs/android.md](docs/android.md)）：

- **按月装载**：进入页面即装载当前月的密文块并在浏览器内解密；‹ 上一月 / 下一月 › 切换。
- **选日**：日历中有数据的日期带蓝点高亮，点击载入当日视图——统计行
  （总距离 / 移动时长 / 停留数 / 轨迹点数）+ 时间线（停留卡与移动段按开始时刻混排，
  跨夜停留有标注）。
- **地图与回放**：右栏地图渲染当日轨迹线与停留点；回放控制条支持播放 / 暂停、
  1x / 4x / 16x / 60x 倍速循环与时间轴拖动，回放联动高亮当前时间线条目。
- **地点命名**：停留卡"标记地点"可命名为家 / 公司 / 自定义名称，写成 `place`
  加密记录多设备同步；同位置重复命名覆盖旧名称（幂等）。

隐私提示：

- **瓦片隐私**：地图瓦片请求会暴露大致浏览区域给瓦片服务商（缺省 OpenStreetMap）；
  地图右上角 ⚙ 可自配瓦片源（如自建 / 内网瓦片服务）。瓦片 URL 是轨迹页唯一允许
  落盘（localStorage）的配置项，不属于轨迹数据。
- **明文不持久化**：解密后的坐标只驻浏览器内存，不写 localStorage / IndexedDB、
  不进日志；锁定或退出登录即清空，需重新解锁才能再次查看。

## 日历（阶段 4b）

阶段 4b 在 Web 与 Android 双端落地日程/日历能力——事件 CRUD、月/周视图、
RRULE B 档子集重复规则、本地精确闹钟提醒（仅 Android）。事件作为
`module="event"` 记录走既有 records 加密信道，**服务端零改动**（无新表、
无新接口、无 AAD 前缀），跨端锚点同密码库 / 地点共用 `sealRecord` ↔ `openRecord`。
Android 开启方式见 [docs/android.md](docs/android.md) "日程/日历（阶段 4b）"章。

- **月视图**：6 行 × 7 列日历网格，cell 顶部以颜色 chip 呈现当日事件块；
  跨日事件按起止日期分块渲染。
- **周视图**：7 列 × N 行小时槽，事件块按 `start_ts~end_ts` 跨列；全天事件独立
  顶部横排栅格。
- **编辑器**：标题 / 起止（date+time 或全天）/ 地点 / 备注 / 颜色 8 色 /
  reminders ≤3 / RRULE B 档（NONE / DAILY / WEEKLY / MONTHLY / YEARLY 五档 +
  interval + WEEKLY 7 工作日多选 + MONTHLY 单 weekday + 结束 Never /
  Until-date / Count-n 三选一）/ exdates。title 空 → 保存禁用；
  `start_ts ≥ end_ts` 弹错；reminders > 3 弹错。
- **本地提醒（仅 Android）**：AlarmManager `setExactAndAllowWhileIdle`
  + PendingIntent 指向 `ReminderReceiver`，单 requestCode 全局续接；
  重复事件通过 `Recurrence.expand` 在 `LOOKAHEAD_MS` 窗口内滚动展开，
  **不物化重复实例**（服务端不存展开点）。
- **跨设备同步**：事件作为 `module="event"` 记录走既有 `since` 增量拉取 +
  `pushRecords` 上行链路；冲突解决采用 **LWW（last-write-wins）**——按
  `updated_at` 取最大 `version` 覆盖。
- **零知识纪律**：事件 title / note / 地点等明文仅驻浏览器内存与 Android Room，
  通知文案仅渲染抽象描述（"即将开始" / "N 分钟后开始"），不渲染 start_ts
  原文。

## 财务（阶段 5）

阶段 5 在 Web 与 Android 双端落地零知识个人财务管理——账户 / 银行卡 / 日常记账
三类条目，月报预算阈值与资产看板聚合，账单日 / 还款日本地提醒。
条目作为 `module="finance"` 记录走既有 records 加密信道（**服务端零改动**，
无新表、无新接口、无 AAD 前缀），复用 `sealRecord` ↔ `openRecord`；
聚合与月报阈值**全部在端侧纯函数计算**，**不上行服务端**。
字段定义见 [docs/finance.md](docs/finance.md)；Room 落地详见
[docs/android.md](docs/android.md) "财务模块（阶段 5）"章。

- **账户**：现金 / 存款 / 股票 / 钱包 / 其他 5 类；余额 decimal-as-string 非负；
  归档后不计入资产看板。资产 / 负债分别聚合：
  `totalAssets = totalAssetValue - totalLiability`
  （信用卡 `used_limit` 自动按负债纳入）。
- **银行卡**：借记卡 / 信用卡 2 类；录入完整 16–19 位卡号 → Luhn 校验 →
  **仅保留后四位**（`last4`）入密文 + Room；BIN 推断 `brand`
  （visa / master / unionpay / amex / jcb / discover / unknown）+ 解析
  `expiry_month` / `expiry_year` / `holder` 仅入 Room 辅助缓存、**不入**密文。
  信用卡必填 `credit_limit` / `used_limit`。
- **日常记账**：`income` / `expense` / `transfer` 三类；金额正数，`kind`
  决定方向；转账生成两条对向流水（出账方 `account_id` + 入账方
  `transfer_to_account_id`），不引用对方反向 ID。
- **月报预算**：分类预算阈值 + 当月实际支出 → `BudgetStatus` 三档
  （`OK` / `WARNING` / `EXCEEDED`）；月报同时输出 `monthlyReport`
  （收入 / 支出 / 净额 / 分类饼图 / 预算阈值）。
- **资产看板**：净资产（`netWorth`）、账户余额（`accountBalance`）、
  信用卡已用额度（`cardUsedLimit`）；总资产 = 总资产价值 − 总负债。
- **本地提醒（仅 Android）**：`ReminderScheduler.rebuildChain` 单闹钟
  requestCode `0x45564556`（"EVEEV"）链式调度，按 module 路由：
  `MODULE_EVENT="event"` / `MODULE_FINANCE="finance"`。
  账单日 T+0 09:00 / 还款日 T-1 09:00 当月+下月双候取最小
  （`NextCardFiring.kt` 纯函数镜像）；`LOOKAHEAD_MS = 14` 天，
  `MAX_MONTH_LOOKAHEAD = 24` 月。`ReminderReceiver` 按 `EXTRA_MODULE`
  分支：finance 通知文案**绝不渲染金额 / 卡号后四位 / 具体日期数字**，
  通知 id 用 `cardId.hashCode()` 保证同一卡片覆盖、不同卡片并行。
- **Web 入口**：路由 `/finance`（[web/src/finance/](web/src/finance/)），
  Pinia store 在 [web/src/stores/finance.ts](web/src/stores/finance.ts)。
- **跨设备同步**：财务条目作为 `module="finance"` 记录走既有 `since`
  增量拉取 + `pushRecords` 上行链路；冲突解决采用 **LWW（last-write-wins）**——
  按 `version` 严格递增覆盖。
- **Room 落地（仅 Android）**：`EveDatabase.kt` `version = 6` +
  `MIGRATION_5_6` 显式迁移四张表（`finance_account` / `finance_card` /
  `finance_tx` / `finance_reminder_log`）；`FinanceRepository` 双写
  （明文表 + records 密文表）+ 墓碑语义（`deleted=true` + `dirty=true`
  保留行供对账）。
- **零知识纪律**：财务金额 / 卡号后四位 / 账户名 / 流水分类 / 备注
  **仅驻**浏览器内存与 Android Room；通知文案仅渲染抽象描述
  （"💳 信用卡账单已生成" / "💳 信用卡还款临近"），**绝不渲染**金额、
  卡号后四位、具体日期数字；服务端不解密、不聚合、不缓存。

## 功能矩阵（阶段 4b / 阶段 5 关键能力）

| 能力 | Android | Web | Go（服务端） |
|---|---|---|---|
| 日程（事件 + 重复规则 + 闹钟） | ✅（CRUD + 月/周视图 + AlarmManager exact 闹钟） | ✅（CRUD + 月/周视图 + 编辑器） | N/A（服务端零改动，仅复用 records 通道透传密文） |
| 重复规则展开 | ✅（JVM `Recurrence.kt`） | ✅（`expand.ts` 纯函数） | N/A |
| 本地精确闹钟 | ✅（AlarmManager + 权限降级） | N/A（浏览器通知走 Notification API，由后续阶段补齐） | N/A |
| RRULE B 档子集 | ✅（DAILY/WEEKLY/MONTHLY/YEARLY + interval + 7 工作日 + 结束三选一） | ✅ | N/A |
| 财务（账户 / 银行卡 / 记账 + 月报预算 + 资产看板） | ✅（`FinanceScreen` + Account/Card/Tx Editor + Room v6 + FinanceRepository 双写） | ✅（`/finance` + Pinia store `stores/finance.ts` + 编辑器） | N/A（服务端零改动，复用 records 通道透传密文；聚合 / 月报阈值全部端侧纯函数计算） |
| Luhn 卡号校验 + 仅后四位入库 | ✅（`Luhn.kt` + Room `finance_card.last4`） | ✅（`luhn.ts` + Web 内存 `last4`） | N/A（完整卡号不入密文、不入 Room、不入日志） |
| 账单日 / 还款日本地提醒 | ✅（`NextCardFiring.kt` + `ReminderScheduler.rebuildChain` 单闹钟链式 + `ReminderReceiver` module 路由） | N/A（仅 Android 端本地提醒） | N/A |

## 已知问题

- **Android 12+ exact alarm 权限被拒时降级为 inexact**：阶段 4b 触发
  `AlarmManager.canScheduleExactAlarms()` 返回 false 时，降级走
  `setAndAllowWhileIdle`（inexact 仍走 wakeup 路径）并写
  `event_reminder_log.kind = "alarm_killed"` 留可观测痕迹。用户在系统设置
  「闹钟与提醒」页重新授权后可立即恢复 exact。
- **阶段 4b 跨设备冲突解决采用 LWW**：与第五节既定策略一致，事件模块未单独
  实现字段级 CRDT，重复事件实例由端侧 `expand` 在 `LOOKAHEAD_MS` 窗口内
  滚动计算，不依赖服务端实例物化。
- **doze + 厂商后台限制**：阶段 4b 沿用阶段 4a 已知问题——Android doze 模式
  与国产 ROM 后台限制可能延迟 AlarmManager 触发；本批不修复，仅做降级
  + 留痕。
- **instrumented 真机冒烟**：阶段 4b 真机闹钟触发、`SCHEDULE_EXACT_ALARM`
  拒绝路径、`POST_NOTIFICATIONS` 拒绝路径等已并入 FU-7 关闭条件清单
  （无设备/CI 环境暂不强制；详见 `.trae/specs/stage4b-calendar/tasks.md`）。

### 阶段 5 财务 v1 限制

- **服务端零聚合**：净资产 / 资产 / 负债 / 月报预算阈值 / 分类饼图 / 趋势点
  全部在端侧纯函数计算（`FinanceAggregator.kt` / `aggregator.ts`），
  **不上行服务端**；多设备聚合仅依赖 records 通道最终一致；用户切换设备
  后需等下行 records 收敛（增量拉取秒级内可达）。
- **完整卡号不入任何持久层**：录入完整卡号 → Luhn 校验 → **仅**`last4`
  入密文 + Room；完整卡号不入 schema / Room / localStorage / IndexedDB /
  服务端 / 通知文案 / 日志 / 崩溃消息；`last4` 之外如需识别同卡，仅依赖
  `issuer` + `last4` 联合指纹（v1 容忍同 issuer+last4 的多张卡视为可区分
  副本，v2 可扩展 BIN 全串缓存）。
- **Web 端无本地提醒**：财务模块本地闹钟仅在 Android 实现（AlarmManager
  + `ReminderScheduler.rebuildChain`）；Web 端如需提醒依赖浏览器
  Notification API + 后台 Service Worker（阶段 6+ 视需求补齐）。
- **预算阈值仅端侧**：分类预算阈值不入 records（视为端侧偏好）；
  多设备可能短暂存在预算阈值不同步；可在 v2 升级为明文字段随 records 同步。
- **transfer 流水的反向引用**：转账生成两条对向流水（出账 / 入账），
  **不**通过反向 ID 互引；UI 层通过 `(transfer_to_account_id, amount,
  occurred_at)` 启发式对账；删除转账等价于删两条 + 重录（不提供单条覆盖）。
- **`include_in_net_assets` 不入 Room**：v1 净资产看板过滤口径仅依赖
  `archived` 字段；如需"不计入净资产"但仍可见的细粒度控制，需 v2
  扩展明文 `include_in_net_assets` 字段（当前以 `archived` 兜底）。
- **instrumented 真机冒烟**：阶段 5 Room v5→v6 迁移、四表 CRUD +
  FinanceRepository 双写、`ReminderReceiver` module 路由等已并
  FU-7 关闭条件清单（无设备/CI 环境暂不强制；详见
  `.trae/specs/stage5-finance/tasks.md`）。

## 文档

- [架构总览](docs/architecture.md)
- [零知识加密信封规范（三端互通）](docs/crypto.md)
- [HTTP API](docs/api.md)
- [模块数据 Schema（密码库/证件/事件/财务）](docs/module-schemas.md)
- [财务模块（阶段 5 v1）](docs/finance.md)
- [开发约定与发布](docs/development.md)
- [安卓构建说明](docs/android.md)

## 安全提示

- **主密码无法找回**：注册时强制生成恢复密钥（Crockford Base32），请离线妥善保存；
  丢失主密码且未保存恢复密钥等于数据永久丢失。新设备登录需已有设备审批。
- 请在 HTTPS 反向代理后对外暴露（Caddy 可自动申请证书）。
- 短信/通话/定位等安卓高敏权限在后续阶段按需申请，默认全部关闭。
