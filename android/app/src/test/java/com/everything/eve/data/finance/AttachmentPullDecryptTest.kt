// ============================================================================
// AttachmentRepository.pullAndDecrypt 单元测试（stage5-finance-v2 / TR-3.2 / TR-11.2）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/data/finance/AttachmentPullDecryptTest.kt
//
// 验证目标（≥4 用例，覆盖 TR-3.2 + TR-11.2 Pass Condition）：
//   1) pullAndDecrypt 空列表 → 返回 0（无副作用）；
//   2) pullAndDecrypt 含 1 条 type="attachment" 的 record → attachmentDao.upsert 被调一次；
//   3) pullAndDecrypt 含 1 条 deleted=true 的 record → attachmentDao.markDeleted 被调一次；
//   4) pullAndDecrypt 含 type="tx" 的 record → 跳过（兜底过滤）；
//
// 设计要点：
//   - JUnit 4 + JVM 单测，**不**依赖 Robolectric / Room in-memory（沿用 0 新增依赖）；
//   - AttachmentDao 用 java.lang.reflect.Proxy 桩 + InvocationHandler 捕获调用参数；
//   - RecordsRepository 用 Proxy 桩，decryptFinanceAttachment 返回固定 plaintext JSON
//     （避免 native libsodium 依赖；JVM 单测场景下"解密回明文"由桩模拟，生产路径由
//     AttachmentRepository.pullAndDecrypt 内部契约保证）；
//   - 过滤 / 入库逻辑是 pullAndDecrypt 的核心心跳，绕过 native crypto 后用桩
//     验证过滤 / 分派 / 计数契约，足够覆盖 TR-3.2 单元测试通过条件。
//
// 关联：
//   - android/.../data/finance/AttachmentRepository.kt（被测目标 pullAndDecrypt）
//   - android/.../data/finance/dao/AttachmentDao.kt（DAO 接口桩）
//   - android/.../data/RecordsRepository.kt（decryptFinanceAttachment 桩入口）
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.AttachmentDao
import com.everything.eve.data.finance.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * AttachmentRepository.pullAndDecrypt 单元测试（JUnit 4，≥4 用例）。
 *
 * **构造策略**：AttachmentDao / RecordsRepository 都是接口（JDK Proxy 可直接桩）；
 * AuthManager 是 final class——用 sun.misc.Unsafe.allocateInstance 绕开构造器、
 * 反射写入 masterKey 字段（与 AttachmentRepositoryTest / FinanceViewModelV2Test 同款）。
 */
class AttachmentPullDecryptTest {

    // ============================================================================
    // DAO Proxy 桩 + 调用捕获
    // ============================================================================

    /**
     * AttachmentDao Proxy 桩：记录所有方法调用参数供断言用。
     *
     * observe* 系列返回 flowOf(emptyList())，其余方法按需返回合理默认值。
     */
    private class AttachmentDaoSpy {
        val upsertCalls = mutableListOf<AttachmentEntity>()
        val markDeletedCalls = mutableListOf<Pair<String, Long>>()
        val markDirtyCalls = mutableListOf<Pair<String, Int>>()

        fun toProxy(): AttachmentDao = Proxy.newProxyInstance(
            AttachmentDao::class.java.classLoader,
            arrayOf(AttachmentDao::class.java),
            { _, method, args ->
                when (method.name) {
                    "upsert" -> {
                        val e = args?.get(0) as? AttachmentEntity
                        if (e != null) upsertCalls.add(e)
                        Unit
                    }
                    "upsertAll" -> {
                        val list = args?.get(0) as? List<*>
                        if (list != null) for (e in list) {
                            (e as? AttachmentEntity)?.let { upsertCalls.add(it) }
                        }
                        Unit
                    }
                    "getById" -> null
                    "observeAll" -> flowOf(emptyList<AttachmentEntity>())
                    "observeByRecordId" -> flowOf(emptyList<AttachmentEntity>())
                    "dirtyList" -> emptyList<AttachmentEntity>()
                    "getUpdatedAfter" -> emptyList<AttachmentEntity>()
                    "markDirty" -> {
                        val id = args?.get(0) as? String
                        val d = args?.get(1) as? Int
                        if (id != null && d != null) markDirtyCalls.add(id to d)
                        Unit
                    }
                    "markDeleted" -> {
                        val id = args?.get(0) as? String
                        val now = (args?.get(1) as? Long) ?: 0L
                        if (id != null) markDeletedCalls.add(id to now)
                        Unit
                    }
                    "deleteById" -> Unit
                    "deleteAll" -> Unit
                    else -> null
                }
            },
        ) as AttachmentDao
    }

    /**
     * RecordsRepository Proxy 桩：decryptFinanceAttachment 返回固定 plaintext JSON；
     * 其他方法走 default stub。
     */
    private class RecordsRepositorySpy : com.everything.eve.data.RecordsRepository(
        dao = emptyRecordDao(),
        auth = makeStubAuth(),
    ) {
        /** 解密回包响应——每个 rec.id 对应一份；为 null 则走 fallback plaintextJson。 */
        val decryptResponses = mutableMapOf<String, String>()
        var decryptCallCount: Int = 0

        override fun decryptFinanceAttachment(entity: com.everything.eve.data.RecordEntity): String {
            decryptCallCount += 1
            return decryptResponses[entity.id]
                ?: """{"id":"att-1","recordId":"policy-1","mime":"application/pdf","size":100,"sha256":"${"a".repeat(64)}"}"""
        }

        fun toStub(): RecordsRepository = this

        companion object {
            /** 桩用空 RecordDao（Proxy stub）。 */
            private fun emptyRecordDao(): com.everything.eve.data.RecordDao = Proxy.newProxyInstance(
                com.everything.eve.data.RecordDao::class.java.classLoader,
                arrayOf(com.everything.eve.data.RecordDao::class.java),
                { _, method, _ ->
                    when {
                        method.returnType == java.lang.Void.TYPE -> Unit
                        Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                        java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                        else -> null
                    }
                },
            ) as com.everything.eve.data.RecordDao

            /** 桩用 AuthManager（Unsafe.allocateInstance + 反射写 masterKey）。 */
            private fun makeStubAuth(): AuthManager {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val auth = allocateMethod.invoke(unsafe, AuthManager::class.java) as AuthManager
                // 写入 masterKey 占位（32B 非空即可；本测试不调加密原语）
                val mk = ByteArray(32) { 0x33.toByte() }
                val mkField: Field = AuthManager::class.java.getDeclaredField("masterKey")
                mkField.isAccessible = true
                mkField.set(auth, mk)
                return auth
            }
        }
    }

    /**
     * 用 Unsafe.allocateInstance 创建 AuthManager 实例（绕开 EncryptedSharedPreferences
     * 构造依赖）；本测试 pullAndDecrypt 不调 auth，但构造器参数非 null 需要占位。
     *
     * 注：本函数留作 newRepo 装配用；spy 类构造器通过 [RecordsRepositorySpy.Companion.makeStubAuth]
     * 拿到 stub AuthManager（companion 内的静态函数不依赖类初始化顺序）。
     */
    private fun stubAuthManager(): AuthManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val auth = allocateMethod.invoke(unsafe, AuthManager::class.java) as AuthManager
        // 写入 masterKey 占位（32B 非空即可；本测试不调加密原语）
        val mk = ByteArray(32) { 0x33.toByte() }
        val mkField: Field = AuthManager::class.java.getDeclaredField("masterKey")
        mkField.isAccessible = true
        mkField.set(auth, mk)
        return auth
    }

    /**
     * 构造 AttachmentRepository（桩 dao + spy recordsRepository + stub auth）。
     */
    private fun newRepo(
        daoSpy: AttachmentDaoSpy,
        recSpy: RecordsRepositorySpy,
    ): AttachmentRepository = AttachmentRepository(
        attachmentDao = daoSpy.toProxy(),
        recordsRepository = recSpy.toStub(),
        auth = stubAuthManager(),
    )

    /**
     * 构造一条 RecordEntity（records 表 schema 与 RecordEntity 一致）。
     */
    private fun rec(
        id: String,
        module: String = "finance",
        type: String = "attachment",
        deleted: Boolean = false,
        ciphertext: String = "stub-ciphertext",
    ): RecordEntity = RecordEntity(
        id = id,
        module = module,
        type = type,
        ciphertext = ciphertext,
        version = 1L,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_001_000L,
        deleted = deleted,
        dirty = false,
    )

    // ============================================================================
    // 1) pullAndDecrypt 空列表 → 返回 0
    // ============================================================================

    @Test
    fun pullAndDecrypt_emptyList_returnsZero() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val count = runBlocking { repo.pullAndDecrypt(emptyList()) }

        assertEquals("空列表应返回 0", 0, count)
        assertEquals("dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
        assertEquals("dao.markDeleted 不应被调", 0, daoSpy.markDeletedCalls.size)
        assertEquals("recordsRepository.decryptFinanceAttachment 不应被调", 0, recSpy.decryptCallCount)
    }

    // ============================================================================
    // 2) pullAndDecrypt 含 1 条 type="attachment" 的 record → attachmentDao.upsert 被调一次
    // ============================================================================

    @Test
    fun pullAndDecrypt_validAttachmentRecords_writesRoom() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val records = listOf(rec(id = "att-1"))
        val count = runBlocking { repo.pullAndDecrypt(records) }

        assertEquals("1 条 attachment record 应返回 1", 1, count)
        assertEquals("dao.upsert 应被调一次", 1, daoSpy.upsertCalls.size)
        assertEquals("dao.markDeleted 不应被调（不是墓碑）", 0, daoSpy.markDeletedCalls.size)
        assertEquals(
            "recordsRepository.decryptFinanceAttachment 应被调一次",
            1, recSpy.decryptCallCount,
        )

        // 入库的 AttachmentEntity 字段契约
        val entity = daoSpy.upsertCalls.first()
        assertEquals("入库 id 应 = 'att-1'", "att-1", entity.id)
        assertEquals("入库 mime 应保留", "application/pdf", entity.mime)
        assertEquals("入库 size 应保留", 100L, entity.size)
        assertEquals("入库 sha256 应保留", "a".repeat(64), entity.sha256)
        assertEquals("入库 dirty 必须 = 0（下行已对账）", 0, entity.dirty)
        assertEquals("入库 deleted 必须 = 0", 0, entity.deleted)
    }

    // ============================================================================
    // 3) pullAndDecrypt 含 1 条 deleted=true 的 record → attachmentDao.markDeleted 被调一次
    // ============================================================================

    @Test
    fun pullAndDecrypt_tombstone_marksDeleted() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val records = listOf(rec(id = "att-tomb-1", deleted = true))
        val count = runBlocking { repo.pullAndDecrypt(records) }

        assertEquals("1 条 tombstone 应返回 1", 1, count)
        assertEquals("dao.upsert 不应被调（墓碑直接 markDeleted）", 0, daoSpy.upsertCalls.size)
        assertEquals("dao.markDeleted 应被调一次", 1, daoSpy.markDeletedCalls.size)
        val (calledId, calledNow) = daoSpy.markDeletedCalls.first()
        assertEquals("markDeleted id 应 = 'att-tomb-1'", "att-tomb-1", calledId)
        assertTrue("markDeleted now 应 > 0", calledNow > 0L)
        // 墓碑优先：decryptFinanceAttachment 不应被调（spec NFR-1 不解密墓碑）
        assertEquals("墓碑优先：recordsRepository.decryptFinanceAttachment 不应被调", 0, recSpy.decryptCallCount)
    }

    // ============================================================================
    // 4) pullAndDecrypt 含 type="tx" 的 record → 跳过（兜底过滤）
    // ============================================================================

    @Test
    fun pullAndDecrypt_skipsNonAttachmentTypes() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val records = listOf(
            rec(id = "tx-1", type = "tx"),
            rec(id = "acc-1", type = "account"),
            rec(id = "card-1", type = "card"),
        )
        val count = runBlocking { repo.pullAndDecrypt(records) }

        // 三条 type 都不是 attachment → 全部跳过 → count=0
        assertEquals("非 attachment type 应全部跳过，count=0", 0, count)
        assertEquals("dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
        assertEquals("dao.markDeleted 不应被调", 0, daoSpy.markDeletedCalls.size)
        assertEquals("recordsRepository.decryptFinanceAttachment 不应被调", 0, recSpy.decryptCallCount)
    }

    // ============================================================================
    // 5) 兜底守护：module="event" 的 type="attachment" record 也应跳过
    //    （防止未来 type 子类型扩张时跨模块污染）
    // ============================================================================

    @Test
    fun pullAndDecrypt_skipsAttachmentTypeFromOtherModule() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        // 假设 type="attachment" 但 module="event"（理论上服务端不应下这种 record，但
        // 兜底过滤守护这种边界：防止 records 通道被注入恶意下行密文）。
        val records = listOf(rec(id = "evt-att-1", module = "event", type = "attachment"))
        val count = runBlocking { repo.pullAndDecrypt(records) }

        assertEquals("跨模块的 type=attachment 应被 module 兜底过滤", 0, count)
        assertEquals("dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
    }
}