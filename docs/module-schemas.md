# 记录模块与明文数据格式（v1）

本文档定义加密记录信封内**明文 JSON** 的字段约定。密文信封、AAD、Argon2id 参数见
[crypto.md](crypto.md)。字段定义以 [vault.ts](../web/src/types/vault.ts)
（`web/src/types/vault.ts`）为唯一准绳；本文档如与代码冲突，以代码为准并回改本文档。

## 目录

1. [总则](#1-总则)
2. [pass 模块](#2-pass-模块)
   - [2.1 type=login（登录项）](#21-typelogin登录项)
   - [2.2 type=note（安全笔记）](#22-typenote安全笔记)
   - [2.3 type=card（银行卡）](#23-typecard银行卡)
3. [identity 模块](#3-identity-模块)
4. [历史兼容：module="note"](#4-历史兼容modulenote)
5. [采集模块（Android only）](#5-采集模块android-only)
   - [5.1 溯源字段 source](#51-溯源字段-source)
   - [5.2 contact 模块](#52-contact-模块)
   - [5.3 sms 模块](#53-sms-模块)
   - [5.4 calllog 模块](#54-calllog-模块)
6. [位置轨迹块（Android 采集，服务端零知识月表存储）](#6-位置轨迹块android-采集服务端零知识月表存储)
   - [6.1 块明文 JSON](#61-块明文-json)
   - [6.2 块 id 与分块策略](#62-块-id-与分块策略)
   - [6.3 服务端月表存储](#63-服务端月表存储)
   - [6.4 明文缓冲纪律（Android）](#64-明文缓冲纪律android)
   - [6.5 API 三端点与限额](#65-api-三端点与限额)
   - [6.6 零知识边界](#66-零知识边界)
7. [place 模块（命名地点）](#7-place-模块命名地点)
8. [event 模块（日程/日历，阶段 4b）](#8-event-模块日程日历阶段-4b)
   - [8.1 模块挂载点](#81-模块挂载点)
   - [8.2 字段定义](#82-字段定义)
   - [8.3 RRULE B 档子集](#83-rrule-b-档子集)
   - [8.4 JSON Schema 示例（完整事件）](#84-json-schema-示例完整事件)
   - [8.5 跨端一致性要求](#85-跨端一致性要求)
9. [finance 模块（阶段 5 v1）](#9-finance-模块阶段-5-v1)
   - [9.1 模块挂载点与 AAD 沿用](#91-模块挂载点与-aad-沿用)
   - [9.2 类型枚举与 v2 子类型占位](#92-类型枚举与-v2-子类型占位)
   - [9.3 字段定义：type=account](#93-字段定义typeaccount)
   - [9.4 字段定义：type=card](#94-字段定义typecard)
   - [9.5 字段定义：type=tx](#95-字段定义typetx)
   - [9.6 调色板（color 枚举，扩展自 4a/4b 既有）](#96-调色板color-枚举扩展自-4a4b-既有)
   - [9.7 隐私字段标记与卡号后四位截取纪律](#97-隐私字段标记与卡号后四位截取纪律)
   - [9.8 跨端一致性要求](#98-跨端一致性要求)
   - [9.9 Android Room v6 schema 与 §9.3–9.5 字段映射](#99-android-room-v6-schema-与-93-95-字段映射)
10. [Android 本期 UI 支持矩阵](#10-android-本期-ui-支持矩阵)

## 1. 总则

### 1.1 module / type 归属

每条记录由 `(module, type)` 二元组归类，映射由 `moduleTypeFor` / `kindOf`
（`web/src/types/vault.ts`）统一维护：

| module | type | UI 归类（RecordKind） |
|---|---|---|
| `pass` | `login` | login |
| `pass` | `note` | note |
| `pass` | `card` | card |
| `identity` | `id_card` / `passport` / `driver_license` / `generic` | identity（记录 `type` 即证件子类型） |
| `note`（历史） | `secure_note`（历史） | note（只读兼容，见第 4 节） |

未知的 module/type：Web 端保留在服务端、不在 UI 暴露（天然前向兼容）；
Android 端原样同步入库、不在 UI 暴露。

### 1.2 加密与存储边界

- 明文 JSON **只存在于端侧**（Web 内存 Pinia store，页面刷新即清空；Android 按需解密），
  服务端只能看到 Base64 密文信封。
- 写入时 `plaintext = UTF-8(JSON.stringify(data))`，再以 MK 做
  `AEAD_Seal`，AAD 绑定 `id / module / BE_UINT64(version)`（见 crypto.md 第 5 节）。
- 服务端记录校验（`server/internal/api/records_handler.go`）只检查：
  `id` 与 `module` 非空；非墓碑记录 `ciphertext` 非空；单批 1–1000 条。
  **服务端不校验 `type`，也不接触任何明文字段。**

### 1.3 版本、时间戳与墓碑

- `version` 为 int64，**严格递增**：新建为 1，每次编辑 / 删除在上一版基础上 +1。
  服务端仅当传入版本**严格大于**库中版本时覆盖，否则该条计入 `skipped`；
  客户端遇到竞争以服务端为准补拉全量。
- `created_at` / `updated_at` 为 Unix 毫秒；服务端在缺省时以当前时间兜底。
- 删除即**墓碑**：`deleted=true`、`ciphertext` 置空字符串、版本号继续 +1；
  其他设备拉到墓碑后从本地移除。
- AAD 绑定记录自身的 `module`，因此同一 `id` 的 module 实际不可变更
  （换 module 会使旧密文无法以新 AAD 解开）；编辑证件时子类型靠明文 `kind` 字段延续。

## 2. pass 模块

密码库模块。TypeScript 判别联合：`LoginData | NoteData | CardData`。

### 2.1 type=login（登录项）

```json
{
  "title": "示例站点",
  "username": "alice",
  "password": "s3cret",
  "urls": ["https://example.com/login"],
  "notes": "备用邮箱在抽屉里",
  "totp": {
    "secret": "JBSWY3DPEHPK3PXP",
    "issuer": "Example",
    "digits": 6,
    "period": 30
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 标题 |
| `username` | string | 否 | 用户名 / 账号 |
| `password` | string | 否 | 密码（仅存在于密文内） |
| `urls` | string[] | 否 | 关联 URL 列表 |
| `notes` | string | 否 | 备注 |
| `totp` | object | 否 | 内嵌 TOTP 配置，见下 |

`totp`（`TotpConfig`，RFC 6238，与认证器 otpauth 约定对齐，HMAC-SHA1）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `secret` | string | 是 | **RFC 4648 Base32**（`A-Z2-7`，忽略大小写 / 空格 / 填充） |
| `issuer` | string | 否 | 签发方展示名 |
| `digits` | number | 否 | 位数，默认 `6`（实现支持 6 或 8） |
| `period` | number | 否 | 周期秒数，默认 `30` |

### 2.2 type=note（安全笔记）

```json
{ "title": "保险箱密码", "body": "左 3 圈……" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 标题 |
| `body` | string | 否 | 正文 |

阶段 2 起新写入的笔记一律使用 `module="pass" / type="note"`（含 Android 端）。

### 2.3 type=card（银行卡）

```json
{
  "title": "招商银行卡",
  "cardholder": "ZHANG SAN",
  "number": "6225880000000000",
  "exp_month": 9,
  "exp_year": 2030,
  "cvv": "123",
  "notes": "工资卡"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 标题 |
| `cardholder` | string | 否 | 持卡人 |
| `number` | string | 否 | 完整卡号，**仅存在于密文内**；列表只展示脱敏 / 尾号（内存搜索额外支持尾 4 位命中） |
| `exp_month` | number | 否 | 到期月，整数 `1–12` |
| `exp_year` | number | 否 | 到期年，**四位**整数（如 `2030`） |
| `cvv` | string | 否 | CVV / CVC（字符串，保留前导零） |
| `notes` | string | 否 | 备注 |

## 3. identity 模块

证件模块。四种 `type`：`id_card`（身份证）、`passport`（护照）、
`driver_license`（驾驶证）、`generic`（通用证件）。
四种 type 共用**同一份**明文结构 `IdentityData`，并以 `kind` 字段重复携带自身 type：

```json
{
  "title": "护照",
  "kind": "passport",
  "name": "张三",
  "number": "E12345678",
  "issuer": "中华人民共和国出入境管理局",
  "issued_on": "2020-01-15",
  "expires_on": "2030-01-14",
  "notes": "旧护照已剪角"
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 | 标题 |
| `kind` | string | 是 | 固定等于记录 `type`，取值 `id_card` / `passport` / `driver_license` / `generic` |
| `name` | string | 否 | 持证人姓名 |
| `number` | string | 否 | 证件号码 |
| `issuer` | string | 否 | 签发机构 |
| `issued_on` | string | 否 | 签发日期，`"YYYY-MM-DD"` |
| `expires_on` | string | 否 | 到期日期，`"YYYY-MM-DD"`（到期提醒据此计算） |
| `notes` | string | 否 | 备注 |

### 3.1 到期提醒分级

`expiryDays` 以本地日历天做差（消除时分秒与时区偏移），`expiryLevel`
（`web/src/stores/vault.ts`）分级如下：

| 条件（距到期日天数） | 级别 | 颜色 |
|---|---|---|
| `days < 0`（已过期） | `expired` | 红 |
| `0 ≤ days ≤ 30`（含到期当天） | `soon` | 橙 |
| `31 ≤ days ≤ 90` | `upcoming` | 黄 |
| `days > 90` 或无 `expires_on` | `null`（不提醒） | — |

证件列表的"即将到期"视图取全部有 `expires_on` 且 `days ≤ 90`（含已过期）的证件，
按到期日升序排列。

## 4. 历史兼容：module="note"

阶段 1 的加密笔记以 `module="note"`、`type="secure_note"` 写入，明文同样为
`{ "title": …, "body": … }`：

- **读取**：Web 端 `kindOf("note", 任何 type)` 一律归类为 `note`，按笔记展示；
  Android 端笔记列表查询同时包含 `module='note'` 与 `module='pass' AND type='note'`，
  解密时 AAD 使用记录自身的 `module='note'`，无需迁移密文。
- **写入**：阶段 2 起三端一律新写 `module="pass" / type="note"`，
  **不再产生 `module="note"` 的新记录**（历史数据保留原样、就地可读）。

## 5. 采集模块（Android only）

阶段 3 新增的三类记录，由 Android 端从系统 ContentProvider 只读采集、加密后写入。
字段定义以 Android `collector/core/Models.kt` 为唯一准绳（地位等同 Web 的 vault.ts）。

**边界约定**：

- **仅 Android 采集写入**；Web 端不展示（未知 module 天然不在 UI 暴露）；
  服务端零知识原样存储（仅校验 `id`/`module` 非空，不接触明文）。
- **记录 id 规则**：`{deviceId}:{kind}:{systemId}`，`kind` 为类别短名
  （`contact`/`sms`/`calllog`），`systemId` 为系统行 `_id`；同设备内全局稳定。
- **变更语义**：系统行内容变化 → `version+1` 重封（结构比对 Canonical JSON）；
  系统侧删除**不跟随**（已采集副本保留，UI 已明示）；端内不提供编辑。
- 增量游标为 `(lastTimestamp, lastSystemId)` 复合游标，存于端侧
  `collector_state` 表（仅存游标/计数/枚举化跳过原因，不含明文）。

### 5.1 溯源字段 source

三类明文均内嵌 `source` 对象（`CollectorSource`），用于回溯系统行：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `system_id` | number(int64) | 是 | 系统 ContentProvider 行 `_id`（参与记录 id 拼接） |
| `lookup_key` | string | 否 | 联系人聚合查找键（仅 contact；行 id 变化时仍可关联同一逻辑联系人） |
| `last_updated` | number(int64) | 是 | 系统侧该行最后变更时间（Unix 毫秒）；短信/通话为行内 `date`（发生时间） |

### 5.2 contact 模块

`module="contact", type="contact"`：

```json
{
  "display_name": "张三",
  "name": { "family": "张", "given": "三", "middle": null, "prefix": null, "suffix": null },
  "organization": "示例公司",
  "job_title": "工程师",
  "phones": [{ "number": "13800000000", "type": "mobile", "label": null, "is_primary": true }],
  "emails": [{ "email": "a@b.c", "type": "home", "label": null, "is_primary": false }],
  "addresses": [{ "formatted": "北京市……", "type": "home", "label": null, "is_primary": false }],
  "birthday": "1990-01-01",
  "notes": "备注",
  "source": { "system_id": 42, "lookup_key": "…", "last_updated": 1758000000000 }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `display_name` | string | 是 | 系统聚合显示名（极端异常源行可能为空串） |
| `name` | object | 否 | 姓名部件 `family/given/middle/prefix/suffix`，全部可空 |
| `organization` | string | 否 | 公司/组织 |
| `job_title` | string | 否 | 职位 |
| `phones` | object[] | 否 | 电话条目，见下 |
| `emails` | object[] | 否 | 邮箱条目（`email` 必填，其余同电话条目） |
| `addresses` | object[] | 否 | 地址条目（`formatted` 为系统拼好的单行/多行文本，必填，其余同电话条目） |
| `birthday` | string | 否 | 仅取系统 `Event.TYPE_BIRTHDAY`，原样保留系统格式（`yyyy-MM-dd` 或 `--MM-dd`） |
| `notes` | string | 否 | 备注 |
| `source` | object | 是 | 溯源字段，见 5.1 |

电话/邮箱/地址条目共有字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `number` / `email` / `formatted` | string | 是 | 条目值 |
| `type` | string | 是 | 归一化标签：`home/work/mobile/fax/pager/other/main/custom/unknown`（系统 TYPE_* 整数码映射，未知码兜底 `unknown`） |
| `label` | string | 否 | 系统自定义标签原文（仅 `type="custom"` 时有值） |
| `is_primary` | boolean | 是 | 是否主条目（系统 `Data.IS_PRIMARY`） |

### 5.3 sms 模块

`module="sms", type="sms"`：

```json
{
  "address": "106900000000",
  "body": "验证码 123456",
  "date": 1758000000000,
  "type": "inbox",
  "read": true,
  "thread_id": 7,
  "source": { "system_id": 1001, "lookup_key": null, "last_updated": 1758000000000 }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `address` | string | 否 | 对方号码/会话地址（匿名通知类短信可能为空） |
| `body` | string | 是 | 正文（系统 NULL 归一化为空串） |
| `date` | number(int64) | 是 | 系统时间戳（Unix 毫秒） |
| `type` | string | 是 | 归一化方向/文件夹：`inbox/sent/draft/outbox/failed/queued/unknown` |
| `read` | boolean | 是 | 已读标记 |
| `thread_id` | number(int64) | 否 | 会话 id |
| `source` | object | 是 | 溯源字段，见 5.1 |

### 5.4 calllog 模块

`module="calllog", type="call"`：

```json
{
  "number": "13800000000",
  "name": "张三",
  "date": 1758000000000,
  "duration": 65,
  "type": "outgoing",
  "source": { "system_id": 55, "lookup_key": null, "last_updated": 1758000000000 }
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `number` | string | 否 | 拨号号码（受限/未知来电可能为空） |
| `name` | string | 否 | 系统缓存的匹配联系人姓名（原始快照） |
| `date` | number(int64) | 是 | 通话发生时间（Unix 毫秒） |
| `duration` | number(int64) | 是 | 通话时长（秒） |
| `type` | string | 是 | 归一化类型：`incoming/outgoing/missed/rejected/blocked/voicemail/unknown` |
| `source` | object | 是 | 溯源字段，见 5.1 |

## 6. 位置轨迹块（Android 采集，服务端零知识月表存储）

阶段 4a 新增。轨迹块**不走通用 records 信封通道**：明文点由 Android 前台定位服务采集，
在本机 Room 短暂缓冲后打包成块、以 MK 做 XChaCha20-Poly1305 加密封存
（AAD 规则见 [crypto.md](crypto.md) 第 6 节），经 `/api/v1/locations/batch` 批量上行；
服务端只存密文块于 `locations_YYYYMM` 月表。块明文 JSON 字段定义以 Android
`collector/location/core/TrackPoint.kt`（moshi DTO）为唯一准绳，
Web `locations/core/types.ts` 与之逐字段一致（三方契约，任何改动必须三方同步）。

### 6.1 块明文 JSON

```json
{
  "device_id": "dev-xxx",
  "start_ts": 1758000000000,
  "end_ts": 1758000060000,
  "points": [
    {
      "ts": 1758000000000, "lat": 39.9042, "lon": 116.4074, "acc": 12.5,
      "speed": 1.2, "bearing": 90.0, "altitude": 52.0, "provider": "gps"
    }
  ]
}
```

块头字段（`LocationBlockJson`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `device_id` | string | 是 | 采集设备 id（服务端配对设备 id；多设备块经该前缀天然隔离） |
| `start_ts` | number(int64) | 是 | 块首点时刻（UTC 毫秒）；服务端月表归属按该值 UTC 月份 |
| `end_ts` | number(int64) | 是 | 块末点时刻（UTC 毫秒） |
| `points` | object[] | 是 | 块内轨迹点（ts 升序，≤100） |

轨迹点（`points[]` 元素，`TrackPoint`）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `ts` | number(int64) | 是 | 采样时刻（UTC 毫秒，取设备系统时间） |
| `lat` | number(double) | 是 | 纬度（WGS84 度） |
| `lon` | number(double) | 是 | 经度（WGS84 度） |
| `acc` | number(float) | 是 | 定位精度半径（米）；Android 入库前已过滤（>100m 丢弃） |
| `speed` | number(float) | 否 | 瞬时速度（米/秒），系统未提供时为 null / 缺省 |
| `bearing` | number(float) | 否 | 航向角（度，正北为 0 顺时针），系统未提供时为 null / 缺省 |
| `altitude` | number(double) | 否 | 海拔（米），系统未提供时为 null / 缺省 |
| `provider` | string | 否 | 定位来源（`"gps"` / `"network"` 等 provider 名），未知为 null / 缺省 |

块 id **不在 JSON 内冗余存储**，由 `(device_id, start_ts, end_ts)` 确定性派生
（Android `BlockPacker.blockId` / Web `decode.ts blockId` 同规则）。

### 6.2 块 id 与分块策略

- **块 id 规则**：`{deviceId}:{startTs}:{endTs}`（时间均为 UTC 毫秒十进制文本）。
  确定性派生是幂等链路锚点：同设备同一批点重复封块必得同 id，
  Android outbox 与服务端月表均按 id `INSERT OR IGNORE` 去重。
- **分块策略**（`BlockPacker.pack`，常量集中于 `LocationParams`）：按 ts 升序遍历
  （乱序输入先排序）；块内点数达 **100** 即结算封块；当前点 ts 距块首点 ts
  **≥ 1 小时**即结算（末点−首点 = 3_599_999 同块、= 3_600_000 开新块）；
  空输入零块，单点自成一块。

### 6.3 服务端月表存储

- 表名：`locations_YYYYMM`（YYYYMM 为块 `start_ts` 的 **UTC** 月份），由写入路径
  `CREATE TABLE IF NOT EXISTS` 动态建表（迁移 0004 仅登记约定，不预建表）。
- 列（均 NOT NULL）：`id TEXT PRIMARY KEY`、`user_id`、`device_id`、`start_ts`、
  `end_ts`、`point_count`、`cipher BLOB`、`created_at`；索引 `(user_id, start_ts)`。
  `created_at` 由服务端权威时间覆盖，客户端值忽略。
- 服务端可见的明文元数据仅限：块 id、设备 id、起止时间、点数、写入时间——
  无任何坐标信息。

### 6.4 明文缓冲纪律（Android）

- 明文坐标唯一驻留点是 Room `location_points` 表（`location_outbox` 只存密文块）。
- **封块即删**：块加密封存成功后，按块覆盖的最大 ts 上界批量删除对应明文行。
- **24h 过期**：超过 24 小时未封块的点强制删除（`POINT_EXPIRY_MS`）。
- 坐标明文禁入日志与异常消息，全链路结果模型只含计数与枚举原因。

### 6.5 API 三端点与限额

三端点均在 approved 分组（访问令牌 + approved scope），`user_id` / `device_id`
以 token claims 覆盖（不信任客户端自声明）：

| 端点 | 说明 | 限额与校验 |
|---|---|---|
| `POST /api/v1/locations/batch` | 批量上行密文块，body `{blocks:[{id,start_ts,end_ts,point_count,cipher(base64)}]}`，返回 `{applied,skipped}` | 块数 1–50；单块密文解码后 ≤256KB 且非空；`0 < start_ts ≤ end_ts`；`point_count > 0`；请求体 ≤20MB；同 id 幂等（`INSERT OR IGNORE`，重复计 skipped）；审计 `location.upload`（仅计数）；广播 `locations_changed` |
| `GET /api/v1/locations?from&to` | 按范围拉取该用户密文块（start_ts 升序，cipher 为 base64），返回 `{blocks:[...]}` | from/to 为 UTC 毫秒整数；`from > 0` 且 `to ≥ from`；跨度 ≤62 天；审计 `location.download`（仅范围与块数） |
| `DELETE /api/v1/locations?from&to` | 范围删除该用户轨迹块，返回 `{deleted}` | 参数同 GET；审计 `location.delete`（仅范围与删除数） |

三类审计事件 detail 只含计数 / 范围，无任何坐标、块 id 或密文字段。

### 6.6 零知识边界

- **服务端永不接触明文**：只透传与存储密文块，不解密、不做空间分析。
- **Web 解密后分析**：停留点检测（驻留 ≥10 分钟且半径 ≤100m）、行程段拼接、
  本地日时间线与统计全部在浏览器内解密后内存完成；明文坐标不持久化
  （不写 localStorage / IndexedDB、不进日志），锁定 / 退出登录即清空。

## 7. place 模块（命名地点）

阶段 4a 新增。Web 轨迹页对停留点命名（"家 / 公司 / 自定义"）产生的通用加密记录，
走既有 records 信封通道（AAD 含版本号，见 [crypto.md](crypto.md) 第 5 节）多设备同步。

- `module="place", type="place"`；不归 `RecordKind` 体系（`kindOf` / `moduleTypeFor`
  不含 place）——Web 端经 vault store 独立 `placeRecords` 缓存分流，不混入
  pass / identity 列表；Android 端原样同步入库、不在 UI 暴露。
- **记录 id 规则**：`place:{geohash7}`——geohash7 为命名点中心坐标的 7 位 base32
  geohash（`web/src/locations/core/geohash.ts`）。
- **幂等覆盖语义**：同 id 重复命名时 version 递增覆盖（records 版本通道天然保证），
  不重复建记录。

明文 payload（`PlaceData`，`web/src/types/vault.ts`）：

```json
{ "name": "家", "category": "home", "center_lat": 39.9042, "center_lon": 116.4074, "radius_m": 100 }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | 地点名称（"家" / "公司" / 自定义文本） |
| `category` | string | 否 | 分类（`home` / `work` / `custom` 等） |
| `center_lat` | number(double) | 是 | 中心纬度（WGS84 度） |
| `center_lon` | number(double) | 是 | 中心经度（WGS84 度） |
| `radius_m` | number | 是 | 覆盖半径（米，约定 100） |

> 写入路径（轨迹页"标记地点"对话框 → `vault.savePlace`）与读取侧
> （place 记录缓存与按 id 查名索引）均已随 Web 轨迹页落地。

## 9. finance 模块（阶段 5 v1）

阶段 5 新增的财务管理模块。账户 / 银行卡 / 日常记账三类条目作为加密个人库中
的一条记录，走既有 records 通道同步（与第 7 章 `place` 模块、第 8 章 `event`
模块同款链路），复用 `CryptoEnvelope` 的 XChaCha20-Poly1305 与 AAD 规则，
**不新造**任何加密原语或服务端接口。客户端聚合净资产 / 总资产 / 总负债 /
分类饼图 / 趋势点 / 月报预算阈值，**不上行服务端**。

本章节为人类可读字段定义，机器可读 JSON Schema 见
[`docs/schemas/finance.schema.json`](schemas/finance.schema.json)（Draft 2020-12）。
独立模块文档（分类体系 / 月报 / 资产看板 / Luhn / 提醒触发 / v2 钩子）见
[`docs/finance.md`](finance.md)，与本节交叉引用。

### 9.1 模块挂载点与 AAD 沿用

finance 作为 records 表一条密文记录写入，明文载荷符合本节定义：

- **`module = "finance"`**、`type ∈ { "account", "card", "tx", "policy",
  "subscription", "loan", "contract", "budget" }`（与第 7 章 `place`、第 8 章
  `event` 同款 `module`/`type` 双键约定；前者用于 records 投递索引，后者随
  明文写入密文内供端侧识别；`budget` 为 B6 新增子标识，不建独立 Room 表）。
- **AAD 沿用 `eve:v1:record:{id}:{module}:{BE(uint64 version)}`**，与既有
  records 记录**逐字节一致**（module=`"finance"`），**不新造** envelope 参数；
  详见 [crypto.md](crypto.md) §5.1。
- 服务端在 records 投递（`server/internal/api/records_handler.go`）阶段
  仅校验 `id`/`module` 非空与密文非空，**不**校验 `type`、不解密、不
  解析财务字段、不缓存明文金额/账户名/卡号后四位。客户端遇到不识别的
  `type` 仍按密文原样入库（保持前向兼容）。
- 服务端可见的明文元数据仅限 records 表已有列（`id`/`module`/`type`
  因投递校验入索引，但其余财务字段均处于密文中），不引入新表、新列、
  新接口。

### 9.2 类型枚举与 v2 子类型占位

| type | v1 启用 | v2 启用 | 字段表 |
|---|---|---|---|
| `account`（账户） | ✅ | ✅ | 9.3（11 字段） |
| `card`（银行卡 / 信用卡） | ✅ | ✅ | 9.4（17 字段） |
| `tx`（日常记账） | ✅ | ✅ | 9.5（13 业务字段 + B6 审计列 `overspend_acknowledged`） |
| `budget`（预算，B6） | ⏳ v1 无 | ✅ | 9.5.2（13 字段，`schema_version=2`；records 通道，无独立 Room 表） |
| `policy`（保单） | ⏳ 占位 | ✅ | v2 启用时按 9.3 + 保单专属字段扩展 |
| `subscription`（订阅） | ⏳ 占位 | ✅ | v2 启用时按 9.4 + 订阅专属字段扩展 |
| `loan`（应收 / 借款） | ⏳ 占位 | ✅ | v2 启用时按 9.3 + loan 专属字段扩展 |
| `contract`（合同 / 发票） | ⏳ 占位 | ✅ | v2 启用时按 9.3 + 合同专属字段扩展 |

`v1` 仅下发前三类（`account` / `card` / `tx`）；`budget` 已随 B6 在 v2
启用（见 §9.5.2，预算记录走 records 密文通道、不建独立 Room 表）；
`policy` / `subscription` / `loan` / `contract` 在 v2 启用，本期**仅占位**
——类型常量与 `schema_version=1` 钩子保留，不下发编辑器与详情页。

### 9.3 字段定义：type=account

账户（type=account，11 字段）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | UUID 字符串 | 是 | 客户端生成（UUID v4）；同时作为 records 主键与服务端投递主键。 |
| `name` | string | 是 | 账户名称；UTF-8；1–40 字符；前端表单校验非空。 |
| `kind` | enum | 是 | 账户类型：`cash` / `deposit` / `stock` / `wallet` / `other`（5 项；本期 v1 枚举固定 5 项）。 |
| `currency` | string | 是 | ISO 4217 三字母货币代码；默认 `"CNY"`；本期 v1 不做汇率换算。 |
| `balance` | string | 是 | **decimal-as-string**（避免浮点精度丢失），如 `"123.45"`；CNY = 元为最小显示单位；非负（支持透支的卡走 `type=card` 而非 account）。 |
| `note` | string \| null | 否 | 纯文本备注；0–200 字符。 |
| `icon` | string \| null | 否 | lucide-icon 名称（如 `"wallet"` / `"landmark"` 等）；选填，未填走默认图标。 |
| `color` | enum | 否 | 调色板 key（见 9.6）；未填走默认色。 |
| `archived` | boolean | 是 | 归档标志（软删除）；默认 `false`；归档后不计入资产看板、不参与聚合。 |
| `created_at` | integer (int64) | 是 | 创建时刻，Unix 毫秒。 |
| `updated_at` | integer (int64) | 是 | 最后更新时刻，Unix 毫秒；编辑即刷新。 |

#### 字段口径补充

- 所有时间戳字段（`created_at` / `updated_at`）一律 Unix 毫秒 `int64`，
  与既有 4a / 4b 字段口径一致。
- `balance` 用 decimal-as-string 是为了**避免 JavaScript Number 浮点精度丢失**
  与 Kotlin `BigDecimal` 互转歧义；前端表单提交前必须做正则校验
  （`/^-?\d+(\.\d+)?$/`）；服务端不解密故无二次校验。
- `archived=true` 时 `include_in_net_assets` 自动视为 `false`（看板过滤口径，
  与 4b `archived` 行为一致）。

### 9.4 字段定义：type=card

银行卡 / 信用卡（type=card，17 字段）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | UUID 字符串 | 是 | 客户端生成（UUID v4）；records 主键 + 服务端投递主键。 |
| `name` | string | 是 | 卡名（如"招行信用卡"）；UTF-8；1–40 字符。 |
| `kind` | enum | 是 | 卡类型：`debit` / `credit`（2 项，本期 v1 枚举固定 2 项；预留 `prepaid` 扩展位由 v2 启用）。 |
| `issuer` | string | 是 | 银行 / 发卡机构名（如"招商银行"）；1–40 字符。 |
| `last4` | string | 是 | 卡号后四位数字字符串（**仅后四位入 schema，完整卡号不入**），4 字符 `0-9`；前端录入完整卡号（16-19 位）→ Luhn 校验后**仅保留后四位**。 |
| `currency` | string | 是 | ISO 4217 三字母货币代码；默认 `"CNY"`。 |
| `credit_limit` | string | 条件必填 | **decimal-as-string**；信用额度；信用卡必填（值非 `null`），借记卡选填（`null` 或 `"0"`）。 |
| `used_limit` | string | 否 | **decimal-as-string**；已用额度；非负；`null` / `"0"` 时按"未用"标注。 |
| `billing_day` | integer (int) | 否 | 账单日（每月 day_of_month），`1-31`；超出当月最大日按月底处理（如 31 在 2 月按 28/29）。 |
| `due_day` | integer (int) | 否 | 还款日距账单日天数 offset，`1-31`（offset 模式：`statement_day + due_day_offset` 跨月滚动）；`null` 表示无还款日配置。 |
| `note` | string \| null | 否 | 纯文本备注；0–200 字符。 |
| `icon` | string \| null | 否 | lucide-icon 名称。 |
| `color` | enum | 否 | 调色板 key（见 9.6）。 |
| `archived` | boolean | 是 | 归档标志；默认 `false`。 |
| `created_at` | integer (int64) | 是 | 创建时刻，Unix 毫秒。 |
| `updated_at` | integer (int64) | 是 | 最后更新时刻，Unix 毫秒。 |
| `include_in_net_assets` | boolean | 是 | 是否计入净资产看板；默认 `true`；归档自动视为 `false`。 |

#### 字段口径补充

- **`last4` 隐私字段纪律**：见 9.7 节。完整卡号**不入** schema / 不入 Room /
  不入 localStorage / IndexedDB / 服务端；UI 录入完整卡号做 Luhn 校验，
  校验通过后仅保留后四位。
- `credit_limit` 与 `used_limit` 字段口径：仅信用卡（`kind="credit"`）必填 / 有效；
  借记卡（`kind="debit"`）这两个字段为 `null` 或 `"0"`。
- `due_day` 为 offset（**距账单日的天数**），非"具体日"模式；v2 可扩展
  "具体日"模式（`due_day_kind: "offset" | "specific"`）。
- `billing_day` + `due_day` 触发本地提醒：账单日 T-3 / 还款日 T-1（账单日 +
  offset 跨月滚动）。详见 `docs/finance.md` §6。

### 9.5 字段定义：type=tx

日常记账（type=tx，13 字段）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | UUID 字符串 | 是 | 客户端生成（UUID v4）；records 主键 + 服务端投递主键。 |
| `account_id` | string (UUID) | 条件必填 | 出账方账户 id；外键到 `Account.id`；`kind ∈ {expense, income}` 必填，`kind="transfer"` 必填。 |
| `card_id` | string (UUID) | 否 | 出账方卡 id；外键到 `Card.id`；**信用卡交易时填**（与 `account_id` 二选一，本期 v1 优先 `account_id`）。 |
| `kind` | enum | 是 | 流水类型：`income` / `expense` / `transfer`（3 项）。 |
| `amount` | string | 是 | **decimal-as-string**；金额，**正数**；`kind` 决定方向（`expense` 减余额、`income` 增余额、`transfer` 双向调整）。 |
| `category` | string | 是 | 分类 ID 或自由文本（如"餐饮"）；1–20 字符；详见 `docs/finance.md` §2 分类体系。 |
| `occurred_at` | integer (int64) | 是 | 流水发生时刻，Unix 毫秒；用户可改回历史日期补录。 |
| `note` | string \| null | 否 | 纯文本备注；0–200 字符。 |
| `transfer_to_account_id` | string (UUID) | 条件必填 | 转账入账方账户 id；外键到 `Account.id`；`kind="transfer"` 时必填，**且不能等于 `account_id`**。 |
| `icon` | string \| null | 否 | lucide-icon 名称。 |
| `color` | enum | 否 | 调色板 key（见 9.6）。 |
| `created_at` | integer (int64) | 是 | 创建时刻，Unix 毫秒。 |
| `updated_at` | integer (int64) | 是 | 最后更新时刻，Unix 毫秒。 |

#### 字段口径补充

- `amount` 字段**一律正数**（`/^-?\d+(\.\d+)?$/` 校验，但业务上不允许负数；
  `kind` 决定方向）；前端表单强制 `> 0`。
- `transfer` 流水**视作两条对向记录**（出账 + 入账），不引用对方 ID 的反向
  引用——即同一笔转账生成两条 `tx` 记录（一笔 `kind="transfer"`、从 A 账户
  出；另一笔 `kind="transfer"`、从 B 账户入 + `transfer_to_account_id` 指向
  A 账户）。
- `transfer` 的 `transfer_to_account_id` **不能等于** `account_id`（前端表单
  校验拒绝保存）；取消转账对账等价于删两条 + 重录（不提供单条覆盖）。
- 账户 / 卡被删除时其历史流水保留 `account_id=null` / `card_id=null` 墓碑，
  避免历史断裂（与 4a place / 4b event 删除语义一致）。

#### 9.5.1 tx 审计扩展列：`overspend_acknowledged`（B6）

B6 预算硬约束给 `finance_tx` Room 表增加一个**本地审计列**：

| Room 列 | 类型 | 对应明文字段 | 说明 |
|---|---|---|---|
| `overspend_acknowledged` | INTEGER NOT NULL DEFAULT 0 | `overspend_acknowledged`（Web `FinanceTx` 可选 boolean；Android 明文 JSON 同名键） | 用户在超支确认对话框选"仍保存"时置 1；仅本地审计留痕，不参与预算判定。 |

- Room 迁移 **v8 → v9**：`ALTER TABLE finance_tx ADD COLUMN
  overspend_acknowledged INTEGER NOT NULL DEFAULT 0`，旧流水升级后一律为
  未经确认；
- 该字段不升 tx 的明文 `schema_version`（仍为 1）：它是端侧审计位而非业务
  契约变更，对端侧预算判定无影响。

### 9.5.2 字段定义：type=budget（B6 预算硬约束）

预算走 records 密文通道子标识 `type="budget"`（**不新建 Room 表**，复用
upsertFinanceV2 / 墓碑 / decryptFinanceV2 链路），明文固定
`schema_version=2`，13 字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | UUID 字符串 | 是 | 客户端生成（UUID v4）。 |
| `schema_version` | integer | 是 | 固定 `2`。 |
| `scope` | enum | 是 | `monthly` / `weekly` / `yearly` / `custom`。 |
| `category` | string | 是 | `all` 表示覆盖全部分类；否则分类标签，1–20 字符。 |
| `amount_minor` | string | 是 | decimal-as-string，预算额度，必须 > 0、最多 2 位小数。 |
| `currency` | string | 是 | ISO 4217 三字母，默认 CNY。 |
| `start_ts` | integer (int64) | 是 | 有效期起点（Unix 毫秒，> 0）。 |
| `end_ts` | integer (int64) | 是 | 有效期终点（含），≥ `start_ts`。 |
| `warning_threshold_pct` | integer | 是 | 预警阈值百分比，默认 80；`1 ≤ warning ≤ block ≤ 10000`。 |
| `block_threshold_pct` | integer | 是 | 硬拦截阈值百分比，默认 100（允许 > 100）。 |
| `active` | boolean | 是 | 停用预算不参与判定。 |
| `created_at` | integer (int64) | 是 | Unix 毫秒。 |
| `updated_at` | integer (int64) | 是 | Unix 毫秒。 |

判定 / 分桶 / 拦截 / 零知识文案细则见 [`finance.md`](finance.md) §7.7。要点：

- 分桶一律按 CST（UTC+8）：`monthly` 自然月、`yearly` 自然年、`weekly`
  以预算 `start_ts` 所在 CST 日期零点为 epoch 的 7 天滚动窗（非周一自然周）、
  `custom` 桶为 `[start_ts, end_ts + 1ms)`；锚点是流水 `occurred_at`；
- 异币支出经 B5 汇率表折算到预算币种，**缺汇率保守放行**（不误拦）；
- 预算告警文案只含"百分比 + 分类名"，**不渲染金额 / 日期 / 卡号 / 对手方**，
  且不接入 Reminders 通知通道（仅端侧 toast / 对话框）。

### 9.6 调色板（color 枚举，扩展自 4a/4b 既有）

4a `place` 与 4b `event` 既有调色板为 8 色板（`blue` / `green` / `red` /
`amber` / `violet` / `pink` / `cyan` / `slate`）。本期**仅新增**以下 key，
**不修改**既有调色板：

| 新增 key | 用途 | 取色 |
|---|---|---|
| `emerald` | 财务（账户绿、入账、收入） | `#10b981` |
| `amber` | 财务（账单日提醒、转账） | `#f59e0b`（沿用 4a/4b 既有 `amber`） |
| `rose` | 财务（信用卡、负债、支出） | `#f43f5e` |
| `sky` | 财务（投资账户、订阅） | `#0ea5e9` |
| `violet` | 财务（保单、合同） | `#8b5cf6`（沿用 4a/4b 既有 `violet`） |
| `slate` | 财务（其他、归档） | `#64748b`（沿用 4a/4b 既有 `slate`） |
| `lime` | 财务（现金、借记卡） | `#84cc16` |
| `orange` | 财务（应收借款、待办） | `#f97316` |

#### 纪律

- **仅新增 key**：本节列出 8 个 key 名 + 颜色映射，**不修改**既有 4a / 4b
  调色板的 key / 取色 / 默认行为；
- 既有 8 色板（`blue` / `green` / `red` / `amber` / `violet` / `pink` /
  `cyan` / `slate`）保持原状，财务模块可继续使用；
- 财务三类条目（account / card / tx）默认色随 kind 推断（如 `account.kind
  = "cash"` → `lime`、`account.kind = "stock"` → `sky`、`card.kind =
  "credit"` → `rose` 等），用户可在编辑器手动覆盖。

### 9.7 隐私字段标记与卡号后四位截取纪律

财务模块相对 4a place / 4b event 涉及**更高敏感度**字段（金额 / 卡号 / 账户
余额 / 流水分类），需明示隐私字段边界。

#### 隐私字段标记表

| 字段 | 敏感度 | 持久化边界 |
|---|---|---|
| `account.name` | 中 | 仅 Android Room + Web 内存 |
| `account.balance` | 高 | 仅 Android Room + Web 内存；不写日志 |
| `card.name` | 中 | 仅 Android Room + Web 内存 |
| `card.issuer` | 中 | 仅 Android Room + Web 内存 |
| **`card.last4`** | **高** | **仅入 schema 后四位**；完整卡号不入任何持久化层 |
| `card.credit_limit` | 高 | 仅 Android Room + Web 内存；不写日志 |
| `card.used_limit` | 高 | 仅 Android Room + Web 内存；不写日志 |
| `card.billing_day` / `card.due_day` | 中 | 仅 Android Room + Web 内存 |
| `tx.amount` | 高 | 仅 Android Room + Web 内存；不写日志 |
| `tx.category` | 中 | 仅 Android Room + Web 内存 |
| `tx.note` | 高 | 仅 Android Room + Web 内存 |
| `tx.account_id` / `card_id` / `transfer_to_account_id` | 中 | 仅 Android Room + Web 内存（UUID 引用，不暴露明文账户名） |

#### 卡号后四位截取说明

1. **录入路径**：用户在编辑器输入完整卡号（16–19 位数字）；
2. **Luhn 校验**：前端表单失焦时调 `luhnValidate(cardNumber)` 校验；
3. **截取**：校验通过 → `extractLast4(cardNumber)` 提取后四位数字；
4. **持久化**：仅 `last4`（4 位数字字符串）入 `card.last4` 字段；**完整卡号
   不入** schema / Room / localStorage / IndexedDB / 服务端 / 通知文案 /
   日志 / 崩溃消息；
5. **校验失败**：弹错并清空输入框，**不持久化**任何位；
6. **空串**：不触发校验（`last4` 必填校验由前端表单把关）。

详见 `docs/finance.md` §5 Luhn 校验。

#### 通知文案零知识纪律（继承 NFR-1）

通知文案**不渲染金额数字 / 卡号后四位 / 具体日期数字**，仅渲染抽象文案
（如"💳 信用卡账单日 3 天后"）+ 跳转路由 id——详见 `docs/finance.md` §6.4。

### 9.8 跨端一致性要求

字段定义为**跨端契约**：Web `web/src/finance/types.ts`（`FinanceAccount` /
`FinanceCard` / `FinanceTx`）、Android `FinanceAccountEntity.kt` /
`FinanceCardEntity.kt` / `FinanceTxEntity.kt` 与本表**逐字段一致**
（命名 / 单位 / 枚举完全相同），任何字段变更须三端同改 + 同步更新本文档
+ 更新 `docs/schemas/finance.schema.json` + 更新 fixture（保持 SHA-256 一致
仍由 fixture 派生）。

- **单位约定**：所有金额字段（`balance` / `credit_limit` / `used_limit` /
  `amount`）一律 **decimal-as-string**，避免浮点精度丢失；CNY = 元为最小
  显示单位；服务端不解密故无二次校验。
- **时间戳约定**：所有时间戳字段（`created_at` / `updated_at` /
  `occurred_at`）一律 Unix 毫秒 `int64`，与既有 4a / 4b 字段口径一致。
- **枚举约定**：所有 enum 字段（`kind` / `color`）的字符串值在 Web / Android
  / JSON Schema / 本文档四处**逐字符一致**；任何枚举值新增必须三端同改 +
  同步更新本文档与 JSON Schema。
- **测试 fixture**：`web/src/finance/__fixtures__/account-cases.json` /
  `card-cases.json` / `tx-cases.json`（与 Android 镜像加载，SHA-256 一致）
  所有用例的字段名按本文档；任何字段变更后必须同步更新 fixture 并重跑
  SHA-256 比对。

### 9.9 Android Room v6 schema 与 §9.3–9.5 字段映射

Android 端在 `EveDatabase.kt` 中以 `version = 6` 与 `MIGRATION_5_6`
（[`EveDatabase.kt`](../../android/app/src/main/java/com/everything/eve/data/EveDatabase.kt)）
将四张 finance 表显式落地。本节列出 Room 实际列与 §9.3–9.5 字段表
（**明文 JSON / Web 契约**）的字段映射，以便后续 Room schema 演进的字段
对照；明文 JSON 字段定义仍以 §9.3–9.5 为唯一准绳。

#### 9.9.1 系统字段（5 列，三表共用）

Room 表在业务字段之外**统一追加**以下 5 列系统字段，便于 records
通道复用与对账：

| 列 | 类型 | 说明 |
|---|---|---|
| `schema_version` | INTEGER NOT NULL DEFAULT 1 | 业务字段 schema 版本；v1 固定 1。 |
| `module` | TEXT NOT NULL DEFAULT 'finance' | records 投递索引；finance 三表默认 `"finance"`。 |
| `type` | TEXT NOT NULL DEFAULT 'account' / `'card'` / `'tx'` | records 子类型；与 §9.2 type 枚举一致。 |
| `dirty` | INTEGER NOT NULL DEFAULT 1 | 0/1；本表写后未上行 records → 1；上行后置 0。 |
| `deleted` | INTEGER NOT NULL DEFAULT 0 | 0/1；墓碑标志（见 §1.3 墓碑纪律）。 |

#### 9.9.2 finance_account（type=account，14 列 = 9 业务字段 + 5 系统字段）

业务字段与 §9.3 对应（9 字段），加 5 系统字段共 14 列：

| Room 列 | 类型 | 对应 §9.3 字段 | 说明 |
|---|---|---|---|
| `id` | TEXT PK | `id` | UUID v4 字符串。 |
| `name` | TEXT NOT NULL | `name` | 1–40 字符。 |
| `kind` | TEXT NOT NULL | `kind` | `cash` / `deposit` / `stock` / `wallet` / `other`。 |
| `currency` | TEXT NOT NULL | `currency` | ISO 4217，默认 `"CNY"`。 |
| `balance` | TEXT NOT NULL | `balance` | decimal-as-string；**非负**。 |
| `note` | TEXT | `note` | 0–200 字符；可空。 |
| `icon` | TEXT | `icon` | lucide-icon 名；可空。 |
| `color` | TEXT | `color` | 调色板 key；可空。 |
| `archived` | INTEGER NOT NULL DEFAULT 0 | `archived` | 0/1；默认 `false`。 |
| `created_at` | INTEGER NOT NULL | `created_at` | Unix 毫秒。 |
| `updated_at` | INTEGER NOT NULL | `updated_at` | Unix 毫秒。 |
| `schema_version` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `module` | TEXT NOT NULL DEFAULT 'finance' | 系统字段 | 见 9.9.1。 |
| `type` | TEXT NOT NULL DEFAULT 'account' | 系统字段 | 见 9.9.1。 |
| `dirty` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `deleted` | INTEGER NOT NULL DEFAULT 0 | 系统字段 | 见 9.9.1。 |

> 索引：`idx_finance_account_updated_at (updated_at)` /
> `idx_finance_account_dirty (dirty)`。

#### 9.9.3 finance_card（type=card，19 列 = 14 业务字段 + 5 系统字段）

业务字段与 §9.4 对应（14 字段，含 `brand` / `expiry_month` / `expiry_year` /
`holder`），加 5 系统字段共 19 列：

| Room 列 | 类型 | 对应 §9.4 字段 | 说明 |
|---|---|---|---|
| `id` | TEXT PK | `id` | UUID v4 字符串。 |
| `name` | TEXT NOT NULL | `name` | 1–40 字符。 |
| `kind` | TEXT NOT NULL | `kind` | `debit` / `credit`；v2 扩展 `prepaid`。 |
| `issuer` | TEXT NOT NULL | `issuer` | 银行 / 发卡机构名。 |
| `last4` | TEXT NOT NULL | `last4` | **仅后四位**数字字符串；完整卡号不入。 |
| `currency` | TEXT NOT NULL | `currency` | ISO 4217，默认 `"CNY"`。 |
| `credit_limit` | TEXT | `credit_limit` | decimal-as-string；信用卡必填非空。 |
| `used_limit` | TEXT | `used_limit` | decimal-as-string；可空 / `"0"`。 |
| `billing_day` | INTEGER | `billing_day` | 1–31；可空。 |
| `due_day` | INTEGER | `due_day` | offset（1–31，距账单日天数）；可空。 |
| `brand` | TEXT | — | **BIN 推断**：`visa` / `master` / `unionpay` / `amex` / `jcb` / `discover` / `unknown`；不入 §9.4 明文 JSON（仅 Room 缓存；录入完整卡号时由前端解析并存入 Room 辅助字段）。 |
| `expiry_month` | INTEGER | — | 到期月（1–12）；不入 §9.4 明文 JSON（仅 Room 缓存）。 |
| `expiry_year` | INTEGER | — | 到期年（4 位整数）；不入 §9.4 明文 JSON（仅 Room 缓存）。 |
| `holder` | TEXT | — | 持卡人姓名；不入 §9.4 明文 JSON（仅 Room 缓存）。 |
| `note` | TEXT | `note` | 0–200 字符；可空。 |
| `icon` | TEXT | `icon` | 可空。 |
| `color` | TEXT | `color` | 可空。 |
| `archived` | INTEGER NOT NULL DEFAULT 0 | `archived` | 0/1。 |
| `created_at` | INTEGER NOT NULL | `created_at` | Unix 毫秒。 |
| `updated_at` | INTEGER NOT NULL | `updated_at` | Unix 毫秒。 |
| `schema_version` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `module` | TEXT NOT NULL DEFAULT 'finance' | 系统字段 | 见 9.9.1。 |
| `type` | TEXT NOT NULL DEFAULT 'card' | 系统字段 | 见 9.9.1。 |
| `dirty` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `deleted` | INTEGER NOT NULL DEFAULT 0 | 系统字段 | 见 9.9.1。 |

> 索引：`idx_finance_card_updated_at (updated_at)` /
> `idx_finance_card_dirty (dirty)`。
>
> 字段对照说明：`brand` / `expiry_month` / `expiry_year` / `holder` 四列
> 是**录入辅助字段**，不入 §9.4 明文 JSON（明文仍仅承载 §9.4 表字段），
> Room 缓存便于 UI 展示与提醒排程；明文合同与 §9.4 一致，**不冲突**。

#### 9.9.4 finance_tx（type=tx，19 列 = 14 业务字段 + 5 系统字段）

业务字段与 §9.5 对应（13 字段）+ B6 审计列 `overspend_acknowledged`，
加 5 系统字段共 19 列：

| Room 列 | 类型 | 对应 §9.5 字段 | 说明 |
|---|---|---|---|
| `id` | TEXT PK | `id` | UUID v4 字符串。 |
| `account_id` | TEXT NOT NULL | `account_id` | 外键到 `Account.id`。 |
| `card_id` | TEXT | `card_id` | 可空。 |
| `kind` | TEXT NOT NULL | `kind` | `income` / `expense` / `transfer`。 |
| `amount` | TEXT NOT NULL | `amount` | decimal-as-string；**正数**。 |
| `currency` | TEXT NOT NULL | `currency` | ISO 4217。 |
| `category` | TEXT NOT NULL | `category` | 分类 ID 或自由文本。 |
| `occurred_at` | INTEGER NOT NULL | `occurred_at` | Unix 毫秒。 |
| `note` | TEXT | `note` | 可空。 |
| `icon` | TEXT | `icon` | 可空。 |
| `color` | TEXT | `color` | 可空。 |
| `transfer_to_account_id` | TEXT | `transfer_to_account_id` | 转账入账方；`kind="transfer"` 时必填且 ≠ `account_id`。 |
| `created_at` | INTEGER NOT NULL | `created_at` | Unix 毫秒。 |
| `updated_at` | INTEGER NOT NULL | `updated_at` | Unix 毫秒。 |
| `overspend_acknowledged` | INTEGER NOT NULL DEFAULT 0 | `overspend_acknowledged`（B6 审计列） | 0/1；超支确认对话框选"仍保存"时置 1，详见 §9.5.1；v8→v9 迁移新增。 |
| `schema_version` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `module` | TEXT NOT NULL DEFAULT 'finance' | 系统字段 | 见 9.9.1。 |
| `type` | TEXT NOT NULL DEFAULT 'tx' | 系统字段 | 见 9.9.1。 |
| `dirty` | INTEGER NOT NULL DEFAULT 1 | 系统字段 | 见 9.9.1。 |
| `deleted` | INTEGER NOT NULL DEFAULT 0 | 系统字段 | 见 9.9.1。 |

> 索引：`idx_finance_tx_updated_at (updated_at)` /
> `idx_finance_tx_occurred_at (occurred_at)` /
> `idx_finance_tx_account_id (account_id)` /
> `idx_finance_tx_dirty (dirty)`。
>
> Room 版本演进：v6 初版 → v7 四张子类型表（B4）→ v8 finance_rate 汇率表
> （B5）→ **v9 本列 `overspend_acknowledged`（B6）**。

#### 9.9.5 finance_reminder_log（4 列 + 自增主键）

唯一非业务记录类表：仅承担"触发 → 已送达"对账日志，无系统字段：

| Room 列 | 类型 | 说明 |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT NOT NULL | 自增主键；本表不参与 records 同步。 |
| `ref_id` | TEXT NOT NULL | 引用对象 id（finance_card.id 等）。 |
| `ref_kind` | TEXT NOT NULL | 引用类型：`card_statement_due` / `card_payment_due` / 后续 v2 扩展。 |
| `fire_at` | INTEGER NOT NULL | 触发时刻，Unix 毫秒。 |
| `delivered` | INTEGER NOT NULL DEFAULT 0 | 0/1；Receiver 投送通知成功后置 1。 |

> 索引：`idx_finance_reminder_log_fire_at (fire_at)` /
> `idx_finance_reminder_log_ref (ref_id, ref_kind)`。

#### 9.9.6 字段数对账小结

| 子类型 | §9.3–9.5 业务字段 | Room 业务列 | Room 系统列 | Room 总列 |
|---|---|---|---|---|
| `account` | 9 | 9 | 5 | 14 |
| `card` | 14 | 14 | 5 | 19 |
| `tx` | 13（另加 B6 审计列 `overspend_acknowledged`，不入明文 JSON 字段计数） | 14 | 5 | 19 |
| `reminder_log` | — | 4（含自增主键） | — | 4 |

> B6 增补：`finance_tx` 表随 Room v8→v9 迁移新增审计列
> `overspend_acknowledged INTEGER NOT NULL DEFAULT 0`（详见 §9.5.1），
> 该列**不进 §9.5 明文 JSON 业务字段**（records 载荷仍为 13 业务字段，
> Web 端 `schema_version` 不升级），仅作本机 Room 审计位，故业务字段
> 计数保持 13、Room 业务列与总列各 +1。

> 说明：§9.3 / §9.4 / §9.5 中标注的 "11 字段 / 17 字段 / 13 字段"
> 为**业务字段计数**（不含 Room 系统列，且 card 17 = 业务 14 + 系统 5
> ≈ 19 列中部分由 BIN 推断字段补充的更早期口径，已随 Room v6 实际
> schema 修订为本节口径）；后续一切字段调整以本节 Room schema 为
> 准并同步更新 §9.3–9.5。

#### 9.9.7 跨端契约铁律

- **明文 JSON 字段表**：以 §9.3–9.5 为唯一准绳；任何字段变更须三端同改 +
  同步更新本文档 + 更新 `docs/schemas/finance.schema.json`。
- **Room 列 ↔ 明文字段映射**：以本节 §9.9.2–9.9.5 为唯一准绳；Room 列
  新增 / 删除 / 重命名必须同步更新本节与对应 Entity（`FinanceAccountEntity.kt`
  / `FinanceCardEntity.kt` / `FinanceTxEntity.kt`）、
  FinanceRepository（`FinanceRepository.kt`）的 `toJson` / `fromJsonObj`
  双向映射。
- **系统字段 5 列**：三表统一追加，`module` / `type` 默认值按表分别
  `'finance'` / `'account' | 'card' | 'tx'`；不暴露 UI，不可编辑。
- **Room 缓存字段**（`brand` / `expiry_month` / `expiry_year` / `holder`
  四列）：仅 Room 持有、**不入明文 JSON**；录入时由前端从完整卡号解析，
  上行 records 时不带这四列；下载解密后回写 Room。

---

## 10. Android 本期 UI 支持矩阵

Android 端本期交付笔记 UI 与采集器（采集写入 + 采集状态页）；其余类型的记录
**完整同步并加密入库**（未知 module/type 也原样保存），但不解密、不展示、不可编辑。

| module | type | Android 本期 |
|---|---|---|
| `pass` | `note` | ✅ 有 UI：笔记列表、新建、解密展示（`VaultScreen` / `RecordsRepository`） |
| `note`（历史） | `secure_note`（历史） | ✅ 只读展示：按笔记解密列出，不再新写入 |
| `contact` | `contact` | ✅ 采集写入（`CollectorScreen` 状态页展示计数/游标状态，不解密内容） |
| `sms` | `sms` | ✅ 采集写入（同上，不解密内容） |
| `calllog` | `call` | ✅ 采集写入（同上，不解密内容） |
| `pass` | `login` | ⏳ 仅同步保存到本地 Room，不解密 / 不展示 |
| `pass` | `card` | ⏳ 仅同步保存，不解密 / 不展示 |
| `identity` | `id_card` / `passport` / `driver_license` / `generic` | ⏳ 仅同步保存，不解密 / 不展示 |
| `place` | `place` | ⏳ 仅同步保存到本地 Room，不解密 / 不展示（命名写入在 Web 轨迹页） |
| 其他未知 module/type | — | ⏳ 原样同步入库，不在 UI 暴露 |

位置轨迹块（第 6 章）不走通用 records 通道：Android 端由前台定位服务采集、
加密封块后经 `/api/v1/locations/batch` 上行，本机仅短期缓冲明文点，无 UI 展示。

## 8. event 模块（日程/日历，阶段 4b）

阶段 4b 新增的日程/日历模块。事件作为加密个人库中的一条记录，
走既有 records 通道同步（与第 7 章 `place` 模块同款链路），
复用 `CryptoEnvelope` 的 XChaCha20-Poly1305 与 AAD 规则，**不新造**
任何加密原语或服务端接口。重复事件不物化实例，由客户端按本地时区
动态展开；服务端**不解密、不解析 rrule、不缓存明文起始时刻**。

### 8.1 模块挂载点

event 作为 records 表一条密文记录写入，明文载荷符合本节定义：

- **`module = "event"`**、`**type = "event"`**（与第 7 章 `place` 模块同款
  `module`/`type` 双键约定；前者用于 records 投递索引，后者随明文写
  入密文内供端侧识别）。
- **AAD 沿用 `eve:v1:record:{id}`**，与既有 records 记录**逐字节一致**，
  **不新造** envelope 参数；详见 [crypto.md](crypto.md) §5.1。
- 服务端在 records 投递（`server/internal/api/records_handler.go`）阶段
  仅校验 `id`/`module` 非空与密文非空，**不**校验 `type`、不解密、不
  解析 rrule、不缓存明文起始时刻。客户端遇到不识别的 `type` 仍按密文
  原样入库（保持前向兼容）。
- 服务端可见的明文元数据仅限 records 表已有列（`id`/`module`/`type`
  因投递校验入索引，但其余事件字段均处于密文中），不引入新表、新列、
  新接口。

### 8.2 字段定义

事件明文 JSON（嵌入 records 密文内）的字段定义如下。该字段表为
跨端契约：Web `web/src/events/types.ts`、Android `EventEntity.kt` 与本表
**逐字段一致**（命名/单位/枚举完全相同），任何字段变更须三端同改 +
同步更新本文档。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | UUID 字符串 | 是 | 客户端生成（UUID v4）；同时作为 records 主键与服务端投递主键；同 id 重复命名以 records LWW 覆盖。 |
| `title` | string | 是 | 事件标题；UTF-8；≤200 字符；前端表单校验非空。 |
| `start_ts` | int64 | 是 | 事件本地起始时刻，Unix **毫秒**（与既有 4a 轨迹块 UTC 毫秒的字段相对，但事件取**本地**时区语义、`tz_mode=local`）；与 `tz_mode=local` 联动按本地日历日换算 `exdates`。 |
| `end_ts` | int64 | 是 | 事件结束时刻，Unix 毫秒；`all_day=true` 时为结束日 00:00（本日起算到结束日 00:00）；校验 `end_ts ≥ start_ts`。 |
| `all_day` | boolean | 是 | 是否全天事件；true 时编辑 UI 仅展示日期选择。 |
| `tz_mode` | string | 是 | 本期固定 `"local"`（设备系统时区语义）；字段保留以便未来扩展为独立时区（`tz_mode="tz"` + 浮动规则展开），UI 本期**不暴露**。 |
| `location_text` | string \| null | 否 | 纯文本地点描述；**不关联**第 7 章轨迹 `place` 模块（保持模块解耦；事件地点不进入命名地点字典）。 |
| `note` | string \| null | 否 | 纯文本备注。 |
| `color` | enum | 是 | 8 色板：`blue` / `green` / `red` / `amber` / `violet` / `pink` / `cyan` / `slate`；事件→实例继承（同一事件所有展开实例同色）。 |
| `reminders` | int[] | 是 | 提前分钟数组，**≤3 个**；`0` 表示事件开始时刻本身；档位 `0 / 5 / 15 / 30 / 60 / 1440`（1440 = 1 天前）；未在前述档位的值前端校验拒绝。 |
| `rrule` | object \| null | 是 | 重复规则；`null` 表示单次事件；非空时为 B 档简化子集，详见 8.3；服务端不解密故无法校验。 |
| `exdates` | string[] | 是 | 例外日期数组，格式 `YYYY-MM-DD`；与 `tz_mode=local` 联动按本地日历日匹配；展开时命中该列表的本地日历日即跳过对应实例（顺序编号不递增）。 |

字段表口径补充：

- 全部时间戳字段（`start_ts` / `end_ts`）一律 Unix 毫秒 `int64`，
  与既有位置轨迹块（`start_ts` / `end_ts`）口径相同但**语义不同**：
  轨迹块时间取 UTC 毫秒，事件时间取设备本地时区语义。
- reminders / rrule / exdates 三个字段即便逻辑为空也**必须存在**
  （空数组 / `null` / `[]`），便于前后端类型扁平化处理。
- `title` 上限由前端表单校验强制；服务端不解密故无二次校验。

### 8.3 RRULE B 档子集

`rrule = null` 表示**单次事件**（仅 `start_ts` 一次出现，编号
固定 `#0`）；非空时为以下简化子集——

| 字段 | 类型 | 取值 |
|---|---|---|
| `freq` | enum | `DAILY` / `WEEKLY` / `MONTHLY` / `YEARLY`（4 档频率，缺省 UI 默认单次 = `rrule=null`） |
| `interval` | int | ≥1；UI 默认 1；每隔 `interval` 个 `freq` 单位出现一次 |
| `byweekday` | enum[] | 仅 `freq ∈ {WEEKLY, MONTHLY}` 时有效；元素取值 `MO` / `TU` / `WE` / `TH` / `FR` / `SA` / `SU`；`WEEKLY` 可多选（同一周内多天命中）；`MONTHLY` **仅支持单 weekday**（取"该月第 N 个 `byweekday[0]`"），**不支持** `MO`+`TU` 同时等多元素组合 |
| `end` | object | 三选一：`{kind: "never"}`（永不复，UI 默认） / `{kind: "date", until: "YYYY-MM-DD"}`（截止到该本地日历日 24:00 后不再展开） / `{kind: "count", count: int}`（累计展开次数，**不含**已被 `exdates` 跳过的实例） |

#### 8.3.1 语义约束

- `end.kind = "never"`：永不复，UI 默认；导出/审计时显示"永不截止"。
- `end.kind = "date"`：`until` 为本地日历日（`YYYY-MM-DD`）；展开时遇
  实例本地日历日 `> until` 即停；该日 24:00 之后不再生成实例（即
  `until` 当日仍可有一次实例）。
- `end.kind = "count"`：累计**实际**展开次数（含被 exdate 跳过的实例**不**计入，
  即 n 编号按"实际命中"递增）；达到 `count` 次后停。例：`freq=WEEKLY,
  byweekday=[MO], interval=1, end.kind=count, count=2, exdates=[第二次本
  地日历日]`，实际只展开 1 次（第二次被 exdate 跳过 → 该次不计入
  count）。`count` 必须 `≥1`。
- **DST 处理**：跨夏令时切换日，`WEEKLY` 维持同一本地时刻（如周一
  09:00 → 周一 09:00），**不补** 23 / 25 小时校正；设备系统时区
  （Java `java.time.ZonedDateTime.systemDefault()` / TS `Date` 默认
  时区）作为唯一锚点。
- **`byweekday` 仅 WEEKLY / MONTHLY 有效**；`DAILY` / `YEARLY` 时该字段
  即使填入也忽略（前端校验警告但不阻断保存）。`MONTHLY` 仅支持单
  weekday（`byweekday.length === 1`）；前端校验拒绝多元素。
- **字段不可识别**：`rrule` 含 B 档之外的字段 / 枚举值越界时，**前端表单
  校验拒绝保存**；服务端不解密故无法二次校验（本文档明示，AC-1 关闭
  条件的依据）。
- 字段必须**完整**：`freq` / `interval` / `byweekday` / `end` 四项即便
  默认值也必须出现（前端 UI 默认值生成器处理），不依赖缺省推断。

### 8.4 JSON Schema 示例（完整事件）

```json
{
  "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "title": "周一晨会",
  "start_ts": 1735689600000,
  "end_ts":   1735693200000,
  "all_day": false,
  "tz_mode": "local",
  "location_text": "会议室 A",
  "note": "准备上周总结",
  "color": "blue",
  "reminders": [0, 15],
  "rrule": {
    "freq": "WEEKLY",
    "interval": 1,
    "byweekday": ["MO"],
    "end": { "kind": "never" }
  },
  "exdates": ["2026-01-01"]
}
```

字段口径解读（与 8.2 对应）：

- `start_ts` / `end_ts`：事件时间，本地时区语义，Unix 毫秒；本例
  = 北京时间 2025-01-01 09:00–10:00（示例值仅展示格式）。
- `rrule.freq=WEEKLY, byweekday=[MO]`：每周一命中（每周一 09:00–10:00
  重复）。
- `end.kind=never`：永不复。
- `exdates=["2026-01-01"]`：2026-01-01（周四）若按规则不命中，但用作
  演示该字段随本地日历日匹配。

### 8.5 跨端一致性要求

- **Web**：`web/src/events/types.ts`（`EventRule` / `Occurrence` /
  `ReminderItem`）与本文档字段表**逐字段一致**（命名/单位/枚举）。
- **Android**：`android/.../data/event/EventEntity.kt` 与本文档字段表
  **逐字段一致**；Room 列映射遵守第 5 章既有模式（明文字段 TEXT/INTEGER、
  JSON 列表/对象以 TEXT 持久化、`reminders_json` / `rrule_json` /
  `exdates_json` 三列对应该表三字段）。
- **重复规则名**：`rrule` 在本文档与 types.ts / EventEntity.kt 中保持同一命名；
  Android 端驼峰可投影至下划线列名，但 JSON 字段名与本文档严格一致。
- **测试 fixture**：`recurrence/__fixtures__/cases.json`（Web 与 Android 镜像
  加载，SHA-256 一致）所有用例的字段名按本文档；`instance_id` 形如
  `<rule.id>:<rule.start_ts>#<n>`，与本文档 8.3.1 一致。
- **字段如有变更**：Web types.ts、Android EventEntity.kt、本文档三端**必须**
  同改 + 同步更新本节字段表与目录入口，并更新 fixture（保持 SHA-256 一致
  仍由 fixture 派生）。
