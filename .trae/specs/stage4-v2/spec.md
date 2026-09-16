# 阶段 4 v2 — 轨迹 + 日历 二版增量 - 产品需求文档（PRD）

## Overview

- **Summary**：在阶段 4a（位置轨迹 v1）+ 阶段 4b（日历事件 v1）已完成的基础上，
  把两份 spec 中明确登记但本期"不做"的能力（4a 共 8 项 Future Enhancements +
  4b 共 9 项 Future Enhancements，合计 17 项候选）合并打包为**阶段 4 v2**。
  v2 不重做 v1 既有架构，沿用 4a 的"前台定位 + 块缓冲 + 加密上行 + 服务端月表
  + 浏览器解密"骨架与 4b 的"事件 records + 单闹钟链式调度 + 三端 RRULE 展开"
  骨架，本 spec 的全部 FR 仅在 v1 既有组件上做增量（新增模块 / 新增 UI / 新增
  端侧能力），**不动**既有加密链路、不动服务端 records/locations 既有 API。
- **Purpose**：把 v1 中"承诺但延后"的能力集中落地，使轨迹 + 日历达到
  "可回放 / 可洞察 / 可导入导出 / 可跨端"的自托管基本盘，为阶段 6（AI Agent）
  提供"地点 / 时间 / 行程 / 重复规律"四类高价值上下文。
- **Target Users**：自托管 Everything 服务、单 Android 主机 + 任意浏览器的
  唯一用户本人。本期仍维持**单用户自托管**，不引入多用户 / 社交 / 共享。

## Goals

- 4a v2 落地 8 项 Future Enhancements：热图 / 交通方式 / 统计洞察 / GPX & GeoJSON
  / Live 实时 / 地理围栏提醒 / Android 端查看 / 照片集成。
- 4b v2 落地 9 项 Future Enhancements：任务模块 / 服务端哑调度 + FCM / 时区独立
  / 单次实例覆盖编辑 / 日视图 + agenda / iCal 导入导出 / 富文本 note + 附件 +
  协作 / Android 桌面 widget / Web 端提醒。
- 跨模块打通：任务 ↔ 事件 / 日历 ↔ 轨迹 place 命名 / 附件 ↔ 事件 + 轨迹点。
- 三端共享纯函数优先：地理围栏判定 / 交通方式分类 / 统计聚合 / 重复规则展开
  v2 子集 / iCal 解析，全部以纯函数实现并配跨端 fixture + 双锁定单测
  （JVM + Vitest）。
- 不破零知识：所有 v2 能力**服务端不解密**；聚合 / 分类 / 围栏判定 / 导入导出
  全部在客户端完成；服务端仅承担"块存储 / 通知到达 / Live 推送密文块 id"。
- 不破坏 v1：v2 不修改 v1 既有 API 形态；v1 单测零回归；新增能力经 v1 接口扩展点
  接入，不绕过既有 envelope / AAD / records / Room 表。
- 文档 / 门禁收尾：docs/locations.md / docs/calendar.md v2 增量补充；CI 三端
  + 零知识 grep + AC 映射复跑全过。

## Non-Goals

- **不做**多用户 / 社交 / 共享 / 邀请 / 协作触发链路：协作 UI 仅做"共享只读链接
  （可选，不强制）"，本期不实现邀请流；富文本协作（@提及 / 评论流）列入 v3 候选。
- **不做**服务端空间分析 / 地理索引 / 服务端 RRULE 展开 / 服务端 iCal 校验：
  零知识约束下服务端不解密，纯函数分析全部在客户端。
- **不做**银行 API / 第三方日历账号（Google Calendar / iCloud / 飞书）直连：
  第三方互通仅走 iCal / GPX / GeoJSON 等文件级导入导出。
- **不做**iOS 端：v2 仍仅 Android + Web；iOS 列入未来阶段。
- **不做** Activity Recognition 权限的强制申请：交通方式分类先做"纯本地启发式"
  （速度阈值 + 加速度变化），不申请 `ACTIVITY_RECOGNITION`；如效果不达标，
  列入 v3 评估路径并单独走权限评估。
- **不做**服务端对事件触发时刻明文 / 地理围栏中心点 / 任务截止时间的存储：
  任何"调度型"能力在客户端或端侧本地完成；服务端哑调度仅推送"块到达事件"。
- **不做** FCm 强制依赖：服务端哑调度 + Web Push + Android 本地闹钟三轨并行；
  用户可关闭 FCM 通道，仅依赖本地闹钟。

## Future Enhancements (v3 候选)

> v2 spec 完成后，以下能力明确列入 v3，本期不做：

- 多用户 / 共享 / 邀请 / 协作触发（OAuth + RBAC）。
- Activity Recognition 权限路径的交通方式精分类（合规评估 + 真机效果验证）。
- iOS 端日历 + 轨迹（HealthKit / CoreLocation）。
- 服务端哑调度的全量调度时刻密文化（v2 仍服务端零明文）。
- AI Agent（阶段 6）联动：v2 已预留"日历 / 轨迹 / 任务"工具注册点；
  v3 由 Agent 模块消费 v2 暴露的工具。
- 跨设备闹钟同步（共享调度表，走 records 通道加密）。

## Background & Context

### 4a v1（阶段 4a 已交付）锚点

- **既有架构**：Android 前台服务（LocationManager GPS + Network 兜底）→
  Room v4 两表（`location_points` 明文缓冲 / `location_outbox` 密文块队列）
  → 15 分钟周期 SyncWorker 兜底上行 → 服务端 `locations_YYYYMM` 月表 →
  `/api/v1/locations/batch` / `?from&to` / `DELETE` 三端点 → Web 浏览器解密
  → Leaflet 双视图（时间线 + 地图）。
- **既有加密**：XChaCha20-Poly1305 信封，AAD 绑块 id `{deviceId}:{startTs}:{endTs}`
  字节级一致；MK 仅驻内存；MK 不可用静默停采。
- **既有非做（v1 已固化在 Non-Goals 与 FR）**：交通方式分类 / 统计洞察 /
  GPX 导入导出 / Live 实时 / 地理围栏 / Android 端查看 / 照片集成 / 热图。
- **v1 留口**：
  - 写库后经 hub 广播 `locations_changed` 事件（v1 Web 不订阅，**v2 消费**）。
  - `module="place"`、`type="place"` 的 vault records 用于 visit 命名
    （id = `place:{geohash7}`），v2 增加"围栏 / 行程段命名"复用同一通路。
  - `docs/module-schemas.md` §9.x 模块明文 JSON Schema，v2 增加 place +
    location 统计字段扩展。

### 4b v1（阶段 4b 已交付）锚点

- **既有架构**：事件作为 records 写入（`module="event"`、`type="event"`）→
  Web + Android 双端 CRUD + 月/周视图 → 三端 RRULE B 档子集纯函数展开
  （DAILY/WEEKLY/MONTHLY/YEARLY + 间隔 + 工作日 + count/date 终止 + exdate）→
  Android AlarmManager 单闹钟链式调度本地提醒（`USE_EXACT_ALARM` + 
  `SCHEDULE_EXACT_ALARM`）→ 常驻通知 + 链头重建三保险（Receiver / BootReceiver
  / SyncWorker 完成后）。
- **既有加密**：复用 4a 同款 envelope；AAD `eve:v1:record:{id}:{module}:
  {BE_UINT64(version)}`（module="event"）。
- **既有非做**：任务模块 / 服务端哑调度 / TZ 独立 / 单次实例覆盖 / 日视图 +
  agenda / iCal 导入导出 / 富文本 + 附件 + 协作 / Android widget / Web 端提醒。
- **v1 留口**：
  - `reminders: number[]`（提前分钟数）已就绪，v2 增加"任务模块"复用同一
    reminders 通道。
  - `tz_mode` 字段已保留（v1 仅 `local`），v2 启用 `tz` 模式。
  - 三端 RRULE 展开纯函数 + fixture 已就绪，v2 增加 BYDAY / HOURLY / RDATE /
    EXRULE 子集扩展。
  - `BootReceiver` 单一接收器模式 + 链头重建，v2 增加"任务"通道不新建
    第二个 receiver。

### v1 → v2 不动条款

v2 spec 的硬约束：

1. **不动** v1 既有 envelope / AAD / records / locations 表 / Room 表 / 
   Pinia store 主结构 / Chi 路由主结构。
2. **不破坏** v1 单测：v1 既有 22 Android suite / 190 tests / 20 Web files /
   268 tests 必须零回归。
3. **不新增** 服务端明文存储能力：任何需要在服务端存放的"调度时刻 / 围栏中心点 /
   任务截止"字段必须先加密上行走 records 通道，禁止明文入库。
4. **不破坏** v1 零知识纪律：通知文案 / 日志 / 崩溃消息 grep 模式必须保持
   "不渲染金额 / 卡号 / 坐标 / 具体日期数字"等同级别红线。

## Functional Requirements

> 17 项 Future Enhancements 按"模块 + 优先级"打包为 8 个 FR 组。每组内含
> 子能力列表 + 跨端约束 + 零知识约束 + 与 v1 的扩展点。

---

### FR-V2-A：轨迹 v2 增量（4a Future Enhancements 8 项 → 8 子能力）

#### FR-V2-A.1 热图 / Fog of War

- **客户端渲染**：Web 端新增 `/locations/heatmap` 子页，浏览器内解密当日
  （或选定月份）轨迹块 → 按本地日切分 → 渲染为 Leaflet 热图层（径向渐变，
  不写瓦片 / 不持久化）；Fog of War 模式额外渲染"已探索区域"覆盖层
  （geohash7 网格，单元透明度按访问次数递增）。
- **配置项**：热图半径 + 模糊度（仅前端本地状态，不入 records）。
- **零知识**：服务端不参与；密文块到达后浏览器本地解密渲染。
- **与 v1 扩展点**：复用 v1 `LocationsPage.vue` 既有解密函数；新增
  `web/src/locations/heatmap.ts`（纯函数：点流 → 热图密度栅格）+ `FogOfWar.vue`。

#### FR-V2-A.2 交通方式分类（纯本地启发式）

- **算法**：纯函数 `classifyTransport(points: TripPoint[])` —
  速度阈值（步行 ≤ 2 m/s / 骑行 2-7 m/s / 驾车 7-30 m/s / 公交 30+ m/s）+ 
  加速度方差 + 行程段起止点是否为命名地点（place 命名命中按段修正）。
- **Android 端**：在 v1 既有 trip 段上挂 `transport` 标签，写入 records
  （`module="location_derived"`、`type="trip_tag"`）；id = `{deviceId}:
  {tripStartTs}:{tripEndTs}`。
- **Web 端**：时间线 trip 段渲染标签图标；统计页（FR-V2-A.3）按标签聚合。
- **零知识**：分类在端侧完成；服务端仅存密文 trip_tag 块。
- **活动识别权限**：v2 **不申请** `ACTIVITY_RECOGNITION`；v3 评估。

#### FR-V2-A.3 统计洞察页

- **客户端聚合**：Web 新增 `/locations/insights`；浏览器解密全月（≤62 天
  滚动窗口）→ 聚合：
  - 里程（haversine 累加 trip 段，按 transport 分桶）
  - 停留 / visit 计数 + 总时长
  - 命名地点访问频率 top 10
  - 月度"活动日历"（每日有无轨迹 + 总距离柱）
  - 国家 / 城市分布（geohash5 / 3 反查表，前端内置静态表）
- **零知识**：聚合在浏览器；服务端零贡献。
- **与 v1 扩展点**：复用 v1 既有解密函数 + `TripPoint` 数据结构。

#### FR-V2-A.4 GPX / GeoJSON 导入导出

- **导出**：Web 端 `/locations/export?format=gpx|geojson`；浏览器解密所选
  范围 → 生成 GPX 1.1 或 GeoJSON RFC 7946 → 浏览器下载（`Blob` + `URL.
  createObjectURL`，不落服务端）。
- **导入**：Web 端 `/locations/import`；选文件 → 客户端解析为统一 `TripPoint`
  → 走 vault sealRecord 入库（`module="location_imported"`、`type="imported"`）→
  上行同步（与 v1 加密链路一致）。
- **零知识**：导出导入全在客户端；服务端仅承载密文 records。
- **冲突策略**：导入文件携带的 id 与本地已存在 id 冲突时按"较大 version 胜"
  （v1 既有 `version: Long` 单调递增）。

#### FR-V2-A.5 Live 实时模式

- **服务端**：写库后 hub 广播 `locations_changed`（v1 已落地，**v2 启用
  Web 订阅**）；新增 SSE 端点 `GET /api/v1/locations/stream`（approved 分组，
  按 user_id 过滤），复用 v1 `sse_handler.go` 模式（与 events_changed
  同款 hub 订阅）。
- **Web 端**：Web LocationsPage 顶部新增"实时模式"开关，开启后 EventSource
  订阅 → 收到块 id 列表 → 浏览器解密追加 → 自动滚动到最新点。
- **零知识**：服务端仅推送"块到达事件 / 块 id 列表"，不推密文不推明文。
- **服务端增量**：仅新增 SSE 端点 + hub 订阅映射；不修改 v1 既有 records /
  locations 存储。

#### FR-V2-A.6 地理围栏提醒

- **命名地点数据源**：复用 v1 visit 命名（`module="place"` records）。
- **围栏判定**：Android 前台定位服务内新增围栏检测器（纯函数，参见
  Android 端纯函数镜像）—— 当点进入 / 离开命名地点半径（默认 100m）→
  触发本地通知（不持久化、不上行）。通知文案抽象："您已到达/离开 某地点"
  （**不渲染坐标 / 不渲染半径数字**）。
- **零知识**：围栏半径 + 中心点已加密在 place records；通知文案仅引用
  `place.name`（明文）做文案渲染。
- **与 v1 扩展点**：复用 v1 `VisitDetector` + `placeDao`；通知文案沿用 v1
  既有 `Reminders` 通道（不新建通知通道）。

#### FR-V2-A.7 Android 端轨迹查看

- **页面**：Android VaultScreen 卡片新增"轨迹"入口 → `LocationsScreen` 
  Compose 页面，复用 4a 既有 Room `LocationOutboxDao` 拉取本地缓冲 →
  本地解密渲染（不依赖网络）→ 简易地图（OSMDroid 或 v1 Web 端的轻量离线瓦片）。
- **地图方案**：v2 采用 **OSMDroid**（开源、零 GMS 依赖、瓦片可走自托管或
  OSM 公共服务器），纯 Kotlin 渲染（不依赖 Compose Map 库）。
- **零知识**：解密在设备本地完成；瓦片源可配（默认 OSM 公共，可改自托管）。

#### FR-V2-A.8 照片集成

- **关联逻辑**：Android 端扫描系统相册（`MediaStore.Images` 时间范围匹配），
  在轨迹时间线中按"拍摄时间 ±30 分钟"窗口匹配当日 trip / visit 段，挂缩略图。
- **权限**：v2 申请 `READ_MEDIA_IMAGES`（API 33+）或 `READ_EXTERNAL_STORAGE` 
  （API ≤32）；权限说明 UI 化（应用设置页明示用途）。
- **照片存储**：v2 **不存储照片密文**（附件存储列入 v3 候选 + 阶段 7 健康模块）；
  仅在本地缓存缩略图引用 ID（MediaStore Uri），不复制照片内容到 App 沙箱。
- **零知识**：照片 URI 不上行；时间线渲染时本地查询 MediaStore。
- **Web 端**：Web 端不做照片集成（避免跨设备照片同步）。

---

### FR-V2-B：日历 v2 增量（4b Future Enhancements 9 项 → 9 子能力）

#### FR-V2-B.1 任务模块

- **数据模型**：复用 events records 通道，新增 `type="todo"` 子类型；明文字段：
  ```json
  {
    "id": "uuid",
    "title": "≤200 字符",
    "due_ts": 1735689600000,
    "all_day": false,
    "tz_mode": "local",
    "priority": "low|normal|high",
    "status": "open|done",
    "linked_event_id": null | "uuid",
    "reminders": [0, 15, 1440]
  }
  ```
- **CRUD**：Web + Android 双端 `TodoList` + `TodoEditor`；状态切换（open / done）
  走 records `version` 自增（v1 同款）。
- **复用 v1 提醒**：todo 复用 v1 events 单闹钟链式调度；链路扩展点 = 在
  `Reminders` 通道增加 `kind="todo"` 分支，渲染文案"任务：{title}"（不渲染
  due_ts 数字）。
- **联动**：todo 可关联到某 event（`linked_event_id`），事件编辑器侧栏显示
  关联 todo 列表。
- **零知识**：todo 走同款 envelope，AAD `module="event"` + `type="todo"`
  子标识（沿用 v1 三端 fixture 模式）。

#### FR-V2-B.2 服务端哑调度 + FCM 唤醒

- **服务端**：新增 `events_changed` SSE 端点（approved 分组，按 user_id 
  过滤），复用 v1 `sse_handler.go` 模式；事件 records 写库后 hub 广播
  `events_changed` + 触发块 id 列表。
- **Android FCM**：v2 不引入 Firebase SDK（避免 GMS 依赖）；改用自托管 Web 
  Push（VAPID）+ Android System Web Push（API 26+ 自定义 Push 通道）作为替代
  路径——**仅服务端哑调度触发"通知到达"事件**，客户端收到后再触发本地
  AlarmManager 链头重建。
- **可关闭**：用户在设置页可关闭 FCM / Web Push 通道，仅依赖本地闹钟
  （v1 行为不变）。
- **零知识**：服务端仅推送"块到达事件"，不推密文不推明文起始时刻。

#### FR-V2-B.3 时区独立存储（tz_mode=tz）

- **字段启用**：v1 `tz_mode` 仅 `local`，v2 启用 `tz` 模式；明文增加
  `tz_id: "Asia/Shanghai"` IANA 时区字段。
- **三端纯函数**：RRULE 展开纯函数新增 `tzMode = "tz"` 分支——展开时按
  `tz_id` 转换为 UTC 毫秒，再按本地 UTC 比较；Android 端 `Recurrence.kt`
  + Web 端 `expand.ts` 双锁定。
- **零知识**：服务端不解析 tz_id，仅存密文。
- **v1 兼容**：v1 既有 `tz_mode=local` 事件保持原行为不变（不强制升级）。

#### FR-V2-B.4 单次实例覆盖编辑

- **数据模型**：明文增加 `overrides: { [isoDate: string]: Partial<EventFields> }` 
  字段；key 为本地时区 ISO 日期（`YYYY-MM-DD`），value 为覆盖字段。
- **编辑 UX**：Web + Android 事件编辑器点击重复事件实例时显示"仅此一次 / 
  整序列"切换；"仅此一次"等价于 v1 的"exdate 跳过 + 新建单发事件"但
  走单一记录不分裂（避免 UX 误导）。
- **三端纯函数**：展开函数新增 overrides 处理分支；fixture 增加 ≥6 用例
  （覆盖字段 / 不覆盖字段 / 嵌套 RRULE 等）。
- **零知识**：overrides 走同款 envelope。

#### FR-V2-B.5 日视图 + agenda 列表视图

- **日视图**：Web + Android 增加 DayView（24 小时列）；点击事件块进入编辑器。
- **agenda 列表**：agenda 按"今天 / 明天 / 本周 / 本月之后"四段折叠列表；
  纯前端组件，复用 events store。
- **零知识**：无服务端增量。

#### FR-V2-B.6 iCal 导入导出

- **导出**：Web 端 `/calendar/export?format=ics`；浏览器解密所选范围 → 
  生成 RFC 5545 iCalendar → 浏览器下载（`Blob`）。
- **导入**：Web 端 `/calendar/import`；选 `.ics` 文件 → 客户端解析（前端
  内置 iCal 解析器，零依赖）→ 走 vault sealRecord 入库（`module="event"`、
  `type="imported"`）→ 上行同步。
- **冲突策略**：导入事件携带 `UID`；UID 与本地已有事件冲突按"较大 version 胜"
  + 编辑器弹"合并 / 覆盖 / 跳过"对话框。
- **零知识**：解析在浏览器；服务端零贡献。

#### FR-V2-B.7 富文本 note + 附件 + 协作

- **note 富文本**：v2 启用 Markdown 子集（标题 / 列表 / 代码块 / 链接），
  渲染层走前端库（marked 或 markdown-it，零网络依赖）；存储为明文
  `note_md: string` 字段。
- **附件**：v2 **不实现附件上传**（阶段 7 健康模块强依赖附件，列入 v3 候选
  与阶段 7 联合启动）；本 FR 仅预留 `attachments: [{id, mime, size, sha256}]` 
  字段，UI 显示"附件（v3 启用）"占位。
- **协作（共享只读链接）**：v2 仅做"生成一次性只读 token"——服务端新增
  `GET /api/v1/events/shared/:token`（approved 分组豁免，仅 token 验证）→
  返回密文事件块；客户端解密后渲染只读视图。**不做**邀请流 / 编辑协作 / 
  评论流（列入 v3）。
- **零知识**：token 仅含 user_id + scope + exp 元数据，事件密文依旧服务端
  零解密。

#### FR-V2-B.8 Android 桌面 widget

- **Widget 组件**：AppWidgetProvider（API 26+），尺寸 4x2 / 4x4 两种；
  显示当日事件列表（标题 + 起止时间，最多 5 条），点击进入 App 编辑器。
- **数据来源**：AppWidgetProvider 启动后从 Room `events` 表拉取本地明文
  （不依赖网络）→ RemoteViewsService 渲染。
- **更新频率**：日切（午夜本地时区）刷新；事件 CRUD 时通过 
  `AppWidgetManager.updateAppWidget` 主动刷新。
- **零知识**：widget 仅读取本地 Room 明文；不触发任何上行。

#### FR-V2-B.9 Web 端提醒

- **Web Notification API**：Web 端 CalendarView 增加"允许浏览器通知"开关；
  用户授权后启用 Notification API + Service Worker（`sw.js`）做后台提醒
  （页面关闭后仍可达）。
- **触发链路**：用户在 Web 端打开 CalendarView → Service Worker 注册 →
  IndexedDB 缓存本地事件明文 + 触发时刻（不上行，仅本地）→ setTimeout /
  Service Worker `showNotification` 触发。
- **零知识**：触发时刻不进 records；IndexedDB 仅缓存客户端本地副本（v3 评估
  是否需要加密 IndexedDB）。
- **降级路径**：用户拒绝通知权限 → 退回"页面打开时弹 toast"路径。

---

### FR-V2-C：跨模块打通（3 子能力）

#### FR-V2-C.1 任务 ↔ 事件联动

- 事件编辑器侧栏显示关联 todo 列表（双向链接）。
- todo 完成时事件编辑器显示徽标（"关联任务全部完成"）。

#### FR-V2-C.2 日历 ↔ place 命名打通

- 事件编辑器可绑定到命名地点（`linked_place_id` 引用 place records）。
- Web 端事件详情页显示地点名称 + Leaflet 小地图标记。

#### FR-V2-C.3 附件字段占位（v3 钩子）

- 事件 / 轨迹 / todo 三类记录增加 `attachments: [{id, mime, size, sha256}]` 
  字段占位（v3 启用）；
- v2 阶段字段始终为空数组，UI 显示"附件（v3 启用）"占位提示。

## Acceptance Criteria (AC 矩阵)

> 共 17 个 AC，每个 AC 对应一组子能力 + 单测 / e2e 验证。

| AC | 标题 | 对应 FR | 验证方式 |
|---|---|---|---|
| AC-V2-1 | 热图渲染 | FR-V2-A.1 | Web 单测 + 浏览器手动冒烟 |
| AC-V2-2 | 交通方式分类正确率 ≥ 80% | FR-V2-A.2 | 三端 fixture + Web / Android 单测 |
| AC-V2-3 | 统计洞察 5 类聚合 | FR-V2-A.3 | Web 单测 + 手动冒烟 |
| AC-V2-4 | GPX / GeoJSON 导入导出回环一致 | FR-V2-A.4 | Web 单测 + fixture |
| AC-V2-5 | Live 实时模式订阅可见 | FR-V2-A.5 | Web EventSource 单测 + 手动冒烟 |
| AC-V2-6 | 地理围栏本地触发 | FR-V2-A.6 | Android JUnit + 手动冒烟 |
| AC-V2-7 | Android 端轨迹查看可达 | FR-V2-A.7 | Android instrumented test |
| AC-V2-8 | 照片时间线匹配（30min 窗口） | FR-V2-A.8 | Android JUnit + 手动冒烟 |
| AC-V2-9 | 任务 CRUD + 复用提醒通道 | FR-V2-B.1 | 双端单测 + 手动冒烟 |
| AC-V2-10 | 服务端哑调度推送到达 | FR-V2-B.2 | Web SSE 单测 + Go 单测 |
| AC-V2-11 | tz_mode=tz 跨端展开一致 | FR-V2-B.3 | 三端 fixture |
| AC-V2-12 | 单次实例覆盖编辑正确 | FR-V2-B.4 | 三端 fixture |
| AC-V2-13 | 日视图 + agenda 渲染 | FR-V2-B.5 | Web / Android 单测 |
| AC-V2-14 | iCal 导入导出回环一致 | FR-V2-B.6 | Web 单测 + fixture |
| AC-V2-15 | 富文本 note + 协作只读链接 | FR-V2-B.7 | Web 单测 + 手动冒烟 |
| AC-V2-16 | Android widget 刷新可达 | FR-V2-B.8 | Android instrumented test |
| AC-V2-17 | Web Notification API 触发 | FR-V2-B.9 | 浏览器手动冒烟 |

## 技术风险与缓解

| # | 风险 | 缓解 |
|---|---|---|
| 1 | 17 个 FR 一次性打包范围过大，里程碑失控 | 任务书分 6-8 批次推进；每批独立门禁 + 推送；任意批次失败不阻塞其他模块 |
| 2 | Activity Recognition 权限未申请导致交通方式分类不准 | v2 仅启发式；≥ 80% 准确率为 AC 通过线；不达标列入 v3 |
| 3 | Web Notification API 浏览器兼容性差 | 仅 Chrome / Edge / Firefox 主干支持；Safari 降级到"页面打开时 toast" |
| 4 | 服务端新增 SSE / 共享只读端点扩大攻击面 | 全部走 approved 分组；共享 token 一次性 + 短时效（24h）；审计 `event.shared.read` |
| 5 | 照片集成触发 `READ_MEDIA_IMAGES` 权限导致用户拒绝 | 权限说明 UI 化；用户拒绝仅隐藏时间线缩略图，不影响轨迹核心功能 |
| 6 | iCal 解析器自研工作量大 | v2 仅支持 RFC 5545 子集（VEVENT + DTSTART/DTEND/RRULE/EXDATE）；非标字段静默忽略 |
| 7 | FCM / Web Push 引入增加分发链路 | v2 设置页明示"可关闭"；关闭后仅本地闹钟（v1 行为不变） |
| 8 | 附件字段空数组导致 records 体积膨胀 | `attachments` 字段在为空时不写入（omitempty）；schema version=2 标记 |

## 度量 / 验收

- v1 零回归：22 Android suite / 190 tests / 20 Web files / 268 tests 全绿。
- v2 新增：≥ 100 Android test + ≥ 60 Web test（按 17 个 FR 平均分布）；
  三端共享纯函数 fixture ≥ 30 个用例。
- 端到端冒烟：`docs/smoke/stage4-v2-e2e.md` 含 ≥ 12 场景。
- 文档同步：`docs/locations.md` / `docs/calendar.md` / `docs/crypto.md` / 
  `docs/android.md` v2 增量补充；`everything_plan.md` 阶段 4 v2 行勾选 ✅。
- 零知识 grep：通知文案 / 日志 / 崩溃消息 grep 模式保持 v1 红线水准；
  围栏通知 + 任务通知 + Live 推送全部不渲染坐标 / 时刻数字 / 附件元数据。
- AC 映射：17 个 AC 全部映射到对应 TR 子任务。

## 后续路线（v3 候选）

- 多用户 / 协作触发（OAuth + RBAC）
- 附件完整闭环（附件块存储 + 上传/下载协议 + 富文本编辑器附件）
- Activity Recognition 合规路径（评估真机效果 + 权限影响）
- iOS 端（HealthKit / CoreLocation）
- AI Agent 工具消费（阶段 6）：消费 v2 暴露的"日历 / 轨迹 / 任务"工具
- 跨设备闹钟同步（共享调度表加密上行）