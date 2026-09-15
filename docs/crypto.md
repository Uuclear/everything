# 零知识加密信封规范（v1）

三端（Go / Web·libsodium-wrappers / Android·lazysodium）必须**逐字节一致**。
任何一端改动原语或参数，都必须更新本文档并通过互通验证。

## 1. 原语与参数

| 项 | 值 |
|---|---|
| 口令派生 | **Argon2id** |
| timeCost (t) | `3` |
| memoryCost (m) | `65536` KiB = 64 MiB = `67108864` 字节 |
| parallelism (p) | **`1`**（libsodium 的 `crypto_pwhash` 固定单 lane，三端取 1） |
| 输出长度 | 32 字节 |
| salt | 16 字节随机 |
| 内容加密 | **XChaCha20-Poly1305**（IETF 变体，24 字节 nonce） |
| nonce | 每次加密随机生成 24 字节，前置在密文前 |
| 密文布局 | `nonce(24) ‖ ciphertext ‖ poly1305_tag(16)` |
| 编码 | API 传输统一使用 **标准 Base64（带填充，不换行）** |

对应实现：

- Go：`golang.org/x/crypto/argon2` + `chacha20poly1305.NewX`
- Web：`crypto_pwhash(…, ALG_ARGON2ID13)` + `crypto_aead_xchacha20poly1305_ietf_*`
- Android：lazysodium `cryptoPwHash(…, PwhashAlgArgon2id13)` + `cryptoAeadXChaCha20Poly1305Ietf*`（easy API，nonce 自动前置）

## 2. 密钥体系

```
password ──Argon2id(authSalt)──► authVerifier(32B)   # 登录凭证，服务端保存并比对
password ──Argon2id(kekSalt)───► KEK(32B)            # 仅客户端存在
MK = random 32B                                     # 主密钥，仅客户端存在
wrappedMK = AEAD_Seal(KEK, MK, AAD = wrapAAD)       # 上传服务端保存
recordCipher = AEAD_Seal(MK, plaintext, AAD = recordAAD)
```

- `wrapAAD`（ASCII 字节）：`eve:v1:master-key/v1`
- 注册：客户端生成两个 salt 与 MK，上传 `auth_salt / kek_salt / auth_verifier / wrapped_master_key`。
- 登录：`GET /auth/parameters` 取 salt 与 `wrapped_master_key`；本地派生 verifier 完成认证，
  再用 KEK 解开 MK。
- MK **永不落盘**（Web 内存；Android 内存，应用被杀需重新输入主密码）。

## 3. 记录 AAD

每条记录的附加认证数据把密文与记录身份、版本绑定，防止搬运/重放：

```
AAD = "eve:v1:record:" ‖ id(UTF-8) ‖ ":" ‖ module(UTF-8) ‖ ":" ‖ BE_UINT64(version)
```

- `id`：客户端生成的 UUID 字符串。
- `version`：int64 的**大端 8 字节**（不要用十进制文本，避免编码歧义）。
- 例：`id="interop"`, `module="test"`, `version=1`：

```
6576653a76313a7265636f72643a696e7465726f703a746573743a0000000000000001
```

## 4. 固定测试向量

- 口令：`everything`
- salt（Base64）：`AAAAAAAAAAAAAAAAAAAAAA==`（16 个 0x00）

派生出的 32 字节密钥（Base64）：

```
g7Oz96nkqUiRSkd4R7H8xqAUAvqlf8SZAZMd+AgfFk8=
```

用该密钥、上述 record AAD 加密明文 `hello everything` 的一个合法密文样本
（nonce 随机，每次不同，此样本可用于验证 Open 方向）：

```
yjmjvXPL6hFDDlpXyGozIr1fuT6S92aSq1glH5rsFS2qX/OrQTq30kPbRw8e9vlQ9puQ0r5n82A=
```

主密钥包裹（明文为 32 字节 ASCII `0123456789abcdef0123456789abcdef`，AAD=`eve:v1:master-key/v1`）样本：

```
jY/6dGU7spGCphO2O5beVQfcoFAzFVjeSrUgojzMxB1A9A/UQFQitpDEy/D39cI4/NNZkmzmEFvUmGLDYqVC4DEa03E6ixdg
```

Go 侧向量测试：`server/internal/crypto/envelope_test.go` 与
`server/internal/api/e2e_test.go`（完整 注册→包裹 MK→加密写入→新设备登录→解密 闭环）。

## 5. 后续规划（尚未实现）

- **设备审批 / 恢复密钥**：X25519 设备密钥，新设备由已有设备扫码授权；恢复密钥离线保管。
- **服务端 AI 解锁会话**：用户主动解锁后 MK 仅驻留服务端内存（TTL、不进日志），锁屏即销毁。
- 附件：明文分片 → XChaCha 密封 → 内容哈希去重。
- 本地搜索索引（SQLite FTS over plaintext）只存在于客户端。
