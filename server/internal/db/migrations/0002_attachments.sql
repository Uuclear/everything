-- 0002 阶段 3 附件基础：只保存密文文件摘要与必要元数据。

CREATE TABLE attachments (
    id           TEXT NOT NULL,
    user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_hash TEXT NOT NULL,
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size         INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    PRIMARY KEY (user_id, id)
);
CREATE INDEX idx_attachments_user_created ON attachments(user_id, created_at);
CREATE INDEX idx_attachments_user_hash ON attachments(user_id, content_hash);
