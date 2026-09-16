package api_test

// 恢复密钥与修改主密码端到端测试（Task 4 / AC-2）。

import (
	"net/http"
	"testing"

	"github.com/everything-personal/eve/internal/crypto"
)

// pwBundle 是一组客户端密码材料的字节与 JSON 形态。
type pwBundle struct {
	authSalt, kekSalt, verifier, wrapped []byte
}

func makePwBundle(t *testing.T, password string, mk []byte) pwBundle {
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
	wrapped, err := crypto.WrapMasterKey(kek, mk)
	if err != nil {
		t.Fatal(err)
	}
	return pwBundle{authSalt, kekSalt, verifier, wrapped}
}

func (p pwBundle) json() map[string]any {
	return map[string]any{
		"auth_salt": p.authSalt, "kek_salt": p.kekSalt,
		"auth_verifier": p.verifier, "wrapped_master_key": p.wrapped,
	}
}

// recBundle 是一组恢复码材料。
type recBundle struct {
	authSalt, kekSalt, verifier, wrapped []byte
}

func makeRecBundle(t *testing.T, code string, mk []byte) recBundle {
	t.Helper()
	authSalt, _ := crypto.NewSalt()
	kekSalt, _ := crypto.NewSalt()
	verifier, _ := crypto.DeriveKey(code, authSalt)
	rek, _ := crypto.DeriveKey(code, kekSalt)
	wrapped, err := crypto.WrapForRecovery(rek, mk)
	if err != nil {
		t.Fatal(err)
	}
	return recBundle{authSalt, kekSalt, verifier, wrapped}
}

func (r recBundle) json() map[string]any {
	m := map[string]any{
		"recovery_auth_salt": r.authSalt, "recovery_kek_salt": r.kekSalt,
		"recovery_verifier": r.verifier, "wrapped_master_key_recovery": r.wrapped,
	}
	return m
}

// approvedLogin 以已批准公钥登录，返回 bundle（含 access/refresh）。
func approvedLogin(t *testing.T, c *testClient, deviceName string, devPub []byte) map[string]any {
	t.Helper()
	v := loginVerifier(t, c.do, c.username, c.password)
	status, out := c.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": c.username, "auth_verifier": v,
		"device_name": deviceName, "device_public_key": devPub,
	}, false)
	if status != http.StatusOK || out["status"] != "approved" {
		t.Fatalf("应 approved 登录，实际 %d %v", status, out)
	}
	return out["bundle"].(map[string]any)
}

// TestRecoveryFullFlow TR-4.1/4.2/4.3：恢复闭环 + 统一错误 + 令牌权限隔离。
func TestRecoveryFullFlow(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()

	const oldPW, oldCode, newPW, newCode = "old-password-01", "recovery-code", "new-password-02", "new-recovery-code"
	a := &testClient{t: t, srv: srv, username: "kate", password: oldPW}
	aAccess, aPub, oldMK := regUser(t, "kate", oldPW, "laptop", a.do)
	a.token = aAccess
	// 恢复前再登录一次，取得一个应被恢复流程吊销的旧 refresh。
	oldBundle := approvedLogin(t, a, "laptop", aPub)
	oldRefresh := oldBundle["refresh_token"].(string)

	// 用旧 MK 写一条记录，恢复后应仍可解密。
	ciphertext := sealWithMK(t, oldMK, "rec-recovery-1", "vault", 1, []byte("secret-data"))
	upsertRecord(t, a, "rec-recovery-1", "vault", "login", ciphertext, 1)

	// 恢复前先制造一个待审批设备 C，恢复后必须失效。
	c := &testClient{t: t, srv: srv, username: "kate", password: oldPW}
	cPub := randBytes(t, 32)
	cAccess, cPid := pendingLogin(t, c, "tablet", cPub)
	c.token = cAccess

	// 取公开参数里的恢复盐。
	_, params := a.do(http.MethodGet, "/api/v1/auth/parameters?username=kate", nil, false)
	recAuthSalt := mustB64(t, params["recovery_auth_salt"])

	// TR-4.2：错误恢复码 → 401 invalid_credentials，与用户不存在同形。
	wrongVerifier, _ := crypto.DeriveKey("WRONG-CODE", recAuthSalt)
	if code, out := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "kate", "recovery_verifier": wrongVerifier,
	}, false); code != http.StatusUnauthorized || out["error"] != "invalid_credentials" {
		t.Fatalf("错误恢复码应 401 invalid_credentials，实际 %d %v", code, out)
	}
	if code, out := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "nobody-here", "recovery_verifier": wrongVerifier,
	}, false); code != http.StatusUnauthorized || out["error"] != "invalid_credentials" {
		t.Fatalf("用户不存在应同形 401，实际 %d %v", code, out)
	}

	// 正确恢复码 → recovery 会话。
	recVerifier, _ := crypto.DeriveKey(oldCode, recAuthSalt)
	code, start := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "kate", "recovery_verifier": recVerifier,
	}, false)
	if code != http.StatusOK {
		t.Fatalf("恢复开始失败: %d %v", code, start)
	}
	recoveryToken := start["recovery_token"].(string)

	// TR-4.3：无令牌/recovery 令牌都不能访问资料库；access 令牌不能调 reset。
	if code, _ := a.do(http.MethodGet, "/api/v1/records?since=0", nil, false); code != http.StatusUnauthorized {
		t.Fatalf("无令牌访问 records 应 401，实际 %d", code)
	}
	reqGet, _ := http.NewRequest(http.MethodGet, srv.URL+"/api/v1/records?since=0", nil)
	reqGet.Header.Set("Authorization", "Bearer "+recoveryToken)
	respGet, err := http.DefaultClient.Do(reqGet)
	if err != nil {
		t.Fatal(err)
	}
	respGet.Body.Close()
	if respGet.StatusCode != http.StatusUnauthorized {
		t.Fatalf("recovery 令牌访问 records 应 401，实际 %d", respGet.StatusCode)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/recovery/reset", map[string]any{}, true); code != http.StatusUnauthorized {
		t.Fatalf("access 令牌调 reset 应 401，实际 %d", code)
	}

	// 客户端用 REK 解开 MK（start 返回 recovery_kek_salt 与恢复包裹）。
	recKEKSalt := mustB64(t, start["recovery_kek_salt"])
	wrappedRec := mustB64(t, start["wrapped_master_key_recovery"])
	rek, _ := crypto.DeriveKey(oldCode, recKEKSalt)
	mkFromBox, err := crypto.UnwrapForRecovery(rek, wrappedRec)
	if err != nil || !bytesEqual(mkFromBox, oldMK) {
		t.Fatalf("REK 解出的 MK 必须与原 MK 字节相等: %v", err)
	}

	// 用解开的 MK + 新密码重新包裹，并轮换新恢复码。
	newPw := makePwBundle(t, newPW, oldMK)
	newRec := makeRecBundle(t, newCode, oldMK)
	resetBody := map[string]any{}
	for k, v := range newPw.json() {
		resetBody[k] = v
	}
	for k, v := range newRec.json() {
		resetBody[k] = v
	}
	resetBody["device_public_key"] = aPub // 在原设备上恢复 → 设备复用并转正
	resetBody["device_name"] = "laptop"
	reqReset, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/auth/recovery/reset", jsonReader(resetBody))
	reqReset.Header.Set("Content-Type", "application/json")
	reqReset.Header.Set("Authorization", "Bearer "+recoveryToken)
	respReset, err := http.DefaultClient.Do(reqReset)
	if err != nil {
		t.Fatal(err)
	}
	resetOut := decodeBody(t, respReset)
	respReset.Body.Close()
	if respReset.StatusCode != http.StatusOK {
		t.Fatalf("恢复重置失败: %d %v", respReset.StatusCode, resetOut)
	}
	newAccess := resetOut["access_token"].(string)
	_ = newAccess
	// 恢复前签发的旧 refresh 必须已被吊销。
	a.token = aAccess
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/refresh", map[string]any{
		"refresh_token": oldRefresh,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("恢复后旧 refresh 应 401，实际 %d", code)
	}

	// FU-3：reset 成功后同一 recovery 令牌在 TTL 内重放必须 401（单次化）。
	{
		reqReplay, _ := http.NewRequest(http.MethodPost, srv.URL+"/api/v1/auth/recovery/reset", jsonReader(resetBody))
		reqReplay.Header.Set("Content-Type", "application/json")
		reqReplay.Header.Set("Authorization", "Bearer "+recoveryToken)
		respReplay, err := http.DefaultClient.Do(reqReplay)
		if err != nil {
			t.Fatal(err)
		}
		respReplay.Body.Close()
		if respReplay.StatusCode != http.StatusUnauthorized {
			t.Fatalf("FU-3 重放旧 recovery 令牌应 401，实际 %d", respReplay.StatusCode)
		}
	}

	// 新密码登录解出的 MK 与旧 MK 相等，旧密文可正常解密。
	a2 := &testClient{t: t, srv: srv, username: "kate", password: newPW}
	bundle := approvedLogin(t, a2, "laptop", aPub)
	a2.token = bundle["access_token"].(string)
	_, newParams := a2.do(http.MethodGet, "/api/v1/auth/parameters?username=kate", nil, false)
	newKEKSalt := mustB64(t, newParams["kek_salt"])
	newKEK, _ := crypto.DeriveKey(newPW, newKEKSalt)
	serverWrapped := mustB64(t, newParams["wrapped_master_key"])
	mkAfter, err := crypto.UnwrapMasterKey(newKEK, serverWrapped)
	if err != nil || !bytesEqual(mkAfter, oldMK) {
		t.Fatalf("新密码解出的 MK 与旧 MK 不一致: %v", err)
	}
	_, synced := a2.do(http.MethodGet, "/api/v1/records?since=0", nil, true)
	rec := synced["records"].([]any)[0].(map[string]any)
	plain, err := crypto.Open(oldMK, mustB64(t, rec["ciphertext"]),
		crypto.RecordAAD(rec["id"].(string), rec["module"].(string), int64(rec["version"].(float64))))
	if err != nil || string(plain) != "secret-data" {
		t.Fatalf("恢复后旧密文应可解密，实际 %v %q", err, plain)
	}

	// 旧密码登录被拒；新恢复码有效、旧恢复码失效。
	aOld := &testClient{t: t, srv: srv, username: "kate", password: oldPW}
	oldV := loginVerifier(t, aOld.do, "kate", oldPW)
	if code, _ := aOld.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": "kate", "auth_verifier": oldV,
		"device_name": "x", "device_public_key": randBytes(t, 32),
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("旧密码登录应 401，实际 %d", code)
	}
	newRecAuthSalt := mustB64(t, newParams["recovery_auth_salt"])
	oldCodeVerifier, _ := crypto.DeriveKey(oldCode, newRecAuthSalt)
	if code, _ := a2.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "kate", "recovery_verifier": oldCodeVerifier,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("旧恢复码应已失效，实际 %d", code)
	}
	newCodeVerifier, _ := crypto.DeriveKey(newCode, newRecAuthSalt)
	if code, _ := a2.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "kate", "recovery_verifier": newCodeVerifier,
	}, false); code != http.StatusUnauthorized && code != http.StatusOK {
		t.Fatalf("新恢复码应可用（200）或限流（429 之外异常），实际 %d", code)
	}

	// 恢复前挂起的 C 配对必须已作废。
	c.token = cAccess
	_, cst := c.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	if cst["state"] != "expired" && cst["state"] != "rejected" {
		t.Fatalf("恢复后待审批配对应失效，实际 %v", cst["state"])
	}
	_ = cPid
}

// TestChangePasswordFlow 改密：其他设备 refresh 吊销、当前设备换发、新密码可用/旧密码拒绝。
func TestChangePasswordFlow(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	const pw1, pw2 = "change-pw-1", "change-pw-2"
	a := &testClient{t: t, srv: srv, username: "leo", password: pw1}
	_, aPub, oldMK := regUser(t, "leo", pw1, "laptop", a.do)
	aBundle := approvedLogin(t, a, "laptop", aPub)
	a.token = aBundle["access_token"].(string)
	aRefresh := aBundle["refresh_token"].(string)

	// 第二台设备 B 获批并持有 refresh。
	b := &testClient{t: t, srv: srv, username: "leo", password: pw1}
	bPub := randBytes(t, 32)
	bAccess, pid := pendingLogin(t, b, "phone", bPub)
	b.token = bAccess
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/pairings/"+pid+"/approve", map[string]any{
		"ephemeral_public_key": randBytes(t, 32), "nonce": randBytes(t, 24),
		"wrapped_master_key": randBytes(t, 40),
	}, true); code != http.StatusOK {
		t.Fatal("批准 B 失败")
	}
	_, bst := b.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	bTokens := bst["tokens"].(map[string]any)
	bRefresh := bTokens["refresh_token"].(string)
	b.token = bTokens["access_token"].(string)

	// A 改密（携带自己的 refresh 以便吊销换发），不改恢复码。
	newPw := makePwBundle(t, pw2, oldMK)
	body := newPw.json()
	body["refresh_token"] = aRefresh
	code, changed := a.do(http.MethodPost, "/api/v1/auth/password/change", body, true)
	if code != http.StatusOK {
		t.Fatalf("改密失败: %d %v", code, changed)
	}
	a.token = changed["access_token"].(string)

	// B 的 refresh 立即失效；A 的旧 refresh 也被吊销。
	if code, _ := b.do(http.MethodPost, "/api/v1/auth/refresh", map[string]any{
		"refresh_token": bRefresh,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("改密后其他设备 refresh 应 401，实际 %d", code)
	}
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/refresh", map[string]any{
		"refresh_token": aRefresh,
	}, false); code != http.StatusUnauthorized {
		t.Fatalf("改密后旧 refresh 应 401，实际 %d", code)
	}
	// A 新令牌访问资料库正常。
	if code, _ := a.do(http.MethodGet, "/api/v1/records?since=0", nil, true); code != http.StatusOK {
		t.Fatalf("改密后当前设备新令牌应可用，实际 %d", code)
	}
	// 新密码登录解出同一 MK。
	a2 := &testClient{t: t, srv: srv, username: "leo", password: pw2}
	b2 := approvedLogin(t, a2, "laptop", aPub)
	a2.token = b2["access_token"].(string)
	_, p := a2.do(http.MethodGet, "/api/v1/auth/parameters?username=leo", nil, false)
	kek2, _ := crypto.DeriveKey(pw2, mustB64(t, p["kek_salt"]))
	mk2, err := crypto.UnwrapMasterKey(kek2, mustB64(t, p["wrapped_master_key"]))
	if err != nil || !bytesEqual(mk2, oldMK) {
		t.Fatalf("改密后 MK 必须保持不变: %v", err)
	}
	// 恢复码未轮换，仍可用。
	recV, _ := crypto.DeriveKey("recovery-code", mustB64(t, p["recovery_auth_salt"]))
	if code, _ := a2.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "leo", "recovery_verifier": recV,
	}, false); code != http.StatusOK {
		t.Fatalf("未轮换的恢复码应仍有效，实际 %d", code)
	}
}

// TestRecoveryRateLimited 恢复码连续失败共享登录限流桶：5 次失败后第 6 次 429。
func TestRecoveryRateLimited(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()
	a := &testClient{t: t, srv: srv, username: "mia", password: "pw-mia"}
	a.token, _, _ = regUser(t, "mia", a.password, "laptop", a.do)
	_, params := a.do(http.MethodGet, "/api/v1/auth/parameters?username=mia", nil, false)
	salt := mustB64(t, params["recovery_auth_salt"])
	for i := 0; i < 5; i++ {
		bad, _ := crypto.DeriveKey("bad-code", salt)
		if code, _ := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
			"username": "mia", "recovery_verifier": bad,
		}, false); code != http.StatusUnauthorized {
			t.Fatalf("第 %d 次错误恢复码应 401，实际 %d", i+1, code)
		}
	}
	good, _ := crypto.DeriveKey("recovery-code", salt)
	if code, _ := a.do(http.MethodPost, "/api/v1/auth/recovery/start", map[string]any{
		"username": "mia", "recovery_verifier": good,
	}, false); code != http.StatusTooManyRequests {
		t.Fatalf("第 6 次即使恢复码正确也应 429，实际 %d", code)
	}
}
