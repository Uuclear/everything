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
- ✅ **阶段 1 — 核心底座**（骨架完成）：注册（first/open/closed 策略）、Argon2id 登录验证器、MK 包裹与登录解锁、JWT+刷新令牌、设备表、records 加密信封版本化幂等同步、`since` 增量拉取、SSE 事件总线、审计日志；Web/Android 双端加密笔记闭环。Go e2e 测试通过；四目标交叉编译通过；amd64 镜像容器实测通过。
  - 遗留（阶段 1 收尾）：设备扫码审批与恢复密钥、Web 端 EventSource 签名查询通道、Room 显式迁移、TOTP 二次验证。
- ⏳ 阶段 2–8：待启动。

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
