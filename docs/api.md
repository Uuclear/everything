# HTTP API（v1）

基础路径：`/api/v1`。除注册/登录/健康检查外均需 `Authorization: Bearer <access_token>`。
所有字节字段（salt、verifier、密文等）使用标准 Base64 字符串。

## 系统

### GET /health
无需认证。返回 `{ "status": "ok", "version": "...", "time": 1757865600000 }`。

## 认证

### POST /auth/register
首个用户注册（`registration=first` 时注册过一次即关闭）。

```json
{
  "username": "alice",
  "auth_salt": "<base64 16B>",
  "kek_salt": "<base64 16B>",
  "auth_verifier": "<base64 32B, Argon2id(password, authSalt)>",
  "wrapped_master_key": "<base64, XChaCha(KEK, MK) AAD=eve:v1:master-key/v1>",
  "device_name": "MacBook Chrome",
  "device_public_key": null
}
```

`201`：`{ access_token, refresh_token, expires_in, token_type, user_id, device_id }`
`409` 用户名冲突；`403` 注册关闭。

### GET /auth/parameters?username=alice
登录前获取盐与包裹密钥：
`{ auth_salt, kek_salt, wrapped_master_key, argon2: {algorithm, time, memory_kib, threads, key_len} }`
用户不存在时返回 `401`（不暴露用户是否存在）。

### POST /auth/login
```json
{ "username": "alice", "auth_verifier": "<base64 32B>", "device_name": "Pixel 8" }
```
`200`：令牌对 + `{ username, auth_salt, kek_salt, wrapped_master_key }`。
客户端用 `Argon2id(password, kekSalt)` 解出 MK 驻留内存。

### POST /auth/refresh
`{ "refresh_token": "<hex64>" }` → 新令牌对。

## 加密记录

### POST /records/batch（认证）
单批 1–1000 条。`device_id` 由服务端按令牌覆盖；`version` 不大于库中版本的记录会被跳过。

```json
{
  "records": [
    {
      "id": "uuid",
      "module": "note",
      "type": "secure_note",
      "ciphertext": "<base64 nonce||cipher, AAD=eve:v1:record:id:module:BE64(version)>",
      "version": 1,
      "created_at": 1757865600000,
      "updated_at": 1757865600000,
      "deleted": false
    }
  ]
}
```

返回 `{ "applied": 1, "skipped": 0 }`。有写入时广播 SSE `records_changed`。

### GET /records?since=0&limit=500（认证）
返回 `updated_at > since` 的记录（升序），`limit` 默认 500、最大 2000；
`has_more=true` 时以最后一条 `updated_at` 为游标继续翻页。

### GET /events（认证，SSE）
`text/event-stream`。事件：`event: records_changed\ndata: {"type":"records_changed"}\n\n`；
每 30 秒发送 `: ping` 注释帧。断线后以 `since=` 补拉为准（SSE 只是加速通知）。

> 浏览器 `EventSource` 无法自定义请求头，阶段 2 将提供"签名查询令牌"通道；
> 当前 Web 端以 10 秒轮询兜底。

## 错误格式

```json
{ "error": "invalid_credentials", "message": "用户名或密码错误" }
```

## 配置

| 配置 | 环境变量 | 默认 |
|---|---|---|
| `addr` | `EVE_ADDR` | `:8787` |
| `data_dir` | `EVE_DATA_DIR` | `./data` |
| `registration` | `EVE_REGISTRATION` | `first` |

完整示例：[deploy/config.example.yaml](../deploy/config.example.yaml)
