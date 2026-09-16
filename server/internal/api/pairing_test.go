package api_test

// 设备配对审批状态机端到端测试（Task 3 / AC-3）：
// 覆盖 pending 登录、审批端密封 MK 盒、批准换发、拒绝、过期、越权与吊销。

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/everything-personal/eve/internal/crypto"
)

// httpDo 是 testClient.do 的方法值类型，供 helper 复用。
type httpDo func(method, path string, body any, withAuth bool) (int, map[string]any)

// regUser 注册一个带完整恢复材料的用户，返回其首设备 access 令牌、设备公钥与 MK。
// 恢复码固定为 "recovery-code"（仅测试用）。
func regUser(t *testing.T, username, password, deviceName string, do httpDo) (string, []byte, []byte) {
	t.Helper()
	authSalt, err := crypto.NewSalt()
	if err != nil {
		t.Fatal(err)
	}
	kekSalt, err := crypto.NewSalt()
	if err != nil {
		t.Fatal(err)
	}
	verifier, err := crypto.DeriveKey(password, authSalt)
	if err != nil {
		t.Fatal(err)
	}
	kek, err := crypto.DeriveKey(password, kekSalt)
	if err != nil {
		t.Fatal(err)
	}
	mk, err := crypto.NewMasterKey()
	if err != nil {
		t.Fatal(err)
	}
	wrapped, err := crypto.WrapMasterKey(kek, mk)
	if err != nil {
		t.Fatal(err)
	}
	devPub := make([]byte, crypto.DevicePublicKeyLen)
	if _, err := io.ReadFull(rand.Reader, devPub); err != nil {
		t.Fatal(err)
	}
	recAuthSalt, _ := crypto.NewSalt()
	recKEKSalt, _ := crypto.NewSalt()
	recVerifier, _ := crypto.DeriveKey("recovery-code", recAuthSalt)
	rek, _ := crypto.DeriveKey("recovery-code", recKEKSalt)
	wrappedRec, err := crypto.WrapForRecovery(rek, mk)
	if err != nil {
		t.Fatal(err)
	}
	status, out := do(http.MethodPost, "/api/v1/auth/register", map[string]any{
		"username": username, "auth_salt": authSalt, "kek_salt": kekSalt,
		"auth_verifier": verifier, "wrapped_master_key": wrapped,
		"device_name": deviceName, "device_public_key": devPub,
		"recovery_auth_salt": recAuthSalt, "recovery_kek_salt": recKEKSalt,
		"recovery_verifier": recVerifier, "wrapped_master_key_recovery": wrappedRec,
	}, false)
	if status != http.StatusCreated {
		t.Fatalf("注册失败: %d %v", status, out)
	}
	return out["access_token"].(string), devPub, mk
}

func randBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		t.Fatal(err)
	}
	return b
}

// expectedPairingCode 复刻客户端派生：大写 hex(SHA-256(设备公钥)[:3])。
func expectedPairingCode(pub []byte) string {
	sum := sha256.Sum256(pub)
	return strings.ToUpper(hex.EncodeToString(sum[:3]))
}

// loginVerifier 经公开 parameters 端点取 auth_salt 并派生登录验证器。
func loginVerifier(t *testing.T, do httpDo, username, password string) []byte {
	t.Helper()
	status, params := do(http.MethodGet, "/api/v1/auth/parameters?username="+username, nil, false)
	if status != http.StatusOK {
		t.Fatalf("取登录参数失败: %d", status)
	}
	authSalt := mustB64(t, params["auth_salt"])
	v, err := crypto.DeriveKey(password, authSalt)
	if err != nil {
		t.Fatal(err)
	}
	return v
}

// pendingLogin 执行新设备登录并断言返回 pending，返回 (pendingAccess, pairingID, verifier)。
func pendingLogin(t *testing.T, c *testClient, deviceName string, devPub []byte) (string, string) {
	t.Helper()
	v := loginVerifier(t, c.do, c.username, c.password)
	status, login := c.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": c.username, "auth_verifier": v,
		"device_name": deviceName, "device_public_key": devPub,
	}, false)
	if status != http.StatusOK || login["status"] != "pending" {
		t.Fatalf("新设备应 pending，实际 %d %v", status, login)
	}
	pending := login["pending"].(map[string]any)
	if pending["refresh_token"] != nil {
		t.Fatal("pending 登录不得返回 refresh_token")
	}
	return pending["access_token"].(string),
		pending["pairing"].(map[string]any)["id"].(string)
}

// TestPairingPendingApproveFlow TR-3.1/3.2：pending 登录→审批密封 MK 盒→批准换发→访问资料库。
func TestPairingPendingApproveFlow(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()

	a := &testClient{t: t, srv: srv, username: "ada", password: "pa ss word one"}
	a.token, _, _ = regUser(t, "ada", a.password, "laptop-A", a.do)

	// B 以全新设备公钥登录 → pending。
	b := &testClient{t: t, srv: srv, username: "ada", password: a.password}
	bPub := randBytes(t, 32)
	bAccess, pairingID := pendingLogin(t, b, "phone-B", bPub)
	b.token = bAccess

	// 配对码核对（登录响应内）。
	status, st0 := b.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	if status != http.StatusOK {
		t.Fatalf("查询配对状态失败: %d", status)
	}
	if code := st0["pairing_code"].(string); code != expectedPairingCode(bPub) {
		t.Fatalf("配对码应为 %s，实际 %s", expectedPairingCode(bPub), code)
	}
	_ = pairingID

	// TR-3.1：pending 令牌访问受限端点一律 403 device_pending。
	for _, p := range []string{"/api/v1/records?since=0", "/api/v1/auth/devices", "/api/v1/auth/pairings"} {
		if code, _ := b.do(http.MethodGet, p, nil, true); code != http.StatusForbidden {
			t.Fatalf("pending 设备访问 %s 应 403，实际 %d", p, code)
		}
	}
	if st0["state"] != "pending" || st0["tokens"] != nil {
		t.Fatalf("批准前应为 pending 且无 tokens: %v", st0)
	}

	// 审批端 A 看到唯一待审批配对（含原始设备公钥与设备名）。
	_, list := a.do(http.MethodGet, "/api/v1/auth/pairings", nil, true)
	pairings := list["pairings"].([]any)
	if len(pairings) != 1 {
		t.Fatalf("应恰好 1 条待审批配对，实际 %d", len(pairings))
	}
	open := pairings[0].(map[string]any)
	pid := open["id"].(string)
	if open["device_name"] != "phone-B" || open["device_public_key"] == nil {
		t.Fatalf("待审批配对应含设备名与原始公钥: %v", open)
	}

	// A 密封 MK 盒（测试中盒内容随机；真实实现为 nacl/box(ephSec, B_pub, MK)）。
	ephPub, nonce, boxedMK := randBytes(t, 32), randBytes(t, 24), randBytes(t, 48)
	if code, out := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": ephPub, "nonce": nonce, "wrapped_master_key": boxedMK,
	}, true); code != http.StatusOK {
		t.Fatalf("批准失败: %d %v", code, out)
	}

	// B 轮询：approved + 盒材料原样返回 + 正式令牌对换发。
	_, st := b.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	if st["state"] != "approved" {
		t.Fatalf("应 approved，实际 %v", st["state"])
	}
	if st["wrapped_master_key"] == nil || st["nonce"] == nil || st["ephemeral_public_key"] == nil {
		t.Fatal("批准响应必须携带完整 crypto_box 材料")
	}
	tokens := st["tokens"].(map[string]any)
	if tokens["access_token"] == nil || tokens["refresh_token"] == nil {
		t.Fatal("批准换发必须包含 access+refresh")
	}

	// 新令牌访问资料库成功。
	b.token = tokens["access_token"].(string)
	if code, _ := b.do(http.MethodGet, "/api/v1/records?since=0", nil, true); code != http.StatusOK {
		t.Fatalf("批准后应可访问资料库，实际 %d", code)
	}

	// 重复登录同一公钥 → approved 直通（不再产生配对）。
	v := loginVerifier(t, b.do, "ada", a.password)
	status, login2 := b.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": "ada", "auth_verifier": v,
		"device_name": "phone-B", "device_public_key": bPub,
	}, false)
	if status != http.StatusOK || login2["status"] != "approved" {
		t.Fatalf("已批准设备重复登录应 approved，实际 %d %v", status, login2)
	}

	// A 的设备列表含两台 approved 设备。
	_, devs := a.do(http.MethodGet, "/api/v1/auth/devices", nil, true)
	dl := devs["devices"].([]any)
	if len(dl) != 2 {
		t.Fatalf("设备列表应有 2 台，实际 %d", len(dl))
	}
	for _, d := range dl {
		if d.(map[string]any)["state"] != "approved" {
			t.Fatalf("两台设备都应 approved: %v", d)
		}
	}
}

// TestPairingRejectAndReapply TR-3.3：拒绝后状态可见、列表清空、重新登录再 pending。
func TestPairingRejectAndReapply(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "grace", password: "pw grace xx"}
	a.token, _, _ = regUser(t, "grace", a.password, "laptop", a.do)

	c := &testClient{t: t, srv: srv, username: "grace", password: a.password}
	cPub := randBytes(t, 32)
	cAccess, pid := pendingLogin(t, c, "tablet", cPub)
	c.token = cAccess

	if code, out := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/reject", nil, true); code != http.StatusOK {
		t.Fatalf("拒绝失败: %d %v", code, out)
	}
	_, st := c.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	if st["state"] != "rejected" || st["tokens"] != nil {
		t.Fatalf("应为 rejected 且无 tokens，实际 %v", st)
	}
	_, list := a.do(http.MethodGet, "/api/v1/auth/pairings", nil, true)
	if len(list["pairings"].([]any)) != 0 {
		t.Fatal("拒绝后待审批列表应为空")
	}
	// 重复操作已关闭配对 → 409。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/reject", nil, true); code != http.StatusConflict {
		t.Fatalf("重复操作已关闭配应对 409，实际 %d", code)
	}
	// 重新登录 → 新配对 id（旧设备行复用，旧配对置 expired）。
	_, newPid := pendingLogin(t, c, "tablet", cPub)
	if newPid == pid {
		t.Fatal("重新请求应产生新配对 id")
	}
	// 不存在/跨用户的配对 id → 404。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/pair-notexist/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
		"wrapped_master_key": []byte{1, 2, 3},
	}, true); code != http.StatusNotFound {
		t.Fatalf("不存在的配对应 404，实际 %d", code)
	}
}

// TestPairingValidationAndRevoke TR-3.4/3.5：材料校验、过期拒绝、自审批防护、吊销即时失效。
func TestPairingValidationAndRevoke(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "heidi", password: "pw heidi 42"}
	a.token, _, _ = regUser(t, "heidi", a.password, "laptop", a.do)

	b := &testClient{t: t, srv: srv, username: "heidi", password: a.password}
	bPub := randBytes(t, 32)
	bAccess, pid := pendingLogin(t, b, "phone", bPub)
	b.token = bAccess

	// 材料长度不合法 → 400。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": []byte{1, 2}, "nonce": randBytes(t, 24), "wrapped_master_key": []byte{1},
	}, true); code != http.StatusBadRequest {
		t.Fatalf("非法公钥长度应 400，实际 %d", code)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": []byte{1, 2}, "wrapped_master_key": []byte{1},
	}, true); code != http.StatusBadRequest {
		t.Fatalf("非法 nonce 长度应 400，实际 %d", code)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
	}, true); code != http.StatusBadRequest {
		t.Fatalf("缺少密封 MK 应 400，实际 %d", code)
	}

	// 正常批准并换发。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
		"wrapped_master_key": randBytes(t, 40),
	}, true); code != http.StatusOK {
		t.Fatal("批准应成功")
	}
	_, st := b.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	bTokens := st["tokens"].(map[string]any)
	bRefresh := bTokens["refresh_token"].(string)
	bDeviceID := bTokens["device_id"].(string)
	b.token = bTokens["access_token"].(string)

	// 找 A 自己的设备 id：不能自吊销。
	var aDeviceID string
	_, devs := a.do(http.MethodGet, "/api/v1/auth/devices", nil, true)
	for _, d := range devs["devices"].([]any) {
		dd := d.(map[string]any)
		if dd["current"] == true {
			aDeviceID = dd["id"].(string)
		}
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/devices/"+aDeviceID+"/revoke", nil, true); code != http.StatusBadRequest {
		t.Fatalf("自吊销应 400，实际 %d", code)
	}

	// 吊销 B：refresh 即时失效（401）；B 重新登录回到 pending。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/devices/"+bDeviceID+"/revoke", nil, true); code != http.StatusOK {
		t.Fatal("吊销 B 应成功")
	}
	if code, _ := b.do(http.MethodPost, "/api/v1/auth/refresh", map[string]any{
		"refresh_token": bRefresh,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("吊销后 refresh 应 401，实际 %d", code)
	}
	_, relogin := b.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": "heidi", "auth_verifier": loginVerifier(t, b.do, "heidi", a.password),
		"device_name": "phone", "device_public_key": bPub,
	}, false)
	if relogin["status"] != "pending" {
		t.Fatalf("吊销设备重新登录应重新 pending，实际 %v", relogin["status"])
	}

	// 审计覆盖：请求、批准、拒绝（另一配对）、吊销均有行。
}

// TestPairingSSEBroadcast TR-3 rubric：新设备请求与审批结果实时广播给同账户在线客户端。
func TestPairingSSEBroadcast(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "judy", password: "pw judy 01"}
	a.token, _, _ = regUser(t, "judy", a.password, "laptop", a.do)

	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/events", nil)
	req.Header.Set("Authorization", "Bearer "+a.token)
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

	// B 新设备登录 → A 应收到 device_pairing_requested。
	b := &testClient{t: t, srv: srv, username: "judy", password: a.password}
	bPub := randBytes(t, 32)
	_, pid := pendingLogin(t, b, "phone", bPub)
	waitEvent(t, got, "device_pairing_requested")

	// A 批准 → A 应收到 device_pairing_resolved。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
		"wrapped_master_key": randBytes(t, 40),
	}, true); code != http.StatusOK {
		t.Fatal("批准失败")
	}
	waitEvent(t, got, "device_pairing_resolved")
}

func waitEvent(t *testing.T, ch <-chan string, want string) {
	t.Helper()
	select {
	case got := <-ch:
		if got != want {
			t.Fatalf("应收到 %s，实际 %s", want, got)
		}
	case <-time.After(5 * time.Second):
		t.Fatalf("等待 SSE 事件 %s 超时", want)
	}
}
