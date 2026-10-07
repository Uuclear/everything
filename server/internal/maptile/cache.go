package maptile

import (
	"os"
	"path/filepath"
)

// diskCache 将成功拉取的上游 PNG 写入 cacheDir/{z}/{x}/{y}.png（可选）。
type diskCache struct {
	dir string
}

func newDiskCache(dir string) *diskCache {
	if dir == "" {
		return nil
	}
	return &diskCache{dir: dir}
}

func (c *diskCache) path(z, x, y int) string {
	return filepath.Join(c.dir, intString(z), intString(x), intString(y)+".png")
}

func (c *diskCache) read(z, x, y int) ([]byte, bool) {
	if c == nil {
		return nil, false
	}
	b, err := os.ReadFile(c.path(z, x, y))
	if err != nil {
		return nil, false
	}
	return b, true
}

func (c *diskCache) write(z, x, y int, data []byte) {
	if c == nil || len(data) == 0 {
		return
	}
	p := c.path(z, x, y)
	if err := os.MkdirAll(filepath.Dir(p), 0o700); err != nil {
		return
	}
	_ = os.WriteFile(p, data, 0o600)
}

func intString(n int) string {
	// 小整数转字符串，避免 fmt 依赖在热路径；瓦片坐标非负且有限。
	if n == 0 {
		return "0"
	}
	var buf [12]byte
	i := len(buf)
	neg := n < 0
	if neg {
		n = -n
	}
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
