# "Everything" 人生操作系统 — 总体架构与实施计划

## 一、项目定位与目标

一个**自托管（self-hosted）、零知识加密（zero-knowledge）、以个人为中心的全生命周期数据系统**，把"关于我的一切"汇聚到自己掌控的服务端，通过网页与安卓端随时访问，并由 AI Agent 助理进行自然语言查询、提醒与主动洞察。

**非目标（明确边界）**：不做多租户社交产品；不做云端开放注册；默认单用户/家庭可信用户。

### 核心质量属性
- **隐私第一**：服务端默认看不到明文（客户端加密、服务端只存密文）；AI 可配置数据授权范围。
- **单二进制部署**：Go 服务端内嵌 Web 前端 + SQLite，一个文件跑起来；同时提供 Docker 多架构镜像。
- **跨平台**：Windows / macOS / Linux（amd64 + arm64）/ Docker / 树莓派类 ARM 设备。
- **数据主权**：加密备份可导出到本地、S3、WebDAV；一键完整迁出。

## 二、技术选型（已与用户确认）

| 层 | 选型 | 理由 |
|---|---|---|
| 服务端 | **Go 1.22+**（标准库 net/http + Chi 路由 + sqlc/SQLBoiler） | 静态单二进制，CGO_ENABLED=0 交叉编译全平台/ARM |
| 数据库 | **SQLite（modernc.org/sqlite 纯 Go 驱动）** + WAL | 免安装、随二进制分发、纯 Go 驱动保证交叉编译；高频轨迹表按月分表 |
| 网页端 | **Vue 3 + TypeScript + Vite + Pinia + Naive UI** | 构建产物由 `go:embed` 内嵌进服务端，部署仍为单文件 |
| 安卓端 | **原生 Kotlin + Jetpack Compose + Room + WorkManager** | 短信/通话记录/后台定位/保活同步只有原生可靠；minSdk 26，目标 SDK 34 |
| 加密 | libsodium（XChaCha20-Poly1305）、Argon2id、X25519 设备密钥协商 | 三端均有成熟绑定（Go、kotlin-jni、web wasm） |
| 同步协议 | HTTPS REST（CRUD/批量）+ SSE（服务端推送） | 比 WebSocket 简单，利于穿透与省电轮询兜底 |
| AI | 统一 **Provider 抽象（OpenAI 兼容协议）**：Ollama（本地）/ 豆包 / DeepSeek / OpenAI 等可切换 | 本地模型隐私不出机；云端能力强 |
| 推送 | WorkManager 周期同步兜底；可选自建 **ntfy/UnifiedPush**；FCM 作为可选通道 | 不依赖 Google 也能用 |
| 分发 | **GoReleaser** + GitHub Actions；Docker **buildx 多架构**（amd64/arm64） | 一条命令产出全平台安装包 |

## 三、仓库结构（Monorepo）

```
everything/
├── server/                    # Go 服务端
│   ├── cmd/eve/main.go        # 入口：初始化配置/DB/路由/嵌入Web
│   ├── internal/
│   │   ├── config/            # 配置文件 + 环境变量 + 启动向导
│   │   ├── auth/              # 注册/登录/设备审批/JWT/TOTP
│   │   ├── crypto/            # 服务端侧封装（密钥保管、解密会话）
│   │   ├── vault/             # 通用加密记录存储（records 信封表）
│   │   ├── modules/           # 各领域模块（薄逻辑，重校验/索引）
│   │   ├── sync/              # 增量同步、冲突解决、SSE 推送
│   │   ├── location/          # 高频轨迹专用存储（批量写入/按月分表）
│   │   ├── agent/             # AI Agent：provider 抽象、工具调用、RAG
│   │   ├── backup/            # 加密导出/定时备份（本地/S3/WebDAV）
│   │   ├── storage/           # 附件/照片块存储（去重 + 密文）
│   │   └── api/               # 中间件、路由、限流、审计日志
│   └── web/embed.go           # go:embed 网页构建产物
├── web/                       # Vue3 网页端
│   ├── src/{views,components,stores,crypto,api,modules}
│   └── vite.config.ts
├── android/                   # Kotlin 安卓
│   └── app/src/main/kotlin/...
│       ├── crypto/  sync/  ui/
│       ├── collector/         # 短信/通话/联系人/定位采集器
│       └── service/           # WorkManager 任务 + 前台定位服务
├── deploy/
│   ├── Dockerfile             # 多架构（scratch/alpine 基础镜像）
│   ├── docker-compose.yml     # 含数据卷 + 可选 Ollama 旁路
│   ├── eve.service            # systemd
│   └── install/               # Windows 服务脚本、macOS launchd
├── docs/                      # 架构文档、API 约定、安卓权限与保活指南
└── .goreleaser.yaml
```

## 四、安全与加密设计（系统成败关键，最先做）

1. **零知识信封**：每条记录为 `{id, module, type, title_cipher?, payload_cipher, version, device_id, 时间戳, 软删除}`；明文仅在客户端（Web/Android）存在。
2. **主密钥体系**：
   - 注册时客户端生成随机 **Master Key（MK）**；由主密码经 Argon2id 派生密钥包裹 MK 后上传。
   - 每台设备生成 X25519 **设备密钥**；新设备登录需**已有设备扫码审批**或使用**恢复密钥（Recovery Key）**解锁 MK。
3. **服务端 AI 解锁会话**：用户主动"解锁资料库"后，MK 仅存在于服务端内存（TTL + 不落盘/不进日志），Agent 才能读明文；锁屏即销毁。使用本地 Ollama 时数据完全不出本机；用云端 Provider 时按模块勾选授权发送范围。
4. **传输与加固**：强制 HTTPS（首次启动引导反代/Caddy 自动证书）、JWT access+refresh、TOTP 二次验证、登录限流、全量审计日志、附件密文块去重存储。
5. **例外字段**：仅 `updated_at/module/deleted` 等同步必需元数据为明文；标题、内容、金额、位置全部加密。轨迹点批量打包为密文块，兼顾隐私与写入性能。

## 五、数据模型策略

- **通用记录模型（vault records）+ 模块 JSON Schema**：新增人生模块 = 新增一份 schema + 前端表单/列表页，服务端基本不改表，支撑"模块无限扩展"。
- **三类专用高频存储**：
  - `locations`：轨迹点批量上报（每 5–15 分钟/位移触发），按月分表，支持地理围栏。
  - `messages`（短信）、`call_logs`、`contacts`：安卓增量游标同步，服务端仅存密文。
  - `attachments`：照片/扫描件/文件，密文块 + 内容哈希去重。
- **冲突解决**：LWW（last-write-wins）按字段 + 删除墓碑；离线优先（Android Room / Web IndexedDB 本地库），联网后增量同步。

## 六、功能模块全景（含补充建议）

用户已明确的模块之外，**补充项用 🆕 标注**，按域组织：

1. **身份核心**：个人基础信息（身份证号/血型/住址/多证件照）、🆕 数字账号台账（邮箱/社交/各平台账号绑定）、🆕 紧急信息 SOS（锁屏可查的紧急联系人/过敏史/医嘱）。
2. **证件与文件**：身份证、护照、港澳通行证、驾照、行驶证、学位/资格证、合同、发票；到期自动提醒；🆕 **证件扫描件/任意文件云盘**。
3. **密码库**：登录项、安全笔记、银行卡模板、TOTP 动态码、密码生成器、泄露检测、🆕 家庭成员密码共享空间。
4. **通讯**：通讯录（含生日/关系标签）、短信、通话记录、🆕 通话录音归档（合规提示）。
5. **位置与时间**：持续轨迹、足迹地图、常用地点（家/公司）、地理围栏提醒；日程/日历/任务/提醒、重复事项。
6. **财务**：账户与资产总览（净资产）、银行卡/信用卡（账单日/还款日/额度提醒）、🆕 **日常记账与收支分类**、🆕 保单管理、🆕 订阅服务/自动扣费清单、🆕 应收/借款、🆕 合同与发票。
7. **物品**：个人物品台账（购买日期/价格/保修/发票/序列号）、二维码标签贴标签扫码管理；🆕 车辆（加油/保养/违章/年检）、🆕 房产租约与物业信息。
8. 🆕 **健康医疗**：病历、体检报告、检验指标曲线、用药与提醒、疫苗、过敏史、就诊记录。
9. 🆕 **人际与家庭**：家庭成员档案、关系图谱、纪念日/生日提醒、人情往来记录。
10. 🆕 **记录与内容**：日记/笔记/灵感、照片时间线、读书笔记、影视记录、收藏夹。
11. 🆕 **旅行**：行程（机票/酒店/签证）、旅行足迹与照片轨迹。
12. 🆕 **成长**：目标（OKR）、习惯打卡、成就/里程碑时间线（"人生年鉴"自动生成）。
13. 🆕 **数字遗产**：继承人授权策略、长期不活跃触发的加密遗嘱包。
14. **AI Agent 助理**（横切所有模块）：
    - 自然语言查询："我上次体检尿酸多少""护照什么时候到期""本月信用卡该还多少"。
    - 工具调用（function calling）：新增记账、建日程、查轨迹、找密码。
    - RAG：对日记/笔记/文件做本地向量检索（embedding 库存服务端密文，解锁后可用）。
    - 主动洞察：还款日/证件到期/订阅扣费/异常支出/纪念日提醒，经 SSE/ntfy 推送。
    - 自动化："到达超市时提醒买牛奶"（地理围栏 + Agent 规则）。

## 七、分阶段实施路线

> 范围很大，按依赖关系分阶段推进；每个阶段都可独立运行、验证、产出可用版本。

- **阶段 0 — 工程骨架**：monorepo 初始化；Go 服务端骨架（配置/日志/优雅退出/SQLite 迁移）；Web 与 Android 空工程；Dockerfile + GoReleaser；CI 跑通跨平台构建。
- **阶段 1 — 核心底座**：注册/登录/设备审批/恢复密钥；零知识加密信封；增量同步 + SSE；Web 与 Android 的本地库/解锁屏；审计日志；系统设置页。
- **阶段 2 — 密码库 + 证件/个人信息**（首批可用业务模块，验证通用 schema 机制）；TOTP；到期提醒。
- **阶段 3 — 安卓采集器**：通讯录/短信/通话记录读取与增量同步（权限向导 + 各国产 ROM 保活指南）；附件上传。
- **阶段 4 — 位置轨迹 + 日程**：前台定位服务 + WorkManager 批量上报；轨迹地图回放页；日历/任务/提醒与推送。
- **阶段 5 — 财务与物品**：账户/银行卡/信用卡、记账、保单、订阅、物品台账与二维码；资产总览看板。
- **阶段 6 — AI Agent**：Provider 抽象（先接 Ollama，再接豆包/DeepSeek）、工具注册表、RAG、解锁会话、主动洞察规则引擎。
- **阶段 7 — 扩展模块**：健康、保险、家庭/纪念日、日记笔记、文件云盘、车辆房产、旅行、目标习惯、SOS、数字遗产（按用户后续优先级排序）。
- **阶段 8 — 分发与运维**：全平台安装包（Win 安装程序/macOS universal binary/ deb+rpm/APK）、多架构镜像、compose 一键部署、加密备份（S3/WebDAV）、数据导入工具（浏览器密码 CSV、vCard 等）、升级与文档。

### 实施进度

- ✅ **阶段 0 — 工程骨架**（2026-09-15 完成）：monorepo、Go/Vue3/Android 三端工程、版本化迁移、Makefile、GoReleaser、多架构 Dockerfile + compose、GitHub Actions CI（四作业）、systemd 单元与配置示例。
- ✅ **阶段 1 — 核心底座**（2026-09-15 全部完成）：注册（first/open/closed 策略）、Argon2id 登录验证器、MK 包裹与登录解锁、JWT 多 scope 令牌、刷新令牌联表吊销、records 加密信封版本化幂等同步、`since` 增量拉取、SSE 事件总线（Web 签名 events 令牌通道）、FR-25 审计 14 类事件；恢复密钥（Crockford Base32，注册强制备份 + 恢复重置轮换）、设备审批（6 位配对码 + X25519 端到端下发 MK，TTL 15 分钟）、TOTP 二次验证（RFC6238，服务端限流 + 恢复码兜底禁用）、Android Room 显式迁移 `Migration(1,2)`。Go e2e 测试通过；四目标交叉编译通过；Android Crockford 单测 9/9 通过。
- ✅ **阶段 2 — 密码库核心**（2026-09-15 完成，Web 端完整 UI）：pass 模块 login/note/card 三类条目 + 密码生成器 + 条目内 RFC6238 TOTP 动态口令 + 客户端搜索；identity 模块证件（id_card/passport/driver_license/generic）与红/橙/黄到期提醒；Android 同步兼容（双模块读取）+ 审批端 + MFA 登录。
  - 阶段 2 未做（按规格留待后续）：Android 密码库完整 UI、附件。
- ✅ **阶段 3 — 安卓采集器**（2026-09-16 完成）：通讯录/短信/通话记录只读采集——ContentProvider 适配层 + 复合游标增量 + 结构比对变化检测（version+1 重封）；Room v3 `collector_state` 显式迁移；唯一周期任务 `eve.collector-sync`（采集+同步合一）；采集页权限向导（运行时逐项申请/永久拒绝引导）、合规告知、五态状态行与国产 ROM 保活指引；module-schemas/android 文档同步。
  - 阶段 3 未做（按规格留待后续）：**附件上传不在本期**（顺延）；位置轨迹属阶段 4 范围。
- ✅ **阶段 4a — 位置轨迹**（2026-09-16 代码落地；全量门禁复跑见 `.trae/specs/stage4-location-tracking/tasks.md` Task 12）：Android 前台定位服务采集（恰六项权限、启动四分支前置检查、静止降频 300s/60s、精度 >100m 过滤、MK 不可用停采、BootReceiver 开机自愈降级）→ Room 明文缓冲封块即删 + 24h 过期 → XChaCha20-Poly1305 密文块上行（块 id `{deviceId}:{startTs}:{endTs}` 双端幂等）；服务端零知识月表 `locations_YYYYMM`（≤256KB/块、≤50 块/批、≤62 天跨度，审计仅计数/范围）；Web 轨迹页（按月装载、日历选日、当日统计与时间线、leaflet 地图、1x/4x/16x/60x 回放、visit 命名写 `place` 记录幂等覆盖）；文档同步（crypto/module-schemas/android/README）。
  - **日程/日历/任务/提醒属阶段 4b**，不在本期；轨迹增强八项（热图/Fog of War、交通方式分类、统计洞察页、GPX/GeoJSON 导入导出、Live 实时模式、地理围栏提醒、Android 端轨迹查看、照片集成）已登记在阶段 4a spec 的 Future Enhancements，本期不承诺。
- ✅ **阶段 4b — 日程/日历**（2026-09-16 文档同步 T11 落地，详见 `.trae/specs/stage4b-calendar/tasks.md` Task 11）：Web + Android 双端事件 CRUD、月/周视图、RRULE B 档子集重复规则、Android 本地精确闹钟（AlarmManager `setExactAndAllowWhileIdle`，权限降级见 README）；事件作为 `module="event"` 记录走既有 records 加密信道（沿用 §5 AAD 与第 1 节信封，**不新造 envelope / AAD 前缀**，服务端零改动）；动态展开 + 链式 AlarmManager 调度，重复事件不物化实例（`expand(rule, window)` 跨端共享纯函数）；Room v4→v5 显式迁移；FU-7 真机冒烟与 instrumented 三套件并入阶段 4a 同款关闭条件清单。文档同步（crypto/android/README/everything_plan）。
  - 阶段 4b 未做（按规格留待后续）：RRULE 全档扩展（当前仅 B 档子集）、编辑器与服务端 AI Agent 联动（建日程工具调用）、Web 端浏览器通知（依赖浏览器 Notification API）。
- ✅ **阶段 5 — 财务 v1**（2026-09-16 代码落地；文档同步 T12 完成，详见 `.trae/specs/stage5-finance/tasks.md`）：Web + Android 双端账户 / 银行卡 / 日常记账三类条目 + 月报预算阈值 + 资产看板聚合；条目作为 `module="finance"` 记录走既有 records 加密信道（沿用 §5 AAD 与第 1 节信封，**不新造 envelope / AAD 前缀**，服务端零改动）；端侧纯函数 `FinanceAggregator`（`netWorth` / `accountBalance` / `cardUsedLimit` / `monthlyReport` / `budgetThreshold`）聚合 / 月报 / 预算阈值三档（`OK` / `WARNING` / `EXCEEDED`），**全部不上行服务端**；Luhn 模 10 卡号校验，完整卡号 → 仅 `last4` 入密文 + Room，**不入任何持久层**（BIN 推断 `brand` + `expiry_month` / `expiry_year` / `holder` 仅入 Room 辅助缓存）；Web 端纯函数镜像（`FinanceScreen` + 4 Tab + 三个 EditorDialog + Pinia store `stores/finance.ts`），Android 端 `FinanceRepository` 双写（明文表 + records 密文表）+ 墓碑语义；Room v5→v6 显式迁移 `MIGRATION_5_6` 四张表（`finance_account` / `finance_card` / `finance_tx` / `finance_reminder_log`）+ 7 个索引；链式 AlarmManager 调度复用（`ReminderScheduler.rebuildChain` 单闹钟 requestCode `0x45564556` "EVEEV"，module 路由 `event` / `finance`）；账单日 T+0 09:00 / 还款日 T-1 09:00 当月+下月双候取最小（`NextCardFiring.kt` 纯函数镜像）；JUnit 4 单测（不依赖 Robolectric）。文档同步（finance/crypto/android/module-schemas/README/everything_plan）。
  - 阶段 5 未做（按规格留待后续 / v2 启用）：`policy`（保单） / `subscription`（订阅） / `loan`（借款） / `contract`（合同）四个子类型占位（`schema_version=1` 钩子保留，本期**仅占位**不下发编辑器与详情页）；`include_in_net_assets` 字段（v1 以 `archived` 兜底）；预算阈值随 records 同步（v1 仅端侧）；Web 端浏览器 Notification API 提醒（v1 仅 Android 本地闹钟）；多币种汇率换算（v1 仅 `currency` 字段留位，不做换算）；卡片 OCR / 自动识别（v2 视需求）。
- ✅ **阶段 5 v2 — 财务扩展**（2026-09-24 文档同步 + 门禁复跑落地，详见 `.trae/specs/stage5-finance-v2/tasks.md` Task 10）：在 v1 三类条目（account / card / tx）基础上扩展 8 类子类型，明文统一 `schema_version=2`，仍走 records 密文通道 + §5 AAD（**不新造 envelope**）；新增 `finance_subscription`（14 字段 / 5 档 billing_cycle / `next_bill_ts` 单闹钟链路由）/ `finance_policy`（14 字段 / 6 档 kind / `policy_number`（≤100 字符完整保单号入密文，`policy_number_encrypted` 默认 `true`，UI 显示末 4 位 + (`insurer`, `name`, `last4`) 联合指纹识别同保单）/ T-30 + T-7 双档提醒）/ `finance_loan`（16 字段 / `direction` 双向 `lend_out` / `lend_in` / `interest_rate_pct` + `repaid_minor` / 客户端纯函数 `principal_minor - repaid_minor` 计算剩余应付 / T-7 + T-1 提醒）/ `finance_contract`（12 字段 / 6 档 kind / `attachment_id` 引用 §9.18 / T-30 + T-7 提醒）；新增 `finance_investment_account`（11 字段 / 6 档 kind：stock / fund / crypto / bond / cash_mgmt / other / `include_in_net_assets` boolean / `last_synced_ts`）+ 持仓表 `finance_investment_holding` 7 列（**不入** records）+ 行情表 `finance_quote` 5 字段（`{symbol}:{as_of_ts}` 确定性派生 id / **仅 Room 缓存不上行**）；新增 0 知识离线汇率表 `finance_rate`（4 字段 + envelope 密文 / `base_currency` / `quote_currency` / `effective_ts` 索引列 / **缺汇率保守放行**：订阅聚合按原币种累加 + 预算拦截不误拦 + 多币种折算跳过折算）；新增附件两层 envelope（`finance_attachment` 6 字段：`name` / `mime` / `size` ≤52428800 字节 ≤50 MiB / `sha256` 客户端验签 hex 64 字符 / `parent_ref_id`）+ 附件块表 `finance_attachment_block` 5 列（256 KiB / 块 + AAD 前缀 `eve:v1:attachment-block:{attachment_id}:{offset}` 专用 envelope）；预算硬约束 B6（`BudgetEnforcer` + `finance_tx.overspend_acknowledged` 审计列 + CST 月度桶聚合）；OCR 语音记账（Android `OcrCaptureSheet` + `VoiceCaptureSheet` + 端侧 ML Kit / 离线 ASR / Web 浏览器 Web Speech API / 图像音频不离开端侧）；Web 端 Service Worker + Notification API（订阅扣费 / 保单到期 / 借款到期三档提醒 + 需 HTTPS + 权限拒绝降级站内横幅）；Room v6→v10 五段显式迁移 `MIGRATION_5_6` / `6_7` / `7_8` / `8_9` / `9_10`（新增表 `CREATE TABLE IF NOT EXISTS` + 新增列 `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...` / **严禁 DROP / DROP COLUMN / 重命名**）；零知识纪律 v2 强化（`policy_number` 入密文（`policy_number_encrypted=true`）+ `rate_minor` 仅 envelope 内 + `attachment_block.cipher` 仅 envelope 内 + 通知文案零渲染金额 / 卡号后四位 / 保单号后四位 / 合同号后四位 / 具体日期数字）；20 AC 验收（AC-V2F-1~20：subscription/policy/loan/contract CRUD + 附件上传下载 + 加密离线汇率包 + 多币种折算 + 投资账户 holdings + 手动行情同步 + OCR/语音 + 预算 CRUD + 超支拦截 + Web Notification API + Service Worker + `include_in_net_assets` + v1 零回归 + schema_version + 零知识 grep + 文档同步）。冒烟 ≥15 场景走 `docs/smoke/stage5-finance-v2-e2e.md`（订阅 T-3 触发 + 保单 T-30/T-7 触发 + 借款双向记账 + 合同附件 sha256 验签失败重传 + 投资账户 CSV 导入 + 手动行情 quote.id 确定性派生 + 汇率缺汇率保守放行 + 附件缺块 UI 标红 + 预算硬约束超支确认弹窗 + OCR 落地准确度 ≥80% + 语音转账金额改写 + Service Worker 注册 + Notification 权限拒绝降级 + Room v9→v10 迁移缺列默认 NULL + v1 零回归冒烟）。文档同步（finance.md §12~§15 / crypto.md §6.7 / android.md v2 模块权限矩阵 + v2 通知文案 + 财务 v2 增量 11 子节 / module-schemas.md §9.10~§9.20 v9→v10 演进 / README 进度行 + v2 功能矩阵 11 行 + 阶段 5 v2 限制 9 条 / everything_plan.md 阶段 5 v2 行）。
- **阶段 5 v3 候选**（按规格依用户优先级排期，本期不承诺）：① 附件分卷（> 50 MiB 大文件支持）走 `finance_attachment_part` 多块索引表 + envelope 链；② 服务端 OpenAPI 公开金融只读聚合（脱敏后净资产 / 月报 / 预算阈值随 records 同步到 Web Dashboard，仅今日 0）；③ 投资账户行情自动同步（接入 yahoo-finance2 / 东方财富等公开行情 API + 端侧缓存策略）；④ 预算阈值随 records 同步（v2 仅端侧，v3 上行 records 字段）；⑤ 多端同步行情表 `finance_quote` 上行 records 密文通道（v3 引入 quote envelope）；⑥ 加密备份端侧进度条 + 中断恢复（阶段 8 范围内，本批不承诺）；⑦ Web 端 RRULE 全档扩展（与阶段 4b 衔接）；⑧ 编辑器 AI Agent 联动（与阶段 6 衔接，建订阅 / 保单 / 借款 / 合同 / 投资账户工具调用）。
- 📝 **阶段 6 — AI Agent**（2026-09-24 调研稿落地，详见 `.trae/specs/stage6-ai-agent/tasks.md` Task 1~12 + `.trae/specs/stage6-ai-agent/spec.md` FR-V6-A~G + AC-V6-1~N；本期**仅调研未动代码**）：服务端 OpenAI 兼容 Agent 抽象层（Provider 接口 / OpenAI 兼容驱动 / Ollama 默认）+ 4 个路由（`POST /api/v1/agent/chat` / `tool-result` / `cancel` + `GET /api/v1/agent/sessions`）+ agent_audit_log 三不存审计（仅 user_msg_bytes / assistant_msg_bytes / tool_args_bytes / tool_result_bytes / tool_name / token 用量 / status / error_code，不存任何明文）+ token bucket 速率限制（云端 RPM 60 / TPM 200K / 并发 3 / 日工具 5000 / Ollama RPM 30 / TPM 80K）+ 服务端**永不见** MK / 主密码 / 用户 API Key（仅内存单次使用）；解锁会话状态机（LOCKED ↔ UNLOCKED / 默认 15 分钟无操作过期 / 多设备隔离 device_id + session_random）；端侧工具注册表 29 个工具（vault 5 + identity 3 + event 5 + finance 5 + trajectory 2 + utility 4 + item 留接口 = 29）；17 个写工具必走客户端二次确认弹窗（5 秒倒计时「同意」置灰）；写工具 result 强制脱敏（`redactForLLM`：policy_number → `***5678` / card_number → `***7890` / password → `已设置` / 邮箱 → `a***@example.com` / 手机 → `138****5678` / MK 字段删除）；系统提示词 + Few-shot 7 范例（纯对话 / 读工具 / 写工具 / 用户拒绝 / 工具失败 / 解锁过期 / 超大上下文）；服务端透明路由器原则（绝不解析 user_msg / assistant_msg / tool_args / tool_result 明文）；Agent 不绕过既有 records 通道（驱动既有 CRUD 的自然语言入口）；Android 端仅留接口（ChatPanel UI 留待阶段 7 / v3）；编辑器 AI 帮填（v3 候选 #8）留待 v3 启动；文档配套 [`docs/ai-agent.md`](../../docs/ai-agent.md) + [`docs/ai-agent-prompts.md`](../../docs/ai-agent-prompts.md) + [`docs/ai-agent-tools.md`](../../docs/ai-agent-tools.md) + [`docs/ai-agent-security.md`](../../docs/ai-agent-security.md)。
  - 阶段 6 待落地（**调研完成 + 任务书就绪 + 代码 pending**）：Task 1 Provider 抽象层 / Task 2 服务端 Agent 路由 + 审计 + 速率限制 / Task 3 Provider 驱动 OpenAI 兼容 + Ollama / Task 4 解锁会话抽象 / Task 5 Web 端 Chat Panel 骨架 / Task 6 二次确认 + 脱敏 / Task 7 读工具最小可对话 / Task 8 写工具全接入 / Task 9 Android 端接口 stub / Task 10 三端门禁复跑 / Task 11 文档同步 / Task 12 异常模式检测告警。
- ⏳ **阶段 7 — 健康 / 物品 / 知识图谱**：健康记录（体重/血压/睡眠/用药）、物品与库存（保质/到期提醒）、生活知识图谱（实体/关系/事件衍生）。
- ⏳ **阶段 8 — 分发与运维**：全平台安装包（Win 安装程序/macOS universal binary/ deb+rpm/APK）、多架构镜像、compose 一键部署、加密备份（S3/WebDAV）、数据导入工具（浏览器密码 CSV、vCard 等）、升级与文档。

## 八、本次批准后首先落地的内容（阶段 0 + 阶段 1 骨架）

1. 建立 monorepo 目录结构与 README/开发约定（docs 内）。
2. `server/`：Go module、配置（yaml+env）、SQLite 初始化与迁移框架、健康检查/`/api/v1` 路由骨架、JWT 中间件、`go:embed` 托管 Web、Makefile（lint/test/build）。
3. `web/`：Vite + Vue3 + TS 工程、登录/解锁页骨架、Naive UI、与 Go embed 联调。
4. `android/`：Gradle + Compose 工程、登录/解锁页骨架、加密与 Room 基础层、WorkManager 同步骨架（采集器留到阶段 3）。
5. `deploy/`：多架构 Dockerfile、compose、`.goreleaser.yaml`（snapshot 交叉编译验证 amd64/arm64）。
6. CI：GitHub Actions 矩阵构建 server/web/android(lint)。
7. 实现注册 → 主密钥包裹 → 单条加密记录读写 → 三端跑通的端到端最小闭环（作为底座验收）。

## 九、依赖与注意事项

- Go 全程保持 **CGO _ENABLED=0**（选 modernc 纯 Go SQLite），否则 Windows/ARM 交叉编译会失败。
- Android 后台定位/读取短信属高危权限：Google Play 上架政策严格，**默认走自建 APK 分发**；需在应用内做用途说明与合规告知；国产 ROM 需引导关闭电池优化。
- 短信/通话/位置等数据涉及个人敏感信息，仅用户自托管使用，提供本地处理与关闭开关。
- 三端加密库选型需先做互操作性验证（同一条密文三端可解），这是阶段 1 的头号技术验证点。
- Web 构建产物嵌入二进制后，版本升级需处理浏览器静态资源缓存（hash 文件名 + index 不缓存）。

## 十、验证方式

- `go test ./...`：auth、vault、sync、agent 工具注册的单元测试；加密信封三端互通向量测试（同一测试向量由 Go/Web/Android 解密）。
- `goreleaser release --snapshot`：验证 windows/linux/darwin × amd64/arm64 全部产出。
- `docker buildx build --platform linux/amd64,linux/arm64`：多架构镜像在 amd64 Mac 与 ARM 设备实测启动。
- Web：`npm run build` 产物被服务端正确托管；登录→解锁→建记录→刷新仍可读。
- Android：模拟器 + 真机（至少一台国产 ROM）完成登录/同步/定位前台服务基本验证。
- 阶段验收：每阶段结束更新本文档勾选状态，并给出可运行版本标签。

## 十一、主要风险与对策

| 风险 | 对策 |
|---|---|
| 范围极大、易烂尾 | 严格按阶段推进，每阶段产出可用版本；扩展模块（阶段 7）按需排期，不阻塞主线 |
| 忘记主密码 = 数据永久丢失 | 注册时强制生成并提示保存恢复密钥；支持已有设备审批新设备；多次解锁提醒备份 |
| 国产安卓杀后台导致轨迹/同步中断 | WorkManager + 前台服务 + 自启动/电池优化引导页；统一同步状态看板让用户感知 |
| AI 云端泄露隐私 | 默认本地 Ollama；云端 Provider 必须逐模块授权、明文不出日志、解锁会话内存态 |
| 单文件 SQLite 在大数据量（轨迹/照片）下性能退化 | 轨迹按月分表 + 批量写；附件走块存储不入库；预留 Postgres 适配层作为远期选项 |
| 单人项目维护成本 | 模块用"schema 驱动"减少重复代码；GoReleaser/CI 自动化发布；网页内嵌减少部署件 |
