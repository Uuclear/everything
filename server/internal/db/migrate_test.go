// Package db 的迁移测试：验证空库全量迁移与 0001→0002 在线升级两条路径。
package db

import (
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

// tableColumns 返回指定表的列名集合。
func tableColumns(t *testing.T, database *sql.DB, table string) map[string]bool {
	t.Helper()
	rows, err := database.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	cols := map[string]bool{}
	for rows.Next() {
		var cid int
		var name, ctype string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			t.Fatal(err)
		}
		cols[name] = true
	}
	return cols
}

func tableExists(t *testing.T, database *sql.DB, name string) bool {
	t.Helper()
	var n int
	if err := database.QueryRow(
		`SELECT COUNT(1) FROM sqlite_master WHERE type='table' AND name=?`, name).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n == 1
}

// TestFreshMigrate 空库应直接应用 0001+0002，全部新列/新表存在。
func TestFreshMigrate(t *testing.T) {
	database, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	userCols := tableColumns(t, database, "users")
	for _, c := range []string{
		"recovery_auth_salt", "recovery_kek_salt", "recovery_verifier",
		"wrapped_master_key_recovery", "totp_confirmed_at", "totp_secret",
		"recovery_reset_at", // 0003：恢复会话单次化
	} {
		if !userCols[c] {
			t.Errorf("users 缺少列 %s", c)
		}
	}
	deviceCols := tableColumns(t, database, "devices")
	if !deviceCols["state"] {
		t.Error("devices 缺少 state 列")
	}
	if !tableExists(t, database, "device_pairings") {
		t.Error("device_pairings 表未创建")
	}

	var version int
	if err := database.QueryRow(`SELECT MAX(version) FROM schema_migrations`).Scan(&version); err != nil {
		t.Fatal(err)
	}
	if version != 4 {
		t.Errorf("迁移版本应为 4，实际 %d", version)
	}
}

// TestUpgradeFrom0001 模拟仅应用过 0001 的旧库：0002 必须在线升级且旧数据不丢、状态回填正确。
func TestUpgradeFrom0001(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "eve.db")

	// 直接以驱动打开，手工只应用 0001，制造一个"旧版本库"。
	old, err := sql.Open("sqlite", path)
	if err != nil {
		t.Fatal(err)
	}
	sql0001, err := migrationFS.ReadFile("migrations/0001_init.sql")
	if err != nil {
		t.Fatal(err)
	}
	// 与 db.Open 相同的迁移记账：先建版本表，再应用 0001，最后写入版本号。
	if _, err := old.Exec(`CREATE TABLE IF NOT EXISTS schema_migrations (
		version INTEGER PRIMARY KEY,
		applied_at INTEGER NOT NULL DEFAULT (unixepoch())
	)`); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(string(sql0001)); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(`INSERT INTO schema_migrations(version) VALUES (1)`); err != nil {
		t.Fatal(err)
	}
	// 插入一个旧形态用户与设备（0001 的列集合，无恢复材料/state）。
	if _, err := old.Exec(`INSERT INTO users
		(id, username, auth_salt, kek_salt, auth_verifier, wrapped_master_key, created_at)
		VALUES ('u1','alice',X'00',X'00',X'00',X'00',1757865600000)`); err != nil {
		t.Fatal(err)
	}
	if _, err := old.Exec(`INSERT INTO devices (id, user_id, name, public_key, approved, last_seen, created_at)
		VALUES ('d1','u1','旧设备',NULL,1,1757865600000,1757865600000)`); err != nil {
		t.Fatal(err)
	}
	if err := old.Close(); err != nil {
		t.Fatal(err)
	}

	// 正式打开：应增量应用 0002。
	database, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer database.Close()

	var users, devices int
	if err := database.QueryRow(`SELECT COUNT(1) FROM users`).Scan(&users); err != nil {
		t.Fatal(err)
	}
	if users != 1 {
		t.Errorf("旧用户丢失：期望 1，实际 %d", users)
	}
	if err := database.QueryRow(`SELECT COUNT(1) FROM devices`).Scan(&devices); err != nil {
		t.Fatal(err)
	}
	if devices != 1 {
		t.Errorf("旧设备丢失：期望 1，实际 %d", devices)
	}
	// 旧设备迁移后必须回填为 approved，恢复材料列允许 NULL（兼容开发期账户）。
	var state, name string
	var recoverySalt []byte
	if err := database.QueryRow(
		`SELECT state, name, recovery_auth_salt FROM devices JOIN users ON users.id = devices.user_id
		 WHERE devices.id='d1'`).Scan(&state, &name, &recoverySalt); err != nil {
		t.Fatal(err)
	}
	if state != "approved" {
		t.Errorf("旧设备 state 应回填 approved，实际 %q", state)
	}
	if name != "旧设备" {
		t.Errorf("旧设备数据异常: %q", name)
	}
	if recoverySalt != nil {
		t.Errorf("旧用户恢复材料应为 NULL，实际 %v", recoverySalt)
	}
}
