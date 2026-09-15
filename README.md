# Everything — 你的人生操作系统

把证件、密码、通讯录、短信、位置轨迹、日程、财产、银行卡、物品、健康等**关于你的一切**
汇聚到自己掌控的服务端，网页与安卓端随时访问，并由 AI Agent 助理查询、提醒与主动洞察。

- **自托管 / 零知识**：数据在客户端加密，服务端只存密文；主密码与主密钥永不明文传输。
- **单二进制**：Go 服务端内嵌 SQLite 与网页端，一个文件即可运行；同时提供多架构 Docker 镜像。
- **跨平台**：Windows / macOS / Linux（amd64 + arm64）/ Docker / 树莓派类 ARM 设备。
- **三端**：Go 服务端 · Vue3 网页（内嵌）· 原生 Kotlin 安卓（Compose）。
- **可插拔 AI**：统一 OpenAI 兼容接口，本地 Ollama 或云端模型（豆包/DeepSeek/OpenAI 等）。

> 当前进度：**阶段 0 + 阶段 1 骨架已完成**（认证、零知识信封、增量同步、SSE 骨架、加密笔记闭环）。
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

## 文档

- [架构总览](docs/architecture.md)
- [零知识加密信封规范（三端互通）](docs/crypto.md)
- [HTTP API](docs/api.md)
- [开发约定与发布](docs/development.md)
- [安卓构建说明](docs/android.md)

## 安全提示

- **主密码无法找回**：忘记主密码等于数据永久丢失，后续阶段会提供恢复密钥与设备审批。
- 请在 HTTPS 反向代理后对外暴露（Caddy 可自动申请证书）。
- 短信/通话/定位等安卓高敏权限在后续阶段按需申请，默认全部关闭。
