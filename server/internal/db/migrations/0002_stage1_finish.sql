-- 0002 阶段 1 收尾：恢复密钥材料、TOTP 确认时间、设备配对审批状态机。

-- ---- 用户：恢复密钥材料 ----
-- 全部由客户端生成，服务端只见过 Argon2id 派生验证器与包裹后的 MK：
-- 忘记主密码时凭恢复码重置密码并解回原 MK（详见 docs/crypto.md）。
-- 均可空：兼容 0001 时代注册的开发账户（注册新协议自 0002 起强制携带）。
ALTER TABLE users ADD COLUMN recovery_auth_salt BLOB;        -- Argon2id 恢复码验证器盐（16B）
ALTER TABLE users ADD COLUMN recovery_kek_salt BLOB;         -- Argon2id 恢复密钥加密密钥盐（16B）
ALTER TABLE users ADD COLUMN recovery_verifier BLOB;         -- Argon2id(规范化恢复码, recovery_auth_salt)（32B）
ALTER TABLE users ADD COLUMN wrapped_master_key_recovery BLOB; -- nonce||ciphertext(MK)，REK 包裹

-- TOTP 二次验证确认时间（unix 毫秒）；NULL 表示未启用。
-- totp_secret 已在 0001 预留（BLOB）：setup 后即写入，enable 校验通过才置确认时间。
ALTER TABLE users ADD COLUMN totp_confirmed_at INTEGER;

-- ---- 设备：审批状态 ----
-- pending  待已有设备审批（只能查配对状态，不能读资料库）
-- approved 已批准（完整访问权；0001 既有设备迁移后默认 approved）
-- revoked  被手动吊销（即时失效其刷新令牌）
-- 旧 approved 整数列保留以兼容历史代码路径，新逻辑一律以 state 为准。
ALTER TABLE devices ADD COLUMN state TEXT NOT NULL DEFAULT 'approved';

-- ---- 新设备配对请求 ----
-- 新设备登录先落 pending，由一台 approved 设备核对配对码后批准。
-- MK 经 X25519 crypto_box 端到端封装：审批端用新设备公钥+一次性临时私钥密封，
-- 服务端只透传 ephemeral_public_key/nonce/wrapped_master_key，永远无法得到 MK。
CREATE TABLE device_pairings (
    id                   TEXT PRIMARY KEY,
    user_id              TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id            TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    device_public_key    BLOB NOT NULL,           -- 待审批设备登记的 X25519 公钥（32B）
    ephemeral_public_key BLOB,                    -- 审批端一次性临时公钥（32B），批准时写入
    nonce                BLOB,                    -- crypto_box nonce（24B）
    wrapped_master_key   BLOB,                    -- crypto_box 密文，服务端不可解密
    state                TEXT NOT NULL,           -- pending / approved / rejected / expired
    created_at           INTEGER NOT NULL,
    expires_at           INTEGER NOT NULL,        -- pending 请求有效期 15 分钟
    responded_device_id  TEXT,                    -- 审批/拒绝操作的设备 id
    responded_at         INTEGER
);
-- 已批准设备拉取待审批列表、按用户广播配对事件都走 (user_id, state)。
CREATE INDEX idx_pairings_user_state ON device_pairings(user_id, state);
-- 一台设备至多有一条活跃配对，状态机更新时按 (device_id, state) 定位。
CREATE INDEX idx_pairings_device ON device_pairings(device_id);
