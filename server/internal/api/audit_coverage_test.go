package api_test

// FR-25 审计目录全覆盖与敏感值泄漏检查（Task 6 / TR-6.4、AC-17）。
// 一条链路触发全部 14 类审计事件，随后：
//  1. 断言每类事件至少 1 行；
//  2. 全表 detail 不得包含 MK/主密码/恢复码/TOTP 码/验证器；
//  3. 接管 slog 默认输出，同样 grep 一遍。

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"log/slog"
	"net/http"
	"testing"

	"github.com/everything-personal/eve/internal/crypto"
)

// mkMaterials 构造某主密码下的完整登录材料（MK 由调用方持有，零知识协议不变）。
func mkMaterials(t *testing.T, password string, mk []byte) map[string]any {
	t.Helper()
	authSalt, err := crypto.NewSalt()
	if err != nil {
		t.Fatal(err)
	}
	kekSalt, _ := crypto.NewSalt()
	verifier, _ := crypto.DeriveKey(password, authSalt)
	kek, _ := crypto.DeriveKey(password, kekSalt)
	wrapped, err := crypto.WrapMasterKey(kek, mk)
	if err != nil {
		t.Fatal(err)
	}
	return map[string]any{
		"auth_salt": authSalt, "kek_salt": kekSalt,
		"auth_verifier": verifier, "wrapped_master_key": wrapped,
	}
}

// recoveryMaterialsFor 构造某恢复码下的恢复信封材料。
func recoveryMaterialsFor(t *testing.T, code string, mk []byte) map[string]any {
	t.Helper()
	recAuthSalt, _ := crypto.NewSalt()
	recKEKSalt, _ := crypto.NewSalt()
	recVerifier, _ := crypto.DeriveKey(code, recAuthSalt)
	rek, _ := crypto.DeriveKey(code, recKEKSalt)
	wrapped, err := crypto.WrapForRecovery(rek, mk)
	if err != nil {
		t.Fatal(err)
	}
	return map[string]any{
		"recovery_auth_salt": recAuthSalt, "recovery_kek_salt": recKEKSalt,
		"recovery_verifier": recVerifier, "wrapped_master_key_recovery": wrapped,
	}
}

// mergeMaps 合并多个 map 成一个 JSON 请求体。
func mergeMaps(parts ...map[string]any) map[string]any {
	out := map[string]any{}
	for _, p := range parts {
		for k, v := range p {
			out[k] = v
		}
	}
	return out
}

// authedJSON 构造带 Bearer 令牌的 JSON 请求。
func authedJSON(t *testing.T, method, url, token string, body map[string]any) *http.Request {
	t.Helper()
	req, err := http.NewRequest(method, url, jsonReader(body))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	return req
}

func TestAuditCoverageAndNoSecrets(t *testing.T) {
	// 接管 slog 默认输出，链路结束后一并 grep。
	var logBuf bytes.Buffer
	old := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&logBuf, nil)))
	t.Cleanup(func() { slog.SetDefault(old) })

	srv, database := newHarnessDB(t)
	defer srv.Close()

	const (
		username = "audituser-zx9"
		pw1      = "auditpw-secret-01"
		pw2      = "auditpw-secret-02"
		pw3      = "auditpw-secret-03"
		recCode1 = "recovery-code"     // regUser 固定恢复码
		recCode2 = "recovery-code-two" // reset 后轮换的新恢复码
		badCode  = "424242"            // 故意错误的 TOTP 码
	)

	// 1) register + login（复用注册公钥走一次 approved 登录）。
	a := &testClient{t: t, srv: srv, username: username, password: pw1}
	firstAccess, aPub, mk := regUser(t, username, pw1, "laptop-A", a.do)
	a.token = firstAccess
	login := approvedLogin(t, a, "laptop-A", aPub)
	a.token = login["access_token"].(string)

	// 2) login_failed：错误主密码恰好 1 次（后续成功操作会清零限流计数）。
	_, params := a.do(http.MethodGet, "/api/v1/auth/parameters?username="+username, nil, false)
	badVerifier, _ := crypto.DeriveKey("totally-wrong-password", mustB64(t, params["auth_salt"]))
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": username, "auth_verifier": badVerifier,
		"device_name": "x", "device_public_key": randBytes(t, 32),
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("错误密码应 401，实际 %d", code)
	}

	// 3) 新设备 B：pending → 批准（盒材料随机，审计只覆盖事件存在性）。
	b := &testClient{t: t, srv: srv, username: username, password: pw1}
	bPub := randBytes(t, 32)
	bAccess, bPid := pendingLogin(t, b, "phone-B", bPub)
	b.token = bAccess
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+bPid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
		"wrapped_master_key": randBytes(t, 40),
	}, true); code != http.StatusOK {
		t.Fatal("批准 B 失败")
	}
	_, bSt := b.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	bTokens := bSt["tokens"].(map[string]any)
	bDeviceID := bTokens["device_id"].(string)
	b.token = bTokens["access_token"].(string)

	// 4) 新设备 C：pending → 拒绝。
	c := &testClient{t: t, srv: srv, username: username, password: pw1}
	cPub := randBytes(t, 32)
	_, cPid := pendingLogin(t, c, "tablet-C", cPub)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+cPid+"/reject", nil, true); code != http.StatusOK {
		t.Fatal("拒绝 C 失败")
	}

	// 5) 吊销 B → device_revoked。
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/devices/"+bDeviceID+"/revoke", nil, true); code != http.StatusOK {
		t.Fatalf("吊销 B 失败: %d", code)
	}

	// 6) TOTP：setup/enable → mfa 登录后错码 verify（totp_verify_failed）→ disable。
	_, setup := a.do(http.MethodPost, "/api/v1/auth/totp/setup", nil, true)
	secret := setup["secret"].(string)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/enable", map[string]any{
		"code": codeNow(t, secret),
	}, true); code != http.StatusOK {
		t.Fatalf("enable 应成功: %d", code)
	}
	_, mfaOut := mfaLogin(t, a, "laptop-A", randBytes(t, 32))
	mfaToken := mfaOut["mfa_token"].(string)
	resp, err := http.DefaultClient.Do(authedJSON(t, http.MethodPost,
		srv.URL+"/api/v1/auth/totp/verify", mfaToken, map[string]any{
			"code": badCode, "device_public_key": randBytes(t, 32), "device_name": "x",
		}))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("错码 verify 应 401，实际 %d", resp.StatusCode)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/totp/disable", map[string]any{
		"code": codeNow(t, secret),
	}, true); code != http.StatusOK {
		t.Fatalf("disable 应成功: %d", code)
	}

	// 7) 恢复：错误恢复码 → 成功 start → reset 新密码 + 新恢复码。
	recSalt := mustB64(t, params["recovery_auth_salt"])
	wrongREK, _ := crypto.DeriveKey("wrong-recovery-code", recSalt)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": username, "recovery_verifier": wrongREK,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("错误恢复码应 401，实际 %d", code)
	}
	rightREK, _ := crypto.DeriveKey(recCode1, recSalt)
	code, recStart := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": username, "recovery_verifier": rightREK,
	}, false)
	if code != http.StatusOK {
		t.Fatalf("恢复 start 失败: %d", code)
	}
	recoveryToken := recStart["recovery_token"].(string)
	// 真实客户端：MK 从恢复信封解出保持不变；这里直接复用原 MK 包裹新密码。
	resetBody := mergeMaps(
		mkMaterials(t, pw2, mk),
		recoveryMaterialsFor(t, recCode2, mk),
		map[string]any{
			"device_public_key": randBytes(t, 32),
			"device_name":       "recovery-laptop",
		})
	resp2, err := http.DefaultClient.Do(authedJSON(t, http.MethodPost,
		srv.URL+"/api/v1/auth/recovery/reset", recoveryToken, resetBody))
	if err != nil {
		t.Fatal(err)
	}
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("recovery reset 应 200，实际 %d", resp2.StatusCode)
	}
	resetOut := decodeBody(t, resp2)
	resp2.Body.Close()
	resetAccess := resetOut["access_token"].(string)

	// 8) password_change：reset 后的设备再改成 pw3（不轮换恢复材料）。
	a.token = resetAccess
	if code, chg := a.do(http.MethodPost, "/api/v1/auth/password/change",
		mkMaterials(t, pw3, mk), true); code != http.StatusOK {
		t.Fatalf("改密失败: %d %v", code, chg)
	}

	// ---- 断言 1：FR-25 十四类事件全部存在 ----
	required := []string{
		"register", "login", "login_failed",
		"recovery_start", "recovery_start_failed",
		"password_reset", "password_change",
		"totp_enabled", "totp_disabled", "totp_verify_failed",
		"device_pair_requested", "device_approved", "device_rejected", "device_revoked",
	}
	for _, ev := range required {
		var n int
		if err := database.QueryRow(
			`SELECT COUNT(1) FROM audit_logs WHERE event=?`, ev).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n == 0 {
			t.Errorf("审计事件 %s 至少应有 1 行", ev)
		}
	}

	// ---- 断言 2：detail 全表敏感值 grep ----
	var details string
	if err := database.QueryRow(`SELECT COALESCE(GROUP_CONCAT(detail, '|'), '') FROM audit_logs`).
		Scan(&details); err != nil {
		t.Fatal(err)
	}
	forbidden := []string{
		pw1, pw2, pw3,
		recCode1, recCode2,
		badCode,
		base64.StdEncoding.EncodeToString(mk),
		hex.EncodeToString(mk),
		"verifier", // 任何验证器字段名都不该进 detail
	}
	for _, s := range forbidden {
		if s != "" && bytes.Contains([]byte(details), []byte(s)) {
			t.Errorf("audit detail 出现敏感串: %q", s)
		}
	}

	// ---- 断言 3：slog 输出同样不得含敏感串 ----
	logText := logBuf.String()
	for _, s := range []string{pw1, pw2, pw3, recCode1, recCode2, badCode,
		base64.StdEncoding.EncodeToString(mk), hex.EncodeToString(mk)} {
		if s != "" && bytes.Contains([]byte(logText), []byte(s)) {
			t.Errorf("服务端日志出现敏感串: %q", s)
		}
	}
}
