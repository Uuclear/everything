// ============================================================================
// RateTableRepository 单元测试（stage5-finance-v2 / B5 补测）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/data/finance/RateTableRepositoryTest.kt
//
// 验证目标（6 用例）：
//   1) importPackage 合法包：records 通道整包密封 1 条（type=rate、id="rate@$ts"）
//      + 本地按货币对拆 3 行（密文空串占位、dirty=1），随后 latest() 拼回等值表；
//   2) importPackage 非法包（rates 为空对象，RateTables.parse 必拒）：零写入；
//   3) importPackage 同 effective_ts 连导两次：records 同 id 2 次密封，本地行
//      REPLACE 语义（4 个行实例、表内仅 2 行），latest() 取到新值（幂等覆盖）；
//   4) pullAndDecrypt：4 条 records（合法包 / 解析必拒明文 / 墓碑 / 非 rate），
//      仅合法包入库 1 组，行 dirty=0、密文与时间取自 records 行，墓碑与异类型
//      在解密之前即被过滤；
//   5) pushChanges：预置 2 条 dirty 行 → 返回 2；records 通道重建整包 1 条且
//      重建 JSON 可被 RateTables.parse 解析含两 pair；逐行 markDirty(id, 0)；
//   6) pushChanges 空 dirty 列表：返回 0，records 零密封、DAO 零翻干净。
//
// 设计要点（照搬 AttachmentRepositoryTest 基建模式）：
//   - JUnit 4 + JVM 单测，不依赖 Robolectric / Room in-memory / native libsodium：
//     加解密全部在 RecordsRepositorySpy 内用预置明文桩掉；
//   - FinanceRateDao 是 interface，用 java.lang.reflect.Proxy 做桩，内部以
//     LinkedHashMap(id 到行) 模拟 Room @Upsert 的同键 REPLACE 覆盖语义，并让
//     latestEffectiveTs / listByEffectiveTs / dirtyList 从这张内存表派生，
//     从而支撑 latest() 与幂等覆盖断言；
//   - RecordsRepository 是 open class（不是 interface，禁止用 Proxy 代理），
//     测试以子类 override open 方法的方式做 spy；
//   - AuthManager 是 final class，用 sun.misc.Unsafe.allocateInstance 绕开
//     构造器并反射写 masterKey；所有 suspend 调用包 runBlocking。
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.FinanceRateDao
import com.everything.eve.data.finance.entity.FinanceRateEntity
import com.everything.eve.finance.RateTables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/** 桩用固定 MK（长度与 lazysodium KEY_LEN 一致；spy 不实际消费密钥）。 */
private val TEST_MK: ByteArray = ByteArray(CryptoEnvelope.KEY_LEN) { 0x11.toByte() }

/**
 * 用 Unsafe.allocateInstance 创建 AuthManager 实例（绕开构造器对
 * EncryptedSharedPreferences 的依赖），反射写入 masterKey 字段。
 */
private fun stubAuthManager(): AuthManager {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
    val auth = allocateMethod.invoke(unsafe, AuthManager::class.java) as AuthManager
    val mkField: Field = AuthManager::class.java.getDeclaredField("masterKey")
    mkField.isAccessible = true
    mkField.set(auth, TEST_MK)
    return auth
}

/** 按 spec FR-V2-C.2 契约拼一份汇率包明文 JSON（key 顺序跟随入参顺序）。 */
private fun packageJson(ts: Long, vararg rates: Pair<String, Double>): String {
    val ratesBody = rates.joinToString(",") { "\"${it.first}\":${it.second}" }
    return "{\"version\":1,\"effective_ts\":$ts,\"rates\":{$ratesBody}}"
}

/** 构造一行本地汇率行（与 Repository 拆行口径一致）。 */
private fun rateRow(
    pair: String,
    rate: Double,
    ts: Long,
    dirty: Int,
    encryptedPayload: String,
    createdAt: Long,
    updatedAt: Long,
): FinanceRateEntity = FinanceRateEntity(
    id = "$pair@$ts",
    currencyBase = pair.substring(0, 3),
    currencyQuote = pair.substring(4, 7),
    rate = rate,
    effectiveTs = ts,
    encryptedPayload = encryptedPayload,
    schemaVersion = 1,
    module = FinanceModule.MODULE,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dirty = dirty,
    deleted = 0,
)

class RateTableRepositoryTest {

    // ============================================================================
    // Stub：FinanceRateDao（interface，Proxy 桩 + LinkedHashMap 模拟 REPLACE）
    // ============================================================================

    /**
     * FinanceRateDao Proxy 桩：
     *  - [rows] 以 LinkedHashMap 存 id 到行，upsert / upsertAll 同键覆盖，
     *    精确模拟 Room @Upsert 的 REPLACE 语义（幂等导入行数不翻倍）；
     *  - [upsertAllBatches] 记录每次 upsertAll 的整批入参（调次与批规模断言）；
     *  - [markDirtyCalls] 记录 (id, dirty) 调用；markDirty 同步改写 [rows]；
     *  - latestEffectiveTs / listByEffectiveTs / dirtyList 均从 [rows] 派生；
     *  - observeLatest 返回 flowOf(emptyList())；墓碑过滤与 SQL 注释口径一致。
     */
    private class FinanceRateDaoSpy {
        val rows = LinkedHashMap<String, FinanceRateEntity>()
        val upsertAllBatches = mutableListOf<List<FinanceRateEntity>>()
        val markDirtyCalls = mutableListOf<Pair<String, Int>>()

        /** 测试预置行（pushChanges 场景直接造 dirty 行）。 */
        fun seed(row: FinanceRateEntity) {
            rows[row.id] = row
        }

        fun toProxy(): FinanceRateDao = Proxy.newProxyInstance(
            FinanceRateDao::class.java.classLoader,
            arrayOf(FinanceRateDao::class.java),
        ) { proxy, method, rawArgs ->
            val args = rawArgs ?: emptyArray<Any?>()
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> "FinanceRateDaoSpyProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args.firstOrNull()
                    else -> null
                }
            } else when (method.name) {
                "upsert" -> {
                    val entity = args[0] as FinanceRateEntity
                    rows[entity.id] = entity
                    Unit
                }
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val entities = args[0] as List<FinanceRateEntity>
                    upsertAllBatches.add(entities)
                    entities.forEach { rows[it.id] = it }
                    Unit
                }
                "getById" -> {
                    val id = args[0] as String
                    rows.values.firstOrNull { it.id == id && it.deleted == 0 }
                }
                "latestEffectiveTs" ->
                    rows.values.filter { it.deleted == 0 }.maxOfOrNull { it.effectiveTs }
                "listByEffectiveTs" -> {
                    val ts = args[0] as Long
                    rows.values.filter { it.effectiveTs == ts && it.deleted == 0 }
                }
                "observeLatest" -> flowOf(emptyList<FinanceRateEntity>())
                "dirtyList" -> rows.values.filter { it.dirty == 1 }
                "markDirty" -> {
                    val id = args[0] as String
                    val dirty = args[1] as Int
                    markDirtyCalls.add(id to dirty)
                    rows[id]?.let { rows[id] = it.copy(dirty = dirty) }
                    Unit
                }
                "markDeleted" -> {
                    val id = args[0] as String
                    val now = args[1] as Long
                    rows[id]?.let { rows[id] = it.copy(deleted = 1, dirty = 1, updatedAt = now) }
                    Unit
                }
                "markClean" -> {
                    @Suppress("UNCHECKED_CAST")
                    val ids = args[0] as List<String>
                    val serverTime = args[1] as Long
                    ids.forEach { id ->
                        rows[id]?.let { rows[id] = it.copy(dirty = 0, updatedAt = serverTime) }
                    }
                    Unit
                }
                "deleteAll" -> {
                    rows.clear()
                    Unit
                }
                else -> error("FinanceRateDaoSpy 未覆盖的 DAO 方法：${method.name}")
            }
        } as FinanceRateDao
    }

    // ============================================================================
    // Stub：RecordsRepository（open class，子类 override，不用 Proxy）
    // ============================================================================

    /**
     * RecordsRepository spy：
     *  - override [upsertFinanceV2]：记录 (type, id, 明文) 三元组并原样回 id，
     *    **不**走真实加密封装（零 native 依赖）；
     *  - override [decryptFinanceV2]（注意父类该方法非 suspend）：按 entity.id
     *    从 [decryptResponses] 取预置明文，同时把被解密过的 id 记入
     *    [decryptCalls]（用于断言墓碑与异类型记录在解密前即被过滤）；
     *    未预置即抛错，暴露非预期调用。
     */
    private class RecordsRepositorySpy(
        val decryptResponses: MutableMap<String, String> = mutableMapOf(),
    ) : RecordsRepository(
        dao = makeEmptyRecordDao(),
        auth = makeStubAuth(TEST_MK),
    ) {
        data class UpsertCall(val type: String, val id: String, val plaintext: String)

        val upsertCalls = mutableListOf<UpsertCall>()
        val decryptCalls = mutableListOf<String>()

        override suspend fun upsertFinanceV2(
            type: String,
            id: String,
            plaintextJson: String,
        ): String {
            upsertCalls.add(UpsertCall(type = type, id = id, plaintext = plaintextJson))
            return id
        }

        override fun decryptFinanceV2(entity: RecordEntity): String {
            decryptCalls.add(entity.id)
            return decryptResponses[entity.id]
                ?: throw IllegalStateException("测试桩未预置解密响应：${entity.id}")
        }

        fun toStub(): RecordsRepository = this

        companion object {
            /** 桩用空 RecordDao（Proxy；spy override 后父类通道实际不被触达）。 */
            private fun makeEmptyRecordDao(): RecordDao = Proxy.newProxyInstance(
                RecordDao::class.java.classLoader,
                arrayOf(RecordDao::class.java),
            ) { _, method, _ ->
                when {
                    method.returnType == java.lang.Void.TYPE -> Unit
                    Flow::class.java.isAssignableFrom(method.returnType) ->
                        flowOf(emptyList<Any>())
                    java.util.List::class.java.isAssignableFrom(method.returnType) ->
                        emptyList<Any>()
                    else -> null
                }
            } as RecordDao

            /** 桩用 AuthManager（Unsafe.allocateInstance + 反射写 masterKey）。 */
            private fun makeStubAuth(mk: ByteArray?): AuthManager {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
                unsafeField.isAccessible = true
                val unsafe = unsafeField.get(null)
                val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
                val auth = allocateMethod.invoke(unsafe, AuthManager::class.java) as AuthManager
                val mkField: Field = AuthManager::class.java.getDeclaredField("masterKey")
                mkField.isAccessible = true
                mkField.set(auth, mk)
                return auth
            }
        }
    }

    /** 装配被测仓库（Proxy DAO + Records 子类 spy + stub auth）。 */
    private fun newRepo(
        daoSpy: FinanceRateDaoSpy,
        recSpy: RecordsRepositorySpy,
    ): RateTableRepository = RateTableRepository(
        rateDao = daoSpy.toProxy(),
        recordsRepository = recSpy.toStub(),
        auth = stubAuthManager(),
    )

    /** 单行全字段断言（时间戳为 null 时跳过，用于导入路径只断言正数）。 */
    private fun assertRateRow(
        row: FinanceRateEntity,
        expectedId: String,
        expectedBase: String,
        expectedQuote: String,
        expectedRate: Double,
        expectedTs: Long,
        expectedPayload: String,
        expectedDirty: Int,
        expectedDeleted: Int,
        expectedCreatedAt: Long? = null,
        expectedUpdatedAt: Long? = null,
    ) {
        assertEquals("行 id 必须为确定性 pair@ts", expectedId, row.id)
        assertEquals("currencyBase", expectedBase, row.currencyBase)
        assertEquals("currencyQuote", expectedQuote, row.currencyQuote)
        assertEquals("rate", expectedRate, row.rate, 1e-9)
        assertEquals("effectiveTs", expectedTs, row.effectiveTs)
        assertEquals("encryptedPayload", expectedPayload, row.encryptedPayload)
        assertEquals("schemaVersion 本期固定 1", 1, row.schemaVersion)
        assertEquals("module 固定 finance", FinanceModule.MODULE, row.module)
        assertEquals("dirty", expectedDirty, row.dirty)
        assertEquals("deleted", expectedDeleted, row.deleted)
        if (expectedCreatedAt != null) {
            assertEquals("createdAt", expectedCreatedAt, row.createdAt)
        }
        if (expectedUpdatedAt != null) {
            assertEquals("updatedAt", expectedUpdatedAt, row.updatedAt)
        }
    }

    // ============================================================================
    // 1) importPackage 合法包：records 整包密封 + 本地拆 3 行 + latest 拼回
    // ============================================================================

    @Test
    fun importPackage_validPackage_sealsAndSplitsRows() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1735689600000L
        val json = packageJson(
            ts,
            "USD/CNY" to 7.25,
            "EUR/CNY" to 7.85,
            "JPY/CNY" to 0.048,
        )

        val result = runBlocking { repo.importPackage(json) }

        assertTrue("合法包应返回 success，实际 $result", result.isSuccess)
        val table = result.getOrNull()
        assertNotNull("Result 必须携带 RateTable", table)
        requireNotNull(table)
        assertEquals(ts, table.effectiveTs)
        assertEquals(3, table.rates.size)
        assertEquals(7.25, table.rates["USD/CNY"]!!, 1e-9)
        assertEquals(7.85, table.rates["EUR/CNY"]!!, 1e-9)
        assertEquals(0.048, table.rates["JPY/CNY"]!!, 1e-9)

        // records 通道恰密封 1 条整包记录：type=rate、确定性 id、原文透传。
        assertEquals("records 通道应密封恰 1 条", 1, recSpy.upsertCalls.size)
        val sealed = recSpy.upsertCalls.single()
        assertEquals("type 必须为 rate", FinanceModule.TYPE_RATE, sealed.type)
        assertEquals("包记录 id 必须为 rate@\$ts 形式", "rate@$ts", sealed.id)
        assertEquals("密封明文必须为导入原文（不在端侧重组）", json, sealed.plaintext)
        assertTrue("明文应包含 USD/CNY", sealed.plaintext.contains("USD/CNY"))
        assertTrue("明文应包含 EUR/CNY", sealed.plaintext.contains("EUR/CNY"))
        assertTrue("明文应包含 JPY/CNY", sealed.plaintext.contains("JPY/CNY"))

        // 本地表恰收到 1 批 upsertAll、3 行，字段全断言。
        assertEquals("upsertAll 应恰调 1 次", 1, daoSpy.upsertAllBatches.size)
        val rows = daoSpy.upsertAllBatches.single()
        assertEquals("应拆为 3 个货币对行", 3, rows.size)
        val byId = rows.associateBy { it.id }
        assertRateRow(
            byId.getValue("USD/CNY@$ts"),
            expectedId = "USD/CNY@$ts",
            expectedBase = "USD", expectedQuote = "CNY", expectedRate = 7.25,
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )
        assertRateRow(
            byId.getValue("EUR/CNY@$ts"),
            expectedId = "EUR/CNY@$ts",
            expectedBase = "EUR", expectedQuote = "CNY", expectedRate = 7.85,
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )
        assertRateRow(
            byId.getValue("JPY/CNY@$ts"),
            expectedId = "JPY/CNY@$ts",
            expectedBase = "JPY", expectedQuote = "CNY", expectedRate = 0.048,
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )
        rows.forEach {
            assertTrue("导入行 createdAt 应为正数毫秒，实际 ${it.createdAt}", it.createdAt > 0L)
            assertTrue("导入行 updatedAt 应为正数毫秒，实际 ${it.updatedAt}", it.updatedAt > 0L)
        }

        // latest 两步查拼回等值表（effectiveTs 与 3 个 pair）。
        val latest = runBlocking { repo.latest() }
        assertNotNull("latest 不应为 null", latest)
        requireNotNull(latest)
        assertEquals(ts, latest.effectiveTs)
        assertEquals(3, latest.rates.size)
        assertEquals(7.25, latest.rates["USD/CNY"]!!, 1e-9)
        assertEquals(7.85, latest.rates["EUR/CNY"]!!, 1e-9)
        assertEquals(0.048, latest.rates["JPY/CNY"]!!, 1e-9)
    }

    // ============================================================================
    // 2) importPackage 非法包（rates 空对象）：parse 拒绝，零写入
    // ============================================================================

    @Test
    fun importPackage_invalidJson_zeroWrites() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1735689600000L
        // version / effective_ts 均合法，唯 rates 为空对象：RateTables.parse
        // 以「rates 至少需要 1 条汇率」拒绝（IllegalArgumentException）。
        val invalidJson = "{\"version\":1,\"effective_ts\":$ts,\"rates\":{}}"

        val result = runBlocking { repo.importPackage(invalidJson) }

        assertTrue("非法包应返回 failure，实际 $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull("failure 必须携带异常", ex)
        assertTrue(
            "异常必须为 IllegalArgumentException，实际 ${ex!!::class.java.simpleName}",
            ex is IllegalArgumentException,
        )
        assertEquals("解析失败不得密封 records 包", 0, recSpy.upsertCalls.size)
        assertEquals("解析失败不得调 upsertAll", 0, daoSpy.upsertAllBatches.size)
        assertNull("零写入时 latest 应为 null", runBlocking { repo.latest() })
    }

    // ============================================================================
    // 3) 同 effective_ts 连导两次：同 id 覆盖（4 行实例，表内仅 2 行新值）
    // ============================================================================

    @Test
    fun importPackage_sameEffectiveTs_idempotentReplace() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1700000000000L
        val firstJson = packageJson(ts, "USD/CNY" to 7.25, "HKD/CNY" to 0.93)
        val secondJson = packageJson(ts, "USD/CNY" to 7.30, "HKD/CNY" to 0.95)

        val first = runBlocking { repo.importPackage(firstJson) }
        val second = runBlocking { repo.importPackage(secondJson) }
        assertTrue("首次导入应成功：$first", first.isSuccess)
        assertTrue("二次导入应成功：$second", second.isSuccess)

        // records 通道密封 2 次，但包记录 id 相同（同键 REPLACE 幂等）。
        assertEquals("records 通道应密封 2 次", 2, recSpy.upsertCalls.size)
        recSpy.upsertCalls.forEach { call ->
            assertEquals(FinanceModule.TYPE_RATE, call.type)
            assertEquals("rate@$ts", call.id)
        }
        assertEquals("rate@$ts", recSpy.upsertCalls[0].id)
        assertEquals("rate@$ts", recSpy.upsertCalls[1].id)

        // DAO 收到 2 批、共 4 个行实例，但内存表按 id REPLACE 后仅 2 行。
        assertEquals("upsertAll 应调 2 次", 2, daoSpy.upsertAllBatches.size)
        val instanceCount = daoSpy.upsertAllBatches.sumOf { it.size }
        assertEquals("两批合计 4 个行实例", 4, instanceCount)
        assertEquals("同 id REPLACE 后表内仅 2 行（不翻倍）", 2, daoSpy.rows.size)

        // latest 只反映第二次导入的新值，旧值被覆盖。
        val latest = runBlocking { repo.latest() }
        assertNotNull(latest)
        requireNotNull(latest)
        assertEquals(ts, latest.effectiveTs)
        assertEquals("幂等覆盖后仍为 2 个 pair", 2, latest.rates.size)
        assertEquals("USD/CNY 必须为新值 7.30", 7.30, latest.rates["USD/CNY"]!!, 1e-9)
        assertEquals("HKD/CNY 必须为新值 0.95", 0.95, latest.rates["HKD/CNY"]!!, 1e-9)
        assertTrue(
            "表内不得残留旧值 7.25",
            latest.rates.values.none { kotlin.math.abs(it - 7.25) < 1e-12 },
        )
        assertTrue(
            "表内不得残留旧值 0.93",
            latest.rates.values.none { kotlin.math.abs(it - 0.93) < 1e-12 },
        )
    }

    // ============================================================================
    // 4) pullAndDecrypt：合法 / 坏明文 / 墓碑 / 异类型，仅合法包入库
    // ============================================================================

    @Test
    fun pullAndDecrypt_recordsFilteredAndDecrypted() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1735689600000L

        // a) finance/rate 未删：解密得合法包 A（1 个 pair）。
        val plainA = packageJson(ts, "USD/CNY" to 7.25)
        recSpy.decryptResponses["rate@$ts"] = plainA
        val recA = RecordEntity(
            id = "rate@$ts",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_RATE,
            ciphertext = "cipher-a",
            version = 1L,
            createdAt = 1111L,
            updatedAt = 2222L,
        )
        // b) finance/rate 未删：解密得 "{}"，RateTables.parse 必拒（缺 effective_ts）。
        recSpy.decryptResponses["rate@bad"] = "{}"
        val recB = RecordEntity(
            id = "rate@bad",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_RATE,
            ciphertext = "cipher-b",
            version = 1L,
            createdAt = 3333L,
            updatedAt = 4444L,
        )
        // c) finance/rate 墓碑：应在解密之前跳过。
        val recC = RecordEntity(
            id = "rate@tomb",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_RATE,
            ciphertext = "cipher-c",
            version = 1L,
            createdAt = 5L,
            updatedAt = 6L,
            deleted = true,
        )
        // d) finance/account 异类型：应在解密之前跳过。
        val recD = RecordEntity(
            id = "account-1",
            module = FinanceModule.MODULE,
            type = "account",
            ciphertext = "cipher-d",
            version = 1L,
            createdAt = 7L,
            updatedAt = 8L,
        )

        val count = runBlocking { repo.pullAndDecrypt(listOf(recA, recB, recC, recD)) }

        assertEquals("仅合法包 A 成功入库", 1, count)

        // 墓碑与异类型记录不得触发解密；解密只覆盖 a、b 两条。
        assertEquals(
            listOf("rate@$ts", "rate@bad"),
            recSpy.decryptCalls,
        )

        // DAO 仅收到 1 批、1 行：下行口径 dirty=0、真实密文与 records 时间落行。
        assertEquals(1, daoSpy.upsertAllBatches.size)
        val rows = daoSpy.upsertAllBatches.single()
        assertEquals(1, rows.size)
        assertRateRow(
            rows.single(),
            expectedId = "USD/CNY@$ts",
            expectedBase = "USD", expectedQuote = "CNY", expectedRate = 7.25,
            expectedTs = ts,
            expectedPayload = "cipher-a",
            expectedDirty = 0,
            expectedDeleted = 0,
            expectedCreatedAt = 1111L,
            expectedUpdatedAt = 2222L,
        )

        // 入库后 latest 可读。
        val latest = runBlocking { repo.latest() }
        assertNotNull(latest)
        requireNotNull(latest)
        assertEquals(ts, latest.effectiveTs)
        assertEquals(1, latest.rates.size)
        assertEquals(7.25, latest.rates["USD/CNY"]!!, 1e-9)
    }

    // ============================================================================
    // 5) pushChanges：dirty 行分组重建整包密封，逐行翻干净
    // ============================================================================

    @Test
    fun pushChanges_dirtyRowsRebuiltAndCleaned() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1700000000000L

        // 预置同一生效时刻的 2 条 dirty 行（seed 同时支撑 listByEffectiveTs）。
        daoSpy.seed(
            rateRow(
                pair = "USD/CNY", rate = 7.25, ts = ts,
                dirty = 1, encryptedPayload = "",
                createdAt = 100L, updatedAt = 200L,
            ),
        )
        daoSpy.seed(
            rateRow(
                pair = "HKD/CNY", rate = 0.93, ts = ts,
                dirty = 1, encryptedPayload = "",
                createdAt = 100L, updatedAt = 200L,
            ),
        )

        val cleaned = runBlocking { repo.pushChanges() }

        assertEquals("返回翻干净的本地行数量", 2, cleaned)

        // records 通道恰重建 1 条整包，且重建 JSON 必须可被纯函数解析含两 pair。
        assertEquals(1, recSpy.upsertCalls.size)
        val call = recSpy.upsertCalls.single()
        assertEquals(FinanceModule.TYPE_RATE, call.type)
        assertEquals("rate@$ts", call.id)
        val rebuilt = RateTables.parse(call.plaintext)
        assertEquals("重建包 effective_ts", ts, rebuilt.effectiveTs)
        assertEquals("重建包含 2 个 pair", 2, rebuilt.rates.size)
        assertEquals(7.25, rebuilt.rates["USD/CNY"]!!, 1e-9)
        assertEquals(0.93, rebuilt.rates["HKD/CNY"]!!, 1e-9)

        // 两行各 markDirty(id, 0) 一次（顺序随 dirtyList 插入序）。
        assertEquals(
            listOf("USD/CNY@$ts" to 0, "HKD/CNY@$ts" to 0),
            daoSpy.markDirtyCalls,
        )
        // 桩随 markDirty 同步改表：全部行已翻干净。
        assertEquals(2, daoSpy.rows.size)
        daoSpy.rows.values.forEach {
            assertEquals("行 ${it.id} 应被翻干净", 0, it.dirty)
        }
    }

    // ============================================================================
    // 6) pushChanges 空 dirty 列表：返回 0，零密封、零翻干净
    // ============================================================================

    @Test
    fun pushChanges_empty_returnsZero() {
        val daoSpy = FinanceRateDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val cleaned = runBlocking { repo.pushChanges() }

        assertEquals(0, cleaned)
        assertEquals("空列表不得密封 records 包", 0, recSpy.upsertCalls.size)
        assertEquals("空列表不得翻干净任何行", 0, daoSpy.markDirtyCalls.size)
        assertEquals("空列表不得写本地表", 0, daoSpy.upsertAllBatches.size)
    }
}
