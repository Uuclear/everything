package api

import (
	"net"
	"net/http"
	"strconv"
	"strings"

	"github.com/go-chi/chi/v5"
)

// mapTile 同源瓦片代理：仅 approved JWT；上游与缓存见 config.Map。
func (s *Server) mapTile(w http.ResponseWriter, r *http.Request) {
	z, err1 := strconv.Atoi(chi.URLParam(r, "z"))
	x, err2 := strconv.Atoi(chi.URLParam(r, "x"))
	yRaw := chi.URLParam(r, "y")
	yRaw = strings.TrimSuffix(yRaw, ".png")
	y, err3 := strconv.Atoi(yRaw)
	if err1 != nil || err2 != nil || err3 != nil {
		writeError(w, http.StatusBadRequest, "invalid_tile", "瓦片坐标非法")
		return
	}
	ip := r.RemoteAddr
	if host, _, err := net.SplitHostPort(ip); err == nil && host != "" {
		ip = host
	}
	s.mapTiles.ServeHTTP(w, r, ip, z, x, y)
}
