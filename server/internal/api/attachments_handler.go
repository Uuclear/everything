// Package api 提供附件密文上传与下载接口。
package api

import (
	"database/sql"
	"errors"
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
)

// uploadAttachment 保存客户端已加密的附件，并验证 X-Content-SHA256。
func (s *Server) uploadAttachment(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	contentType := r.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	size, err := s.attachments.Upload(
		claims.UserID,
		chi.URLParam(r, "id"),
		contentType,
		r.Header.Get("X-Content-SHA256"),
		r.Body,
	)
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"size": size})
}

// downloadAttachment 返回用户自己的密文附件，不尝试解密或解析内容。
func (s *Server) downloadAttachment(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	file, contentType, size, err := s.attachments.Open(claims.UserID, chi.URLParam(r, "id"))
	if err != nil {
		if errors.Is(err, os.ErrNotExist) || errors.Is(err, sql.ErrNoRows) {
			writeError(w, http.StatusNotFound, "not_found", "附件不存在")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal", "读取附件失败")
		return
	}
	defer file.Close()
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	http.ServeContent(w, r, chi.URLParam(r, "id"), time.Time{}, file)
}
