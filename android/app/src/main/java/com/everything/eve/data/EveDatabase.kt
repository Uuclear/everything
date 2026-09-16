package com.everything.eve.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.everything.eve.collector.location.db.LocationDao
import com.everything.eve.collector.location.db.LocationOutboxEntity
import com.everything.eve.collector.location.db.LocationPointEntity
import com.everything.eve.data.event.EventDao
import com.everything.eve.data.event.EventEntity
import com.everything.eve.data.event.EventReminderLogDao
import com.everything.eve.data.event.EventReminderLogEntity

@Database(
    entities = [
        RecordEntity::class,
        SyncStateEntity::class,
        CollectorStateEntity::class,
        LocationPointEntity::class,
        LocationOutboxEntity::class,
        // 阶段 4b：日程/日历模块（v5 迁移新增）
        EventEntity::class,
        EventReminderLogEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class EveDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao

    abstract fun collectorStateDao(): CollectorStateDao

    abstract fun locationDao(): LocationDao

    /** 阶段 4b：日程事件 DAO（v5 迁移新增）。 */
    abstract fun eventDao(): EventDao

    /** 阶段 4b：提醒降级日志 DAO（v5 迁移新增）。 */
    abstract fun eventReminderLogDao(): EventReminderLogDao

    companion object {
        /**
         * v1 → v2：新增 sync_state 单键值表（记录 last_successful_sync 等）。
         * records 表结构不变；用显式迁移替代破坏性重建，保证升级后密文记录零丢失。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v2 → v3：新增 collector_state 表（阶段 3 采集器游标/运行状态）。
         * 只建表，不触碰 records / sync_state，保证既有密文记录与同步状态零影响。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
            }
        }

        /**
         * v3 → v4：新增 location_points（轨迹明文缓冲）与 location_outbox（密文块上行队列）。
         *
         * 仅 CREATE TABLE IF NOT EXISTS + 索引（TR-4.2），不 ALTER/DROP 既有三表，
         * 保证 records / sync_state / collector_state 数据零影响。
         *
         * location_points 是全库唯一明文坐标驻留点，其 (ts) 索引服务于
         * 封块全取（ts ASC）、已封段删除、24h 过期清理、今日点数统计；
         * location_outbox 只存密文块，attempts 缺省 DEFAULT 0。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 明文缓冲点表：ts/lat/lon/acc/created_at 非空，speed/bearing/altitude/provider 可空。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS location_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ts INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lon REAL NOT NULL,
                        acc REAL NOT NULL,
                        speed REAL,
                        bearing REAL,
                        altitude REAL,
                        provider TEXT,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_location_points_ts ON location_points (ts)",
                )
                // 密文块上行队列表：block_id 文本主键（幂等锚点），attempts 缺省 0。
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS location_outbox (
                        block_id TEXT NOT NULL PRIMARY KEY,
                        start_ts INTEGER NOT NULL,
                        end_ts INTEGER NOT NULL,
                        point_count INTEGER NOT NULL,
                        cipher BLOB NOT NULL,
                        created_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v4 → v5：新增 event（事件明文缓存）与 event_reminder_log（提醒降级日志）。
         *
         * 与 4a v3→v4 同模式：仅 CREATE TABLE IF NOT EXISTS + 索引，不 ALTER/DROP
         * 既有五表（records / sync_state / collector_state / location_points /
         * location_outbox），保证既有数据零影响。
         *
         * event 表（spec FR-4 字段表）：
         *  - id TEXT PRIMARY KEY（UUID）；12 列与 EventEntity 字段一一对应；
         *  - tz_mode / exdates_json 两列带缺省（"local" / "[]"），与 @ColumnInfo
         *    defaultValue 同语义，旧库升级遇到漏值时 SQLite 层兜底；
         *  - 索引 (start_ts) 服务窗口查询与 reminder 下一触发计算，
         *    (dirty) 服务同步推送对账（理论上与 records 表 dirty 联动）。
         *
         * event_reminder_log 表（spec FR-6 降级日志）：
         *  - 自增 INTEGER PRIMARY KEY AUTOINCREMENT；
         *  - kind 仅三枚举（alarm_killed / notification_denied / exact_denied），
         *    严禁写 title/location/note/rrule 等明文（NFR-1）。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 步骤 1：建 event 表（spec FR-4 字段表，与 EventEntity 列一一对应）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS event (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        start_ts INTEGER NOT NULL,
                        end_ts INTEGER NOT NULL,
                        all_day INTEGER NOT NULL,
                        tz_mode TEXT NOT NULL DEFAULT 'local',
                        location_text TEXT,
                        note TEXT,
                        color TEXT NOT NULL,
                        reminders_json TEXT NOT NULL,
                        rrule_json TEXT,
                        exdates_json TEXT NOT NULL DEFAULT '[]',
                        dirty INTEGER NOT NULL,
                        updated_ts INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // ---- 步骤 2：建 event 表索引（spec FR-4 明示）----
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_event_start_ts ON event (start_ts)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_event_dirty ON event (dirty)",
                )
                // ---- 步骤 3：建 event_reminder_log 表（spec FR-6）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS event_reminder_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        event_id TEXT NOT NULL,
                        occurrence_ts INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        created_ts INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): EveDatabase =
            Room.databaseBuilder(context, EveDatabase::class.java, "eve.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
