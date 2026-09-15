-- 0001 初始 schema：用户、设备、刷新令牌、加密记录信封、审计日志。

CREATE TABLE users (
    id                   TEXT PRIMARY KEY,
    username             TEXT NOT NULL UNIQUE,
    auth_salt            BLOB NOT NULL,           -- Argon2id 登录验证器盐
    kek_salt             BLOB NOT NULL,           -- Argon2id 主密钥包裹盐
    auth_verifier        BLOB NOT NULL,           -- Argon2id(password, auth_salt)，服务端只见过这个派生值
    wrapped_master_key   BLOB NOT NULL,           -- nonce||ciphertext(MK)，MK 由客户端随机生成
    totp_secret          BLOB,                    -- 阶段 1 预留：二次验证
    created_at           INTEGER NOT NULL         -- unix 毫秒
);

CREATE TABLE devices (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    public_key  BLOB,                             -- X25519 设备公钥（设备审批阶段启用）
    approved    INTEGER NOT NULL DEFAULT 1,       -- MVP 自动批准；阶段 1 后期改为扫码审批
    last_seen   INTEGER,
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_devices_user ON devices(user_id);

CREATE TABLE refresh_tokens (
    token_hash  BLOB PRIMARY KEY,                 -- SHA-256(refresh token)
    user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id   TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    expires_at  INTEGER NOT NULL,
    created_at  INTEGER NOT NULL,
    revoked     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);

-- 通用加密记录信封：服务端对 payload 完全不可见。
CREATE TABLE records (
    id           TEXT NOT NULL,
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    module       TEXT NOT NULL,                   -- 模块名（password / identity / ...），同步路由用，明文
    type         TEXT NOT NULL DEFAULT '',        -- 模块内子类型，明文
    ciphertext   BLOB NOT NULL,                   -- nonce||ciphertext(明文 JSON)
    version      INTEGER NOT NULL,                -- 单调版本号，冲突按 version 取大
    device_id    TEXT NOT NULL,
    created_at   INTEGER NOT NULL,                -- unix 毫秒（明文，仅用于排序展示）
    updated_at   INTEGER NOT NULL,
    deleted      INTEGER NOT NULL DEFAULT 0,      -- 软删除墓碑
    PRIMARY KEY (user_id, id)
);
CREATE INDEX idx_records_sync ON records(user_id, updated_at);

CREATE TABLE audit_logs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     TEXT,
    event       TEXT NOT NULL,
    detail      TEXT NOT NULL DEFAULT '',
    ip          TEXT NOT NULL DEFAULT '',
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_audit_user_time ON audit_logs(user_id, created_at);
