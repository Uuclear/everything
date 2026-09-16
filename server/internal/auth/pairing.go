// 设备配对审批领域：设备状态、配对请求、配对码/指纹派生。
// MK 永远不经过服务端明文：审批端用新设备公钥做 crypto_box 封装，
// 本包只存取与转发密文字节（ephemeral_public_key/nonce/wrapped_master_key）。
package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
)

// 设备/配对生命周期状态。
const (
	StatePending  = "pending"  // 设备待审批 / 配对等待响应
	StateApproved = "approved" // 设备已批准 / 配对已授权
	StateRejected = "rejected" // 配对被拒绝（设备可重新发起登录请求）
	StateRevoked  = "revoked"  // 已批准设备被手动吊销
	StateExpired  = "expired"  // 配对 15 分钟内未获响应
)

// PairingTTL 是 pending 配对请求的有效期。
const PairingTTL = 15 * time.Minute

// 配对域错误。
var (
	ErrPairingNotFound  = errors.New("配对不存在或无权访问")
	ErrPairingNotOpen   = errors.New("配对不在可操作状态")
	ErrCannotRevokeSelf = errors.New("不能吊销当前设备")
)

// PairingInfo 是配对请求的服务端形态（含审批端需要的新设备原始公钥）。
type PairingInfo struct {
	ID                 string `json:"id"`
	DeviceID           string `json:"device_id"`
	DeviceName         string `json:"device_name"`
	DevicePublicKey    []byte `json:"device_public_key"`
	EphemeralPublicKey []byte `json:"ephemeral_public_key,omitempty"`
	Nonce              []byte `json:"nonce,omitempty"`
	WrappedMasterKey   []byte `json:"wrapped_master_key,omitempty"`
	State              string `json:"state"`
	CreatedAt          int64  `json:"created_at"`
	ExpiresAt          int64  `json:"expires_at"`
	RespondedDeviceID  string `json:"responded_device_id,omitempty"`
	// PairingCode 是设备公钥指纹前 3 字节的大写 hex（6 位），供两端人工核对。
	PairingCode string `json:"pairing_code"`
	// Fingerprint 是公钥指纹前 4 字节大写 hex（8 位），设备列表中辅助识别。
	Fingerprint string `json:"fingerprint"`
}

// DeviceInfo 是设备列表项。
type DeviceInfo struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	State       string `json:"state"`
	Fingerprint string `json:"fingerprint"`
	LastSeen    int64  `json:"last_seen"`
	CreatedAt   int64  `json:"created_at"`
	Current     bool   `json:"current"`
}

// PairingCode 派生配对码：大写 hex(SHA-256(设备公钥)[:3])。
// 配对码只用于人工核对防调包，本身不构成任何持密凭证。
func PairingCode(pub []byte) string {
	sum := sha256.Sum256(pub)
	return strings.ToUpper(hex.EncodeToString(sum[:3])) // 3 字节 → 恰好 6 个 hex 字符
}

// Fingerprint 派生设备公钥短指纹（8 位大写 hex）。
func Fingerprint(pub []byte) string {
	sum := sha256.Sum256(pub)
	return strings.ToUpper(hex.EncodeToString(sum[:4]))
}

// createOrRenewPairing 为设备创建一条新的 pending 配对，并把该设备的旧 pending 配对置过期。
func (s *Service) createOrRenewPairing(userID, deviceID string, devicePub []byte, now time.Time) (PairingInfo, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return PairingInfo{}, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`UPDATE device_pairings SET state = ? WHERE device_id = ? AND state = ?`,
		StateExpired, deviceID, StatePending); err != nil {
		return PairingInfo{}, err
	}
	id := fmt.Sprintf("pair-%s", randHex(8))
	created := now.UnixMilli()
	expires := now.Add(PairingTTL).UnixMilli()
	if _, err := tx.Exec(`INSERT INTO device_pairings
		(id, user_id, device_id, device_public_key, state, created_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, userID, deviceID, devicePub, StatePending, created, expires); err != nil {
		return PairingInfo{}, err
	}
	if err := tx.Commit(); err != nil {
		return PairingInfo{}, err
	}
	return PairingInfo{
		ID:              id,
		DeviceID:        deviceID,
		DevicePublicKey: devicePub,
		State:           StatePending,
		CreatedAt:       created,
		ExpiresAt:       expires,
		PairingCode:     PairingCode(devicePub),
		Fingerprint:     Fingerprint(devicePub),
	}, nil
}

// LatestPairingForDevice 返回某设备最新一条配对；pending 已过期时惰性置 expired。
func (s *Service) LatestPairingForDevice(userID, deviceID string) (PairingInfo, error) {
	var p PairingInfo
	var responded sql.NullString
	err := s.db.QueryRow(`SELECT p.id, p.device_id, COALESCE(d.name,''), p.device_public_key,
		p.ephemeral_public_key, p.nonce, p.wrapped_master_key, p.state,
		p.created_at, p.expires_at, p.responded_device_id
		FROM device_pairings p JOIN devices d ON d.id = p.device_id
		WHERE p.user_id = ? AND p.device_id = ?
		ORDER BY p.created_at DESC LIMIT 1`, userID, deviceID).Scan(
		&p.ID, &p.DeviceID, &p.DeviceName, &p.DevicePublicKey,
		&p.EphemeralPublicKey, &p.Nonce, &p.WrappedMasterKey, &p.State,
		&p.CreatedAt, &p.ExpiresAt, &responded)
	if errors.Is(err, sql.ErrNoRows) {
		return PairingInfo{}, ErrPairingNotFound
	}
	if err != nil {
		return PairingInfo{}, err
	}
	if responded.Valid {
		p.RespondedDeviceID = responded.String
	}
	p.PairingCode = PairingCode(p.DevicePublicKey)
	p.Fingerprint = Fingerprint(p.DevicePublicKey)
	// 惰性过期：pending 且超过有效期则标记，避免新设备无限轮询一个已死的请求。
	if p.State == StatePending && time.Now().UnixMilli() > p.ExpiresAt {
		if _, err := s.db.Exec(`UPDATE device_pairings SET state = ? WHERE id = ? AND state = ?`,
			StateExpired, p.ID, StatePending); err != nil {
			return PairingInfo{}, err
		}
		p.State = StateExpired
	}
	return p, nil
}

// ListOpenPairings 返回用户当前全部 pending 配对（已批准设备的审批列表用）。
// 惰性顺带把过期项置 expired 后再查询。
func (s *Service) ListOpenPairings(userID string) ([]PairingInfo, error) {
	now := time.Now().UnixMilli()
	if _, err := s.db.Exec(`UPDATE device_pairings SET state = ? WHERE user_id = ? AND state = ? AND expires_at < ?`,
		StateExpired, userID, StatePending, now); err != nil {
		return nil, err
	}
	rows, err := s.db.Query(`SELECT p.id, p.device_id, COALESCE(d.name,''), p.device_public_key,
		p.state, p.created_at, p.expires_at
		FROM device_pairings p JOIN devices d ON d.id = p.device_id
		WHERE p.user_id = ? AND p.state = ?
		ORDER BY p.created_at DESC`, userID, StatePending)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]PairingInfo, 0)
	for rows.Next() {
		var p PairingInfo
		if err := rows.Scan(&p.ID, &p.DeviceID, &p.DeviceName, &p.DevicePublicKey,
			&p.State, &p.CreatedAt, &p.ExpiresAt); err != nil {
			return nil, err
		}
		p.PairingCode = PairingCode(p.DevicePublicKey)
		p.Fingerprint = Fingerprint(p.DevicePublicKey)
		out = append(out, p)
	}
	return out, rows.Err()
}

// ApprovePairing 校验配对归属与状态后，写入审批端密封的 MK 盒并把设备置为 approved。
// 服务端不校验盒内容（也无法校验），仅保证请求者与配对归属同一用户且配对仍开放。
func (s *Service) ApprovePairing(userID, approverDeviceID, pairingID string,
	ephemeralPub, nonce, wrappedMK []byte) (PairingInfo, error) {
	return s.resolvePairing(userID, approverDeviceID, pairingID,
		ephemeralPub, nonce, wrappedMK, StateApproved)
}

// RejectPairing 拒绝配对：设备与配对都置 rejected，不写任何密钥材料。
func (s *Service) RejectPairing(userID, approverDeviceID, pairingID string) (PairingInfo, error) {
	return s.resolvePairing(userID, approverDeviceID, pairingID, nil, nil, nil, StateRejected)
}

func (s *Service) resolvePairing(userID, approverDeviceID, pairingID string,
	ephemeralPub, nonce, wrappedMK []byte, targetState string) (PairingInfo, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return PairingInfo{}, err
	}
	defer tx.Rollback()

	var deviceID string
	var state string
	var expiresAt int64
	err = tx.QueryRow(`SELECT device_id, state, expires_at FROM device_pairings
		WHERE id = ? AND user_id = ?`, pairingID, userID).Scan(&deviceID, &state, &expiresAt)
	if errors.Is(err, sql.ErrNoRows) {
		return PairingInfo{}, ErrPairingNotFound
	}
	if err != nil {
		return PairingInfo{}, err
	}
	if state != StatePending || time.Now().UnixMilli() > expiresAt {
		return PairingInfo{}, ErrPairingNotOpen
	}
	// 不能审批/拒绝自己（同一台设备的配对）。
	if deviceID == approverDeviceID {
		return PairingInfo{}, ErrPairingNotOpen
	}

	now := time.Now().UnixMilli()
	if targetState == StateApproved {
		if len(ephemeralPub) != 32 || len(nonce) != 24 || len(wrappedMK) == 0 {
			return PairingInfo{}, fmt.Errorf("批准材料长度不合法")
		}
		if _, err := tx.Exec(`UPDATE device_pairings
			SET state=?, ephemeral_public_key=?, nonce=?, wrapped_master_key=?,
			    responded_device_id=?, responded_at=?
			WHERE id=?`,
			StateApproved, ephemeralPub, nonce, wrappedMK, approverDeviceID, now, pairingID); err != nil {
			return PairingInfo{}, err
		}
		if _, err := tx.Exec(`UPDATE devices SET state=?, last_seen=? WHERE id=? AND user_id=?`,
			StateApproved, now, deviceID, userID); err != nil {
			return PairingInfo{}, err
		}
	} else {
		if _, err := tx.Exec(`UPDATE device_pairings
			SET state=?, responded_device_id=?, responded_at=? WHERE id=?`,
			StateRejected, approverDeviceID, now, pairingID); err != nil {
			return PairingInfo{}, err
		}
		if _, err := tx.Exec(`UPDATE devices SET state=? WHERE id=? AND user_id=?`,
			StateRejected, deviceID, userID); err != nil {
			return PairingInfo{}, err
		}
	}
	if err := tx.Commit(); err != nil {
		return PairingInfo{}, err
	}
	return s.LatestPairingForDevice(userID, deviceID)
}

// IssueDeviceTokens 在配对获批后为设备签发正式令牌对（pending 状态端点换发用）。
// 再次确认设备确属该用户且 state=approved，防止用 pending 令牌越权换发。
func (s *Service) IssueDeviceTokens(userID, deviceID string) (TokenPair, error) {
	var state string
	err := s.db.QueryRow(`SELECT state FROM devices WHERE id=? AND user_id=?`,
		deviceID, userID).Scan(&state)
	if errors.Is(err, sql.ErrNoRows) {
		return TokenPair{}, ErrPairingNotFound
	}
	if err != nil {
		return TokenPair{}, err
	}
	if state != StateApproved {
		return TokenPair{}, ErrPairingNotOpen
	}
	return s.issuePair(userID, deviceID, ScopeApproved)
}

// ListDevices 列出用户的全部设备。
func (s *Service) ListDevices(userID, currentDeviceID string) ([]DeviceInfo, error) {
	rows, err := s.db.Query(`SELECT id, name, state, public_key, COALESCE(last_seen,0), created_at
		FROM devices WHERE user_id = ? ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]DeviceInfo, 0)
	for rows.Next() {
		var d DeviceInfo
		var pub []byte
		if err := rows.Scan(&d.ID, &d.Name, &d.State, &pub, &d.LastSeen, &d.CreatedAt); err != nil {
			return nil, err
		}
		d.Current = d.ID == currentDeviceID
		if len(pub) == 32 {
			d.Fingerprint = Fingerprint(pub)
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// RevokeDevice 吊销已批准设备并即时吊销其全部刷新令牌；不允许自吊销。
func (s *Service) RevokeDevice(userID, currentDeviceID, targetDeviceID string) error {
	if targetDeviceID == currentDeviceID {
		return ErrCannotRevokeSelf
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.Exec(`UPDATE devices SET state=? WHERE id=? AND user_id=? AND state != ?`,
		StateRevoked, targetDeviceID, userID, StateRevoked)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrPairingNotFound
	}
	if _, err := tx.Exec(`UPDATE refresh_tokens SET revoked=1 WHERE device_id=? AND user_id=?`,
		targetDeviceID, userID); err != nil {
		return err
	}
	return tx.Commit()
}

// InvalidatePendingTx 在事务内作废全部待审批设备与配对（恢复主密码/账户接管时调用）。
// approved 设备保留（MK 未变），但其 refresh token 由调用方统一吊销。
func InvalidatePendingTx(tx *sql.Tx, userID string) error {
	if _, err := tx.Exec(`UPDATE device_pairings SET state=? WHERE user_id=? AND state=?`,
		StateExpired, userID, StatePending); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE devices SET state=? WHERE user_id=? AND state=?`,
		StateRejected, userID, StatePending); err != nil {
		return err
	}
	return nil
}

// randHex 生成 n 字节随机数的小写 hex（用于 pairing 行 id，避免可预测 id 枚举）。
func randHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		// 随机源失败在启动阶段即会暴露，此处退化为时间戳不可接受，直接 panic。
		panic(err)
	}
	return hex.EncodeToString(b)
}
