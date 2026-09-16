# 开发约定与发布

## 仓库结构

```
server/    Go 服务端（module: github.com/everything-personal/eve）
  cmd/eve/             入口
  internal/            config crypto auth vault sync api db
  web/dist/            网页构建产物（go:embed；提交时仅保留 .gitkeep）
web/       Vue3 + TS + Vite + Naive UI（构建输出到 ../server/web/dist）
android/   原生 Kotlin（Compose + Room + WorkManager），Gradle 8.11 / AGP 8.7 / JDK 17
deploy/    Dockerfile（多架构）、compose、systemd、配置示例
docs/      本文档目录
.goreleaser.yaml
```

## 常用命令

```bash
# Go
cd server
go test ./...          # 单元 + 端到端（含三端信封向量）
go run ./cmd/eve       # 本地运行
make cross-check       # 四目标交叉编译

# Web
cd web
npm install
npm run dev            # :5173，代理 /api → :8787
npm run build          # 输出到 server/web/dist

# Android（需 JAVA_HOME=JDK17，GRADLE_USER_HOME 默认即可）
cd android
./gradlew :app:assembleDebug            # 构建 APK
./gradlew :app:testDebugUnitTest        # JVM 单测（含 Crockford Base32）
./gradlew :app:assembleDebugAndroidTest # 编译 instrumented 测试（Room 迁移等，需设备/模拟器运行）

# 一键（先构建 Web 再编译服务端）
make server
```

## 约定

- Go 必须 `CGO_ENABLED=0`（modernc 纯 Go SQLite 是交叉编译的前提）；提交前 `gofmt -s -w .`。
- 时间戳一律 **Unix 毫秒 int64**；ID 一律客户端 UUID 字符串。
- 新增业务模块不改 `records` 表：定义模块 JSON Schema + 客户端表单即可。
- 任何加密原语/参数变更必须同步更新 [crypto.md](crypto.md) 的向量并让三端测试通过。
- 明文数据不得进入日志、错误信息与审计详情；审计只记录事件类型与设备名。
- 安卓敏感权限（短信/通话/定位/通讯录）只在对应阶段声明并运行时申请，默认无权限。

## 发布

- 本地快照：`goreleaser release --snapshot --clean`（会先 `npm ci && npm run build`）。
- 正式：打 tag `vX.Y.Z`，GitHub Actions / GoReleaser 产出：
  - linux/darwin/windows × amd64/arm64 压缩包与 checksum
  - `ghcr.io/everything-personal/eve` 多架构 manifest（amd64+arm64）
  - APK 由 android job 产出 artifact（签名发布在阶段 8 接入 keystore）。

## CI

`.github/workflows/ci.yml` 四个作业：server（test+vet+交叉编译）、web（构建+Go 内嵌验证）、
android（assembleDebug 产出 APK）、docker（buildx 多架构构建）。

## 配置

`dataDir/config.yaml`（缺省自动生成默认值），环境变量优先：

| 配置项 | 环境变量 | 默认 | 说明 |
| --- | --- | --- | --- |
| addr | EVE_ADDR | :8787 | 监听地址 |
| data_dir | EVE_DATA_DIR | ./data | 数据目录（SQLite/配置） |
| registration | EVE_REGISTRATION | first | first=仅首个用户 / open=开放 / closed=关闭 |
| access_token_ttl_minutes | — | 15 | 访问令牌有效期（分钟） |
| refresh_token_ttl_days | — | 90 | 刷新令牌有效期（天） |

## 当前状态与已知限制

- 设备审批（6 位配对码 + X25519 密封下发主密钥）、恢复密钥（Crockford Base32）、
  TOTP 二次验证、Web SSE 签名令牌通道均已落地；详见 [api.md](api.md) 与 [crypto.md](crypto.md)。
- Room 使用显式迁移（`Migration(1,2)` 建 `sync_state`），禁止 `fallbackToDestructiveMigration`。
- 安卓加密层使用 lazysodium Native API（AEAD 9 参 / PwHash 8 参 / Box 6 参，NativeLong 内存参数），
  三端信封逐字节一致，向量见 [crypto.md](crypto.md)。
- Android instrumented 测试（Room 迁移、加密层）需连接设备或模拟器执行
  `connectedDebugAndroidTest`；无设备环境仅能编译验证。
- Argon2id（t=3/m=64MiB）在低端 Android 设备上派生耗时可达数百毫秒至数秒，真机体验待验证。
