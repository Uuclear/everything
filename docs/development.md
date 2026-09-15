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

# Android
cd android
./gradlew :app:assembleDebug

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

## 当前已知限制（后续阶段处理）

- 设备自动批准；设备审批/恢复密钥未实现（阶段 1 收尾）。
- Web 端用轮询而非 SSE（EventSource 认证通道待定）。
- Room 使用 destructive migration；正式版改显式迁移。
- 安卓加密层基于 lazysodium easy API（nonce 自动前置，布局与规范一致）；首次工程构建时
  若 JNA 方法签名有出入，集中在 `CryptoEnvelope.kt` 单文件修正。
