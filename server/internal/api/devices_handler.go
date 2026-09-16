package api

import (
	"errors"
	"net/http"

	"github.com/everything-personal/eve/internal/auth"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/sync"
	"github.com/go-chi/chi/v5"
)

// pairingStatusResponse 在配对信息之外，批准瞬间附上完整令牌对：
// 新设备轮询到 approved 时，一次性拿到 MK 盒 + 正式凭证，随后即可访问资料库。
type pairingStatusResponse struct {
	auth.PairingInfo
	Tokens *auth.TokenPair `json:"tokens,omitempty"`
}

// pairingStatus 供待审批设备（pending access 令牌）轮询自身配对状态。
// approved 时响应携带审批端密封的 crypto_box 材料与新签发的正式令牌对。
func (s *Server) pairingStatus(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	p, err := s.auth.LatestPairingForDevice(claims.UserID, claims.DeviceID)
	if errors.Is(err, auth.ErrPairingNotFound) {
		writeError(w, http.StatusNotFound, "not_found", "没有配对请求")
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "查询配对失败")
		return
	}
	resp := pairingStatusResponse{PairingInfo: p}
	if p.State == auth.StateApproved && len(p.WrappedMasterKey) > 0 {
		// 设备已批准：换发正式令牌对（pending access 不能访问资料库）。
		// 每次调用都会落一行 refresh_tokens；客户端批准后只取一次，无实际膨胀。
		pair, err := s.auth.IssueDeviceTokens(claims.UserID, claims.DeviceID)
		if err != nil {
			writeError(w, http.StatusForbidden, "device_pending", "设备尚未通过审批")
			return
		}
		resp.Tokens = &pair
	}
	writeJSON(w, http.StatusOK, resp)
}

// listDevices 已批准设备查看账户下全部设备（含当前设备标记）。
func (s *Server) listDevices(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	devices, err := s.auth.ListDevices(claims.UserID, claims.DeviceID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "查询设备失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"devices": devices})
}

// listPairings 已批准设备查看全部待审批配对（审批列表/角标）。
func (s *Server) listPairings(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	pairings, err := s.auth.ListOpenPairings(claims.UserID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal", "查询配对失败")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"pairings": pairings})
}

type approvePairingRequest struct {
	// 审批端生成的临时 X25519 密钥对：公钥给新设备；私钥仅存审批端内存。
	EphemeralPublicKey []byte `json:"ephemeral_public_key"` // 32B
	Nonce              []byte `json:"nonce"`                // crypto_box nonce 24B
	WrappedMasterKey   []byte `json:"wrapped_master_key"`   // nacl/box(ephSec, newDevicePub, MK)
}

// approvePairing 批准配对：服务端只转发审批端密封的 MK 盒，无法接触 MK 明文。
func (s *Server) approvePairing(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	pairingID := chi.URLParam(r, "id")
	var req approvePairingRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if len(req.EphemeralPublicKey) != crypto.DevicePublicKeyLen {
		writeError(w, http.StatusBadRequest, "bad_request", "临时公钥必须为 32 字节")
		return
	}
	if len(req.Nonce) != 24 {
		writeError(w, http.StatusBadRequest, "bad_request", "nonce 必须为 24 字节")
		return
	}
	if len(req.WrappedMasterKey) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少密封后的主密钥")
		return
	}
	p, err := s.auth.ApprovePairing(claims.UserID, claims.DeviceID, pairingID,
		req.EphemeralPublicKey, req.Nonce, req.WrappedMasterKey)
	if err != nil {
		s.writePairingError(w, err)
		return
	}
	s.audit(claims.UserID, "device_approved", "device_id="+p.DeviceID, r.RemoteAddr)
	// 通知同账户所有在线客户端（含等待中的新设备）刷新配对状态。
	s.hub.Publish(claims.UserID, sync.Event{Type: "device_pairing_resolved"})
	writeJSON(w, http.StatusOK, p)
}

// rejectPairing 拒绝配对：不传递任何密钥材料。
func (s *Server) rejectPairing(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	pairingID := chi.URLParam(r, "id")
	p, err := s.auth.RejectPairing(claims.UserID, claims.DeviceID, pairingID)
	if err != nil {
		s.writePairingError(w, err)
		return
	}
	s.audit(claims.UserID, "device_rejected", "device_id="+p.DeviceID, r.RemoteAddr)
	s.hub.Publish(claims.UserID, sync.Event{Type: "device_pairing_resolved"})
	writeJSON(w, http.StatusOK, map[string]any{"status": "rejected", "device_id": p.DeviceID})
}

// revokeDevice 吊销已批准设备；不能吊销当前设备。
func (s *Server) revokeDevice(w http.ResponseWriter, r *http.Request) {
	claims := claimsFrom(r)
	targetID := chi.URLParam(r, "id")
	if err := s.auth.RevokeDevice(claims.UserID, claims.DeviceID, targetID); err != nil {
		switch {
		case errors.Is(err, auth.ErrCannotRevokeSelf):
			writeError(w, http.StatusBadRequest, "bad_request", "不能吊销当前正在使用的设备")
		case errors.Is(err, auth.ErrPairingNotFound):
			writeError(w, http.StatusNotFound, "not_found", "设备不存在或已吊销")
		default:
			writeError(w, http.StatusInternalServerError, "internal", "吊销设备失败")
		}
		return
	}
	s.audit(claims.UserID, "device_revoked", "device_id="+targetID, r.RemoteAddr)
	s.hub.Publish(claims.UserID, sync.Event{Type: "device_list_changed"})
	writeJSON(w, http.StatusOK, map[string]any{"status": "revoked", "device_id": targetID})
}

// writePairingError 统一配对操作的错误码，避免向审批端泄露过多内部状态。
func (s *Server) writePairingError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, auth.ErrPairingNotFound):
		writeError(w, http.StatusNotFound, "not_found", "配对不存在")
	case errors.Is(err, auth.ErrPairingNotOpen):
		writeError(w, http.StatusConflict, "pairing_closed", "配对已被处理、已过期，或不能操作自身")
	default:
		writeError(w, http.StatusInternalServerError, "internal", "处理配对失败")
	}
}
