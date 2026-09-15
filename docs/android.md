# 安卓端说明

## 环境要求

- JDK 17
- Android SDK：compileSdk/targetSdk 35，minSdk 26（Android 8.0）
- Gradle 8.11.1（仓库已带 wrapper，无需本机安装 Gradle）

## 构建

```bash
cd android
./gradlew :app:assembleDebug      # APK：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug       # 连接真机/模拟器后直接安装
```

> 中国大陆网络可在 `~/.gradle/init.gradle.kts` 配置 Maven 镜像（阿里云/腾讯云）。

## 连接服务端

- Android 模拟器访问宿主机：默认 `http://10.0.2.2:8787`（登录页可改）。
- 真机：填电脑局域网 IP，如 `http://192.168.1.10:8787`，确保防火墙放行。
- 当前允许明文 HTTP（`usesCleartextTraffic=true`）仅用于局域网调试；
  正式使用请走 HTTPS 域名并关闭该选项。

## 现有能力（阶段 0/1）

- 注册 / 登录解锁（libsodium 本地派生 Argon2id、XChaCha 信封，与 Go/Web 互通）
- 加密笔记：Room 离线存储 → WorkManager 推送 → 增量拉取
- 令牌存于 EncryptedSharedPreferences；主密钥仅存内存，重启需重新解锁
- 周期同步：每 15 分钟（联网约束 + 指数退避），本地变更立即触发一次

## 后续阶段权限规划（当前均未声明）

| 能力 | 权限 | 阶段 | 保活要点 |
|---|---|---|---|
| 通讯录 | `READ_CONTACTS` | 3 | 一次性增量游标同步 |
| 短信 | `READ_SMS` `RECEIVE_SMS` | 3 | 高危权限，仅自建 APK 分发，用途说明页 |
| 通话记录 | `READ_CALL_LOG` | 3 | 同上 |
| 后台轨迹 | `ACCESS_FINE_LOCATION` `ACCESS_BACKGROUND_LOCATION` | 4 | 前台服务 + 引导关闭电池优化 + 自启动 |
| 通知 | `POST_NOTIFICATIONS` | 4 | Android 13+ 运行时申请 |
| 开机自启 | `RECEIVE_BOOT_COMPLETED`（已声明） | 4 | WorkManager 自动恢复周期任务 |

## 分发策略

短信/通话/后台定位属 Google Play 敏感权限，政策上难以过审，**默认自建 APK 分发**；
后续会提供签名 release APK 的下载通道与升级检查。
