// Package vault 位置轨迹存储层测试：月表动态建表、幂等写入、跨月范围查询/删除。
// 对齐 db 包测试方式：modernc.org/sqlite 纯 Go 驱动 + t.TempDir() 临时库（无 CGO）。
package vault

import (
	"database/sql"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

// 月归属边界锚点：2024-01-31T23:59:59.999Z（1 月最后一毫秒）与
// 2024-02-01T00:00:00.000Z（2 月第一毫秒），用于验证块 start_ts 恰在月末/月初各归其月。
var (
	janEndMs   = time.Date(2024, 1, 31, 23, 59, 59, 999_000_000, time.UTC).UnixMilli()
	febStartMs = time.Date(2024, 2, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	janMidMs   = time.Date(2024, 1, 15, 12, 0, 0, 0, time.UTC).UnixMilli()
)

// openTestDB 在临时目录打开空 SQLite 库（单连接，与 db.Open 同口径避免写锁竞争）。
func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	database, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	database.SetMaxOpenConns(1)
	t.Cleanup(func() { database.Close() })
	return database
}

// mkBlock 构造一个测试块；CreatedAt 故意填客户端旧值，验证服务端 now 覆盖语义。
func mkBlock(id, deviceID string, startTs int64) LocationBlock {
	return LocationBlock{
		ID:         id,
		DeviceID:   deviceID,
		StartTs:    startTs,
		EndTs:      startTs + 60_000,
		PointCount: 42,
		Cipher:     []byte{0xDE, 0xAD, 0xBE, 0xEF},
		CreatedAt:  1, // 客户端值应被 UpsertBlocks 的 now 覆盖
	}
}

// locTableExists 报告指定月表是否已建。
func locTableExists(t *testing.T, database *sql.DB, name string) bool {
	t.Helper()
	var n int
	if err := database.QueryRow(
		`SELECT COUNT(1) FROM sqlite_master WHERE type='table' AND name=?`, name).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n == 1
}

// locRowCount 返回指定月表当前行数。
func locRowCount(t *testing.T, database *sql.DB, table string) int {
	t.Helper()
	var n int
	if err := database.QueryRow(`SELECT COUNT(1) FROM ` + table).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// TestMonthTableBoundary 月表名纯函数：月末 23:59:59.999 UTC 与次月 00:00:00.000 各归其月。
func TestMonthTableBoundary(t *testing.T) {
	if got := monthTable(janEndMs); got != "locations_202401" {
		t.Errorf("月末毫秒应归 locations_202401，实际 %s", got)
	}
	if got := monthTable(febStartMs); got != "locations_202402" {
		t.Errorf("月初毫秒应归 locations_202402，实际 %s", got)
	}
}

// TestUpsertBlocksAcrossMonths 跨两月写入：两张月表动态建成、各表行数与归属正确。
func TestUpsertBlocksAcrossMonths(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	now := time.Date(2024, 3, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	// 两块分属 1 月末与 2 月初（边界毫秒，验证月归属）。
	blocks := []LocationBlock{
		mkBlock("dev1:jan", "dev1", janEndMs),
		mkBlock("dev1:feb", "dev1", febStartMs),
	}
	applied, skipped, err := store.UpsertBlocks("u1", blocks, now)
	if err != nil {
		t.Fatal(err)
	}
	if applied != 2 || skipped != 0 {
		t.Errorf("applied/skipped 应为 2/0，实际 %d/%d", applied, skipped)
	}
	// 两张月表建成且各 1 行（月末块归 1 月、月初块归 2 月）。
	if !locTableExists(t, database, "locations_202401") || !locTableExists(t, database, "locations_202402") {
		t.Fatal("跨月写入后两张月表应均已建成")
	}
	if n := locRowCount(t, database, "locations_202401"); n != 1 {
		t.Errorf("locations_202401 应有 1 行，实际 %d", n)
	}
	if n := locRowCount(t, database, "locations_202402"); n != 1 {
		t.Errorf("locations_202402 应有 1 行，实际 %d", n)
	}
	// created_at 一律以服务端 now 覆盖（客户端值 1 不可信）。
	var createdAt int64
	if err := database.QueryRow(
		`SELECT created_at FROM locations_202401 WHERE id='dev1:jan'`).Scan(&createdAt); err != nil {
		t.Fatal(err)
	}
	if createdAt != now {
		t.Errorf("created_at 应为服务端 now=%d，实际 %d", now, createdAt)
	}
	// 范围查询索引已随建表创建。
	var idx int
	if err := database.QueryRow(`SELECT COUNT(1) FROM sqlite_master
		WHERE type='index' AND name='idx_locations_202401_user_ts'`).Scan(&idx); err != nil {
		t.Fatal(err)
	}
	if idx != 1 {
		t.Error("idx_locations_202401_user_ts 索引未创建")
	}
}

// TestUpsertBlocksIdempotent 同 id 重复提交：第二次全部计 skipped，行数不增（块不可变幂等）。
func TestUpsertBlocksIdempotent(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	now := time.Date(2024, 3, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	blocks := []LocationBlock{mkBlock("dev1:a", "dev1", janMidMs)}

	applied, skipped, err := store.UpsertBlocks("u1", blocks, now)
	if err != nil {
		t.Fatal(err)
	}
	if applied != 1 || skipped != 0 {
		t.Fatalf("首写 applied/skipped 应为 1/0，实际 %d/%d", applied, skipped)
	}
	// 整批原样重提（含相同 id）：幂等跳过，不重复建行。
	applied, skipped, err = store.UpsertBlocks("u1", blocks, now)
	if err != nil {
		t.Fatal(err)
	}
	if applied != 0 || skipped != 1 {
		t.Errorf("重提 applied/skipped 应为 0/1，实际 %d/%d", applied, skipped)
	}
	if n := locRowCount(t, database, "locations_202401"); n != 1 {
		t.Errorf("幂等重提后行数应保持 1，实际 %d", n)
	}
}

// TestListRangeAcrossMonthsSorted 跨月 ListRange：按 start_ts 升序返回，月归属边界正确。
func TestListRangeAcrossMonthsSorted(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	now := time.Date(2024, 3, 1, 0, 0, 0, 0, time.UTC).UnixMilli()

	// 故意乱序写入三块（2 月初 → 1 月中 → 1 月末），验证返回顺序与写入顺序无关。
	blocks := []LocationBlock{
		mkBlock("dev1:feb-first", "dev1", febStartMs),
		mkBlock("dev1:jan-mid", "dev1", janMidMs),
		mkBlock("dev1:jan-last", "dev1", janEndMs),
	}
	if _, _, err := store.UpsertBlocks("u1", blocks, now); err != nil {
		t.Fatal(err)
	}

	got, err := store.ListRange("u1", janMidMs, febStartMs)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 3 {
		t.Fatalf("跨月范围应返回 3 块，实际 %d", len(got))
	}
	// 严格按 start_ts 升序：1 月中 → 1 月末 → 2 月初。
	wantIDs := []string{"dev1:jan-mid", "dev1:jan-last", "dev1:feb-first"}
	for i, id := range wantIDs {
		if got[i].ID != id {
			t.Errorf("第 %d 块应为 %s，实际 %s", i, id, got[i].ID)
		}
	}
	// 字段透传完整（UserID 回填、密文原样）。
	if got[0].UserID != "u1" || got[0].PointCount != 42 || len(got[0].Cipher) != 4 {
		t.Errorf("块字段透传异常: %+v", got[0])
	}
	// 仅查 2 月范围：只命中月初块（月末块归 1 月表，不串月）。
	got, err = store.ListRange("u1", febStartMs, febStartMs+86_400_000)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].ID != "dev1:feb-first" {
		t.Errorf("2 月范围应只返回 dev1:feb-first，实际 %+v", got)
	}
	// 其他用户隔离：同范围查 u2 应为空。
	got, err = store.ListRange("u2", janMidMs, febStartMs)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Errorf("u2 不应看到 u1 的块，实际 %d 块", len(got))
	}
}

// TestListRangeEmptyDB 空库 ListRange：月表不存在路径不报错、返回空。
func TestListRangeEmptyDB(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	got, err := store.ListRange("u1", janMidMs, febStartMs)
	if err != nil {
		t.Fatalf("空库 ListRange 不应报错: %v", err)
	}
	if len(got) != 0 {
		t.Errorf("空库应返回 0 块，实际 %d", len(got))
	}
}

// TestDeleteRangeEmptyDB 空库 DeleteRange：月表不存在路径不报错、返回 0。
func TestDeleteRangeEmptyDB(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	n, err := store.DeleteRange("u1", janMidMs, febStartMs)
	if err != nil {
		t.Fatalf("空库 DeleteRange 不应报错: %v", err)
	}
	if n != 0 {
		t.Errorf("空库删除数应为 0，实际 %d", n)
	}
}

// TestDeleteRangeAcrossMonths 跨月删除：返回正确计数、范围外块保留、月表本身保留。
func TestDeleteRangeAcrossMonths(t *testing.T) {
	database := openTestDB(t)
	store := NewLocationStore(database)
	now := time.Date(2024, 3, 1, 0, 0, 0, 0, time.UTC).UnixMilli()
	blocks := []LocationBlock{
		mkBlock("dev1:jan-mid", "dev1", janMidMs),
		mkBlock("dev1:jan-last", "dev1", janEndMs),
		mkBlock("dev1:feb-first", "dev1", febStartMs),
	}
	if _, _, err := store.UpsertBlocks("u1", blocks, now); err != nil {
		t.Fatal(err)
	}

	// 跨两月全范围删除：3 块全删。
	n, err := store.DeleteRange("u1", janMidMs, febStartMs)
	if err != nil {
		t.Fatal(err)
	}
	if n != 3 {
		t.Errorf("跨月删除应返回 3，实际 %d", n)
	}
	if r := locRowCount(t, database, "locations_202401"); r != 0 {
		t.Errorf("locations_202401 删后应为 0 行，实际 %d", r)
	}
	if r := locRowCount(t, database, "locations_202402"); r != 0 {
		t.Errorf("locations_202402 删后应为 0 行，实际 %d", r)
	}

	// 重灌后只删 1 月范围：2 月块保留（BETWEEN 含端点，1 月末块在范围内）。
	if _, _, err := store.UpsertBlocks("u1", blocks, now); err != nil {
		t.Fatal(err)
	}
	n, err = store.DeleteRange("u1", janMidMs, janEndMs)
	if err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Errorf("1 月范围删除应返回 2，实际 %d", n)
	}
	got, err := store.ListRange("u1", janMidMs, febStartMs)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0].ID != "dev1:feb-first" {
		t.Errorf("删除后应仅剩 dev1:feb-first，实际 %+v", got)
	}
}
