package maptile

import (
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

const upstreamUserAgent = "Everything-Eve/1.0 (+self-hosted)"

// Proxy 从配置的上游模板拉取 PNG 瓦片，可选磁盘缓存。
type Proxy struct {
	template string
	client   *http.Client
	cache    *diskCache
	limiter  *IPRateLimiter
}

// New 构造瓦片代理；cacheDir 非空则启用磁盘缓存目录。
func New(upstreamTemplate string, cacheDir string, rpmPerIP int) *Proxy {
	if strings.TrimSpace(upstreamTemplate) == "" {
		upstreamTemplate = defaultUpstreamTemplate
	}
	return &Proxy{
		template: strings.TrimSpace(upstreamTemplate),
		client: &http.Client{
			Timeout: 20 * time.Second,
		},
		cache:   newDiskCache(cacheDir),
		limiter: NewIPRateLimiter(rpmPerIP),
	}
}

// DefaultUpstreamTemplate 缺省上游（OSM 标准瓦片）。
const defaultUpstreamTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

// DefaultUpstreamTemplate 导出缺省模板供配置默认值复用。
func DefaultUpstreamTemplate() string { return defaultUpstreamTemplate }

// ServeHTTP 处理 GET 瓦片：clientIP 用于限流；z/x/y 由路由解析传入。
func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request, clientIP string, z, x, y int) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if !validTileCoords(z, x, y) {
		http.Error(w, "invalid tile", http.StatusBadRequest)
		return
	}
	if !p.limiter.Allow(clientIP, time.Now()) {
		http.Error(w, "rate limited", http.StatusTooManyRequests)
		return
	}

	if b, ok := p.cache.read(z, x, y); ok {
		writePNG(w, b)
		return
	}

	upURL := expandTemplate(p.template, z, x, y)
	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, upURL, nil)
	if err != nil {
		http.Error(w, "upstream url", http.StatusInternalServerError)
		return
	}
	req.Header.Set("User-Agent", upstreamUserAgent)
	req.Header.Set("Accept", "image/png,image/*,*/*")

	resp, err := p.client.Do(req)
	if err != nil {
		http.Error(w, "upstream unreachable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		http.Error(w, "upstream error", resp.StatusCode)
		return
	}

	const maxTile = 2 * 1024 * 1024 // 2MB 上限，防异常响应撑爆内存
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxTile+1))
	if err != nil {
		http.Error(w, "upstream read", http.StatusBadGateway)
		return
	}
	if len(body) > maxTile {
		http.Error(w, "tile too large", http.StatusBadGateway)
		return
	}

	p.cache.write(z, x, y, body)
	writePNG(w, body)
}

func writePNG(w http.ResponseWriter, body []byte) {
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.WriteHeader(http.StatusOK)
	if len(body) > 0 {
		_, _ = w.Write(body)
	}
}

// validTileCoords 拒绝越界坐标，减轻滥用与无意义上游请求。
func validTileCoords(z, x, y int) bool {
	if z < 0 || z > 19 {
		return false
	}
	max := 1 << z
	return x >= 0 && x < max && y >= 0 && y < max
}

func expandTemplate(tpl string, z, x, y int) string {
	s := tpl
	s = strings.ReplaceAll(s, "{z}", fmt.Sprintf("%d", z))
	s = strings.ReplaceAll(s, "{x}", fmt.Sprintf("%d", x))
	s = strings.ReplaceAll(s, "{y}", fmt.Sprintf("%d", y))
	return s
}
