package maptile_test

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/everything-personal/eve/internal/maptile"
)

// TestProxyServeHTTP 用 mock 上游验证 User-Agent、模板展开与 200 PNG 回写。
func TestProxyServeHTTP(t *testing.T) {
	var gotUA string
	var gotPath string
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUA = r.Header.Get("User-Agent")
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte{0x89, 0x50, 0x4e, 0x47}) // PNG 魔数前缀
	}))
	defer up.Close()

	tpl := strings.TrimSuffix(up.URL, "/") + "/{z}/{x}/{y}.png"
	p := maptile.New(tpl, t.TempDir(), 1000)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/tiles/3/4/5.png", nil)
	p.ServeHTTP(rec, req, "127.0.0.1", 3, 4, 5)

	if rec.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", rec.Code, rec.Body.String())
	}
	if gotUA != "Everything-Eve/1.0 (+self-hosted)" {
		t.Fatalf("User-Agent=%q", gotUA)
	}
	if gotPath != "/3/4/5.png" {
		t.Fatalf("path=%q", gotPath)
	}
	if rec.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("content-type=%q", rec.Header().Get("Content-Type"))
	}
}

func TestValidTileCoords(t *testing.T) {
	p := maptile.New("", "", 1000)
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	p.ServeHTTP(rec, req, "1.2.3.4", 25, 0, 0)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("invalid z should 400, got %d", rec.Code)
	}
}

func TestDiskCacheHit(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte{1, 2, 3})
	}))
	defer up.Close()
	tpl := strings.TrimSuffix(up.URL, "/") + "/{z}/{x}/{y}.png"
	cacheDir := t.TempDir()
	p := maptile.New(tpl, cacheDir, 1000)

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	rec1 := httptest.NewRecorder()
	p.ServeHTTP(rec1, req, "10.0.0.1", 2, 1, 1)
	if rec1.Code != http.StatusOK {
		t.Fatal(rec1.Code)
	}
	up.Close() // 第二次应走缓存
	rec2 := httptest.NewRecorder()
	p.ServeHTTP(rec2, req, "10.0.0.1", 2, 1, 1)
	if rec2.Code != http.StatusOK || rec2.Body.String() != "\x01\x02\x03" {
		t.Fatalf("cache miss? code=%d body=%q", rec2.Code, rec2.Body.String())
	}
}
