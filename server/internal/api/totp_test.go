package api_test

// TOTP 二次验证端到端测试（Task 5 / AC-7）。

import (
	"net/http"
	"strings"
	"testing"
	"time"

	otpotp "github.com/pquerna/otp/totp"
)

// codeNow 生成当前 30s 窗口的 6 位 TOTP 码（±1 窗口由服务端容差）。
func codeNow(t *testing.T, secret string) string {
	t.Helper()
	code, err := otpotp.GenerateCode(secret, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	return code
}

// mfaLogin 提交密码登录并返回原始响应（不假设状态）。
func mfaLogin(t *testing.T, c *testClient, deviceName string, devPub []byte) (int, map[string]any) {
	t.Helper()
	v := loginVerifier(t, c.do, c.username, c.password)
	return c.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": c.username, "auth_verifier": v,
		"device_name": deviceName, "device_public_key": devPub,
	}, false)
}

// TestTOTPSetupEnableDisable TR-5.1：setup/enable 错误码不置确认，正确码启用，重复 setup 冲突。
func TestTOTPSetupEnableDisable(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "nora", password: "pw-nora-01"}
	a.token, _, _ = regUser(t, "nora", a.password, "laptop", a.do)

	// 初始状态。
	_, st := a.do(http.MethodGet, "/api/v1/auth/totp", nil, true)
	if st["enabled"] != false {
		t.Fatalf("新用户不应启用 TOTP: %v", st)
	}
	// setup 返回密钥与二维码。
	code, setup := a.do(http.MethodPost, "/api/v1/auth/totp/setup", nil, true)
	if code != http.StatusOK {
		t.Fatalf("setup 失败: %d %v", code, setup)
	}
	secret := setup["secret"].(string)
	if secret == "" || !strings.HasPrefix(setup["qr_data_uri"].(string), "data:image/png;base64,") {
		t.Fatalf("setup 应返回 secret 与二维码 data URI: %v", setup)
	}
	if !strings.Contains(setup["otpauth_url"].(string), "Everything:nora") {
		t.Fatalf("otpauth URI 应含 issuer 与账号: %v", setup["otpauth_url"])
	}
	// 错误码 enable → 401，状态仍未启用。
	if code, out := a.do(http.MethodPost, "/api/v1/auth/totp/enable", map[string]any{
		"code": "000000",
	}, true); code != http.StatusUnauthorized || out["error"] != "invalid_totp" {
		t.Fatalf("错误验证码应 401 invalid_totp，实际 %d %v", code, out)
	}
	_, st2 := a.do(http.MethodGet, "/api/v1/auth/totp", nil, true)
	if st2["enabled"] != false {
		t.Fatal("enable 失败后不应启用")
	}
	// 正确码 enable。
	if code, out := a.do(http.MethodPost, "/api/v1/auth/totp/enable", map[string]any{
		"code": codeNow(t, secret),
	}, true); code != http.StatusOK {
		t.Fatalf("正确码 enable 应成功: %d %v", code, out)
	}
	_, st3 := a.do(http.MethodGet, "/api/v1/auth/totp", nil, true)
	if st3["enabled"] != true {
		t.Fatal("应已启用")
	}
	// 已启用再 setup → 409。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/setup", nil, true); code != http.StatusConflict {
		t.Fatalf("已启用后 setup 应 409，实际 %d", code)
	}
	returnSecret := secret
	// disable 错误码拒绝；正确码关闭。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/disable", map[string]any{
		"code": "111111",
	}, true); code != http.StatusUnauthorized {
		t.Fatalf("disable 错码应 401，实际 %d", code)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/disable", map[string]any{
		"code": codeNow(t, returnSecret),
	}, true); code != http.StatusOK {
		t.Fatalf("正确码 disable 应成功，实际 %d", code)
	}
	_, st4 := a.do(http.MethodGet, "/api/v1/auth/totp", nil, true)
	if st4["enabled"] != false {
		t.Fatal("disable 后应关闭")
	}
}

// TestTOTPLoginMFA TR-5.2/5.3：启用后登录需 MFA；错码限流；mfa 令牌权限隔离；新设备走 pending。
func TestTOTPLoginMFA(t *testing.T) {
	srv, database := newHarnessDB(t)
	defer srv.Close()
	const pw = "pw-olive-09"
	a := &testClient{t: t, srv: srv, username: "olive", password: pw}
	_, aPub, _ := regUser(t, "olive", pw, "laptop", a.do)
	a.token = approvedLogin(t, a, "laptop", aPub)["access_token"].(string)

	// 启用 TOTP。
	_, setup := a.do(http.MethodPost, "/api/v1/auth/totp/setup", nil, true)
	secret := setup["secret"].(string)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/enable", map[string]any{
		"code": codeNow(t, secret),
	}, true); code != http.StatusOK {
		t.Fatal("enable 失败")
	}

	// 仅密码登录 → mfa_required，响应不含任何 wrapped 材料/设备信息。
	status, login := mfaLogin(t, a, "laptop", aPub)
	if status != http.StatusOK || login["status"] != "mfa_required" {
		t.Fatalf("应 mfa_required，实际 %d %v", status, login)
	}
	mfaToken := login["mfa_token"].(string)
	if mfaToken == "" {
		t.Fatal("应返回 mfa_token")
	}
	for _, leak := range []string{"bundle", "pending", "wrapped_master_key"} {
		if login[leak] != nil {
			t.Fatalf("mfa_required 响应不得含 %s", leak)
		}
	}

	// TR-5.3：mfa 令牌不能当 access 用；伪造令牌调 verify → 401；access 调 verify → 401。
	req, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/records?since=0", nil)
	req.Header.Set("Authorization", "Bearer "+mfaToken)
	resp, _ := http.DefaultClient.Do(req)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("mfa 令牌访问 records 应 401，实际 %d", resp.StatusCode)
	}
	verify := func(token string, body map[string]any) (int, map[string]any) {
		req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/auth/totp/verify", jsonReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		r, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		out := decodeBody(t, r)
		r.Body.Close()
		return r.StatusCode, out
	}
	if code, _ := verify("not-a-jwt", map[string]any{"code": "123456", "device_public_key": aPub}); code != http.StatusUnauthorized {
		t.Fatalf("伪造 mfa 令牌应 401，实际 %d", code)
	}
	if code, _ := verify(a.token, map[string]any{"code": "123456", "device_public_key": aPub}); code != http.StatusUnauthorized {
		t.Fatalf("access 令牌调 verify 应 401，实际 %d", code)
	}

	// 错码 → 401；连续 5 次错码后第 6 次（码正确）429。
	for i := 0; i < 5; i++ {
		// 每次错码后 mfa token 仍有效（5 分钟内），限流按 IP 计数。
		if code, out := verify(mfaToken, map[string]any{
			"code": "999999", "device_public_key": aPub,
		}); code != http.StatusUnauthorized || out["error"] != "invalid_totp" {
			t.Fatalf("第 %d 次错码应 401 invalid_totp，实际 %d %v", i+1, code, out)
		}
	}
	if code, _ := verify(mfaToken, map[string]any{
		"code": codeNow(t, secret), "device_public_key": aPub,
	}); code != http.StatusTooManyRequests {
		t.Fatalf("第 6 次即使码正确也应 429，实际 %d", code)
	}
	// 审计必须有错码行（且 detail 不得包含验证码本身）。
	var n int
	if err := database.QueryRow(
		`SELECT COUNT(1) FROM audit_logs WHERE event='totp_verify_failed'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n < 5 {
		t.Fatalf("totp_verify_failed 审计至少 5 行，实际 %d", n)
	}
	var leaked int
	if err := database.QueryRow(
		`SELECT COUNT(1) FROM audit_logs WHERE detail LIKE '%999999%' OR detail LIKE '%verifier%'`).
		Scan(&leaked); err != nil {
		t.Fatal(err)
	}
	if leaked != 0 {
		t.Fatal("审计 detail 不得包含验证码/验证器明文")
	}
}

// TestTOTPVerifyDeviceBranch 独立服务器验证 verify 通过后的设备分支（避开限流污染）。
func TestTOTPVerifyDeviceBranch(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	const pw = "pw-paul-03"
	a := &testClient{t: t, srv: srv, username: "paul", password: pw}
	aAccess, aPub, _ := regUser(t, "paul", pw, "laptop", a.do)
	a.token = aAccess
	_, setup := a.do(http.MethodPost, "/api/v1/auth/totp/setup", nil, true)
	secret := setup["secret"].(string)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/enable", map[string]any{
		"code": codeNow(t, secret),
	}, true); code != http.StatusOK {
		t.Fatal("enable 失败")
	}

	// 已批准设备：mfa_required → verify 正确码 → approved 直通。
	_, login := mfaLogin(t, a, "laptop", aPub)
	mfaToken := login["mfa_token"].(string)
	req, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/auth/totp/verify", jsonReader(map[string]any{
		"code": codeNow(t, secret), "device_public_key": aPub, "device_name": "laptop",
	}))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+mfaToken)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	out := decodeBody(t, resp)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || out["status"] != "approved" {
		t.Fatalf("已批准设备 verify 后应 approved，实际 %d %v", resp.StatusCode, out)
	}

	// 全新设备：mfa_required → verify → pending（TOTP 通过不豁免设备审批）。
	d := &testClient{t: t, srv: srv, username: "paul", password: pw}
	dPub := randBytes(t, 32)
	_, login2 := mfaLogin(t, d, "watch", dPub)
	req2, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/auth/totp/verify", jsonReader(map[string]any{
		"code": codeNow(t, secret), "device_public_key": dPub, "device_name": "watch",
	}))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("Authorization", "Bearer "+login2["mfa_token"].(string))
	resp2, _ := http.DefaultClient.Do(req2)
	out2 := decodeBody(t, resp2)
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK || out2["status"] != "pending" {
		t.Fatalf("新设备 verify 后应 pending，实际 %d %v", resp2.StatusCode, out2)
	}
	// A 的待审批列表能看到该设备。
	if code, list := a.do(http.MethodGet, "/api/v1/auth/pairings", nil, true); code != http.StatusOK ||
		len(list["pairings"].([]any)) != 1 {
		t.Fatalf("应出现 1 条 TOTP 后的配对请求: %d %v", code, list)
	}
}
