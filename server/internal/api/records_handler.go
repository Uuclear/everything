package api

import (
	"net/http"
	"strconv"

	"github.com/everything-personal/eve/internal/sync"
	"github.com/everything-personal/eve/internal/vault"
)

type batchRequest struct {
	Records []vault.Record `json:"records"`
}

// upsertRecords 批量接收客户端加密信封，按 version 幂等合并，成功后广播 SSE。
func (s *Server) upsertRecords(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req batchRequest
	if !decodeJSON(w, r, &req) || len(req.Records) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "records 为空")
		return
	}
	if len(req.Records) > 1000 {
		writeError(w, http.StatusBadRequest, "bad_request", "单批最多 1000 条")
		return
	}
	now := timeMillis()
	modules := make(map[string]struct{})
	for i := range req.Records {
		rec := &req.Records[i]
		if rec.ID == "" || rec.Module == "" || len(rec.Ciphertext) == 0 {
			writeError(w, http.StatusBadRequest, "bad_request", "记录缺少 id/module/ciphertext")
			return
		}
		rec.DeviceID = claims.DeviceID
		if rec.UpdatedAt == 0 {
			rec.UpdatedAt = now
		}
		if rec.CreatedAt == 0 {
			rec.CreatedAt = rec.UpdatedAt
		}
		modules[rec.Module] = struct{}{}
	}
	result, err := s.vault.ApplyBatch(claims.UserID, req.Records)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "写入失败")
		return
	}
	if result.Applied > 0 {
		s.hub.Publish(claims.UserID, sync.Event{Type: "records_changed"})
	}
	writeJSON(w, http.StatusOK, result)
}

// listRecords 增量拉取：?since=<unix_milli>&limit=<n>。
func (s *Server) listRecords(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	records, err := s.vault.ListSince(claims.UserID, since, limit)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "读取失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"records": records,
		"has_more": func() bool {
			limit := limit
			if limit <= 0 || limit > 2000 {
				limit = 500
			}
			return len(records) >= limit
		}(),
	})
}
