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
	if !decodeJSON(w, r, &req) {
		return // decodeJSON 已写出 400 错误响应
	}
	if len(req.Records) == 0 {
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
		// 墓碑记录（删除同步）允许空 ciphertext；普通记录必须携带加密信封。
		if rec.ID == "" || rec.Module == "" || (!rec.Deleted && len(rec.Ciphertext) == 0) {
			writeError(w, http.StatusBadRequest, "bad_request", "记录缺少 id/module/ciphertext")
			return
		}
		rec.DeviceID = claims.DeviceID
		// created_at 缺省时回填服务端时间（展示字段，允许客户端自带本地时钟）；
		// updated_at 不由客户端决定——ApplyBatch 统一以服务端权威时间覆盖（FU-1）。
		if rec.CreatedAt == 0 {
			rec.CreatedAt = now
		}
		modules[rec.Module] = struct{}{}
	}
	result, err := s.vault.ApplyBatch(claims.UserID, req.Records, now)
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
