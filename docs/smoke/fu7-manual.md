# FU-7 设备冒烟手册 — 阶段 4a + 4b

> 本文档为 [stage4-location-tracking/tasks.md](../../.trae/specs/stage4-location-tracking/tasks.md) L730-733
> 与 [stage4b-calendar/spec.md](../../.trae/specs/stage4b-calendar/spec.md) "FU-7 真机冒烟"
> 关闭条件清单的执行手册。
>
> **适用对象**：维护者/用户在物理设备或 Android emulator 上做收尾验证。
> **不替代 4a/4b 已通过的 AndroidTest 单元/仪器层验证**；本文专注"真实运行环境维度"。

---

## 〇、关闭条件总览（5 项）

| 编号 | 项目 | emulator | 真机（国产 ROM） | 真机（Pixel / 原生 Android） |
|---|---|---|---|---|
| FU-7.1 | **FGS 权限流**：前台定位服务权限申请 → 用户授权 → 服务正常启动 | ✅ | ✅ | ✅ |
| FU-7.2 | **后台定位持续采集**：灭屏 / 锁屏 / 切换应用 ≥ 30 分钟不丢失点 | ⚠️ 部分 | ✅ | ✅ |
| FU-7.3 | **BootReceiver 开机自愈**：设备重启后定位服务自动恢复 | ✅ | ✅ | ✅ |
| FU-7.4 | **包体安装**：APK 安装/启动/权限引导 | ✅ | ✅ | ✅ |
| FU-7.5 | **instrumented 三套件**：MigrationTest / LocationPackagerAndroidTest / LocationUploaderAndroidTest 真机运行 | ✅ | ✅ | ✅ |

> ⚠️ **emulator 限制**：FU-7.2 在 emulator 上"杀后台"行为接近 Android Studio AVD 而非国产 ROM；
> 小米/华为/OPPO 的省电策略无法模拟。**FU-7.2 必须在国产 ROM 真机验证一次**。

---

## 一、准备

### 1.1 设备要求

| 设备类型 | 最低要求 | 验证项 |
|---|---|---|
| 真机 A（推荐） | 小米 MIUI 14+ 或 华为 EMUI 11+ 或 OPPO ColorOS 13+ | FU-7.2 全部 |
| 真机 B（次选） | Pixel 6+ / 原生 Android 14+ | FU-7.1 / 7.3 / 7.4 / 7.5 |
| emulator（最次） | Android Studio AVD API 29+ (Google APIs) | FU-7.1 / 7.3 / 7.4 / 7.5 |

### 1.2 后端要求

- 本机或 LAN 启动 `server/cmd/eve` 二进制（或 `go run ./cmd/eve`）
- 端口 8080，监听 `0.0.0.0`
- 客户端 `http://<server-ip>:8080` 已可达（emulator 用 `10.0.2.2`）
- 服务端 `EVE_PUBLIC_BASE_URL` 环境变量指向真实可访问地址

### 1.3 APK 安装

```powershell
# 1) 重新生成 debug APK（如未生成）
cd android
.\gradlew.bat :app:assembleDebug

# 2) 通过 adb 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3) 启动并授权（首次）
adb shell am start -n com.everything.eve/.MainActivity
```

> 真机安装时若出现 "INSTALL_FAILED_UPDATE_INCOMPATIBLE"：先 `adb uninstall com.everything.eve`
> 再重装（debug 包签名与 release 不同）。

---

## 二、FU-7.1 — FGS 权限流

**目标**：验证前台定位服务权限申请 → 用户授权 → 服务正常启动。

**步骤**：

1. 启动应用 → 进入"采集"页（侧栏"采集"）
2. 第 1 卡片"定位采集"显示"未授权"
3. 点击"申请定位权限"按钮：
   - 系统弹窗依次出现：粗略定位 → 精确定位 → 后台定位 → 前台服务
4. 全部授权后，状态变为"仅前台定位"
5. 点击"申请前台服务权限" → 系统跳到设置页（Android 14+ 强制）→ 用户开启
6. 返回应用，状态变为"采集中"✅
7. **期望**：通知栏出现常驻通知"Everything 正在采集位置"

**失败排查**：

- 状态卡在"未授权"：检查 `AndroidManifest.xml` 6 项权限是否齐备
  （`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` / `ACCESS_BACKGROUND_LOCATION`
   / `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` / `POST_NOTIFICATIONS`）
- 前台服务权限跳不到设置页：检查 `Build.VERSION.SDK_INT >= 34` 分支
  与 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 调用

---

## 三、FU-7.2 — 后台定位持续采集（⚠️ 必须国产 ROM 真机）

**目标**：灭屏 / 锁屏 / 切换应用 ≥ 30 分钟不丢失点（采样间隔 ≤ 30 分钟）。

**步骤**：

1. 完成 FU-7.1 后，状态显示"采集中"
2. **关闭屏幕** 5 分钟 → 重新打开 → 检查"今日轨迹"卡片点数 ≥ 1
3. **切换到其他应用** 10 分钟（前台应用非采集）→ 返回采集页 → 检查"今日轨迹"点数继续累加
4. **锁屏 30 分钟**（最严苛）：
   - 灭屏 → 等 30 分钟 → 解锁 → 检查"今日轨迹"点数 ≥ 6（每 5 分钟一点）
5. **触发杀后台**（国产 ROM 关键）：
   - 进入"设置 → 应用管理 → Everything → 电池 → 后台运行" → 设为"无限制"
   - "自启动" → 开启
   - 关闭屏幕 30 分钟 → 解锁 → 检查"今日轨迹"是否继续累加

**期望**：

- 全程不出现"采集中断" / "服务已停止"通知
- 数据入库后正常上行（看服务端 `locations_YYYYMM` 月表新行）

**失败排查（国产 ROM 重点）**：

| 现象 | 处理 |
|---|---|
| MIUI 杀后台 | 设置 → 应用管理 → Everything → 自启动；电池无限制；MIUI 省电策略关闭 |
| EMUI 杀后台 | 设置 → 应用 → 启动管理 → 改为"手动管理"→ 全部开启 |
| ColorOS 杀后台 | 设置 → 电池 → 更多电池设置 → 关闭"睡眠待机优化" |
| 通知被关 | 通知设置 → 允许通知 + 锁定屏幕通知 + 横幅 + 响铃 |
| 数据未上行 | 检查网络；服务端 `GET /api/v1/records/sync?since=...` 看下行记录 |

---

## 四、FU-7.3 — BootReceiver 开机自愈

**目标**：设备重启后定位服务自动恢复（4a spec FR-8）。

**步骤**：

1. 完成 FU-7.1 + FU-7.2 验证（确保"采集中"状态正常）
2. **重启设备**：`adb reboot` 或长按电源键选择"重启"
3. 等待系统启动完成 → 不打开应用 → 等 5 分钟
4. 检查：
   - 通知栏是否出现"Everything 正在采集位置"常驻通知
   - 服务端 `locations_YYYYMM` 月表是否有新行（说明开机后自动恢复采集）
5. **手动启动应用** → "采集"页状态显示"采集中"（绿色）而非"未授权"

**期望**：

- 开机 5 分钟内自动恢复
- 不需要用户手动操作

**失败排查**：

- 通知未出现：检查 `BootReceiver` 是否注册 `BOOT_COMPLETED` +
  `LOCKED_BOOT_COMPLETED`（API 24+ 后者必填）
- 数据库有断档：`android/app/src/main/java/com/everything/eve/BootReceiver.kt`
  检查 `MK 不可用 → 静默放弃` 逻辑；如 MK 仍存在应启动
- 通知有但服务未启动：`LocationTrackingService.startForeground()` 失败 catch

---

## 五、FU-7.4 — 包体安装

**目标**：APK 安装/启动/权限引导无崩溃。

**步骤**：

1. `adb install -r app-debug.apk` → 期望 `Success`
2. `adb shell am start -n com.everything.eve/.MainActivity` → 期望无 ANR / 闪退
3. 走完首启引导：登录 → 解锁 → 进入 VaultScreen
4. **冷启动** 5 次：杀掉进程 → 重新启动 → 无白屏 / 黑屏 / 崩溃
5. **热启动** 5 次：Home 键 → 重新打开 → 无白屏
6. **adb logcat** 过滤 `com.everything.eve`：无 `FATAL EXCEPTION` / `ANR`

**期望**：

- 首启引导 ≤ 30 秒（不含主密码派生 Argon2id 的 1-3 秒）
- 冷启动 ≤ 3 秒到首页
- 零崩溃

**失败排查**：

- 启动崩溃：`adb logcat *:E` 看栈首行；常见 Room 迁移失败 / 主密钥派生错误
- ANR：检查 WorkManager `CollectorWorker` 是否主线程跑 IO

---

## 六、FU-7.5 — instrumented 三套件真机运行

**目标**：AndroidTest 三个文件真机/emulator 运行通过。

**步骤**：

```powershell
cd android
.\gradlew.bat :app:connectedDebugAndroidTest

# 或指定设备
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.everything.eve.data.MigrationTest
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.everything.eve.collector.location.LocationPackagerAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.everything.eve.collector.location.LocationUploaderAndroidTest
```

**期望**：

- 三套件全绿：`tests=... failures=0 errors=0`
- 报告：`app/build/reports/androidTests/connected/index.html`

**失败排查**：

- MigrationTest 失败：检查 Room v4→v5 迁移 SQL（4b 引入 `event_*` 表）
- LocationPackagerAndroidTest 失败：检查密文块打包格式
- LocationUploaderAndroidTest 失败：检查网络可达 + 服务端 /api/v1/records/push

---

## 七、阶段 4b 联动冒烟（CalendarScreen）

**目标**：在采集功能验证后顺带验证 4b 日历 UI 不依赖采集权限。

**步骤**：

1. 完成 FU-7.1 → FU-7.5 任意一项后
2. 进入"日历"页面（侧栏新增菜单项 / vault/calendar）
3. 验证：
   - 月视图 / 周视图切换
   - 新建事件 → 保存 → 退出再进 → 事件仍在
   - 重复规则编辑器：DAILY/WEEKLY/MONTHLY/YEARLY
   - 本地闹钟：精确闹钟权限申请 + 闹钟触发（需等待触发时刻）

**期望**：

- 事件 CRUD 全流程无崩溃
- 跨设备同步：另一台设备登录同一账号 → 看到刚才创建的事件

---

## 八、关闭确认

完成所有 FU-7.x 后，在 [stage-summary.md](../../.trae/specs/stage-summary.md) 第 5 节
"需要修正 / 关注的隐患"表把 H-1 状态改为"✅ 已关闭"。

---

## 九、记录模板

每次冒烟请记录：

```
设备: <厂商型号> / Android <版本> / <ROM 版本>
日期: <YYYY-MM-DD>
执行人: <name>
环境: 后端 <ip:port> / 网络 <wifi/cellular/emulator>

FU-7.1 FGS 权限流: ✅ / ❌ <备注>
FU-7.2 后台持续采集: ✅ / ❌ <备注>
FU-7.3 BootReceiver:   ✅ / ❌ <备注>
FU-7.4 包体安装:       ✅ / ❌ <备注>
FU-7.5 instrumented:   ✅ / ❌ <备注>

附加观察: <国产 ROM 特殊问题 / 备注>
```