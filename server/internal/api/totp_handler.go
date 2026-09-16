package api

import (
	"errors"
	"net/http"

	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/sync"
)

// totpStatus 供客户端判断二次验证当前状态（未配置/待确认/已启用）。
func (s *Server) totpStatus(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	secret, confirmed, err := s.auth.TOTPSecretState(claims.UserID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "查询二次验证状态失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":    confirmed,
		"has_secret": secret, // true=已 setup 待确认或已启用，前端据此引导
	})
}

// totpSetup 生成新的待确认 TOTP 密钥与二维码（已启用须先 disable）。
func (s *Server) totpSetup(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	setup, err := s.auth.SetupTOTP(claims.UserID)
	if err != nil {
		s.writeTOTPError(w, err)
		return
	}
	s.audit(claims.UserID, "totp_setup", "", r.RemoteAddr)
	writeJSON(w, http.StatusOK, setup)
}

type totpCodeRequest struct {
	Code string `json:"code"`
}

func (s *Server) totpEnable(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req totpCodeRequest
	if !decodeJSON(w, r, &req) {
		return // decodeJSON 已写出 400 错误响应
	}
	if req.Code == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少验证码")
		return
	}
	if err := s.auth.EnableTOTP(claims.UserID, req.Code); err != nil {
		if errors.Is(err, auth.ErrTOTPInvalidCode) {
			s.audit(claims.UserID, "totp_enable_failed", "", r.RemoteAddr)
		}
		s.writeTOTPError(w, err)
		return
	}
	s.audit(claims.UserID, "totp_enabled", "", r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]any{"enabled": true})
}

func (s *Server) totpDisable(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	var req totpCodeRequest
	if !decodeJSON(w, r, &req) {
		return // decodeJSON 已写出 400 错误响应
	}
	if req.Code == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少验证码")
		return
	}
	if err := s.auth.DisableTOTP(claims.UserID, req.Code); err != nil {
		if errors.Is(err, auth.ErrTOTPInvalidCode) {
			s.audit(claims.UserID, "totp_disable_failed", "", r.RemoteAddr)
		}
		s.writeTOTPError(w, err)
		return
	}
	s.audit(claims.UserID, "totp_disabled", "", r.RemoteAddr)
	writeJSON(w, http.StatusOK, map[string]any{"enabled": false})
}

type totpVerifyRequest struct {
	Code            string `json:"code"`
	DevicePublicKey []byte `json:"device_public_key"`
	DeviceName      string `json:"device_name"`
}

// totpVerify 用 mfa 短期会话 + TOTP 验证码接续登录设备分支；
// 验证码错误计入与登录相同的 auth-write 限流桶（FR-24）。
func (s *Server) totpVerify(w http.ResponseWriter, r *http.Request) {
	s.guardAuth(rateCategoryAuth, s.doTOTPVerify)(w, r)
}

func (s *Server) doTOTPVerify(w http.ResponseWriter, r *http.Request) authResult {
	claims := claimsFrom(r)
	var req totpVerifyRequest
	if !decodeJSON(w, r, &req) {
		return authSkipped
	}
	if len(req.DevicePublicKey) != crypto.DevicePublicKeyLen {
		writeError(w, http.StatusBadRequest, "bad_request", "设备公钥必须为 32 字节")
		return authSkipped
	}
	if req.Code == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少验证码")
		return authSkipped
	}
	if req.DeviceName == "" {
		req.DeviceName = r.UserAgent()
	}
	if err := s.auth.VerifyTOTPCode(claims.UserID, req.Code); err != nil {
		s.audit(claims.UserID, "totp_verify_failed", "", r.RemoteAddr)
		s.writeTOTPError(w, err)
		return authFailed
	}
	result, err := s.auth.VerifyMFAFinishLogin(claims.UserID, req.DeviceName, req.DevicePublicKey)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "登录处理失败")
		return authSkipped
	}
	// 设备分支结果复用登录后的审计/广播：pending 也要通知审批端。
	switch result.Status {
	case auth.LoginPending:
		p := result.Pending
		s.audit(p.UserID, "device_pair_requested",
			"device_id="+p.DeviceID+" device="+req.DeviceName+" mfa=true", r.RemoteAddr)
		s.hub.Publish(p.UserID, sync.Event{Type: "device_pairing_requested"})
	default:
		s.audit(result.Bundle.UserID, "login", "device="+req.DeviceName+" mfa=true", r.RemoteAddr)
	}
	writeJSON(w, http.StatusOK, result)
	return authSucceeded
}

func (s *Server) writeTOTPError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, auth.ErrTOTPInvalidCode):
		writeError(w, http.StatusUnauthorized, "invalid_totp", "验证码错误或已过期")
	case errors.Is(err, auth.ErrTOTPNotConfigured):
		writeError(w, http.StatusConflict, "totp_not_configured", "尚未配置或启用二次验证")
	case errors.Is(err, auth.ErrTOTPAlreadyEnabled):
		writeError(w, http.StatusConflict, "totp_already_enabled", "二次验证已启用")
	default:
		writeError(w, http.StatusInternalServerError, "internal", "二次验证操作失败")
	}
}
