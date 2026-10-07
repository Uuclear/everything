package api_test

// 瓦片代理黑盒：approved JWT + mock 上游 User-Agent。

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/everything-personal/eve/internal/api"
	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/db"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

func newMapHarness(t *testing.T, upstreamTemplate string) *httptest.Server {
	t.Helper()
	cfg := config.Default()
	cfg.DataDir = t.TempDir()
	cfg.Map.UpstreamTemplate = upstreamTemplate
	cfg.Map.CacheEnabled = false
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
	return httptest.NewServer(handler)
}

func getTile(t *testing.T, srvURL, path, token string) (int, []byte) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, srvURL+path, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	return resp.StatusCode, body
}

func TestMapTilesAuthAndProxy(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("User-Agent") != "Everything-Eve/1.0 (+self-hosted)" {
			t.Errorf("User-Agent=%q", r.Header.Get("User-Agent"))
		}
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a})
	}))
	defer up.Close()

	tpl := strings.TrimSuffix(up.URL, "/") + "/{z}/{x}/{y}.png"
	srv := newMapHarness(t, tpl)
	defer srv.Close()

	c := &testClient{t: t, srv: srv, username: "map-user", password: "map-user-password-01"}
	c.token, _, _ = regUser(t, c.username, c.password, "laptop", c.do)

	code, _ := getTile(t, srv.URL, "/api/v1/map/tiles/2/1/1.png", "")
	if code != http.StatusUnauthorized {
		t.Fatalf("无 token 应 401，实际 %d", code)
	}

	code, body := getTile(t, srv.URL, "/api/v1/map/tiles/2/1/1.png", c.token)
	if code != http.StatusOK {
		t.Fatalf("approved 应 200，实际 %d", code)
	}
	if len(body) < 4 || body[0] != 0x89 {
		t.Fatalf("应返回 PNG 前缀，len=%d", len(body))
	}
}
