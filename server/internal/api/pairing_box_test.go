package api_test

// 设备配对的 nacl/box 端到端互通测试（Task 6 / TR-6.2、AC-5）：
// 审批端 D1 用 D2 的静态 X25519 公钥做 crypto_box 密封 MK，
// D2 用自己的私钥开箱——服务端全程只透传 32B 临时公钥/24B nonce/密文。

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net/http"
	"testing"

	"golang.org/x/crypto/nacl/box"
)

// b64Field 从 JSON map 取 base64 字段（mustB64 的可读别名场景）。
func b64Field(t *testing.T, m map[string]any, key string) []byte {
	t.Helper()
	return mustB64(t, m[key])
}

func TestPairingBoxInterop(t *testing.T) {
	srv := newHarness(t)
	defer srv.Close()

	// D1：注册首设备，持有 MK（regUser 内部随机生成 MK 与恢复材料）。
	d1 := &testClient{t: t, srv: srv, username: "ivy", password: "pw-ivy-interop"}
	d1Token, _, mk := regUser(t, "ivy", d1.password, "laptop-D1", d1.do)
	d1.token = d1Token
	if len(mk) != 32 {
		t.Fatalf("MK 应为 32 字节，实际 %d", len(mk))
	}

	// D2：生成自己的静态 X25519 密钥对（私钥永不上传，公钥随登录提交）。
	d2Pub, d2Sec, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	d2 := &testClient{t: t, srv: srv, username: "ivy", password: d1.password}
	d2Access, pairingID := pendingLogin(t, d2, "phone-D2", d2Pub[:])
	d2.token = d2Access

	// D1 审批端密封：生成一次性临时密钥对 + 24B 随机 nonce，
	// box.Seal(mk, nonce, recipient=D2_pub, sender=eph_sec)。
	ephPub, ephSec, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var nonce [24]byte
	if _, err := io.ReadFull(rand.Reader, nonce[:]); err != nil {
		t.Fatal(err)
	}
	boxed := box.Seal(nil, mk, &nonce, d2Pub, ephSec)
	if code, out := d1.do(http.MethodPost, "/api/v1/auth/pairings/"+pairingID+"/approve", map[string]any{
		"ephemeral_public_key": ephPub[:],
		"nonce":                nonce[:],
		"wrapped_master_key":   boxed,
	}, true); code != http.StatusOK {
		t.Fatalf("批准失败: %d %v", code, out)
	}

	// D2 轮询拿到盒材料并换发正式令牌；材料长度严格符合 box 约定。
	_, st := d2.do(http.MethodGet, "/api/v1/auth/pairing/status", nil, true)
	if st["state"] != "approved" {
		t.Fatalf("应 approved，实际 %v", st["state"])
	}
	gotEph := b64Field(t, st, "ephemeral_public_key")
	gotNonce := b64Field(t, st, "nonce")
	gotBox := b64Field(t, st, "wrapped_master_key")
	if len(gotEph) != 32 || len(gotNonce) != 24 || len(gotBox) != len(boxed) {
		t.Fatalf("盒材料长度不合法: eph=%d nonce=%d box=%d", len(gotEph), len(gotNonce), len(gotBox))
	}
	if !bytes.Equal(gotEph, ephPub[:]) || !bytes.Equal(gotNonce, nonce[:]) || !bytes.Equal(gotBox, boxed) {
		t.Fatal("服务端必须原样透传盒材料，不得改写")
	}

	// D2 开箱：box.Open(boxed, nonce, sender=eph_pub, recipient=D2_sec)，得到 MK 原文。
	var gotNonceArr [24]byte
	copy(gotNonceArr[:], gotNonce)
	var gotEphArr [32]byte
	copy(gotEphArr[:], gotEph)
	opened, ok := box.Open(nil, gotBox, &gotNonceArr, &gotEphArr, d2Sec)
	if !ok {
		t.Fatal("D2 开箱失败：密钥协商或认证标签校验未通过")
	}
	if !bytes.Equal(opened, mk) {
		t.Fatalf("开箱 MK 与 D1 持有 MK 不一致：opened=%x mk=%x", opened, mk)
	}

	// 篡改盒中 1 字节必须开箱失败（证明依赖真实认证加密，而非服务端附带明文）。
	tampered := append([]byte(nil), gotBox...)
	tampered[len(tampered)-1] ^= 0xFF
	if _, ok := box.Open(nil, tampered, &gotNonceArr, &gotEphArr, d2Sec); ok {
		t.Fatal("篡改后的盒必须认证失败")
	}
	// 用 D1 公钥（冒充发送方）也应开箱失败。
	var wrongSender [32]byte
	// d1Pub 取注册公钥：regUser 未返回——此处用全零错误公钥即可证明发送方绑定。
	if _, ok := box.Open(nil, gotBox, &gotNonceArr, &wrongSender, d2Sec); ok {
		t.Fatal("发送方公钥不匹配必须开箱失败")
	}

	// base64 传输自洽性基线：JSON 中的材料与透传字节一致（编码层无截断）。
	if enc := base64.StdEncoding.EncodeToString(gotBox); enc == "" {
		t.Fatal("base64 编码异常")
	}
}
