# 阶段 4 v2 — 轨迹 + 日历 二版增量 - 实施计划

> 需求来源：[spec.md](file:///d:/github/everything/everything/.trae/specs/stage4-v2/spec.md)
> 任务按依赖 + 模块打包，分 8 个批次（B1~B8）推进。每批独立门禁 + 推送；
> 每批含子代理派发 + 主会话门禁复跑 + tasks.md 三段式回填（Pass Condition
> / Status / Completion Evidence）。

## 批次划分总览

| 批次 | Task | 内容 | 前置 |
|---|---|---|---|
| B1 | T1 | 轨迹 v2 纯函数：交通方式分类 + 统计聚合 + 热图密度栅格 | v1 已落地 |
| B2 | T2 + T3 | 轨迹 v2 Web UI：热图页 / Fog of War / 统计洞察页 / GPX 导出 | T1 |
| B3 | T4 + T5 | 轨迹 v2 Android：地理围栏 + trip 标签 + 端侧照片集成 | T1 |
| B4 | T6 | 服务端增量：SSE 端点（locations + events）+ 共享只读 token | v1 |
| B5 | T7 + T8 | Web Live 实时模式 + Web Notification API + iCal 导入导出 | T6 |
| B6 | T9 + T10 | 日历 v2 纯函数扩展：tz_mode=tz + 单次覆盖 + RRULE BYDAY/HOURLY 子集 | v1 |
| B7 | T11 + T12 | 日历 v2 双端 UI：日视图 + agenda + todo 模块 + 任务联动 | T6 + T9 |
| B8 | T13 + T14 | 跨模块：place 命名打通 / 附件字段占位 + Android widget + 文档 + 门禁 | T7 + T12 |

---

## Task 1: 轨迹 v2 纯函数层（交通方式 + 统计 + 热图密度）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 既有纯函数（`VisitDetector` / `TripPoint`）
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/location/TransportClassifier.kt`：
    - 纯函数 `classify(points: List<TripPoint>): TransportLabel`
    - `enum class TransportLabel { WALK, BIKE, CAR, BUS, UNKNOWN }`
    - 算法：速度分桶 + 加速度方差 + 命名地点起止修正（命中 place records
      按段修正）
  - 新建 `android/app/src/main/java/com/everything/eve/location/LocationStats.kt`：
    - 纯函数 `aggregate(points: List<TripPoint>, visits: List<Visit>, 
      places: List<Place>): LocationStats`
    - 返回：mileage / visit 总时长 / 命名地点访问频率 top 10 / 月度活动日历 /
      国家城市分布（geohash5/3 反查）
  - 新建 `android/app/src/main/java/com/everything/eve/location/Heatmap.kt`：
    - 纯函数 `densityGrid(points: List<TripPoint>, cellSizeMeters: Int): 
      List<GridCell>`
  - Web 端镜像三个 `.ts` 文件：`web/src/locations/transportClassifier.ts` /
    `locationStats.ts` / `heatmap.ts`
  - 三端共享 fixture：`web/src/locations/__fixtures__/transport-cases.json` /
    `stats-cases.json` / `heatmap-cases.json`（SHA-256 双端一致）
- **TR 列表**:
  - TR-1.1 交通方式分类纯函数（Android + Web 镜像 + fixture）
  - TR-1.2 统计聚合纯函数（5 类聚合 + 反查表）
  - TR-1.3 热图密度栅格纯函数
  - TR-1.4 三端 fixture SHA-256 一致

---

## Task 2: 轨迹 v2 Web UI - 热图 / Fog of War / 统计洞察

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `web/src/views/LocationsHeatmap.vue`：Leaflet 热图层 + Fog of War
    覆盖层（geohash7 网格）
  - 新建 `web/src/views/LocationsInsights.vue`：5 类聚合卡片（里程 /
    停留 / 命名地点 / 活动日历 / 国家城市）+ recharts 图表
  - 修改 `web/src/router/index.ts`：注册 `/locations/heatmap` + `/locations/
    insights`
  - 修改 `web/src/views/AppShell.vue` `menuOptions`：增加"热图" + "统计"
    子菜单项
  - 新建 `web/src/views/locations/__tests__/LocationsHeatmap.spec.ts`
  - 新建 `web/src/views/locations/__tests__/LocationsInsights.spec.ts`
- **TR 列表**:
  - TR-2.1 热图页渲染 + Fog of War 切换
  - TR-2.2 统计洞察页 5 类聚合卡片
  - TR-2.3 路由 + 菜单挂载

---

## Task 3: 轨迹 v2 Web UI - GPX / GeoJSON 导入导出

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `web/src/locations/importExport.ts`：
    - `exportGpx(points, visits): string`（GPX 1.1）
    - `exportGeoJson(points, visits): string`（RFC 7946）
    - `parseGpx(content): TripPoint[]` + `parseGeoJson(content): TripPoint[]`
    - `roundtripGpx(points): boolean` / `roundtripGeoJson(points): boolean`
      回环一致校验
  - 新建 `web/src/views/LocationsExport.vue`：导出对话框（格式选择 +
    日期范围 + 下载触发）
  - 新建 `web/src/views/LocationsImport.vue`：导入对话框（文件选择 +
    解析预览 + vault sealRecord 入库）
  - 修改 `web/src/router/index.ts`：注册 `/locations/export` + `/locations/
    import`
  - 新建 `web/src/locations/__tests__/importExport.spec.ts`：≥6 用例
    （GPX 导出 / GeoJSON 导出 / GPX 解析 / GeoJSON 解析 / 回环一致 / 冲突策略）
- **TR 列表**:
  - TR-3.1 GPX / GeoJSON 导出（含回环一致）
  - TR-3.2 GPX / GeoJSON 解析入库
  - TR-3.3 导入导出 UI + 路由

---

## Task 4: 轨迹 v2 Android - 地理围栏 + trip 标签

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/location/GeofenceDetector.kt`：
    - 纯函数 `detect(prev: Point, curr: Point, places: List<Place>): 
      GeofenceEvent?`
    - 触发条件：进入 / 离开命名地点半径（默认 100m，可在 place records
      `radius_m` 字段自定义）
  - 修改 `android/app/src/main/java/com/everything/eve/location/LocationForegroundService.kt`：
    - 在 v1 既有 onLocationResult 末尾追加 `GeofenceDetector.detect` 调用
    - 命中 → 走 v1 `Reminders` 通道发本地通知（不新建通知通道）
    - 文案："您已到达/离开 {place.name}"（**不渲染坐标 / 半径数字**）
  - 新建 `android/app/src/main/java/com/everything/eve/data/location/TripTagDao.kt`：
    - Room 表 `location_trip_tags`（id / trip_start_ts / trip_end_ts / 
      transport_label / encrypted_payload）
  - 修改 `LocationForegroundService`：trip 段结算时调用 `TransportClassifier`
    + `TripTagDao.upsert`
  - 新建 `android/app/src/test/java/com/everything/eve/location/GeofenceDetectorTest.kt`：
    ≥6 用例（进入 / 离开 / 半径自定义 / 静默跳过未命名地点 / 通知文案红线）
  - 新建 `android/app/src/test/java/com/everything/eve/location/TransportClassifierTest.kt`：
    ≥6 用例（含 ≥ 80% 准确率 AC 验证 fixture）
- **TR 列表**:
  - TR-4.1 地理围栏检测器纯函数
  - TR-4.2 围栏触发本地通知（沿用 v1 Reminders 通道）
  - TR-4.3 trip 标签入库（`location_trip_tags` 表）
  - TR-4.4 GeofenceDetectorTest ≥6 用例 + TransportClassifierTest ≥6 用例

---

## Task 5: 轨迹 v2 Android - 端侧照片集成 + Android 端查看

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 1
- **Description**:
  - 新建 `android/app/src/main/java/com/everything/eve/location/PhotoMatcher.kt`：
    - 纯函数 `match(photoTs: Long, trips: List<TripPoint>, visits: List<Visit>):
      MatchResult?`（±30 分钟窗口）
    - `enum class MatchKind { TRIP, VISIT, NONE }`
  - 新建 `android/app/src/main/java/com/everything/eve/location/PhotoTimeline.kt`：
    - `queryMediaStore(tsRange: LongRange): List<MediaUri>`（ContentResolver
      拉取 `MediaStore.Images`）
  - 修改 `LocationForegroundService`：每 15 分钟拉取一次相册 + 关联时间线
  - 新建 `android/app/src/main/java/com/everything/eve/ui/locations/LocationsScreen.kt`：
    - Compose 页面 + OSMDroid 地图 + 时间线 + 照片缩略图
  - 修改 `android/app/src/main/java/com/everything/eve/ui/screens/VaultScreen.kt`：
    - 增加"轨迹"卡片入口
  - 修改 `android/app/src/main/AndroidManifest.xml`：增加 `READ_MEDIA_IMAGES`
    （API 33+）/ `READ_EXTERNAL_STORAGE`（API ≤32）
  - 修改 `android/app/src/main/res/values/strings.xml`：增加照片权限说明
  - 新建 `android/app/src/test/java/com/everything/eve/location/PhotoMatcherTest.kt`：
    ≥4 用例
  - 新建 `android/app/src/test/java/com/everything/eve/ui/locations/LocationsScreenTest.kt`：
    ≥4 用例（Robolectric + ComposeTestRule）
- **TR 列表**:
  - TR-5.1 PhotoMatcher 纯函数（±30min 窗口）
  - TR-5.2 MediaStore 查询集成
  - TR-5.3 LocationsScreen Compose 页面（OSMDroid 地图 + 时间线 + 缩略图）
  - TR-5.4 权限声明 + VaultScreen 入口

---

## Task 6: 服务端增量 - SSE 端点 + 共享只读 token

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 既有 sse_handler.go / records_handler.go
- **Description**:
  - 修改 `server/internal/api/locations_handler.go`：
    - 新增 `GET /api/v1/locations/stream`（approved 分组）→ 复用 v1 
      sse_handler.go hub 模式，订阅 `locations_changed`
  - 修改 `server/internal/api/events_handler.go`：
    - 新增 `GET /api/v1/events/stream`（approved 分组）→ 同款模式订阅
      `events_changed`
  - 新增 `server/internal/api/share_handler.go`：
    - `POST /api/v1/events/share`（approved 分组）：生成一次性 token + 
      scope + exp 元数据；写库 `share_tokens` 表；审计 `event.share.create`
    - `GET /api/v1/events/shared/:token`（approved 豁免，仅 token 验证）：
      返回密文事件块；审计 `event.share.read`
  - 新建 `server/internal/db/migrations/0005_share_tokens.sql`：表 DDL
    （`token TEXT PRIMARY KEY` / `user_id` / `scope` / `exp INTEGER` / 
    `created_at`）
  - 新建 `server/internal/vault/share_store.go`：CRUD
  - 新建服务端单测：`locations_sse_test.go` / `events_sse_test.go` / 
    `share_handler_test.go`（≥4 + 4 + 6 = ≥14 用例）
- **TR 列表**:
  - TR-6.1 locations SSE 端点
  - TR-6.2 events SSE 端点
  - TR-6.3 share_tokens 表 + 迁移 0005
  - TR-6.4 share_handler.go + 单测 ≥14 用例

---

## Task 7: Web Live 实时模式 + Web Notification API

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 6
- **Description**:
  - 新建 `web/src/locations/liveStream.ts`：
    - `subscribeLocations(onBlock: (ids: string[]) => void): EventSource`
    - `subscribeEvents(onBlock: (ids: string[]) => void): EventSource`
    - 断线重连（指数退避）+ 心跳
  - 修改 `web/src/views/LocationsView.vue`：顶部增加"实时模式"开关
  - 修改 `web/src/views/CalendarView.vue`：增加"实时模式"开关
  - 新建 `web/public/sw.js`：Service Worker 注册
  - 新建 `web/src/notifications/calendarNotifications.ts`：
    - `requestPermission()` / `scheduleNotification(event, dueTs)` / 
      `cancelNotification(eventId)`
    - IndexedDB 缓存本地事件明文 + 触发时刻（不上行）
  - 修改 `web/src/views/CalendarView.vue`：增加"允许浏览器通知"开关
  - 新建 `web/src/locations/__tests__/liveStream.spec.ts`：≥4 用例
  - 新建 `web/src/notifications/__tests__/calendarNotifications.spec.ts`：
    ≥4 用例
- **TR 列表**:
  - TR-7.1 Web EventSource 订阅（locations + events）
  - TR-7.2 实时模式开关 UI
  - TR-7.3 Service Worker 注册 + IndexedDB 缓存
  - TR-7.4 Web Notification API 触发链路

---

## Task 8: iCal 导入导出

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 events records
- **Description**:
  - 新建 `web/src/calendar/ical.ts`：
    - `exportIcs(events: Event[]): string`（RFC 5545）
    - `parseIcs(content: string): ImportEvent[]`（VEVENT + DTSTART/DTEND/
      RRULE/EXDATE 子集）
    - `roundtripIcs(events): boolean` 回环一致校验
  - 新建 `web/src/views/CalendarExport.vue`：导出对话框
  - 新建 `web/src/views/CalendarImport.vue`：导入对话框（解析预览 + 冲突
    对话框 + vault sealRecord 入库）
  - 修改 `web/src/router/index.ts`：注册 `/calendar/export` + `/calendar/
    import`
  - 新建 `web/src/calendar/__tests__/ical.spec.ts`：≥8 用例（导出 / 解析
    / 回环 / RRULE 子集 / EXDATE / 冲突策略 / 非标字段忽略 / 时区处理）
- **TR 列表**:
  - TR-8.1 iCal 导出（含回环一致）
  - TR-8.2 iCal 解析入库（含冲突策略）
  - TR-8.3 导入导出 UI + 路由

---

## Task 9: 日历 v2 纯函数扩展 - tz_mode=tz + 单次覆盖 + RRULE 子集

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 `Recurrence.kt` / `expand.ts`
- **Description**:
  - 修改 `android/app/src/main/java/com/everything/eve/recurrence/Recurrence.kt`：
    - `tzMode = "tz"` 分支：`tz_id` 转 UTC 毫秒后按本地 UTC 比较
    - `overrides: Map<LocalDate, Partial<EventFields>>` 处理分支
    - RRULE 扩展：`freq=HOURLY` / `byday: List<Weekday>` 简化子集
    - `rdate: List<Long>`（新增 RDATE 支持）
    - 新增 fixture 用例 ≥6（tz_mode=tz / overrides / HOURLY / BYDAY / 
      RDATE / 嵌套 RRULE）
  - Web 端镜像修改 `web/src/calendar/expand.ts`（同款逻辑）
  - 三端共享 fixture：`web/src/calendar/__fixtures__/recurrence-v2-cases.json`
    （SHA-256 双端一致）
  - 修改 `android/app/src/test/java/com/everything/eve/recurrence/RecurrenceTest.kt`：
    +6 用例
  - 新建 `web/src/calendar/__tests__/expand-v2.spec.ts`：+6 用例
- **TR 列表**:
  - TR-9.1 tz_mode=tz 跨端展开
  - TR-9.2 单次实例覆盖（overrides）
  - TR-9.3 RRULE 子集扩展（HOURLY / BYDAY / RDATE）
  - TR-9.4 三端 fixture SHA-256 一致

---

## Task 10: 日历 v2 - 富文本 note + 协作只读链接

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 events records + Task 6
- **Description**:
  - 修改事件明文 schema（`docs/module-schemas.md` §8）：`note_md: string` 
    字段启用
  - 新建 `web/src/calendar/markdown.ts`：
    - `renderNoteMd(content: string): string`（marked 子集 + 转义）
    - 渲染白名单：标题 / 列表 / 代码块 / 链接
  - 修改 Web + Android 事件编辑器：`note` 字段改为 Markdown 编辑器
  - 修改 Web 端事件详情页：`note` 字段改为 Markdown 渲染视图
  - 新建 `web/src/views/EventSharedView.vue`：只读视图（token 验证 → 
    解密 → 渲染）
  - 修改 `web/src/router/index.ts`：注册 `/events/shared/:token`
  - 新建 `web/src/calendar/__tests__/markdown.spec.ts`：≥4 用例
  - 新建 `web/src/views/__tests__/EventSharedView.spec.ts`：≥4 用例
- **TR 列表**:
  - TR-10.1 Markdown note 字段启用 + 渲染
  - TR-10.2 共享只读 token 端到端（生成 → 访问 → 渲染）
  - TR-10.3 EventSharedView.vue + 路由

---

## Task 11: 日历 v2 - 任务模块（todo）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 9 + v1 events reminders
- **Description**:
  - 修改 `docs/module-schemas.md` §8：增加 `type="todo"` 子类型 schema
    （`id / title / due_ts / all_day / tz_mode / priority / status / 
    linked_event_id / reminders`）
  - 修改 Android + Web events records 处理：识别 `type="todo"` → 走 Todo 路径
  - 新建 `web/src/views/TodoList.vue`：列表 + 状态切换（open / done）
  - 新建 `web/src/views/TodoEditor.vue`：CRUD
  - 新建 `android/app/src/main/java/com/everything/eve/ui/todo/TodoListScreen.kt`：
    Compose 列表
  - 新建 `android/app/src/main/java/com/everything/eve/ui/todo/TodoEditorScreen.kt`：
    Compose 编辑器
  - 修改 v1 `Reminders` 通道：增加 `kind="todo"` 分支，渲染文案"任务：
    {title}"（**不渲染 due_ts 数字**）
  - 修改 `web/src/router/index.ts`：注册 `/todos`
  - 修改 `web/src/views/AppShell.vue`：增加"任务"菜单项
  - 修改 `android/app/src/main/java/com/everything/eve/ui/AppNav.kt`：增加
    `Routes.TODO`
  - 新建双端单测：≥4 + 4 = ≥8 用例（CRUD / 状态切换 / reminders 触发 /
    联动 linked_event）
- **TR 列表**:
  - TR-11.1 todo schema + 三端识别
  - TR-11.2 Web TodoList + TodoEditor
  - TR-11.3 Android TodoListScreen + TodoEditorScreen
  - TR-11.4 reminders 通道 kind=todo 分支 + 路由挂载

---

## Task 12: 日历 v2 - 日视图 + agenda 列表视图

- **Status**: `pending`
- **Priority**: high
- **Depends On**: v1 CalendarView / MonthGrid / WeekGrid
- **Description**:
  - 新建 `web/src/views/CalendarDayView.vue`：24 小时列 + 事件块
  - 新建 `web/src/views/CalendarAgendaView.vue`：四段折叠列表（今天 / 
    明天 / 本周 / 本月之后）
  - 新建 `android/app/src/main/java/com/everything/eve/ui/calendar/CalendarDayScreen.kt`：
    Compose 24 小时列
  - 新建 `android/app/src/main/java/com/everything/eve/ui/calendar/CalendarAgendaScreen.kt`：
    Compose 列表
  - 修改 Web + Android CalendarView：顶部 tab 切换（月 / 周 / 日 / agenda）
  - 新建 `web/src/views/__tests__/CalendarDayView.spec.ts`：≥4 用例
  - 新建 `web/src/views/__tests__/CalendarAgendaView.spec.ts`：≥4 用例
  - 新建 Android JUnit：≥4 + 4 = ≥8 用例
- **TR 列表**:
  - TR-12.1 Web DayView + AgendaView
  - TR-12.2 Android DayScreen + AgendaScreen
  - TR-12.3 tab 切换 UI

---

## Task 13: 跨模块打通 - place 命名 / 附件字段占位 / widget

- **Status**: `pending`
- **Priority**: high
- **Depends On**: Task 11 + v1 place records
- **Description**:
  - 修改事件 schema（`docs/module-schemas.md` §8）：增加 `linked_place_id` 
    字段 + `attachments: [{id, mime, size, sha256}]` 占位字段
  - 修改 Web + Android 事件编辑器：增加"关联地点"选择器（拉取 place 
    records 列表）
  - 修改 Web 端事件详情页：显示地点名称 + Leaflet 小地图标记
  - todo 完成时事件编辑器显示徽标（联动 FR-V2-C.1）
  - 新建 `android/app/src/main/java/com/everything/eve/widget/CalendarWidgetProvider.kt`：
    AppWidgetProvider（4x2 / 4x4）
  - 新建 `android/app/src/main/java/com/everything/eve/widget/CalendarWidgetService.kt`：
    RemoteViewsService
  - 新建 `android/app/src/main/res/xml/calendar_widget_info.xml`
  - 修改 `android/app/src/main/AndroidManifest.xml`：注册 widget receiver
  - 新建 Android instrumented test：≥3 用例（widget 渲染 / 刷新 / 点击）
- **TR 列表**:
  - TR-13.1 事件 ↔ place 联动
  - TR-13.2 附件字段占位（schema + UI）
  - TR-13.3 Android widget 实现

---

## Task 14: 文档同步 + 门禁复跑（最终）

- **Status**: `pending`
- **Priority**: high
- **Depends On**: T1~T13
- **Description**:
  - 修改 `docs/locations.md`：v2 增量章节（交通方式 / 统计洞察 / 热图 / 
    GPX / Live / 围栏 / Android 查看 / 照片）
  - 修改 `docs/calendar.md`：v2 增量章节（todo / tz 独立 / 单次覆盖 / 
    日视图 / iCal / 富文本 / widget / Web 提醒）
  - 修改 `docs/crypto.md`：v2 增量（trip_tag / todo / share_token envelope）
  - 修改 `docs/android.md`：v2 增量（OSMDroid / widget / 权限新增 / 后台
    围栏检测）
  - 修改 `docs/module-schemas.md` §8/§9：todo 子类型 + linked_place_id + 
    attachments 字段 + trip_tag 表
  - 修改 `README.md`：v2 功能矩阵扩展 + 已知问题补充
  - 修改 `everything_plan.md`：阶段 4 v2 行勾选 ✅ + v3 候选列出
  - 门禁复跑：Android `testDebugUnitTest` + `assembleDebug` + Web `vitest` 
    + `vue-tsc` + 零知识 grep + 17 AC 映射
  - 新建 `docs/smoke/stage4-v2-e2e.md`：≥12 场景冒烟
- **TR 列表**:
  - TR-14.1 docs/locations.md v2 增量
  - TR-14.2 docs/calendar.md v2 增量
  - TR-14.3 docs/crypto.md + docs/android.md + module-schemas.md v2 增量
  - TR-14.4 README + everything_plan 同步
  - TR-14.5 端到端冒烟脚本 ≥12 场景
  - TR-14.6 三端门禁复跑全绿 + 17 AC 映射 + 零知识 grep

---

## 经验汇总（v1 → v2 关键决策）

1. **v2 不重写 v1**：仅在 v1 既有组件上做增量；v1 单测零回归为硬约束。
2. **不申请 `ACTIVITY_RECOGNITION`**：交通方式分类仅启发式，v3 评估。
3. **不引入 GMS / Firebase**：FCM 改 Web Push（VAPID）+ Android System Push。
4. **附件延后**：阶段 7 健康模块强依赖附件，v2 仅字段占位。
5. **服务端零增量聚合**：统计 / 分类 / 围栏判定全部客户端。
6. **照片集成仅 Android**：Web 不做照片（避免跨设备照片同步）。

## 度量目标

- v1 零回归：22 Android suite / 190 tests / 20 Web files / 268 tests 全绿。
- v2 新增：≥ 100 Android test + ≥ 60 Web test + ≥ 30 fixture 用例。
- 三端门禁 + 17 AC 映射 + 零知识 grep + 文档同步全过。