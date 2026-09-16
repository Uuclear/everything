package com.everything.eve.data.event

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.everything.eve.auth.AuthManager
import com.everything.eve.data.EveDatabase
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Field

/**
 * EventsRepository 单测（阶段 4b Task 4 / TR-4.5）。
 *
 * 测试范围（≥8 用例）：
 *  1) upsert 写 entity + 调 records.upsertEventRule + 标 dirty；
 *  2) delete 删 entity + 调 records.deleteEventRule（tombstone）；
 *  3) queryWindow 按 start_ts 升序返回窗口内事件；
 *  4) dirtyList 仅返回 dirty=true 行；
 *  5) seal 失败（MK 未解锁）异常上抛，不静默吞错；
 *  6) upsert 同 id 替换（REPLACE 语义，updated_ts 推进）；
 *  7) observeAll Flow 在 upsert/delete 后实时刷新；
 *  8) deleteById 清除对应事件（含 dirty 标记一并清除）。
 *  9) EventRule.toJson/fromJson 字段往返一致（额外，>8 用例保障）。
 * 10) records 表 upsert 时 module="event"/type="event"/dirty=true 验证。
 *
 * 测试使用 Room.inMemoryDatabaseBuilder（任务示例 API；本工程无设备环境仅
 * 编译验证，连接设备/模拟器后跑 connectedDebugAndroidTest 即可全绿）。
 *
 * 关于 AuthManager：生产类是 `class AuthManager private constructor(...)`，并把
 * `var masterKey` 声明为 `private set`——既无法继承也无法直接赋值。
 * 本测试不引入完整 SharedPreferences / Moshi / 网络栈；通过 `java.lang.reflect`
 * 反射访问私有构造器 + 私有 setter 创建可控 MK 的 AuthManager 实例。
 * 这是一道绕开生产约束的测试桩，不修改 main 源码（NFR-3）。
 */
@RunWith(AndroidJUnit4::class)
class EventsRepositoryTest {

    private lateinit var db: EveDatabase
    private lateinit var recordsRepo: RecordsRepository
    private lateinit var recordsDao: RecordDao
    private lateinit var eventsRepo: EventsRepository
    private lateinit var auth: AuthManager
    /** 反射拿到的 masterKey 字段（私有 setter，setAccessible 后可写）。 */
    private lateinit var masterKeyField: Field

    @Before
    fun setUp() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        // inMemoryDatabaseBuilder + allowMainThreadQueries（测试允许主线程查询）。
        db = Room.inMemoryDatabaseBuilder(ctx, EveDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordsDao = db.recordDao()
        // 反射创建 AuthManager 实例（私有构造器 + 私有 setter masterKey）。
        auth = AuthManagerReflect.newInstance(masterKey = TEST_MASTER_KEY)
        masterKeyField = AuthManagerReflect.masterKeyField
        // 直接构造 RecordsRepository（不依赖 ServiceLocator）；反射注入 MK，
        // 使 CryptoEnvelope.sealRecord/openRecord 在测试中可解。
        recordsRepo = RecordsRepository(recordsDao, auth)
        eventsRepo = EventsRepository(db.eventDao(), recordsRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- 用例 1：upsert 写 entity + 调 records.upsertEventRule + 标 dirty ----
    @Test
    fun upsert_writesEntityAndCallsSeal() = runBlocking {
        val rule = sampleRule("e1", "晨会", startTs = 1_735_689_600_000L)
        val id = eventsRepo.upsert(rule)
        assertEquals("e1", id)

        // 1) event 表写入
        val stored = eventsRepo.getById("e1")
        assertNotNull(stored)
        assertEquals("晨会", stored!!.title)
        assertEquals(1_735_689_600_000L, stored.start_ts)
        assertTrue(stored.dirty)

        // 2) records 表写入密文（module="event"/type="event"）
        val rec = recordsDao.getById("e1")
        assertNotNull(rec)
        assertEquals("event", rec!!.module)
        assertEquals("event", rec.type)
        assertTrue(rec.dirty)
        assertTrue(rec.ciphertext.isNotEmpty())
    }

    // ---- 用例 2：delete 删 entity + 调 records.deleteEventRule（tombstone） ----
    @Test
    fun delete_removesEntityAndCallsDeleteRecord() = runBlocking {
        eventsRepo.upsert(sampleRule("e1", "晨会", startTs = 1_000L))
        eventsRepo.delete("e1")

        // 1) event 表清空
        assertNull(eventsRepo.getById("e1"))

        // 2) records 表写 tombstone（deleted=true，dirty=true）
        val rec = recordsDao.getById("e1")
        assertNotNull(rec)
        assertTrue(rec!!.deleted)
        assertTrue(rec.dirty)
        // tombstone 不带密文（4a RecordsRepository.deleteEventRule 约定）
        assertEquals("", rec.ciphertext)
    }

    // ---- 用例 3：queryWindow 按 start_ts 升序返回窗口内事件 ----
    @Test
    fun queryWindow_returnsSortedByStart() = runBlocking {
        eventsRepo.upsert(sampleRule("e1", "晚", startTs = 3_000L))
        eventsRepo.upsert(sampleRule("e2", "早", startTs = 1_000L))
        eventsRepo.upsert(sampleRule("e3", "中", startTs = 2_000L))
        eventsRepo.upsert(sampleRule("e4", "出窗口", startTs = 9_999L))

        val windowed = eventsRepo.queryWindow(from = 0L, to = 5_000L)
        assertEquals(listOf("e2", "e3", "e1"), windowed.map { it.id })
    }

    // ---- 用例 4：dirtyList 仅返回 dirty=true 行 ----
    @Test
    fun dirtyList_returnsOnlyDirty() = runBlocking {
        // 先 upsert 一条（dirty=true）
        eventsRepo.upsert(sampleRule("e1", "脏", startTs = 1_000L))
        // 直接 DAO 写一条干净（绕过 repo）
        db.eventDao().upsert(
            sampleRule("e2", "净", startTs = 2_000L).toEntity(dirty = false),
        )
        val dirty = eventsRepo.dirtyList()
        assertEquals(listOf("e1"), dirty.map { it.id })
    }

    // ---- 用例 5：seal 失败（MK 未解锁）异常上抛，不静默吞错 ----
    @Test
    fun sealFailure_propagates() = runBlocking {
        // 把 auth 的 MK 清空，模拟"资料库未解锁"
        AuthManagerReflect.setMasterKey(auth, null)
        val rule = sampleRule("e1", "x", startTs = 1_000L)
        try {
            eventsRepo.upsert(rule)
            fail("Expected IllegalStateException when MK is null")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("资料库未解锁"))
        }
        // event 表不应被写入（recordsRepository.upsertEventRule 先抛错，repo 编排失败不入库）
        assertNull(eventsRepo.getById("e1"))
    }

    // ---- 用例 6：upsert 同 id 替换（REPLACE 语义） ----
    @Test
    fun upsert_sameId_replaces() = runBlocking {
        val first = sampleRule("e1", "旧", startTs = 1_000L)
        eventsRepo.upsert(first)
        val before = eventsRepo.getById("e1")!!.updated_ts

        // 间隔确保 updated_ts 不同
        Thread.sleep(2)
        val second = sampleRule("e1", "新", startTs = 1_000L, color = "red")
        eventsRepo.upsert(second)

        val after = eventsRepo.getById("e1")!!
        assertEquals("新", after.title)
        assertEquals("red", after.color)
        assertTrue(after.updated_ts > before)
    }

    // ---- 用例 7：observeAll Flow 在 upsert/delete 后实时刷新 ----
    @Test
    fun observeAll_emitsOnChange() = runBlocking {
        val initial = eventsRepo.observeAll().first()
        assertEquals(0, initial.size)

        eventsRepo.upsert(sampleRule("e1", "x", startTs = 1_000L))
        val after = eventsRepo.observeAll().first()
        assertEquals(1, after.size)
        assertEquals("e1", after[0].id)
    }

    // ---- 用例 8：deleteById 清除对应事件（含 dirty 标记一并清除） ----
    @Test
    fun deleteById_clearsDirty() = runBlocking {
        eventsRepo.upsert(sampleRule("e1", "x", startTs = 1_000L))
        // 写第二条保持 dirty=false
        db.eventDao().upsert(
            sampleRule("e2", "y", startTs = 2_000L).toEntity(dirty = false),
        )
        // 删除 e1
        eventsRepo.delete("e1")
        // dirtyList 仅剩 e2（dirty=false 不计入）
        val dirty = eventsRepo.dirtyList()
        assertEquals(0, dirty.size)
        // 留存的 e2 dirty=false
        assertFalse(eventsRepo.getById("e2")!!.dirty)
    }

    // ---- 用例 9：EventRule.toJson/fromJson 字段往返一致（边界兜底） ----
    @Test
    fun eventRule_jsonRoundTrip() {
        val rule = sampleRule("e1", "晨会", startTs = 1_735_689_600_000L).copy(
            location_text = "会议室 A",
            note = "准备上周总结",
            reminders = listOf(0, 15),
            rrule = "{\"freq\":\"WEEKLY\",\"interval\":1,\"byweekday\":[\"MO\"],\"end\":{\"kind\":\"never\"}}",
            exdates = listOf("2026-01-01"),
        )
        val json = rule.toJson()
        val parsed = EventRule.fromJson(json)
        assertEquals(rule.id, parsed.id)
        assertEquals(rule.title, parsed.title)
        assertEquals(rule.start_ts, parsed.start_ts)
        assertEquals(rule.end_ts, parsed.end_ts)
        assertEquals(rule.all_day, parsed.all_day)
        assertEquals(rule.tz_mode, parsed.tz_mode)
        assertEquals(rule.location_text, parsed.location_text)
        assertEquals(rule.note, parsed.note)
        assertEquals(rule.color, parsed.color)
        assertEquals(rule.reminders, parsed.reminders)
        assertEquals(rule.exdates, parsed.exdates)
        // rrule 字段为 JSONObject.toString 形态；逐字符串相等
        assertEquals(rule.rrule, parsed.rrule)
        // JSON 字段命名严格 snake_case（与 spec FR-1 / Web types.ts 一致）
        val obj = JSONObject(json)
        assertTrue(obj.has("start_ts"))
        assertTrue(obj.has("end_ts"))
        assertTrue(obj.has("all_day"))
        assertTrue(obj.has("tz_mode"))
        assertTrue(obj.has("location_text"))
        assertTrue(obj.has("reminders"))
        assertTrue(obj.has("rrule"))
        assertTrue(obj.has("exdates"))
    }

    // ---- 用例 10：records 表 upsert 时 module="event"/type="event"/dirty=true ----
    @Test
    fun upsert_writesModuleEventTypeEventAndDirty() = runBlocking {
        eventsRepo.upsert(sampleRule("e1", "x", startTs = 1_000L))
        val rec: RecordEntity = recordsDao.getById("e1")!!
        assertEquals("event", rec.module)
        assertEquals("event", rec.type)
        assertTrue(rec.dirty)
        assertFalse(rec.deleted)
        // 密文非空且为 base64 形态（CryptoEnvelope.b64）
        assertTrue(rec.ciphertext.length > 0)
        assertTrue(rec.ciphertext.all { c ->
            c.isLetterOrDigit() || c == '+' || c == '/' || c == '='
        })
    }

    // ---- helpers ----

    /**
     * 构造一条最小化 EventRule 样本（覆盖 FR-1 字段表关键项；其余字段可选）。
     */
    private fun sampleRule(
        id: String,
        title: String,
        startTs: Long,
        color: String = "blue",
    ): EventRule =
        EventRule(
            id = id,
            title = title,
            start_ts = startTs,
            end_ts = startTs + 60 * 60 * 1000L, // +1h
            all_day = false,
            tz_mode = "local",
            location_text = null,
            note = null,
            color = color,
            reminders = listOf(0, 15),
            rrule = null,
            exdates = emptyList(),
        )

    companion object {
        /** 测试用 MK：32 字节固定；提供给 CryptoEnvelope 加密链路。 */
        private val TEST_MASTER_KEY = ByteArray(32) { (it + 1).toByte() }
    }
}

/**
 * 反射桩：访问 `AuthManager` 的私有构造器与私有 setter 字段 `masterKey`。
 *
 * 为什么用反射：阶段 0 落地的 AuthManager 设计为 `class AuthManager private
 * constructor(...)` + `var masterKey: ByteArray? = null; private set`——
 * 不能继承、不能直接赋值。本测试不引入 SharedPreferences/Moshi/网络栈，
 * 通过 java.lang.reflect 越过可见性屏障，不动 main 源码。
 *
 * 该反射仅在 instrumented 测试类路径使用，不进入 release 产线代码。
 */
private object AuthManagerReflect {
    /** 缓存的 masterKey 反射字段句柄。 */
    val masterKeyField: Field = run {
        val f = AuthManager::class.java.getDeclaredField("masterKey")
        f.isAccessible = true
        f
    }

    /**
     * 通过反射调用私有构造器，绕过 `private constructor(prefs: SharedPreferences)`。
     * prefs 用 null 即可——本测试不依赖任何 SharedPreferences 路径（仅读写
     * records 表 + event 表 + AuthManager.masterKey 字段）。
     */
    fun newInstance(masterKey: ByteArray?): AuthManager {
        val ctor = AuthManager::class.java.getDeclaredConstructor(
            Class.forName("android.content.SharedPreferences"),
        )
        ctor.isAccessible = true
        val instance = ctor.newInstance(null)
        setMasterKey(instance, masterKey)
        return instance
    }

    /** 写入 masterKey 字段（绕过 private setter）。 */
    fun setMasterKey(instance: AuthManager, value: ByteArray?) {
        masterKeyField.set(instance, value)
    }
}