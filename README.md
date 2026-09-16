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
> 服务端零改动；详见下文"日历（阶段 4b）"小节）。
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

## 功能矩阵（阶段 4b 关键能力）

| 能力 | Android | Web | Go（服务端） |
|---|---|---|---|
| 日程（事件 + 重复规则 + 闹钟） | ✅（CRUD + 月/周视图 + AlarmManager exact 闹钟） | ✅（CRUD + 月/周视图 + 编辑器） | N/A（服务端零改动，仅复用 records 通道透传密文） |
| 重复规则展开 | ✅（JVM `Recurrence.kt`） | ✅（`expand.ts` 纯函数） | N/A |
| 本地精确闹钟 | ✅（AlarmManager + 权限降级） | N/A（浏览器通知走 Notification API，由后续阶段补齐） | N/A |
| RRULE B 档子集 | ✅（DAILY/WEEKLY/MONTHLY/YEARLY + interval + 7 工作日 + 结束三选一） | ✅ | N/A |

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

## 文档

- [架构总览](docs/architecture.md)
- [零知识加密信封规范（三端互通）](docs/crypto.md)
- [HTTP API](docs/api.md)
- [模块数据 Schema（密码库/证件）](docs/module-schemas.md)
- [开发约定与发布](docs/development.md)
- [安卓构建说明](docs/android.md)

## 安全提示

- **主密码无法找回**：注册时强制生成恢复密钥（Crockford Base32），请离线妥善保存；
  丢失主密码且未保存恢复密钥等于数据永久丢失。新设备登录需已有设备审批。
- 请在 HTTPS 反向代理后对外暴露（Caddy 可自动申请证书）。
- 短信/通话/定位等安卓高敏权限在后续阶段按需申请，默认全部关闭。
