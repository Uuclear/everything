// Package vault 是通用加密记录信封的存储层。
// 服务端只做存取、版本比较与增量同步，永远不接触明文。
package vault

import (
	"database/sql"
)

// Record 是一条加密记录的线上形态。
type Record struct {
	ID         string `json:"id"`
	Module     string `json:"module"`
	Type       string `json:"type"`
	Ciphertext []byte `json:"ciphertext"`
	Version    int64  `json:"version"`
	DeviceID   string `json:"device_id"`
	CreatedAt  int64  `json:"created_at"`
	UpdatedAt  int64  `json:"updated_at"`
	Deleted    bool   `json:"deleted"`
}

// Store 封装 records 表操作。
type Store struct{ db *sql.DB }

// New 创建存储层。
func New(database *sql.DB) *Store { return &Store{db: database} }

// BatchResult 汇报批量写入结果，供同步去重/诊断。
type BatchResult struct {
	Applied int `json:"applied"`
	Skipped int `json:"skipped"`
}

// ApplyBatch 按 (user_id, id) 幂等 upsert；仅当传入版本号严格大于库中版本时覆盖。
func (s *Store) ApplyBatch(userID string, records []Record) (BatchResult, error) {
	res := BatchResult{}
	tx, err := s.db.Begin()
	if err != nil {
		return res, err
	}
	defer tx.Rollback()

	sel := tx.Stmt(prepare(tx, `SELECT version FROM records WHERE user_id = ? AND id = ?`))
	ins := tx.Stmt(prepare(tx, `INSERT INTO records
		(id, user_id, module, type, ciphertext, version, device_id, created_at, updated_at, deleted)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`))
	upd := tx.Stmt(prepare(tx, `UPDATE records SET module=?, type=?, ciphertext=?, version=?, device_id=?,
		created_at=?, updated_at=?, deleted=? WHERE user_id=? AND id=?`))

	for _, r := range records {
		var current sql.NullInt64
		row := sel.QueryRow(userID, r.ID)
		err := row.Scan(&current)
		if err != nil && err != sql.ErrNoRows {
			return res, err
		}
		if err == nil && current.Valid && r.Version <= current.Int64 {
			res.Skipped++
			continue
		}
		deleted := 0
		if r.Deleted {
			deleted = 1
		}
		if err == sql.ErrNoRows || !current.Valid {
			if _, err := ins.Exec(r.ID, userID, r.Module, r.Type, r.Ciphertext, r.Version,
				r.DeviceID, r.CreatedAt, r.UpdatedAt, deleted); err != nil {
				return res, err
			}
		} else {
			if _, err := upd.Exec(r.Module, r.Type, r.Ciphertext, r.Version, r.DeviceID,
				r.CreatedAt, r.UpdatedAt, deleted, userID, r.ID); err != nil {
				return res, err
			}
		}
		res.Applied++
	}
	if err := tx.Commit(); err != nil {
		return res, err
	}
	return res, nil
}

// ListSince 返回 updated_at > since 的记录（升序，limit 控制单页大小）。
// 客户端以最后一条的 updated_at 作为下次 since 游标。
func (s *Store) ListSince(userID string, since int64, limit int) ([]Record, error) {
	if limit <= 0 || limit > 2000 {
		limit = 500
	}
	rows, err := s.db.Query(`SELECT id, module, type, ciphertext, version, device_id, created_at, updated_at, deleted
		FROM records WHERE user_id = ? AND updated_at > ? ORDER BY updated_at ASC, id ASC LIMIT ?`,
		userID, since, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]Record, 0, limit)
	for rows.Next() {
		var r Record
		var deleted int
		if err := rows.Scan(&r.ID, &r.Module, &r.Type, &r.Ciphertext, &r.Version,
			&r.DeviceID, &r.CreatedAt, &r.UpdatedAt, &deleted); err != nil {
			return nil, err
		}
		r.Deleted = deleted == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

// prepare 吞掉预备语句的错误（SQL 为常量，出错只会在执行时暴露），保持调用简洁。
func prepare(tx *sql.Tx, query string) *sql.Stmt {
	st, err := tx.Prepare(query)
	if err != nil {
		panic(err)
	}
	return st
}
