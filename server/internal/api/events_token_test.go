package api_test

// events 短期令牌与 SSE 双入口鉴权测试（Task 6 / TR-6.1、AC-8）。

import (
	"bufio"
	"net/http"
	"strings"
	"testing"
	"time"
)

// openSSE 建立一条 SSE 连接并返回事件类型通道（仅截取 "event: " 行）。
func openSSE(t *testing.T, url string, setHeader func(*http.Request)) (*http.Response, <-chan string) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	if setHeader != nil {
		setHeader(req)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		resp.Body.Close()
		t.Fatalf("SSE 建连应 200，实际 %d", resp.StatusCode)
	}
	ch := make(chan string, 8)
	go func() {
		defer close(ch)
		sc := bufio.NewScanner(resp.Body)
		sc.Buffer(make([]byte, 0, 64*1024), 1024*1024)
		for sc.Scan() {
			line := sc.Text()
			if strings.HasPrefix(line, "event: ") {
				select {
				case ch <- strings.TrimPrefix(line, "event: "):
				default:
				}
			}
		}
	}()
	return resp, ch
}

// TestEventsTokenMatrix TR-6.1：events 令牌签发、双入口建连收广播、
// 越权矩阵（events 令牌不能访问 JSON 端点、过期/伪造拒绝、pending 拒绝）。
func TestEventsTokenMatrix(t *testing.T) {
	srv, _, authSvc := newHarnessFull(t)
	defer srv.Close()

	a := &testClient{t: t, srv: srv, username: "wren", password: "pw-wren-77"}
	a.token, _, _ = regUser(t, "wren", a.password, "laptop", a.do)

	// approved 设备换取 events 令牌。
	code, out := a.do(http.MethodPost, "/api/v1/auth/events-token", nil, true)
	if code != http.StatusOK || out["events_token"] == "" || out["expires_in"].(float64) != 300 {
		t.Fatalf("events-token 应答不合法: %d %v", code, out)
	}
	eventsToken := out["events_token"].(string)

	// ?token= 入口建连，并由另一台设备的配对请求触发一条广播。
	resp, events := openSSE(t, srv.URL+"/api/v1/events?token="+eventsToken, nil)
	defer resp.Body.Close()
	b := &testClient{t: t, srv: srv, username: "wren", password: a.password}
	bPub := randBytes(t, 32)
	_, _ = pendingLogin(t, b, "watch", bPub)
	waitEvent(t, events, "device_pairing_requested")

	// events 令牌作 Authorization 访问 JSON 端点 → 401（typ 非 access）。
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/records?since=0", nil)
	req.Header.Set("Authorization", "Bearer "+eventsToken)
	r1, _ := http.DefaultClient.Do(req)
	r1.Body.Close()
	if r1.StatusCode != http.StatusUnauthorized {
		t.Fatalf("events 令牌访问 /records 应 401，实际 %d", r1.StatusCode)
	}
	// events 令牌放进 query 访问 JSON 端点 → 同样 401（query 令牌仅 /events 识别）。
	req2, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/records?token="+eventsToken, nil)
	r2, _ := http.DefaultClient.Do(req2)
	r2.Body.Close()
	if r2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("query events 令牌访问 /records 应 401，实际 %d", r2.StatusCode)
	}
	// Authorization approved access 入口仍可建连（向后兼容）。
	resp2, events2 := openSSE(t, srv.URL+"/api/v1/events", func(r *http.Request) {
		r.Header.Set("Authorization", "Bearer "+a.token)
	})
	defer resp2.Body.Close()
	select {
	case <-events2:
	case <-time.After(2 * time.Second):
		// 连接已建立（openSSE 内已断言 200），无新广播可等即通过。
	}

	// 过期 events 令牌 → 401（TTL 由内部方法构造，不经 HTTP 暴露）。
	expired, err := authSvc.IssueEventsTokenWithTTL("any-user", "any-device", -time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	req3, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events?token="+expired, nil)
	r3, _ := http.DefaultClient.Do(req3)
	r3.Body.Close()
	if r3.StatusCode != http.StatusUnauthorized {
		t.Fatalf("过期 events 令牌应 401，实际 %d", r3.StatusCode)
	}
	// 伪造令牌 → 401。
	req4, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events?token=forged.token.value", nil)
	r4, _ := http.DefaultClient.Do(req4)
	r4.Body.Close()
	if r4.StatusCode != http.StatusUnauthorized {
		t.Fatalf("伪造令牌应 401，实际 %d", r4.StatusCode)
	}
	// 无凭证 → 401。
	req5, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events", nil)
	r5, _ := http.DefaultClient.Do(req5)
	r5.Body.Close()
	if r5.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无凭证应 401，实际 %d", r5.StatusCode)
	}

	// pending access 走 Authorization 入口 → 403（SSE 仅 approved）。
	c := &testClient{t: t, srv: srv, username: "wren", password: a.password}
	cPub := randBytes(t, 32)
	cAccess, _ := pendingLogin(t, c, "fridge", cPub)
	req6, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events", nil)
	req6.Header.Set("Authorization", "Bearer "+cAccess)
	r6, _ := http.DefaultClient.Do(req6)
	r6.Body.Close()
	if r6.StatusCode != http.StatusForbidden {
		t.Fatalf("pending 设备订阅 SSE 应 403，实际 %d", r6.StatusCode)
	}
}
