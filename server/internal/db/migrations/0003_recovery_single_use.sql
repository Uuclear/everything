-- 0003 恢复会话单次化（FU-3）：
-- 记录用户最近一次恢复重置的毫秒时间戳。reset 成功后，凡 iat 不晚于该时间戳的
-- recovery 短期令牌一律拒绝，杜绝 10 分钟 TTL 内的重放（反复吊销会话的 DoS 面）。
-- NULL 表示该用户从未执行过恢复重置。
ALTER TABLE users ADD COLUMN recovery_reset_at INTEGER;
