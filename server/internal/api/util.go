package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"
)

func timeMillis() int64 { return time.Now().UnixMilli() }

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	if v != nil {
		_ = json.NewEncoder(w).Encode(v)
	}
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": code, "message": message})
}

func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	defer r.Body.Close()
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求体不是合法 JSON")
		return false
	}
	return true
}

func (s *Server) audit(userID, event, detail, ip string) {
	if _, err := s.db.Exec(`INSERT INTO audit_logs (user_id, event, detail, ip, created_at)
		VALUES (?, ?, ?, ?, unixepoch() * 1000)`, userID, event, detail, ip); err != nil {
		slog.Warn("写入审计日志失败", "err", err)
	}
}
