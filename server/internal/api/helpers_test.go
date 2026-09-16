package api_test

// api 包黑盒测试共享的小工具（字节比较、JSON 请求、记录写入）。

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/everything-personal/eve/internal/api"
	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/db"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

// newHarnessDB 同 newHarness，但额外返回数据库句柄（审计/状态白盒断言用）。
func newHarnessDB(t *testing.T) (*httptest.Server, *sql.DB) {
	t.Helper()
	srv, database, _ := newHarnessFull(t)
	return srv, database
}

// newHarnessFull 返回测试服务器、数据库句柄与认证服务（构造自定义 TTL 令牌用，
// 仅测试内部可达，不经任何 HTTP 后门）。
func newHarnessFull(t *testing.T) (*httptest.Server, *sql.DB, *auth.Service) {
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
	handler := api.New(cfg, database, authSvc, vault.New(database), sync.New()).Handler()
	return httptest.NewServer(handler), database, authSvc
}

func bytesEqual(a, b []byte) bool { return bytes.Equal(a, b) }

func jsonReader(v any) io.Reader {
	b, _ := json.Marshal(v)
	return bytes.NewReader(b)
}

func decodeBody(t *testing.T, resp *http.Response) map[string]any {
	t.Helper()
	raw, _ := io.ReadAll(resp.Body)
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	return out
}

// sealWithMK 用给定 MK 按记录信封 AAD 加密明文。
func sealWithMK(t *testing.T, mk []byte, id, module string, version int64, plaintext []byte) []byte {
	t.Helper()
	ct, err := crypto.Seal(mk, plaintext, crypto.RecordAAD(id, module, version))
	if err != nil {
		t.Fatal(err)
	}
	return ct
}

// upsertRecord 通过 /records/batch 写入一条记录并断言 2xx 成功。
func upsertRecord(t *testing.T, c *testClient, id, module, typ string, ciphertext []byte, version int64) {
	t.Helper()
	now := time.Now().UnixMilli()
	status, out := c.do(http.MethodPost, "/api/v1/records/batch", map[string]any{
		"records": []map[string]any{{
			"id": id, "module": module, "type": typ,
			"ciphertext": ciphertext, "version": version,
			"created_at": now, "updated_at": now,
		}},
	}, true)
	if status != http.StatusOK {
		t.Fatalf("写入记录失败: %d %v", status, out)
	}
}
