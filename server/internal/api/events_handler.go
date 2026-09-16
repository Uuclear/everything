package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"time"

	"github.com/everything-personal/eve/internal/auth"
)

// eventsToken 为 EventSource 建连换发 5 分钟一次性短期令牌（approved 设备）。
// 浏览器 EventSource 不能携带 Authorization 头，故 SSE 走 ?token= 鉴权。
func (s *Server) eventsToken(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	token, err := s.auth.IssueEventsToken(claims.UserID, claims.DeviceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "事件令牌签发失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"events_token": token,
		"expires_in":   int64(auth.EventsTokenTTL.Seconds()),
	})
}

// events 以 SSE 推送当前用户的资料库变更；断线后客户端用 since= 增量补拉兜底。
// 鉴权由 requireEventsAccess 双入口完成（Authorization approved 或 ?token= events）。
func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "internal", "服务器不支持 SSE")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	events, cancel := s.hub.Subscribe(claims.UserID)
	defer cancel()

	ping := time.NewTicker(30 * time.Second)
	defer ping.Stop()

	// 初始注释帧，帮助中间代理尽快建立连接。
	fmt.Fprintf(w, ": connected\n\n")
	flusher.Flush()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			fmt.Fprintf(w, ": ping\n\n")
			flusher.Flush()
		case e := <-events:
			payload, _ := json.Marshal(e)
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", e.Type, payload)
			flusher.Flush()
		}
	}
}
