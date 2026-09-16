// Package vault 的位置轨迹块存储层（阶段 4a，spec FR-6）。
// 零知识口径：服务端只存 XChaCha20-Poly1305 密文块与最小明文元数据，
// 本文件不接触明文坐标、不做任何解密。
package vault

import (
	"database/sql"
	"fmt"
	"time"
)

// LocationBlock 是一个位置轨迹密文块的线上形态。
// json tag 与 Task 2 API 契约对齐（cipher 在 API 层以 base64 编解码）。
type LocationBlock struct {
	ID         string `json:"id"`
	UserID     string `json:"-"` // 仅服务端内部用户隔离口径，不随 API 序列化外发
	DeviceID   string `json:"device_id"`
	StartTs    int64  `json:"start_ts"`   // 块首点 UTC 毫秒（月表归属依据）
	EndTs      int64  `json:"end_ts"`     // 块末点 UTC 毫秒
	PointCount int    `json:"point_count"` // 块内轨迹点数
	Cipher     []byte `json:"cipher"`      // 密文信封（nonce||ciphertext），服务端永不解密
	CreatedAt  int64  `json:"created_at"`  // 服务端权威写入时间（UTC 毫秒）
}

// LocationStore 封装 locations_YYYYMM 月表操作，与 Store 同款的 *sql.DB 注入模式。
type LocationStore struct{ db *sql.DB }

// NewLocationStore 创建位置块存储层。
func NewLocationStore(database *sql.DB) *LocationStore { return &LocationStore{db: database} }

// monthTableDDL 月表建表模板（%s 为月表名）。
// 列定义与 spec FR-6 / docs/module-schemas.md 逐字段一致；
// 月表由写入路径 CREATE TABLE IF NOT EXISTS 动态创建，不在迁移中预建。
const monthTableDDL = `CREATE TABLE IF NOT EXISTS %s (
	id TEXT PRIMARY KEY,
	user_id TEXT NOT NULL,
	device_id TEXT NOT NULL,
	start_ts INTEGER NOT NULL,
	end_ts INTEGER NOT NULL,
	point_count INTEGER NOT NULL,
	cipher BLOB NOT NULL,
	created_at INTEGER NOT NULL
)`

// monthIndexDDL 月表范围查询索引模板（%[1]s 复用同一表名实参）。
const monthIndexDDL = `CREATE INDEX IF NOT EXISTS idx_%[1]s_user_ts ON %[1]s(user_id, start_ts)`

// monthTable 返回 startTs（UTC 毫秒）所属 UTC 月份的月表名 locations_YYYYMM。
// 安全口径：月表名只允许由本函数生成（输入为整数时间戳、输出格式固定），
// 严禁用任何外部字符串拼接表名（防 SQL 注入）。
func monthTable(startTs int64) string {
	return "locations_" + time.UnixMilli(startTs).UTC().Format("200601")
}

// monthSpan 按时间升序枚举 [from, to]（UTC 毫秒，含两端）覆盖的全部 UTC 月表名。
// 例：from 属 2024-01、to 属 2024-03 → [locations_202401, locations_202402, locations_202403]。
func monthSpan(from, to int64) []string {
	f := time.UnixMilli(from).UTC()
	t := time.UnixMilli(to).UTC()
	// 两端各自对齐到所在月 1 号 00:00，逐月步进枚举。
	cur := time.Date(f.Year(), f.Month(), 1, 0, 0, 0, 0, time.UTC)
	last := time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, time.UTC)
	out := make([]string, 0, 4)
	for !cur.After(last) {
		out = append(out, "locations_"+cur.Format("200601"))
		cur = cur.AddDate(0, 1, 0)
	}
	return out
}

// UpsertBlocks 批量写入密文块：(applied, skipped, err)。
// 逐块确保所属月表已建（CREATE TABLE IF NOT EXISTS 动态建表），
// 随后 INSERT OR IGNORE——同 id 重复提交 RowsAffected=0 计 skipped（块不可变、幂等）。
// created_at 一律以入参 now（服务端权威时间）覆盖，客户端上传值不予信任。
func (s *LocationStore) UpsertBlocks(userID string, blocks []LocationBlock, now int64) (applied, skipped int, err error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()

	for _, b := range blocks {
		table := monthTable(b.StartTs)
		// 动态建表与索引（IF NOT EXISTS，重复执行零代价）。
		if _, err := tx.Exec(fmt.Sprintf(monthTableDDL, table)); err != nil {
			return applied, skipped, fmt.Errorf("建月表 %s: %w", table, err)
		}
		if _, err := tx.Exec(fmt.Sprintf(monthIndexDDL, table)); err != nil {
			return applied, skipped, fmt.Errorf("建月表索引 %s: %w", table, err)
		}
		res, err := tx.Exec(fmt.Sprintf(`INSERT OR IGNORE INTO %s
			(id, user_id, device_id, start_ts, end_ts, point_count, cipher, created_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, table),
			b.ID, userID, b.DeviceID, b.StartTs, b.EndTs, b.PointCount, b.Cipher, now)
		if err != nil {
			return applied, skipped, err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return applied, skipped, err
		}
		if n == 0 {
			skipped++ // 同 id 已存在：块不可变，直接幂等跳过
		} else {
			applied++
		}
	}
	if err := tx.Commit(); err != nil {
		return applied, skipped, err
	}
	return applied, skipped, nil
}

// existingMonthTables 查询当前库中已建的 locations_YYYYMM 月表集合。
// LIKE 中每个下划线是单字符通配符："_YYYYMM" 共 7 个字符，故需 7 个下划线
// （tasks.md 原文写 6 个系 off-by-one 笔误，匹配不上 16 字符表名，此处按语义修正）。
func (s *LocationStore) existingMonthTables() (map[string]bool, error) {
	rows, err := s.db.Query(
		`SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'locations_______'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		out[name] = true
	}
	return out, rows.Err()
}

// ListRange 返回该用户 start_ts ∈ [from, to]（UTC 毫秒，含两端）的全部块，
// 跨月表按 start_ts 升序拼接（月表枚举本身按时间升序，块归属由 start_ts 决定，故整体有序）。
// 未建月表直接跳过（空库/空月份不视为错误）。
func (s *LocationStore) ListRange(userID string, from, to int64) ([]LocationBlock, error) {
	existing, err := s.existingMonthTables()
	if err != nil {
		return nil, err
	}
	out := make([]LocationBlock, 0, 64)
	for _, table := range monthSpan(from, to) {
		if !existing[table] {
			continue // 该月从未有写入，无表可查
		}
		rows, err := s.db.Query(fmt.Sprintf(`SELECT id, user_id, device_id, start_ts, end_ts, point_count, cipher, created_at
			FROM %s WHERE user_id = ? AND start_ts BETWEEN ? AND ? ORDER BY start_ts ASC`, table),
			userID, from, to)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var b LocationBlock
			if err := rows.Scan(&b.ID, &b.UserID, &b.DeviceID, &b.StartTs, &b.EndTs,
				&b.PointCount, &b.Cipher, &b.CreatedAt); err != nil {
				rows.Close()
				return nil, err
			}
			out = append(out, b)
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return nil, err
		}
		rows.Close()
	}
	return out, nil
}

// DeleteRange 删除该用户 start_ts ∈ [from, to]（UTC 毫秒，含两端）的全部块，
// 返回实际删除行数（跨月表累计）。未建月表直接跳过。
func (s *LocationStore) DeleteRange(userID string, from, to int64) (int, error) {
	existing, err := s.existingMonthTables()
	if err != nil {
		return 0, err
	}
	total := 0
	for _, table := range monthSpan(from, to) {
		if !existing[table] {
			continue
		}
		res, err := s.db.Exec(fmt.Sprintf(
			`DELETE FROM %s WHERE user_id = ? AND start_ts BETWEEN ? AND ?`, table),
			userID, from, to)
		if err != nil {
			return total, err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return total, err
		}
		total += int(n)
	}
	return total, nil
}
