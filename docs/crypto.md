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
- Android：lazysodium-android 5.1.0，`cryptoPwHash` + `cryptoAeadXChaCha20Poly1305Ietf*` **Native 字节 API**

> **Android 实现注意（lazysodium-android 5.1.0）**：该版本**没有** XChaCha20 的
> Lazy 字符串重载，不能走 `cryptoAeadXChaCha20Poly1305IetfEncrypt(String, …)` 一类
> 接口。Android 端直接调用 Native 9 参 API：
> `cryptoAeadXChaCha20Poly1305IetfEncrypt(cipher, cipherLen, plain, plainLen, aad, aadLen, nsec, nonce, key)`，
> 其中 `cipherLen` 为 `long[1]` **出参**（实际密文长度），`nsec` 固定传 `null`，
> 并自行 `randomBytesBuf(24)` 生成 nonce 前置拼接，最终布局与 Go/Web 完全一致，
> 仍为 `nonce(24) ‖ ciphertext ‖ tag(16)`。
> Argon2id 走 8 参 Native `cryptoPwHash(pw, pwLen, out, outLen, salt, opsLimit, memLimit, alg)`：
> `opsLimit=3` 为 `long`；内存参数必须包成 JNA **`com.sun.jna.NativeLong(64*1024*1024)`**
> （字节数）；算法常量为 `PwHash.Alg.PWHASH_ALG_ARGON2ID13`。

## 2. 密钥体系

### 2.1 主密码子体系

```
password ──Argon2id(authSalt)──► authVerifier(32B)   # 登录凭证，服务端保存并比对
password ──Argon2id(kekSalt)───► KEK(32B)            # 仅客户端存在
MK = random 32B                                     # 主密钥，仅客户端存在
wrappedMK = AEAD_Seal(KEK, MK, AAD = wrapAAD)       # 上传服务端保存
recordCipher = AEAD_Seal(MK, plaintext, AAD = recordAAD)
```

- `wrapAAD`（ASCII 字节）：`eve:v1:master-key/v1`
- 注册：客户端生成两个 salt 与 MK，上传主密码四材料
  `auth_salt / kek_salt / auth_verifier / wrapped_master_key`，
  同时强制携带第 2.2 节的恢复四材料与 `device_public_key`（见第 4 节）。
- 登录：`GET /auth/parameters` 取 salt 与 `wrapped_master_key`；本地派生 verifier 完成认证，
  再用 KEK 解开 MK。
- MK **永不落盘**（Web 内存；Android 内存，应用被杀需重新输入主密码）。

### 2.2 恢复密钥子体系

恢复码是与主密码**独立的账户级根凭证**（20 字节随机量的 Crockford 人工备份形式，
编码规则见第 3 节）：

```
recoveryKey = random 20B                            # 仅用于生成备份码，不直接参与派生
code = Crockford(recoveryKey)                       # 32 字符，展示为 4 组×8
recoveryVerifier = Argon2id(normalize(code), recovery_auth_salt)   # 16B 盐 → 32B
REK               = Argon2id(normalize(code), recovery_kek_salt)   # 16B 盐 → 32B
wrappedMKRecovery = AEAD_Seal(REK, MK, AAD = recoveryWrapAAD)
```

- Argon2id 参数与第 1 节完全相同（t=3 / m=64MiB / p=1 / 输出 32B / salt 16B）。
  进入派生的"口令"是**归一化后的 32 字符串**（ASCII），不是 20 字节原始随机量。
- `recoveryWrapAAD`（ASCII 字节）：`eve:v1:master-key-recovery/v1`，
  与主密码包裹 AAD 严格域分离——恢复包裹不能用 `eve:v1:master-key/v1` 解开，反之亦然。
- 注册**强制**携带恢复四材料：
  `recovery_auth_salt / recovery_kek_salt / recovery_verifier / wrapped_master_key_recovery`。
  服务端注册校验：两盐各恰好 16 字节、`recovery_verifier` 恰好 32 字节、恢复包裹非空。
- 恢复流程（忘记主密码）全程离线解 MK，服务端不见恢复码明文、也不见 MK：
  1. 客户端本地归一化恢复码，用 `recovery_auth_salt` 派生出 `recovery_verifier` 提交；
     服务端常量时间比对，用户不存在 / 未登记恢复材料 / verifier 不符返回**同一错误**
     （防枚举），通过后签发 recovery 作用域短期令牌；
  2. 服务端回传 `recovery_kek_salt` 与 `wrapped_master_key_recovery`（盐可公开，
     包裹只有恢复码派生出的 REK 能解开）；
  3. 客户端本地派生 REK，`AEAD_Open` 离线还原 MK。
- 恢复成功等价于账户接管，重置在一个事务内完成：重写主密码四材料、**强制轮换新恢复码**
  （旧码立即作废）、吊销全部刷新令牌、作废所有待审批设备与配对，执行恢复的设备凭其
  X25519 公钥直接成为 approved 设备。
- 修改主密码时 MK 不变（仅换包裹与登录材料），可选择一并轮换恢复码材料。

## 3. 恢复码编码（Crockford Base32）

实现：Web `web/src/crypto/crockford.ts`、Android `Crockford.kt`，两端逐字节一致。
服务端不接触恢复码明文——归一化只在端侧发生。

| 项 | 值 |
|---|---|
| 字母表 | `0123456789ABCDEFGHJKMNPQRSTVWXYZ`（**无 I / L / O / U**） |
| 输入 | 20 字节随机量 = 160 bit，恰好编码 **32 字符**（160 / 5 = 32，整除无填充） |
| 分组 | MSB 优先的 5bit 分组，每组索引一个字母表字符 |
| 展示 | 4 组 × 8 字符，连字符分隔（如 `XXXXXXXX-XXXXXXXX-XXXXXXXX-XXXXXXXX`）；连字符仅用于展示 |

归一化规则（`normalizeRecoveryCode` / `Crockford.normalize`，按顺序执行）：

1. `trim` 去首尾空白；
2. 转大写；
3. 删除内部所有空白与连字符 `-`；
4. Crockford 经典纠错映射：`I → 1`、`L → 1`、`O → 0`；
5. 结果必须恰好 32 字符且每个字符都在字母表内，否则报错；
   **`U` 不在字母表内，直接判非法**（归一化串才进入 Argon2id 派生）。

固定向量（三端自测锁定）：

| 输入 | 输出 |
|---|---|
| 20 × `0x00` | `"0"` × 32 = `00000000000000000000000000000000` |
| 20 × `0xFF` | `"Z"` × 32 = `ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ` |
| 完整字母表串 `0123456789ABCDEFGHJKMNPQRSTVWXYZ` | 解码恰为 20 字节，重新编码恒等 |

归一化示例：

```
normalize("6mk2mj04-hkbbffny-p2syakcm-g5pb6gc5")
       = "6MK2MJ04HKBBFFNYP2SYAKCMG5PB6GC5"
```

小写 + 连字符输入 `01234567-89abcdef-ghjkmnpq-rstvwxyz` 归一化后即为完整字母表串
`0123456789ABCDEFGHJKMNPQRSTVWXYZ`，解码再编码往返恒等。

## 4. 设备配对 crypto_box 信道

新设备登录时由一台已批准设备端到端下发 MK。协议为 X25519 + `crypto_box`
（与 Go `golang.org/x/crypto/nacl/box`、libsodium、lazysodium 同协议）。
实现：Web `web/src/crypto/device-box.ts`、Android `CryptoEnvelope.kt` 内 box 系列、
服务端 `server/internal/auth/pairing.go` 只存取与转发密文字节。

### 4.1 设备身份

- 每台设备保存一个 **32 字节 seed**：
  - Web：`localStorage` 键 `eve.deviceSeed`，标准 Base64；
  - Android：EncryptedSharedPreferences 键 `device_seed`，Base64
    （重装 / 清除数据即丢失身份；退出登录保留 seed）。
- 经 `crypto_box_seed_keypair(seed)` **恒定导出** X25519 公私钥（各 32B）：
  同 seed 必得同一密钥对，无需分别持久化。
- 公钥随**注册 / 登录**请求以 `device_public_key`（Base64，注册强制恰好 32B）提交；
  私钥仅存本机。

### 4.2 配对码与指纹（仅人工核对）

| 项 | 公式 | 长度 |
|---|---|---|
| 配对码 pairing_code | `uppercase(hex(SHA-256(devicePublicKey)[:3]))` | 6 个 hex 字符 |
| 指纹 fingerprint | `uppercase(hex(SHA-256(devicePublicKey)[:4]))` | 8 个 hex 字符 |

两者都只供两端人工核对防调包，**本身不构成任何持密凭证**。

### 4.3 密封下发协议

1. 新设备提交公钥进入 `pending`，轮询配对状态；
2. **审批端**每次批准生成全新的一次性临时 X25519 密钥对 `(eph_pk, eph_sk)`
   与随机 **24B nonce**（禁止复用），计算：

   ```
   sealed = crypto_box_easy(MK, nonce, recipient_pk = 新设备公钥, eph_sk)
   ```

   并提交三件 Base64 材料：`ephemeral_public_key`(32B) / `nonce`(24B) /
   `wrapped_master_key`（box 密文 = `ciphertext ‖ Poly1305_tag(16B)`，
   对 32B MK 为 48B；注意 nonce 在此信道为**独立字段**，不像第 1 节 AEAD 信封那样前置）；
3. 服务端只透传：仅校验归属同一用户、配对仍处于 `pending` 且未过期、材料长度
   （32 / 24 / 非空），**无法也不会校验盒内容**；批准后设备置 `approved`；
4. **新设备**用本机私钥开箱：

   ```
   MK = crypto_box_open_easy(sealed, nonce, sender_pk = eph_pk, own_sk)
   ```

   Poly1305 盒认证失败（盒被调包 / 材料不匹配）即拒绝，按配对失败处理。

### 4.4 生命周期与 TTL

- 配对 TTL = **15 分钟**（`PairingTTL`）；pending 过期后惰性置 `expired`，
  同一设备重新发起会把旧 pending 配对置过期。
- 状态：`pending / approved / rejected / expired`（设备另有 `revoked`）。
- 风险边界：Web 端 seed 存 localStorage，浏览器 XSS 可窃取设备身份并冒充该设备参与配对；
  但 MK 仅在内存，页面刷新后仍须主密码（或 TOTP + 已审批设备）重新解锁，
  窃取 seed 不等于获得资料库内容。

## 5. 记录 AAD

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

记录明文 JSON 的 module/type 归属与字段约定见 [module-schemas.md](module-schemas.md)。

## 6. 轨迹块 AAD（阶段 4a）

位置轨迹块的附加认证数据把密文与块身份绑定：

```
AAD = "eve:v1:location-block:" ‖ blockId(UTF-8)
blockId = deviceId ‖ ":" ‖ startTs ‖ ":" ‖ endTs    # 时间均为 UTC 毫秒十进制文本
```

- **无版本号段**：轨迹块不可变、幂等——同设备同一批点重复封块必得同 blockId，
  Android outbox 与服务端月表均按 id `INSERT OR IGNORE` 去重，
  无需 records 的版本递增语义。
- blockId 由 `(device_id, start_ts, end_ts)` 确定性派生（Android `BlockPacker.blockId` /
  Web `locations/core/decode.ts blockId` 同规则），不在块明文 JSON 内冗余存储。
- 加密原语、密钥（MK）与密文布局同第 1 节（XChaCha20-Poly1305，随机 24B nonce 前置）。
- 三端口径：**Go 服务端不涉及**（只存取密文块，从不接触 AAD 与明文）；
  **Android seal**（`CryptoEnvelope.kt` `locationBlockAAD` / `sealLocationBlock`）；
  **Web open**（`web/src/crypto/envelope.ts` `locationBlockAAD` / `openLocationBlock`）。
  两端实现必须**逐字节一致**（AAD 前缀恰为 `eve:v1:location-block:`，UTF-8 编码）。
- 跨端锚点已实证：Web `decode.test.ts` 内联 Android 线上 `sealLocationBlock` 真实产物
  （固定 MK 与 blockId=`dev-fixed-1:1700000000000:1700000060000`），解密后 points
  逐字段断言通过，并附 AAD 错 / 密文篡改 / MK 错三负例（Android seal ↔ Web open 可逆）。

## 6.5 事件 / 重复规则加密链路（阶段 4b）

阶段 4b 日程/日历能力**完全复用** §5 记录 AAD 与第 1 节 XChaCha20-Poly1305 信封，
**不新造 envelope 参数、不新造 AAD 前缀**，以保证"加密信封 + 重复规则 + 闹钟调度"
全链路在 records 通道内自洽。具体约定：

- **挂载点**：事件作为 `module="event"` / `type="event"` 的记录条目写入既有 `records`
  表，与阶段 2 password/note/card、阶段 4a place 共用同一条加密信道。
- **AAD**：沿用 `eve:v1:record:{id}:event:{BE_UINT64(version)}`（即第 5 节通用 AAD，
  `module` 段文本取 `"event"`），不引入新的 AAD 前缀，不破坏第 7 节互通向量。
- **字段归属**：事件明文 JSON 的 12 字段（id / title / start_ts / end_ts / all_day /
  tz_mode / location_text / note / color / reminders / rrule / exdates）与 RRULE B 档
  子集定义见 [module-schemas.md](module-schemas.md) 第 8 章（含 8.1 挂载点、
  8.2 字段定义、8.3 RRULE B 档子集语义约束）。
- **加密原语 / 密钥**：与第 1 节一致——XChaCha20-Poly1305 IETF、随机 24B nonce 前置、
  32B MK、`nonce(24) ‖ ciphertext ‖ tag(16)` 布局；服务端不接触明文，AAD 校验由
  端侧 `openRecord` 完成，材料长度或前缀不符直接拒绝。
- **跨端锚点**：Web `web/src/crypto/envelope.ts` `sealRecord` 与 Android
  `CryptoEnvelope.kt` `sealRecord` 沿用阶段 1/2/4a 同一条封/开路径；
  EventsRepository / eventsStore 不重写 envelope，仅在 records 表上加挂 `module` 维度。
- **范围外**：事件不上传"重复实例"（仅上传规则 + exdates），展开算法
  `expand(rule, window)` 是跨端共享纯函数（见 Web `web/src/events/expand.ts` 与
  Android `Recurrence.kt`），不依赖服务端计算；服务端零改动（阶段 4b 显式声明）。

## 6.6 财务模块加密链路（阶段 5 v1）

阶段 5 财务 v1（账户 / 银行卡 / 日常记账三类条目）**完全复用** §5 记录 AAD 与
第 1 节 XChaCha20-Poly1305 信封，**不新造 envelope 参数、不新造 AAD 前缀**，
沿用 4a place / 4b event 同一套 records 通道。具体约定：

- **挂载点**：财务三类条目作为 `module="finance"` / `type ∈ {"account", "card",
  "tx"}` 的记录条目写入既有 `records` 表（v1 仅下发这三类；`policy` /
  `subscription` / `loan` / `contract` 四类仅占位常量与 `schema_version=1`
  钩子，下发时机由 v2 决定）。
- **AAD**：沿用通用 AAD `eve:v1:record:{id}:finance:{BE_UINT64(version)}`
  （即第 5 节通用 AAD，`module` 段文本取 `"finance"`），与 4a place /
  4b event 字段位置完全相同，**module 文本不同即可**；不引入新前缀，
  不破坏第 7 节互通向量。
- **字段归属**：财务明文 JSON 三类字段定义见
  [module-schemas.md](module-schemas.md) 第 9 章（含 9.1 挂载点、9.2 类型枚举、
  9.3–9.5 account / card / tx 字段表、9.6 调色板、9.7 隐私字段纪律、9.8
  跨端一致性要求）；独立模块文档（分类体系 / 月报 / 资产看板 / Luhn /
  提醒触发 / v2 钩子）见 [finance.md](finance.md)。
- **加密原语 / 密钥**：与第 1 节一致——XChaCha20-Poly1305 IETF、随机 24B
  nonce 前置、32B MK、`nonce(24) ‖ ciphertext ‖ tag(16)` 布局；服务端不接触
  明文，AAD 校验由端侧 `openRecord` 完成，材料长度或前缀不符直接拒绝。
- **decimal-as-string 金额约定**：balance / credit_limit / used_limit / amount
  四类金额字段一律以**字符串**承载（避免 JavaScript Number 与 Kotlin Double
  浮点精度丢失），CNY = 元为最小显示单位；端侧由 cents 整数算术聚合（cents =
  最小单位），输出统一两位小数；服务端不解密故无二次校验。
- **仅后四位入库纪律（卡号）**：完整卡号**不入** schema、不入 Room /
  localStorage / IndexedDB / 服务端；UI 录入完整卡号（13–19 位数字）经
  Luhn 校验通过后仅保留后四位数字字符串入 `card.last4` 字段；
  详见 [finance.md](finance.md) §5 与 [module-schemas.md](module-schemas.md)
  §9.7。校验失败弹错并清空输入框，**不持久化任何位**。
- **跨端锚点**：Web `web/src/crypto/envelope.ts` `sealRecord` 与 Android
  `CryptoEnvelope.kt` `sealRecord` 沿用阶段 1/2/4a/4b 同一条封/开路径；
  FinanceRepository / financeStore 不重写 envelope，仅在 records 表上加挂
  `module="finance"` 维度。
- **范围外**：聚合算法（净资产 / 总资产 / 总负债 / 月报收支 / 预算阈值）
  全部在客户端纯函数（Web `web/src/finance/aggregator.ts` + Android
  [`FinanceAggregator.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt)）
  完成，**不上行**服务端；触发计算（账单日 / 还款日双触发）走客户端
  纯函数（Web `web/src/finance/nextCardFiring.ts` + Android
  [`NextCardFiring.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt)），
  不上传展开点；服务端零改动（阶段 5 显式声明）。

## 7. 固定测试向量

### 7.1 主密码信封

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

### 7.2 恢复信封（锁定互通向量）

- 恢复码：归一化后的 32 字符大写串（无连字符分隔；内容恰为完整 Crockford 字母表）
  `0123456789ABCDEFGHJKMNPQRSTVWXYZ`，派生前不做任何分组/大小写变换
- salt：16 个 `0x44`（Base64 `RERERERERERERERERERERA==`）
- MK：32 个 `0x5A`
- nonce：24 个 `0x66`
- AAD：`eve:v1:master-key-recovery/v1`

REK = Argon2id(恢复码, salt)（t=3 / m=64MiB / p=1 / 输出 32B），再以固定 nonce 做
XChaCha20-Poly1305 IETF 加密。密封布局 `nonce(24) ‖ ciphertext‖tag(16)` 的 Base64
（tag 由 AEAD 附加在密文尾部，与 libsodium/Go 的 Seal 输出为一体，非独立拼接段）：

```
ZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmZmIWDn9l9j4ZLuseuCaVv4fttrfeQEX6yIjtKfYJ+tAQAXXyOjaMvn6A/BQ0hc9idK
```

三端均以该向量做锁定断言——任何一端 Argon2id 参数、AEAD 参数或 AAD 文本漂移都会失败：

- Go：`server/internal/crypto/envelope_test.go` 的 `TestRecoveryInteropVector`；
- Web：`web/src/crypto/selftest.ts` 的 `recoveryEnvelopeSelfTest()`（开发模式启动自测）；
- Android：`android/app/src/androidTest/java/com/everything/eve/crypto/RecoveryEnvelopeVectorTest.kt`
  （instrumented，需设备经 `connectedDebugAndroidTest` 运行；无设备环境仅参与编译）。

另：Go `server/internal/api/e2e_test.go` 覆盖完整 注册→包裹 MK→加密写入→新设备登录→解密
闭环；Crockford 编解码向量由 Web 自测与 Android `CrockfordTest`（JVM）共同锁定。

## 8. 后续规划

**已落地**（原阶段 1 规划项）：

- **设备审批**：每设备 X25519 身份密钥（seed 恒定派生），新设备登录由已有设备在
  15 分钟配对窗口内经 crypto_box 端到端下发 MK，服务端只透传密文盒（见第 4 节）。
- **恢复密钥**：注册强制 20 字节 Crockford 恢复码与独立 Argon2id 验证器 / REK 包裹，
  恢复全程在客户端离线解 MK，恢复即账户接管并轮换新码（见第 2.2、3 节）。

**已落地**（阶段 4a 扩展）：

- **轨迹块 AAD**：阶段 4a 位置轨迹走专用前缀 `eve:v1:location-block:{blockId}`，
  双端 `sealLocationBlock` ↔ `openLocationBlock` 逐字节一致（见第 6 节）。

**已落地**（阶段 4b 扩展）：

- **日程/日历加密链路**：事件作为 `module="event"` 记录沿用 §5 AAD 与第 1 节
  信封原语，**不新造 envelope 参数 / 不新造 AAD 前缀**；服务端零改动，跨端锚点
  `sealRecord` ↔ `openRecord` 与既有密码库/地点记录共用同一条链路（见第 6.5 节）。

**已落地**（阶段 5 扩展）：

- **财务模块加密链路**：账户 / 银行卡 / 流水三类条目作为 `module="finance"`
  记录沿用 §5 AAD 与第 1 节信封原语，**不新造 envelope 参数 / 不新造 AAD 前缀**；
  服务端零改动（无新表 / 无新列 / 无新接口），跨端锚点 `sealRecord` ↔
  `openRecord` 与 1/2/4a/4b 既有链路共用同一条；聚合与触发全部在客户端纯函数
  镜像（Web `web/src/finance/aggregator.ts` + `web/src/finance/nextCardFiring.ts`
  ↔ Android [`FinanceAggregator.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/FinanceAggregator.kt)
  + [`NextCardFiring.kt`](file:///d:/github/everything/everything/android/app/src/main/java/com/everything/eve/finance/NextCardFiring.kt)），
  见第 6.6 节。

**尚未实现**：

- **服务端 AI 解锁会话**：用户主动解锁后 MK 仅驻留服务端内存（TTL、不进日志），锁屏即销毁。
- 附件：明文分片 → XChaCha 密封 → 内容哈希去重。
- 本地搜索索引（SQLite FTS over plaintext）只存在于客户端。
