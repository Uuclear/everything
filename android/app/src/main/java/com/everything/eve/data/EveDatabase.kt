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
import com.everything.eve.data.finance.dao.AttachmentDao
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceRateDao
import com.everything.eve.data.finance.dao.FinanceReminderLogDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.data.finance.entity.AttachmentEntity
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceRateEntity
import com.everything.eve.data.finance.entity.FinanceReminderLogEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity

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
        // 阶段 5：财务模块（v6 迁移新增——账户/卡/流水/提醒日志四表）
        FinanceAccountEntity::class,
        FinanceCardEntity::class,
        FinanceTxEntity::class,
        FinanceReminderLogEntity::class,
        // 阶段 5 v2：财务附件本地缓存（v7 迁移新增——密文 envelope 缓存表）
        AttachmentEntity::class,
        // 阶段 5 v2 / B5：离线汇率本地缓存（v8 迁移新增——按货币对拆行的汇率表）
        FinanceRateEntity::class,
    ],
    version = 8,
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

    /** 阶段 5：财务账户 DAO（v6 迁移新增）。 */
    abstract fun financeAccountDao(): FinanceAccountDao

    /** 阶段 5：财务银行卡 DAO（v6 迁移新增）。 */
    abstract fun financeCardDao(): FinanceCardDao

    /** 阶段 5：财务流水 DAO（v6 迁移新增）。 */
    abstract fun financeTxDao(): FinanceTxDao

    /** 阶段 5：财务提醒降级日志 DAO（v6 迁移新增）。 */
    abstract fun financeReminderLogDao(): FinanceReminderLogDao

    /** 阶段 5 v2：财务附件本地缓存 DAO（v7 迁移新增）。 */
    abstract fun attachmentDao(): AttachmentDao

    /** 阶段 5 v2 / B5：离线汇率本地缓存 DAO（v8 迁移新增）。 */
    abstract fun financeRateDao(): FinanceRateDao

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

        /**
         * v5 → v6：新增财务四张表（账户/银行卡/流水/提醒日志）。
         *
         * 与 4a v3→v4 / 4b v4→v5 同模式：仅 CREATE TABLE IF NOT EXISTS + 索引，
         * 不 ALTER/DROP 既有七表（records / sync_state / collector_state /
         * location_points / location_outbox / event / event_reminder_log），
         * 保证既有数据零影响。
         *
         * finance_account（spec FR-1.1 字段表）：
         *  - 14 列（含 schema_version / module / type / dirty / deleted 五列系统
         *    字段，与 FinanceAccountEntity 一一对应）；
         *  - 索引 (updated_at) 服务增量同步游标，(dirty) 服务同步推送对账。
         *
         * finance_card（spec FR-1.2 字段表）：
         *  - 21 列（含 credit_limit / used_limit / billing_day / due_day /
         *    brand / expiry_month / expiry_year 等信用卡专用字段）；
         *  - **零知识红线**：卡号完整 PAN **不入库**；本表只存 `last4`
         *    （已通过 Luhn 校验的末四位）。
         *
         * finance_tx（spec FR-1.3 字段表）：
         *  - 18 列（含 account_id / card_id / transfer_to_account_id 三组关联
         *    字段，关联账户/卡被删除后保留为历史引用）；
         *  - 索引 (occurred_at) 服务按时间排序与月报聚合，
         *    (account_id) 服务按账户过滤。
         *
         * finance_reminder_log（spec FR-4）：
         *  - 自增 INTEGER PRIMARY KEY AUTOINCREMENT；
         *  - 4 列（ref_id / ref_kind / fire_at / delivered）全 NOT NULL；
         *  - 严禁写 title/amount/last4 等明文（NFR-1 零知识红线）；
         *  - ref_kind 五枚举（v1 启用前两类，v2 占位后三类）。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 步骤 1：建 finance_account 表（spec FR-1.1）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_account (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        balance TEXT NOT NULL,
                        note TEXT,
                        icon TEXT,
                        color TEXT,
                        archived INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        module TEXT NOT NULL DEFAULT 'finance',
                        type TEXT NOT NULL DEFAULT 'account',
                        dirty INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_account_updated_at " +
                        "ON finance_account (updated_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_account_dirty " +
                        "ON finance_account (dirty)",
                )

                // ---- 步骤 2：建 finance_card 表（spec FR-1.2）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_card (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        issuer TEXT NOT NULL,
                        last4 TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        credit_limit TEXT,
                        used_limit TEXT,
                        billing_day INTEGER,
                        due_day INTEGER,
                        brand TEXT,
                        expiry_month INTEGER,
                        expiry_year INTEGER,
                        holder TEXT,
                        note TEXT,
                        icon TEXT,
                        color TEXT,
                        archived INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        module TEXT NOT NULL DEFAULT 'finance',
                        type TEXT NOT NULL DEFAULT 'card',
                        dirty INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_card_updated_at " +
                        "ON finance_card (updated_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_card_dirty " +
                        "ON finance_card (dirty)",
                )

                // ---- 步骤 3：建 finance_tx 表（spec FR-1.3）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_tx (
                        id TEXT NOT NULL PRIMARY KEY,
                        account_id TEXT NOT NULL,
                        card_id TEXT,
                        kind TEXT NOT NULL,
                        amount TEXT NOT NULL,
                        currency TEXT NOT NULL,
                        category TEXT NOT NULL,
                        occurred_at INTEGER NOT NULL,
                        note TEXT,
                        icon TEXT,
                        color TEXT,
                        transfer_to_account_id TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        module TEXT NOT NULL DEFAULT 'finance',
                        type TEXT NOT NULL DEFAULT 'tx',
                        dirty INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_tx_updated_at " +
                        "ON finance_tx (updated_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_tx_occurred_at " +
                        "ON finance_tx (occurred_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_tx_account_id " +
                        "ON finance_tx (account_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_tx_dirty " +
                        "ON finance_tx (dirty)",
                )

                // ---- 步骤 4：建 finance_reminder_log 表（spec FR-4）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_reminder_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ref_id TEXT NOT NULL,
                        ref_kind TEXT NOT NULL,
                        fire_at INTEGER NOT NULL,
                        delivered INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_reminder_log_fire_at " +
                        "ON finance_reminder_log (fire_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_reminder_log_ref " +
                        "ON finance_reminder_log (ref_id, ref_kind)",
                )
            }
        }

        /**
         * v6 → v7：新增 `finance_attachment`（财务附件本地密文缓存表）。
         *
         * 与既有 v5→v6 同模式：仅 CREATE TABLE IF NOT EXISTS + 索引，
         * 不 ALTER/DROP 既有十一表（records / sync_state / collector_state /
         * location_points / location_outbox / event / event_reminder_log /
         * finance_account / finance_card / finance_tx / finance_reminder_log），
         * 保证既有数据零影响。
         *
         * finance_attachment（spec TR-3.1 / 附件本地缓存）：
         *  - 12 列（含 schema_version / module 五列系统字段，与 AttachmentEntity
         *    一一对应；encrypted_payload 字节字段存本地 Room 缓存密文——用 vault
         *    masterKey 再封一层 envelope 的内容，与服务端 records 通道外发的
         *    ciphertext 是不同密文）；
         *  - **零知识红线**：mime / size / sha256 / encrypted_payload 仅本地用，
         *    不进 SharedPreferences / 日志 / 通知文案；
         *  - SQL 完全用 SQLite 兼容类型（INTEGER / TEXT / BLOB），不依赖 Room
         *    类型转换，便于 v6 旧库升级到 v7 时无差异执行；
         *  - 索引 (record_id) 服务按父记录（policy/contract）筛选附件；
         *    (updated_at) 服务增量同步游标；
         *    (dirty) 服务同步推送对账。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 步骤 1：建 finance_attachment 表（spec TR-3.1）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_attachment (
                        id TEXT NOT NULL PRIMARY KEY,
                        record_id TEXT NOT NULL,
                        mime TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        encrypted_payload BLOB NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        module TEXT NOT NULL DEFAULT 'finance',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        dirty INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // ---- 步骤 2：建 finance_attachment 表索引（spec TR-3.1 明示）----
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_attachment_record_id " +
                        "ON finance_attachment (record_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_attachment_updated_at " +
                        "ON finance_attachment (updated_at)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_attachment_dirty " +
                        "ON finance_attachment (dirty)",
                )
            }
        }

        /**
         * v7 → v8：新增 `finance_rate`（离线汇率本地缓存表，B5 多币种折算）。
         *
         * 与既有 v6→v7 同模式：仅 CREATE TABLE IF NOT EXISTS + 索引，
         * 不 ALTER/DROP 既有十二表，保证既有数据零影响。
         *
         * finance_rate（spec FR-V2-C.2 汇率包本地缓存）：
         *  - 12 列，与 FinanceRateEntity 一一对应；id 为确定性键
         *    "${base}/${quote}@${effective_ts}"，同生效时刻重复导入 REPLACE 幂等；
         *  - encrypted_payload TEXT NOT NULL：本地导入存空串占位（密文以 records
         *    通道 type=rate 行为准），pull 下行写服务端 base64 密文；
         *  - 索引 (currency_base, currency_quote) 按货币对查询、
         *    (effective_ts) 取 MAX 最新生效时刻、(dirty) 同步推送对账。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 步骤 1：建 finance_rate 表（列定义与 Room 实体逐列对齐）----
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS finance_rate (
                        id TEXT NOT NULL PRIMARY KEY,
                        currency_base TEXT NOT NULL,
                        currency_quote TEXT NOT NULL,
                        rate REAL NOT NULL,
                        effective_ts INTEGER NOT NULL,
                        encrypted_payload TEXT NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1,
                        module TEXT NOT NULL DEFAULT 'finance',
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        dirty INTEGER NOT NULL DEFAULT 1,
                        deleted INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                // ---- 步骤 2：建 finance_rate 表索引 ----
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_rate_pair " +
                        "ON finance_rate (currency_base, currency_quote)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_rate_effective_ts " +
                        "ON finance_rate (effective_ts)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS idx_finance_rate_dirty " +
                        "ON finance_rate (dirty)",
                )
            }
        }

        fun build(context: Context): EveDatabase =
            Room.databaseBuilder(context, EveDatabase::class.java, "eve.db")
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                )
                .build()
    }
}
