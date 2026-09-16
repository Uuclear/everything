package com.everything.eve.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TR-12.1：Room v1（仅 records）→ v2（+sync_state）显式迁移。
 * 运行需要连接设备/模拟器（connectedDebugAndroidTest）；本机无设备时仅保留用例。
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EveDatabase::class.java,
        emptyList(),
    )

    /** v1 records 表结构（与 RecordEntity 字段一一对应）。 */
    private fun createV1RecordsSql(): String = """
        CREATE TABLE IF NOT EXISTS records (
            id TEXT NOT NULL PRIMARY KEY,
            module TEXT NOT NULL,
            type TEXT NOT NULL,
            ciphertext TEXT NOT NULL,
            version INTEGER NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            deleted INTEGER NOT NULL,
            dirty INTEGER NOT NULL
        )
    """.trimIndent()

    @Test
    fun migrate1To2_keepsRecordsAndAddsSyncState() {
        // 1) 建立 v1 库并插入 2 条密文记录（一条历史 module=note，一条新 module=pass）。
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(createV1RecordsSql())
            db.execSQL(
                """
                INSERT INTO records
                  (id, module, type, ciphertext, version, createdAt, updatedAt, deleted, dirty)
                VALUES
                  ('r1', 'note', 'secure_note', 'Y0==', 1, 1000, 1000, 0, 0),
                  ('r2', 'pass', 'note',         'Yg==', 1, 2000, 2000, 0, 1)
                """.trimIndent(),
            )
        }

        // 2) 跑 1→2 迁移并按 v2 实体校验 schema。
        val db = helper.runMigrationsAndValidate(
            dbName, 2, true, EveDatabase.MIGRATION_1_2,
        )

        // 3) records 行数与内容不变（密文零丢失）。
        db.query("SELECT COUNT(*) FROM records").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT module, type, version, dirty FROM records WHERE id='r2'").use { c ->
            c.moveToFirst()
            assertEquals("pass", c.getString(0))
            assertEquals("note", c.getString(1))
            assertEquals(1, c.getLong(2))
            assertEquals(1, c.getInt(3))
        }

        // 4) sync_state 可写可读。
        db.execSQL(
            "INSERT INTO sync_state (`key`, value) VALUES ('last_successful_sync', '12345')",
        )
        db.query("SELECT value FROM sync_state WHERE `key`='last_successful_sync'").use { c ->
            c.moveToFirst()
            assertEquals("12345", c.getString(0))
        }
        db.close()
    }

    /** 阶段 3：v2 → v3 新增 collector_state 表，records/sync_state 数据不受影响。 */
    @Test
    fun migrate2To3_addsCollectorStateAndKeepsData() {
        // 1) 建立 v2 库：records 两行 + sync_state 一行（模拟阶段 2 收尾时的真实形态）。
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(createV1RecordsSql())
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    `key` TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO records
                  (id, module, type, ciphertext, version, createdAt, updatedAt, deleted, dirty)
                VALUES
                  ('r1', 'note', 'secure_note', 'Y0==', 1, 1000, 1000, 0, 0),
                  ('r2', 'pass', 'note',         'Yg==', 1, 2000, 2000, 0, 1)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO sync_state (`key`, value) VALUES ('last_successful_sync', '12345')",
            )
        }

        // 2) 跑 2→3 迁移并按 v3 实体校验 schema（列名/可空性由 Room 比对）。
        val db = helper.runMigrationsAndValidate(
            dbName, 3, true, EveDatabase.MIGRATION_2_3,
        )

        // 3) 旧数据零丢失：records 仍 2 行、sync_state 值不变。
        db.query("SELECT COUNT(*) FROM records").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT value FROM sync_state WHERE `key`='last_successful_sync'").use { c ->
            c.moveToFirst()
            assertEquals("12345", c.getString(0))
        }

        // 4) collector_state 可写可读：写入一行游标并回读校验。
        db.execSQL(
            """
            INSERT INTO collector_state
              (kind, lastTimestamp, lastSystemId, lastRunAt, lastScannedCount, lastSkipReason)
            VALUES ('sms', 1726000000000, 42, 1726000001000, 7, NULL)
            """.trimIndent(),
        )
        db.query(
            "SELECT lastTimestamp, lastSystemId, lastScannedCount FROM collector_state WHERE kind='sms'",
        ).use { c ->
            c.moveToFirst()
            assertEquals(1726000000000L, c.getLong(0))
            assertEquals(42L, c.getLong(1))
            assertEquals(7, c.getInt(2))
        }
        db.close()
    }

    /**
     * 直读 PRAGMA table_info：列名 → (类型, 是否 NOT NULL, 是否主键)。
     * 用于 runMigrationsAndValidate（Room 实体比对）之外，显式核对 SQLite 层真实表结构。
     */
    private fun readTableInfo(
        db: SupportSQLiteDatabase,
        table: String,
    ): Map<String, Triple<String, Boolean, Boolean>> {
        val cols = mutableMapOf<String, Triple<String, Boolean, Boolean>>()
        db.query("PRAGMA table_info($table)").use { c ->
            // 游标列序固定：cid, name, type, notnull, dflt_value, pk。
            while (c.moveToNext()) {
                cols[c.getString(1)] = Triple(c.getString(2), c.getInt(3) == 1, c.getInt(5) == 1)
            }
        }
        return cols
    }

    /** 阶段 4a：v3 → v4 新增 location_points / location_outbox，旧三表数据保留。 */
    @Test
    fun migrate3To4_addsLocationTablesAndKeepsData() {
        // 1) 建立 v3 库：records 两行 + sync_state 一行 + collector_state 一行
        //    （模拟阶段 3 收尾时的真实形态，三表结构与对应迁移 SQL 逐字一致）。
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(createV1RecordsSql())
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS sync_state (
                    `key` TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS collector_state (
                    kind TEXT NOT NULL PRIMARY KEY,
                    lastTimestamp INTEGER NOT NULL DEFAULT 0,
                    lastSystemId INTEGER NOT NULL DEFAULT 0,
                    lastRunAt INTEGER,
                    lastScannedCount INTEGER NOT NULL DEFAULT 0,
                    lastSkipReason TEXT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO records
                  (id, module, type, ciphertext, version, createdAt, updatedAt, deleted, dirty)
                VALUES
                  ('r1', 'note', 'secure_note', 'Y0==', 1, 1000, 1000, 0, 0),
                  ('r2', 'pass', 'note',         'Yg==', 1, 2000, 2000, 0, 1)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO sync_state (`key`, value) VALUES ('last_successful_sync', '12345')",
            )
            db.execSQL(
                """
                INSERT INTO collector_state
                  (kind, lastTimestamp, lastSystemId, lastRunAt, lastScannedCount, lastSkipReason)
                VALUES ('sms', 1726000000000, 42, 1726000001000, 7, NULL)
                """.trimIndent(),
            )
        }

        // 2) 跑 3→4 迁移并按 v4 实体校验 schema（列名/类型/可空性/默认值/索引由 Room 比对）。
        val db = helper.runMigrationsAndValidate(
            dbName, 4, true, EveDatabase.MIGRATION_3_4,
        )

        // 3) 旧三表数据零丢失：records 仍 2 行、sync_state 值不变、collector_state 游标不变。
        db.query("SELECT COUNT(*) FROM records").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        db.query("SELECT value FROM sync_state WHERE `key`='last_successful_sync'").use { c ->
            c.moveToFirst()
            assertEquals("12345", c.getString(0))
        }
        db.query(
            "SELECT lastTimestamp, lastScannedCount FROM collector_state WHERE kind='sms'",
        ).use { c ->
            c.moveToFirst()
            assertEquals(1726000000000L, c.getLong(0))
            assertEquals(7, c.getInt(1))
        }

        // 4) 两新表列/PK 正确（PRAGMA table_info 直读 SQLite 层逐列核对）。
        //    Triple = (类型, NOT NULL, PK)。
        val pointCols = readTableInfo(db, "location_points")
        assertEquals(10, pointCols.size)
        // 自增主键 id；非空采样四元组 ts/lat/lon/acc + 非空入库时间 created_at。
        assertEquals(Triple("INTEGER", true, true), pointCols["id"])
        assertEquals(Triple("INTEGER", true, false), pointCols["ts"])
        assertEquals(Triple("REAL", true, false), pointCols["lat"])
        assertEquals(Triple("REAL", true, false), pointCols["lon"])
        assertEquals(Triple("REAL", true, false), pointCols["acc"])
        assertEquals(Triple("INTEGER", true, false), pointCols["created_at"])
        // 可空增强字段 speed/bearing/altitude/provider。
        assertEquals(Triple("REAL", false, false), pointCols["speed"])
        assertEquals(Triple("REAL", false, false), pointCols["bearing"])
        assertEquals(Triple("REAL", false, false), pointCols["altitude"])
        assertEquals(Triple("TEXT", false, false), pointCols["provider"])

        val outboxCols = readTableInfo(db, "location_outbox")
        assertEquals(7, outboxCols.size)
        // 块 id 文本主键（幂等锚点）+ 块头三元组 + 密文 + 入队时间 + 重试计数。
        assertEquals(Triple("TEXT", true, true), outboxCols["block_id"])
        assertEquals(Triple("INTEGER", true, false), outboxCols["start_ts"])
        assertEquals(Triple("INTEGER", true, false), outboxCols["end_ts"])
        assertEquals(Triple("INTEGER", true, false), outboxCols["point_count"])
        assertEquals(Triple("BLOB", true, false), outboxCols["cipher"])
        assertEquals(Triple("INTEGER", true, false), outboxCols["created_at"])
        assertEquals(Triple("INTEGER", true, false), outboxCols["attempts"])

        // 5) location_points 基本读写：一行全字段 + 一行仅必填（可空列写 NULL）。
        db.execSQL(
            """
            INSERT INTO location_points
              (ts, lat, lon, acc, speed, bearing, altitude, provider, created_at)
            VALUES
              (1726000000000, 31.2304, 121.4737, 12.5, 3.2, 90.0, 15.0, 'gps', 1726000000001),
              (1726000060000, 31.2305, 121.4738, 20.0, NULL, NULL, NULL, NULL, 1726000060001)
            """.trimIndent(),
        )
        db.query("SELECT COUNT(*) FROM location_points").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }
        // 按 ts 升序回读：首行 id 自增为 1、全字段正确；次行可空字段为 NULL。
        db.query(
            "SELECT id, ts, lat, acc, speed, provider FROM location_points ORDER BY ts ASC",
        ).use { c ->
            c.moveToFirst()
            assertEquals(1L, c.getLong(0)) // AUTOINCREMENT 自增主键
            assertEquals(1726000000000L, c.getLong(1))
            assertEquals(31.2304, c.getDouble(2), 1e-6)
            assertEquals(12.5, c.getDouble(3), 1e-6)
            assertEquals(3.2, c.getDouble(4), 1e-6)
            assertEquals("gps", c.getString(5))
            c.moveToNext()
            assertEquals(2L, c.getLong(0))
            assertTrue(c.isNull(4)) // speed 未提供 → NULL
            assertTrue(c.isNull(5)) // provider 未知 → NULL
        }

        // 6) location_outbox 基本读写 + 块 id 幂等：
        //    首次 INSERT 不写 attempts（验证列缺省 DEFAULT 0 生效）；
        //    同 block_id INSERT OR IGNORE 不再增行（AC-3 幂等锚点）。
        db.execSQL(
            """
            INSERT INTO location_outbox
              (block_id, start_ts, end_ts, point_count, cipher, created_at)
            VALUES ('dev1:1726000000000:1726000060000', 1726000000000, 1726000060000, 2, X'010203', 1726000060002)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO location_outbox
              (block_id, start_ts, end_ts, point_count, cipher, created_at)
            VALUES ('dev1:1726000000000:1726000060000', 1726000000000, 1726000060000, 2, X'010203', 1726000060002)
            """.trimIndent(),
        )
        db.query(
            "SELECT point_count, cipher, attempts FROM location_outbox " +
                "WHERE block_id='dev1:1726000000000:1726000060000'",
        ).use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
            assertEquals(3, c.getBlob(1).size) // 密文 BLOB 原样回读
            assertEquals(0, c.getInt(2)) // attempts 缺省 DEFAULT 0
        }
        db.query("SELECT COUNT(*) FROM location_outbox").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0)) // OR IGNORE 去重：同 id 不重复入队
        }
        db.close()
    }
}
