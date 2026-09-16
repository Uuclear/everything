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
9. [Android 本期 UI 支持矩阵](#9-android-本期-ui-支持矩阵)

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

## 9. Android 本期 UI 支持矩阵

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
