package api_test

// 阶段 4a Task 2：locations 三端点黑盒测试。
// 覆盖 tasks.md 测试清单：51 块 400 / cipher 超 256KB 400 / 缺 id·非法 ts 400 /
// 正常批量 200 且 applied 正确 / 同批重提 skipped=全部 / GET 跨度 63 天 400 /
// 合法跨月 GET 升序 / DELETE 返回删除数 / 三审计事件（detail 仅计数与范围）/
// 未授权（无 token 401、pending scope 403）。

import (
	"bufio"
	"database/sql"
	"encoding/base64"
	"fmt"
	"net/http"
	"strings"
	"testing"
	"time"
)

// utcMs 构造 UTC 毫秒时间戳（测试数据全部按 UTC 对齐，与月表归属口径一致）。
func utcMs(y int, m time.Month, d, hh, mm int) int64 {
	return time.Date(y, m, d, hh, mm, 0, 0, time.UTC).UnixMilli()
}

// locBlock 构造一个上行块请求体。cipher 为 size 字节的 base64（内容全零，
// 服务端零知识不验证密文内容）；device_id 故意带客户端自声明值，
// 用于验证服务端以 token claims 覆盖。
func locBlock(id string, start, end int64, cipherSize int) map[string]any {
	return map[string]any{
		"id":          id,
		"device_id":   "client-claimed-device",
		"start_ts":    start,
		"end_ts":      end,
		"point_count": 7,
		"cipher":      base64.StdEncoding.EncodeToString(make([]byte, cipherSize)),
	}
}

// locBatch 组装批量上行请求体。
func locBatch(blocks ...map[string]any) map[string]any {
	list := make([]any, len(blocks))
	for i, b := range blocks {
		list[i] = b
	}
	return map[string]any{"blocks": list}
}

// auditDetails 取某审计事件的全部 detail（对齐 audit_coverage_test.go 的直查表模式）。
func auditDetails(t *testing.T, database *sql.DB, event string) []string {
	t.Helper()
	rows, err := database.Query(`SELECT detail FROM audit_logs WHERE event = ?`, event)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var d string
		if err := rows.Scan(&d); err != nil {
			t.Fatal(err)
		}
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

// hasExact 判断字符串切片中是否存在完全相等的元素。
func hasExact(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

// TestLocationUploadValidation 覆盖上行校验：各类非法请求 400，边界合法请求 200。
func TestLocationUploadValidation(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	c := &testClient{t: t, srv: srv, username: "loc-valid", password: "pw loc valid 01"}
	c.token, _, _ = regUser(t, c.username, c.password, "laptop", c.do)

	jan15 := utcMs(2025, 1, 15, 10, 0)

	// 51 块（超单批上限）。
	tooMany := make([]any, 51)
	for i := range tooMany {
		tooMany[i] = locBlock(fmt.Sprintf("blk-m-%d", i), jan15+int64(i), jan15+int64(i)+1, 8)
	}
	// 非法 cipher（非 base64）。
	badB64 := locBlock("blk-b64", jan15, jan15+1000, 8)
	badB64["cipher"] = "!!!not-base64!!!"

	cases := []struct {
		name string
		body map[string]any
	}{
		{"空批量", map[string]any{"blocks": []any{}}},
		{"51 块超限", map[string]any{"blocks": tooMany}},
		{"cipher 超 256KB", locBatch(locBlock("blk-big", jan15, jan15+1000, 256*1024+1))},
		{"缺 id", locBatch(locBlock("", jan15, jan15+1000, 8))},
		{"start_ts 为 0", locBatch(locBlock("blk-ts0", 0, 1000, 8))},
		{"start_ts 为负", locBatch(locBlock("blk-tsneg", -5, 1000, 8))},
		{"start_ts 大于 end_ts", locBatch(locBlock("blk-tsrev", 2000, 1000, 8))},
		{"point_count 为 0", func() map[string]any {
			b := locBlock("blk-pc0", jan15, jan15+1000, 8)
			b["point_count"] = 0
			return locBatch(b)
		}()},
		{"cipher 非 base64", locBatch(badB64)},
		{"cipher 为空", func() map[string]any {
			b := locBlock("blk-empty", jan15, jan15+1000, 8)
			b["cipher"] = ""
			return locBatch(b)
		}()},
	}
	for _, tc := range cases {
		code, out := c.do(http.MethodPost, "/api/v1/locations/batch", tc.body, true)
		if code != http.StatusBadRequest {
			t.Errorf("%s：应 400，实际 %d %v", tc.name, code, out)
		}
	}

	// 边界合法：恰 50 块 → 200 applied=50。
	fifty := make([]any, 50)
	for i := range fifty {
		fifty[i] = locBlock(fmt.Sprintf("blk-f-%d", i), jan15+int64(i)*2000, jan15+int64(i)*2000+1000, 8)
	}
	code, out := c.do(http.MethodPost, "/api/v1/locations/batch", map[string]any{"blocks": fifty}, true)
	if code != http.StatusOK || out["applied"].(float64) != 50 {
		t.Fatalf("恰 50 块应 200 且 applied=50，实际 %d %v", code, out)
	}
	// 边界合法：单块恰 256KB → 200。
	code, out = c.do(http.MethodPost, "/api/v1/locations/batch",
		locBatch(locBlock("blk-max", jan15, jan15+1000, 256*1024)), true)
	if code != http.StatusOK || out["applied"].(float64) != 1 {
		t.Fatalf("恰 256KB 应 200 且 applied=1，实际 %d %v", code, out)
	}
}

// TestLocationUploadGetDeleteFlow 覆盖主流程：跨月写入、幂等重提、
// 升序跨月查询、跨度上限、范围删除计数、claims 覆盖 device_id。
func TestLocationUploadGetDeleteFlow(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	c := &testClient{t: t, srv: srv, username: "loc-flow", password: "pw loc flow 01"}
	c.token, _, _ = regUser(t, c.username, c.password, "laptop", c.do)

	jan1 := utcMs(2025, 1, 1, 0, 0)
	jan15 := utcMs(2025, 1, 15, 10, 0)
	jan20 := utcMs(2025, 1, 20, 10, 0)
	jan31End := utcMs(2025, 2, 1, 0, 0) - 1
	feb10 := utcMs(2025, 2, 10, 10, 0)
	feb28 := utcMs(2025, 2, 28, 23, 59)

	// 故意乱序上传（2 月块在前），验证 GET 按 start_ts 升序而非插入序。
	blocks := []map[string]any{
		locBlock("blk-feb10", feb10, feb10+1000, 32),
		locBlock("blk-jan15", jan15, jan15+1000, 48),
		locBlock("blk-jan20", jan20, jan20+1000, 64),
	}
	code, out := c.do(http.MethodPost, "/api/v1/locations/batch", locBatch(blocks...), true)
	if code != http.StatusOK || out["applied"].(float64) != 3 || out["skipped"].(float64) != 0 {
		t.Fatalf("首次上行应 applied=3 skipped=0，实际 %d %v", code, out)
	}

	// 同批重提 → 全部幂等跳过（块不可变，AC-5）。
	code, out = c.do(http.MethodPost, "/api/v1/locations/batch", locBatch(blocks...), true)
	if code != http.StatusOK || out["applied"].(float64) != 0 || out["skipped"].(float64) != 3 {
		t.Fatalf("重提应 applied=0 skipped=3，实际 %d %v", code, out)
	}

	// 当前设备 id（token claims 口径），用于验证 device_id 覆盖。
	_, devs := c.do(http.MethodGet, "/api/v1/auth/devices", nil, true)
	var curDevID string
	for _, d := range devs["devices"].([]any) {
		dd := d.(map[string]any)
		if dd["current"] == true {
			curDevID = dd["id"].(string)
		}
	}
	if curDevID == "" {
		t.Fatal("设备列表未找到当前设备")
	}

	// 合法跨月 GET → 升序返回 3 块，cipher base64 往返一致，device_id 已被 claims 覆盖。
	q := fmt.Sprintf("?from=%d&to=%d", jan1, feb28)
	code, out = c.do(http.MethodGet, "/api/v1/locations"+q, nil, true)
	if code != http.StatusOK {
		t.Fatalf("跨月 GET 应 200，实际 %d %v", code, out)
	}
	got := out["blocks"].([]any)
	if len(got) != 3 {
		t.Fatalf("应返回 3 块，实际 %d", len(got))
	}
	wantIDs := []string{"blk-jan15", "blk-jan20", "blk-feb10"}
	wantSizes := []int{48, 64, 32}
	var prevTs int64
	for i, b := range got {
		blk := b.(map[string]any)
		if blk["id"].(string) != wantIDs[i] {
			t.Fatalf("第 %d 块应为 %s（升序），实际 %s", i, wantIDs[i], blk["id"])
		}
		ts := int64(blk["start_ts"].(float64))
		if ts <= prevTs {
			t.Fatalf("返回必须按 start_ts 升序: %v", got)
		}
		prevTs = ts
		if blk["device_id"].(string) != curDevID {
			t.Fatalf("device_id 应以 token claims 覆盖为 %s，实际 %v", curDevID, blk["device_id"])
		}
		if ct := mustB64(t, blk["cipher"]); len(ct) != wantSizes[i] {
			t.Fatalf("cipher base64 往返长度应 %d，实际 %d", wantSizes[i], len(ct))
		}
		if blk["created_at"].(float64) <= 0 {
			t.Fatal("created_at 应由服务端权威写入")
		}
	}

	// 跨度 63 天 → 400；恰 62 天（边界）→ 200。
	span63 := fmt.Sprintf("?from=%d&to=%d", jan1, jan1+63*24*3600*1000)
	if code, _ := c.do(http.MethodGet, "/api/v1/locations"+span63, nil, true); code != http.StatusBadRequest {
		t.Fatalf("GET 跨度 63 天应 400，实际 %d", code)
	}
	if code, _ := c.do(http.MethodDelete, "/api/v1/locations"+span63, nil, true); code != http.StatusBadRequest {
		t.Fatalf("DELETE 跨度 63 天应 400，实际 %d", code)
	}
	span62 := fmt.Sprintf("?from=%d&to=%d", jan1, jan1+62*24*3600*1000)
	if code, _ := c.do(http.MethodGet, "/api/v1/locations"+span62, nil, true); code != http.StatusOK {
		t.Fatalf("GET 跨度恰 62 天应 200，实际 %d", code)
	}

	// 其余非法范围参数 → 400。
	for name, bad := range map[string]string{
		"to 小于 from": fmt.Sprintf("?from=%d&to=%d", feb28, jan1),
		"from 为 0":   fmt.Sprintf("?from=0&to=%d", feb28),
		"from 非整数":   fmt.Sprintf("?from=abc&to=%d", feb28),
		"缺少参数":       "",
	} {
		if code, _ := c.do(http.MethodGet, "/api/v1/locations"+bad, nil, true); code != http.StatusBadRequest {
			t.Errorf("GET %s 应 400，实际 %d", name, code)
		}
		if code, _ := c.do(http.MethodDelete, "/api/v1/locations"+bad, nil, true); code != http.StatusBadRequest {
			t.Errorf("DELETE %s 应 400，实际 %d", name, code)
		}
	}

	// DELETE 一月范围 → 返回删除数 2（跨月表语义由 store 层保障）。
	qJan := fmt.Sprintf("?from=%d&to=%d", jan1, jan31End)
	code, out = c.do(http.MethodDelete, "/api/v1/locations"+qJan, nil, true)
	if code != http.StatusOK || out["deleted"].(float64) != 2 {
		t.Fatalf("DELETE 一月应 deleted=2，实际 %d %v", code, out)
	}
	// 删后 GET 只剩 2 月块。
	code, out = c.do(http.MethodGet, "/api/v1/locations"+q, nil, true)
	if code != http.StatusOK || len(out["blocks"].([]any)) != 1 {
		t.Fatalf("删后应剩 1 块，实际 %d %v", code, out)
	}
	// 全量删除 → deleted=1；再删 → deleted=0（幂等口径）。
	code, out = c.do(http.MethodDelete, "/api/v1/locations"+q, nil, true)
	if code != http.StatusOK || out["deleted"].(float64) != 1 {
		t.Fatalf("DELETE 全量应 deleted=1，实际 %d %v", code, out)
	}
	code, out = c.do(http.MethodDelete, "/api/v1/locations"+q, nil, true)
	if code != http.StatusOK || out["deleted"].(float64) != 0 {
		t.Fatalf("重复 DELETE 应 deleted=0，实际 %d %v", code, out)
	}
	// 空库 GET → 200 且 blocks 为空数组。
	code, out = c.do(http.MethodGet, "/api/v1/locations"+q, nil, true)
	if code != http.StatusOK || len(out["blocks"].([]any)) != 0 {
		t.Fatalf("空库 GET 应返回空 blocks，实际 %d %v", code, out)
	}
}

// TestLocationAuditEvents 覆盖三端点审计事件（TR-2.2）：
// detail 精确等于计数/范围格式串，且不含任何块 id/密文标记。
func TestLocationAuditEvents(t *testing.T) {
	srv, database := newHarnessDB(t)
	defer srv.Close()
	c := &testClient{t: t, srv: srv, username: "loc-audit", password: "pw loc audit 01"}
	c.token, _, _ = regUser(t, c.username, c.password, "laptop", c.do)

	jan1 := utcMs(2025, 1, 1, 0, 0)
	jan15 := utcMs(2025, 1, 15, 10, 0)
	feb28 := utcMs(2025, 2, 28, 23, 59)

	// 用带独特标记的密文与块 id，事后在审计 detail 中 grep 其不存在。
	markerPlain := []byte("SECRET-COORDINATE-PAYLOAD-MARKER")
	markerB64 := base64.StdEncoding.EncodeToString(markerPlain)
	const markerID = "blk-unique-marker-1"
	block := map[string]any{
		"id": markerID, "start_ts": jan15, "end_ts": jan15 + 1000,
		"point_count": 7, "cipher": markerB64,
	}
	if code, out := c.do(http.MethodPost, "/api/v1/locations/batch",
		locBatch(block), true); code != http.StatusOK {
		t.Fatalf("上行失败: %d %v", code, out)
	}
	// 重提一次，制造 applied=0 skipped=1 的第二条 upload 审计。
	if code, _ := c.do(http.MethodPost, "/api/v1/locations/batch",
		locBatch(block), true); code != http.StatusOK {
		t.Fatal("重提失败")
	}
	q := fmt.Sprintf("?from=%d&to=%d", jan1, feb28)
	if code, _ := c.do(http.MethodGet, "/api/v1/locations"+q, nil, true); code != http.StatusOK {
		t.Fatal("GET 失败")
	}
	if code, out := c.do(http.MethodDelete, "/api/v1/locations"+q, nil, true); code != http.StatusOK ||
		out["deleted"].(float64) != 1 {
		t.Fatalf("DELETE 失败: %d %v", code, out)
	}

	// 精确断言：detail 只含计数/范围（格式串全等匹配，多余字段必然破坏相等性）。
	if up := auditDetails(t, database, "location.upload"); !hasExact(up, "blocks=1 applied=1 skipped=0") ||
		!hasExact(up, "blocks=1 applied=0 skipped=1") {
		t.Errorf("location.upload detail 不符: %v", up)
	}
	if dl := auditDetails(t, database, "location.download"); !hasExact(dl,
		fmt.Sprintf("from=%d to=%d blocks=1", jan1, feb28)) {
		t.Errorf("location.download detail 不符: %v", dl)
	}
	if del := auditDetails(t, database, "location.delete"); !hasExact(del,
		fmt.Sprintf("from=%d to=%d deleted=1", jan1, feb28)) {
		t.Errorf("location.delete detail 不符: %v", del)
	}

	// 泄漏断言：三类事件 detail 拼接后不得出现密文（base64/原始）或块 id。
	var all []string
	for _, ev := range []string{"location.upload", "location.download", "location.delete"} {
		all = append(all, auditDetails(t, database, ev)...)
	}
	joined := strings.Join(all, "|")
	for _, forbidden := range []string{markerB64, string(markerPlain), markerID} {
		if strings.Contains(joined, forbidden) {
			t.Errorf("审计 detail 泄漏敏感内容: %q（全部 detail: %s）", forbidden, joined)
		}
	}
}

// TestLocationUploadBroadcastsChange 验证上行成功后经 hub 广播 locations_changed（FR-7）。
func TestLocationUploadBroadcastsChange(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	c := &testClient{t: t, srv: srv, username: "loc-sse", password: "pw loc sse 01"}
	c.token, _, _ = regUser(t, c.username, c.password, "laptop", c.do)

	// 建立 SSE 订阅（对齐 TestPairingSSEBroadcast 模式）。
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events", nil)
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	scanner := bufio.NewScanner(resp.Body)
	got := make(chan string, 4)
	go func() {
		for scanner.Scan() {
			line := scanner.Text()
			if strings.HasPrefix(line, "event: ") {
				select {
				case got <- strings.TrimPrefix(line, "event: "):
				default:
				}
			}
		}
	}()

	jan15 := utcMs(2025, 1, 15, 10, 0)
	if code, out := c.do(http.MethodPost, "/api/v1/locations/batch",
		locBatch(locBlock("blk-sse", jan15, jan15+1000, 16)), true); code != http.StatusOK {
		t.Fatalf("上行失败: %d %v", code, out)
	}
	waitEvent(t, got, "locations_changed")
}

// TestLocationEndpointsRequireApprovedScope 覆盖鉴权：
// 无 token → 401；pending scope token → 403（device_pending）。
func TestLocationEndpointsRequireApprovedScope(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "loc-authz", password: "pw loc authz 01"}
	a.token, _, _ = regUser(t, a.username, a.password, "laptop", a.do)

	jan1 := utcMs(2025, 1, 1, 0, 0)
	feb28 := utcMs(2025, 2, 28, 23, 59)
	endpoints := []struct {
		method string
		path   string
	}{
		{http.MethodPost, "/api/v1/locations/batch"},
		{http.MethodGet, fmt.Sprintf("/api/v1/locations?from=%d&to=%d", jan1, feb28)},
		{http.MethodDelete, fmt.Sprintf("/api/v1/locations?from=%d&to=%d", jan1, feb28)},
	}

	// 无 token → 401。
	anon := &testClient{t: t, srv: srv}
	for _, ep := range endpoints {
		if code, _ := anon.do(ep.method, ep.path, nil, false); code != http.StatusUnauthorized {
			t.Errorf("无 token %s %s 应 401，实际 %d", ep.method, ep.path, code)
		}
	}

	// pending 设备 token → 403。
	b := &testClient{t: t, srv: srv, username: a.username, password: a.password}
	bAccess, _ := pendingLogin(t, b, "phone-pending", randBytes(t, 32))
	b.token = bAccess
	for _, ep := range endpoints {
		if code, _ := b.do(ep.method, ep.path, nil, true); code != http.StatusForbidden {
			t.Errorf("pending token %s %s 应 403，实际 %d", ep.method, ep.path, code)
		}
	}
}
