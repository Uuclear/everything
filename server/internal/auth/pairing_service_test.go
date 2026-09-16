package auth

// 配对状态机服务层白盒测试：TTL 过期分支与跨设备/跨用户防护（TR-3.4）。

import (
	"testing"
	"time"

	"github.com/everything-personal/eve/internal/config"
	"github.com/everything-personal/eve/internal/crypto"
	"github.com/everything-personal/eve/internal/db"
)

func newPairingService(t *testing.T) (*Service, string) {
	t.Helper()
	dir := t.TempDir()
	database, err := db.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { database.Close() })
	cfg := config.Default()
	cfg.DataDir = dir
	svc, err := New(database, cfg)
	if err != nil {
		t.Fatal(err)
	}
	return svc, dir
}

// registerWith 注册一个用户，返回 userID 与首设备 id（首设备自动 approved）。
func registerWith(t *testing.T, svc *Service, username string, devPub []byte) (string, string) {
	t.Helper()
	authSalt, _ := crypto.NewSalt()
	kekSalt, _ := crypto.NewSalt()
	verifier, _ := crypto.DeriveKey("pw", authSalt)
	kek, _ := crypto.DeriveKey("pw", kekSalt)
	mk, _ := crypto.NewMasterKey()
	wrapped, _ := crypto.WrapMasterKey(kek, mk)
	recAuthSalt, _ := crypto.NewSalt()
	recKEKSalt, _ := crypto.NewSalt()
	recVerifier, _ := crypto.DeriveKey("rc", recAuthSalt)
	rek, _ := crypto.DeriveKey("rc", recKEKSalt)
	wrappedRec, _ := crypto.WrapForRecovery(rek, mk)
	pair, err := svc.Register(RegisterInput{
		Username: username, AuthSalt: authSalt, KEKSalt: kekSalt,
		AuthVerifier: verifier, WrappedMasterKey: wrapped,
		DeviceName: "first", DevicePublicKey: devPub,
		RecoveryAuthSalt: recAuthSalt, RecoveryKEKSalt: recKEKSalt,
		RecoveryVerifier: recVerifier, WrappedMasterKeyRecovery: wrappedRec,
	})
	if err != nil {
		t.Fatal(err)
	}
	return pair.UserID, pair.DeviceID
}

// TestPairingExpiry 配对超过 TTL 未响应：惰性置 expired，审批/换发均被拒。
func TestPairingExpiry(t *testing.T) {
	svc, _ := newPairingService(t)
	pubA := make([]byte, 32)
	pubA[31] = 1
	pubB := make([]byte, 32)
	pubB[31] = 2
	userID, devA := registerWith(t, svc, "u1", pubA)

	res, err := svc.Login("u1", "phone", func() []byte {
		salt, _ := svc.LoginParams("u1")
		v, _ := crypto.DeriveKey("pw", salt.AuthSalt)
		return v
	}(), pubB)
	if err != nil || res.Status != LoginPending {
		t.Fatalf("新设备应 pending: %v %v", res.Status, err)
	}
	devB := res.Pending.DeviceID
	pairingID := res.Pending.Pairing.ID

	// 人为将配对过期时间移到过去。
	if _, err := svc.db.Exec(`UPDATE device_pairings SET expires_at=? WHERE id=?`,
		time.Now().Add(-time.Minute).UnixMilli(), pairingID); err != nil {
		t.Fatal(err)
	}

	// 新设备轮询：惰性 expired。
	p, err := svc.LatestPairingForDevice(userID, devB)
	if err != nil {
		t.Fatal(err)
	}
	if p.State != StateExpired {
		t.Fatalf("过期配对应惰性置 expired，实际 %s", p.State)
	}
	// 审批端不能批准一个已过期配对。
	_, err = svc.ApprovePairing(userID, devA, pairingID,
		make([]byte, 32), make([]byte, 24), []byte("box"))
	if err != ErrPairingNotOpen {
		t.Fatalf("过期配对审批应返回 ErrPairingNotOpen，实际 %v", err)
	}
	// 待审批列表不含过期项。
	open, err := svc.ListOpenPairings(userID)
	if err != nil || len(open) != 0 {
		t.Fatalf("过期后待审批列表应为空，实际 %d (%v)", len(open), err)
	}
	// 过期未批准设备不能换发令牌。
	if _, err := svc.IssueDeviceTokens(userID, devB); err != ErrPairingNotOpen {
		t.Fatalf("过期设备换发应被拒，实际 %v", err)
	}
}

// TestPairingCrossUser 配对方授权属校验：其他用户 id 不可见、不可操作。
func TestPairingCrossUser(t *testing.T) {
	svc, _ := newPairingService(t)
	pubA1 := make([]byte, 32)
	pubA1[31] = 1
	pubB1 := make([]byte, 32)
	pubB1[31] = 2
	pubA2 := make([]byte, 32)
	pubA2[31] = 3
	user1, devA1 := registerWith(t, svc, "user1", pubA1)
	_, _ = registerWith(t, svc, "user2", pubA2)

	res, err := svc.Login("user1", "phone", func() []byte {
		salt, _ := svc.LoginParams("user1")
		v, _ := crypto.DeriveKey("pw", salt.AuthSalt)
		return v
	}(), pubB1)
	if err != nil {
		t.Fatal(err)
	}
	pid := res.Pending.Pairing.ID
	devB1 := res.Pending.DeviceID
	_ = user1

	// user2 的首设备尝试批准 user1 的配对：按归属查不到 → NotFound。
	// 取 user2 的设备 id：
	var devA2 string
	if err := svc.db.QueryRow(`SELECT id FROM devices WHERE user_id=(SELECT id FROM users WHERE username='user2') AND state='approved'`).
		Scan(&devA2); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.ApprovePairing(func() string {
		var u string
		svc.db.QueryRow(`SELECT id FROM users WHERE username='user2'`).Scan(&u)
		return u
	}(), devA2, pid, make([]byte, 32), make([]byte, 24), []byte("box")); err != ErrPairingNotFound {
		t.Fatalf("跨用户批准应 ErrPairingNotFound，实际 %v", err)
	}

	// user2 查询 user1 设备的配对状态：NotFound。
	if _, err := svc.LatestPairingForDevice("other-user-id", devB1); err != ErrPairingNotFound {
		t.Fatalf("跨用户查询配对应 ErrPairingNotFound，实际 %v", err)
	}
	// 待审批设备不能审批自己。
	if _, err := svc.ApprovePairing(user1, devB1, pid,
		make([]byte, 32), make([]byte, 24), []byte("box")); err != ErrPairingNotOpen {
		t.Fatalf("自审批应 ErrPairingNotOpen，实际 %v", err)
	}
	_ = devA1
}
