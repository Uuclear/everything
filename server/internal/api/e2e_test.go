package api_test

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/everything-personal/eve/internal/api"
	"github.com/everything-personal/eve/internal/attachments"
	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/db"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

// 模拟一个最小客户端：派生密钥、注册、登录、同步加密记录并本地解密。
type testClient struct {
	t        *testing.T
	srv      *httptest.Server
	token    string
	username string
	password string
	authSalt []byte
	kekSalt  []byte
	mk       []byte
	device   string
}

func newHarness(t *testing.T) *httptest.Server {
	t.Helper()
	cfg := config.Default()
	cfg.DataDir = t.TempDir()

	database, err := db.Open(cfg.DataDir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { database.Close() })

	authSvc, err := auth.New(database, cfg)
	if err != nil {
		t.Fatal(err)
	}
	files, err := attachments.New(database, cfg.DataDir+"/attachments")
	if err != nil {
		t.Fatal(err)
	}
	handler := api.New(cfg, database, authSvc, vault.New(database), files, sync.New()).Handler()
	return httptest.NewServer(handler)
}

func (c *testClient) do(method, path string, body any, withAuth bool) (int, map[string]any) {
	var rdr io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.srv.URL+path, rdr)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if withAuth {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func TestEndToEndRegisterSyncDecrypt(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()

	c := &testClient{t: t, srv: srv, username: "alice", password: "correct horse battery staple", device: "go-test"}
	var err error
	if c.authSalt, err = crypto.NewSalt(); err != nil {
		t.Fatal(err)
	}
	if c.kekSalt, err = crypto.NewSalt(); err != nil {
		t.Fatal(err)
	}
	verifier, err := crypto.DeriveKey(c.password, c.authSalt)
	if err != nil {
		t.Fatal(err)
	}
	kek, err := crypto.DeriveKey(c.password, c.kekSalt)
	if err != nil {
		t.Fatal(err)
	}
	c.mk, err = crypto.NewMasterKey()
	if err != nil {
		t.Fatal(err)
	}
	wrapped, err := crypto.WrapMasterKey(kek, c.mk)
	if err != nil {
		t.Fatal(err)
	}

	// 1) 注册。
	status, out := c.do(http.MethodPost, "/api/v1/auth/register", map[string]any{
		"username": c.username, "auth_salt": c.authSalt, "kek_salt": c.kekSalt,
		"auth_verifier": verifier, "wrapped_master_key": wrapped, "device_name": c.device,
	}, false)
	if status != http.StatusCreated {
		t.Fatalf("注册失败: %d %v", status, out)
	}
	c.token = out["access_token"].(string)

	// 2) 上传客户端已加密的附件，服务端只验证摘要并保存密文。
	attachment := []byte("encrypted attachment bytes")
	sum := sha256.Sum256(attachment)
	req, _ := http.NewRequest(http.MethodPut, c.srv.URL+"/api/v1/attachments/photo-1", bytes.NewReader(attachment))
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Content-SHA256", hex.EncodeToString(sum[:]))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("附件上传失败: %d", resp.StatusCode)
	}
	resp.Body.Close()
	req, _ = http.NewRequest(http.MethodPut, c.srv.URL+"/api/v1/attachments/photo-2", bytes.NewReader(attachment))
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	req.Header.Set("X-Content-SHA256", hex.EncodeToString(sum[:]))
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("相同密文摘要应支持逻辑附件: %d", resp.StatusCode)
	}
	resp.Body.Close()
	req, _ = http.NewRequest(http.MethodGet, c.srv.URL+"/api/v1/attachments/photo-1", nil)
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	downloaded, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !bytes.Equal(downloaded, attachment) {
		t.Fatalf("附件下载不一致: %d %q", resp.StatusCode, downloaded)
	}

	// 3) 重复注册（first 策略下应被拒绝）。
	status, _ = c.do(http.MethodPost, "/api/v1/auth/register", map[string]any{
		"username": "bob", "auth_salt": c.authSalt, "kek_salt": c.kekSalt,
		"auth_verifier": verifier, "wrapped_master_key": wrapped, "device_name": c.device,
	}, false)
	if status != http.StatusForbidden {
		t.Fatalf("首个用户后注册应关闭，得到 %d", status)
	}

	// 4) 用 MK 加密一条证件记录并同步。
	plaintext := []byte(`{"module":"identity","title":"我的护照","number":"E12345678","expires_at":1946985600000}`)
	const id, module, version = "rec-passport-1", "identity", int64(1)
	ciphertext, err := crypto.Seal(c.mk, plaintext, crypto.RecordAAD(id, module, version))
	if err != nil {
		t.Fatal(err)
	}
	status, out = c.do(http.MethodPost, "/api/v1/records/batch", map[string]any{
		"records": []map[string]any{{
			"id": id, "module": module, "type": "passport",
			"ciphertext": ciphertext, "version": version,
			"created_at": 1757865600000, "updated_at": 1757865600000,
		}},
	}, true)
	if status != http.StatusOK || out["applied"].(float64) != 1 {
		t.Fatalf("写入失败: %d %v", status, out)
	}

	// 5) 旧版本重复推送必须被跳过。
	status, out = c.do(http.MethodPost, "/api/v1/records/batch", map[string]any{
		"records": []map[string]any{{
			"id": id, "module": module, "type": "passport",
			"ciphertext": ciphertext, "version": version,
			"created_at": 1757865600000, "updated_at": 1757865600000,
		}},
	}, true)
	if status != http.StatusOK || out["skipped"].(float64) != 1 {
		t.Fatalf("旧版本应跳过: %d %v", status, out)
	}

	// 6) 新设备登录：拿盐 → 派生 KEK 解开服务器返回的包裹 MK → 增量拉取并解密。
	c2 := &testClient{t: t, srv: srv, username: c.username, password: c.password}
	status, params := c2.do(http.MethodGet, "/api/v1/auth/parameters?username=alice", nil, false)
	if status != http.StatusOK {
		t.Fatalf("取登录参数失败: %d", status)
	}
	c2.authSalt = mustB64(t, params["auth_salt"])
	c2.kekSalt = mustB64(t, params["kek_salt"])
	serverWrapped := mustB64(t, params["wrapped_master_key"])
	v2, err := crypto.DeriveKey(c2.password, c2.authSalt)
	if err != nil {
		t.Fatal(err)
	}
	status, login := c2.do(http.MethodPost, "/api/v1/auth/login", map[string]any{
		"username": "alice", "auth_verifier": v2, "device_name": "second-device",
	}, false)
	if status != http.StatusOK {
		t.Fatalf("登录失败: %d", status)
	}
	c2.token = login["access_token"].(string)
	kek2, err := crypto.DeriveKey(c2.password, c2.kekSalt)
	if err != nil {
		t.Fatal(err)
	}
	c2.mk, err = crypto.UnwrapMasterKey(kek2, serverWrapped)
	if err != nil || !bytes.Equal(c2.mk, c.mk) {
		t.Fatalf("新设备解出的 MK 与原 MK 不一致: %v", err)
	}

	status, synced := c2.do(http.MethodGet, "/api/v1/records?since=0", nil, true)
	if status != http.StatusOK {
		t.Fatalf("增量拉取失败: %d", status)
	}
	recs := synced["records"].([]any)
	if len(recs) != 1 {
		t.Fatalf("应同步 1 条记录，实际 %d", len(recs))
	}
	rec := recs[0].(map[string]any)
	ct := mustB64(t, rec["ciphertext"])
	decrypted, err := crypto.Open(c2.mk, ct,
		crypto.RecordAAD(rec["id"].(string), rec["module"].(string), int64(rec["version"].(float64))))
	if err != nil {
		t.Fatalf("新设备解密失败: %v", err)
	}
	if !bytes.Equal(decrypted, plaintext) {
		t.Fatalf("解密明文不一致: %q", decrypted)
	}

	// 7) 无令牌访问受保护接口必须 401。
	status, _ = c.do(http.MethodGet, "/api/v1/records?since=0", nil, false)
	if status != http.StatusUnauthorized {
		t.Fatalf("未带令牌应 401，得到 %d", status)
	}
}

// mustB64 从 JSON 响应中取 base64 字段（解码到 any 时为 string）。
func mustB64(t *testing.T, v any) []byte {
	t.Helper()
	s, ok := v.(string)
	if !ok {
		if b, ok := v.([]byte); ok {
			return b
		}
		t.Fatalf("期望 base64 字符串，实际 %T: %v", v, v)
	}
	b, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		t.Fatalf("base64 解码失败: %v", err)
	}
	return b
}
