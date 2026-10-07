# 轨迹底图瓦片代理 — 端到端手动冒烟脚本

> 对应产品批次 **P0 地图 403 修复**：`GET /api/v1/map/tiles/{z}/{x}/{y}.png`
> 同源代理 + Web 默认 `DEFAULT_TILE_URL` + 失败 UI。
>
> 实现锚点：
> - `server/internal/api/map_handler.go`、`server/internal/maptile/*`
> - `server/internal/config/config.go` → `map.upstream_template` / `cache_enabled` / `rate_limit_rpm_per_ip`
> - `web/src/locations/tile.ts`、`web/src/components/LocationMap.vue`
> - 黑盒单测：`server/internal/api/map_tiles_test.go`
>
> 自动化复跑：
>
> ```powershell
> powershell -NoProfile -File scripts/ci-local.ps1 -Target server   # 需 PATH 或 .tools/go
> cd server && go test -count=1 ./internal/api/... ./internal/maptile/...
> cd web && npx vitest run src/locations/__tests__/tile.test.ts
> ```
>
> **隐私说明**：瓦片请求 **不含** 用户轨迹坐标包；仅 `{z,x,y}` 与合规 `User-Agent` 访问上游。
> 轨迹明文仍在客户端解密，本路由不接触 MK。

---

## §A 通用前置

1. **配置（可选）** `data/config.yaml` 或环境变量：
   ```yaml
   map:
     upstream_template: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
     cache_enabled: true
     rate_limit_rpm_per_ip: 240
   ```
   - 环境变量覆盖：`EVE_MAP_UPSTREAM_TEMPLATE`（见 `config.applyEnv`）。
2. **用户态**
   - 已登录且设备 **approved**（与 records API 相同 JWT）；
   - Web 已解锁资料库（轨迹页可读本地解密时间线）。
3. **默认 Web 瓦片 URL**
   - `loadTileUrl()` 缺省为 `/api/v1/map/tiles/{z}/{x}/{y}.png`；
   - `isProxiedTileUrl()` 为 true 时，地图组件用 **fetch + Authorization** 加载（`img` 无法带 JWT）。

---

## §B 场景列表

### 场景 1：未带 JWT → 401

- **步骤**：
  ```http
  GET /api/v1/map/tiles/2/1/1.png
  （无 Authorization 头）
  ```
- **期望**：HTTP **401**；响应体为统一 JSON 错误（非 PNG）。
- **自动化**：`TestMapTilesAuthAndProxy` 已断言。

### 场景 2：approved JWT → 200 + PNG

- **步骤**：登录获取 access token → 同上 URL 带 `Authorization: Bearer <token>`。
- **期望**：
  - HTTP **200**；
  - `Content-Type` 含 `image`；
  -  body 以 PNG 魔数 `0x89 0x50 0x4E 0x47` 开头（上游正常时）。
- **上游侧**：服务端请求上游时 `User-Agent: Everything-Eve/1.0 (+self-hosted)`（单测校验）。

### 场景 3：Web 轨迹页底图可见

- **步骤**：
  1. 登录 → 解锁 → 打开「位置/轨迹」页，选择有数据的日期；
  2. DevTools → Network，筛选 `map/tiles` 或 `.png`。
- **期望**：
  - 瓦片请求 **200**（非直连 `tile.openstreetmap.org` 403）；
  - 地图可见底图；轨迹 polyline 正常叠加。
- **失败 UI**：若瓦片层 `error` → 页面 `NAlert`「底图加载失败，请在地图设置中更换源」。

### 场景 4：401 排障（未登录 / token 过期）

- **步骤**：清除 token 或等待过期后仅打开轨迹页。
- **期望**：瓦片 401 → 触发场景 3 失败 UI 或地图空白 + 提示；**不**静默失败无提示。

### 场景 5：上游不可达 → 5xx

- **步骤**（测试环境）：将 `upstream_template` 指向不可解析主机或返回 404 的 URL，重启服务端。
- **期望**：客户端收到 **502/504** 类错误（以实现为准）；地图显示失败提示；服务端日志 **不含** 用户轨迹内容。

### 场景 6：自定义第三方模板（MapTiler 等）

- **步骤**：地图 ⚙ → 填入合法 `{z}/{x}/{y}` HTTPS 模板（含 API key 若需要）→ 保存到 `localStorage['eve.locations.tileUrl']`。
- **期望**：`isValidTileUrl` 通过则生效；若改回同源代理 URL，恢复场景 3 行为。

### 场景 7：磁盘缓存（可选）

- **前置**：`map.cache_enabled: true`，`data_dir/map_cache/` 可写。
- **步骤**：同区域重复缩放平移。
- **期望**：第二次起上游请求减少（命中本地缓存）；删除缓存目录后仍可拉取。

### 场景 8：限流（可选，压测）

- **前置**：将 `rate_limit_rpm_per_ip` 设为较小值（如 10）。
- **步骤**：脚本快速请求同一 IP 超过 RPM。
- **期望**：超出后 **429** 或等价拒绝；不影响 records 同步 API。

---

## §C 与零知识 / 敏感数据边界

| 数据 | 是否经瓦片 API | 说明 |
|------|----------------|------|
| 轨迹点明文 | 否 | 仅客户端解密渲染 |
| 登录 JWT | 是（Header） | 标准 HTTPS，不落日志 |
| 上游瓦片 PNG | 是（透传） | 公开地图数据，非用户密文 |
| 证件/卡号 | 否 | 本路由无业务字段 |

**禁止**：在服务端为「调试瓦片」打印 JWT 全文或用户 id 与 `{z,x,y}` 关联档案。

---

## §D 签署

| 项 | 执行人 | 日期 | 结果 |
|----|--------|------|------|
| 场景 1–2（curl/单测） | | | ☐ PASS |
| 场景 3–4（Web 手动） | | | ☐ PASS / ☐ SKIP |
| 场景 5–8 | | | ☐ PASS / ☐ SKIP |

自动化（2026-10-07 W6）：`go test ./internal/api` 含 `TestMapTilesAuthAndProxy` **PASS**；Web `tile.test.ts` 随全量 vitest **PASS**。
`ci-local.ps1 -Target all` 在 **PATH 无 go** 时会 **SKIP** server 段；请用 `scripts/setup-go-local.ps1` 后复跑 server 段。
