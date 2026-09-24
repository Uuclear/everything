-- 0005 AI Agent 审计日志（阶段 6 Task 2，spec FR-V6-G + AC-V6-7）：
-- 仅记录路由元数据 + 哈希指纹，**严禁**写入 user_msg / assistant_msg / tool_args / tool_result 明文。
-- 三不存契约由本表的 schema 与 Audit.Write() 双重保证：
--   1. schema 不提供相关明文字段；
--   2. 写入路径在 agent.audit.Write() 中强制接收 hash 字段，不接受明文载荷。

CREATE TABLE agent_audit_logs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,

    -- 上下文维度（与服务端审计 logs 一致的「业务字段」），
    -- 不含任何明文敏感载荷。
    user_id         TEXT NOT NULL,
    device_id       TEXT NOT NULL DEFAULT '',
    session_id      TEXT NOT NULL DEFAULT '',       -- Agent 会话 id（32 字符 hex）
    provider_name   TEXT NOT NULL DEFAULT '',       -- 来源 Provider 名（"ollama" / "openai" / ...）
    event           TEXT NOT NULL,                  -- chat_requested / chat_completed / tool_invoked / tool_completed / cancelled / rate_limited / session_locked / session_expired / provider_error / ...
    status          TEXT NOT NULL DEFAULT 'ok',     -- ok / error
    error_code      TEXT NOT NULL DEFAULT '',       -- 与 agent.ErrorCode 对齐

    -- 工具调用维度（写工具重点留痕，读工具轻量摘要）
    tool_name       TEXT NOT NULL DEFAULT '',       -- 工具名（已在白名单内）
    tool_args_hash  TEXT NOT NULL DEFAULT '',       -- SHA-256(tool_args JSON) 的 64 字符 hex；不存明文
    tool_result_kind TEXT NOT NULL DEFAULT '',      -- redacted_summary / error / '' 等脱敏摘要类别

    -- 性能 + 速率（为 Task 12 异常模式检测做数据基础）
    prompt_tokens   INTEGER NOT NULL DEFAULT 0,     -- 入侧 token 估算
    completion_tokens INTEGER NOT NULL DEFAULT 0,    -- 出侧 token 估算
    latency_ms      INTEGER NOT NULL DEFAULT 0,      -- 端到端耗时（毫秒）

    -- 元数据
    ip              TEXT NOT NULL DEFAULT '',        -- 来源 IP
    detail          TEXT NOT NULL DEFAULT '',        -- 受控 detail（≤200 字符，无明文载荷）
    created_at      INTEGER NOT NULL                  -- unix 毫秒（写入侧用 unixepoch() * 1000）
);

-- 高频审计读取索引（按用户近 1 小时扫描、单个会话全链路追溯）。
CREATE INDEX idx_agent_audit_user_time
    ON agent_audit_logs(user_id, created_at);

-- 单会话全链路回溯（按 session_id）。
CREATE INDEX idx_agent_audit_session
    ON agent_audit_logs(session_id, created_at);

-- 异常模式检测（按用户 + 事件类型扫描：RPM 超限 / 写工具滥用 / 时段过载）。
CREATE INDEX idx_agent_audit_user_event_time
    ON agent_audit_logs(user_id, event, created_at);