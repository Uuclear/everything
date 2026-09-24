// ============================================================================
// QuoteTableRepository 单元测试（stage5-finance-v2 / Task 8 补测）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/data/finance/QuoteTableRepositoryTest.kt
//
// 验证目标（6 用例）：
//   1) importPackage 合法包：records 通道整包密封 1 条（type=quote、id="quote@$ts"）
//      + 本地按 symbol 拆 3 行（密文空串占位、dirty=1），随后 latest() 拼回等值表；
//   2) importPackage 非法包（quotes 为空，QuoteTables.parse 必拒）：零写入；
//   3) importPackage 同 ts 连导两次：records 同 id 2 次密封，本地行 REPLACE 语义
//      （4 个行实例、表内仅 2 行），latest() 取到新值（幂等覆盖）；
//   4) pullAndDecrypt：4 条 records（合法包 / 解析必拒明文 / 墓碑 / 非 quote），
//      仅合法包入库 1 组，行 dirty=0、密文与时间取自 records 行，墓碑与异类型
//      在解密之前即被过滤；
//   5) pushChanges：预置 2 条 dirty 行 → 返回 2；records 通道重建整包 1 条且
//      重建 JSON 可被 QuoteTables.parse 解析含两 quote；逐行 markDirty(id, 0)；
//   6) pushChanges 空 dirty 列表：返回 0，records 零密封、DAO 零翻干净。
//
// 设计要点：照搬 RateTableRepositoryTest 基建模式（Proxy DAO + 子类 spy）。
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.QuoteTableDao
import com.everything.eve.data.finance.entity.QuoteTableEntity
import com.everything.eve.finance.QuoteTables
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
private val TEST_MK: ByteArray = ByteArray(CryptoEnvelope.KEY_LEN) { 0x22.toByte() }

/** Unsafe.allocateInstance + 反射写 masterKey —— 绕开 EncryptedSharedPreferences 依赖。 */
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

/** 按 spec FR-V2-D.2 契约拼一份行情包明文 JSON（quotes 按入参顺序输出）。 */
private fun packageJson(
    ts: Long,
    base: String? = null,
    vararg quotes: Triple<String, Long, String>,
): String {
    val baseLine = if (base != null) ""","base":"$base"""" else ""
    val body = quotes.joinToString(",") { (symbol, priceMinor, currency) ->
        """{"symbol":"$symbol","price_minor":$priceMinor,"currency":"$currency","ts":$ts}"""
    }
    return """{"version":1,"ts":$ts$baseLine,"quotes":[$body]}"""
}

/** 构造一行本地行情行（与 Repository 拆行口径一致）。 */
private fun quoteRow(
    symbol: String,
    priceMinor: Long,
    currency: String,
    ts: Long,
    dirty: Int,
    encryptedPayload: String,
    createdAt: Long,
    updatedAt: Long,
): QuoteTableEntity = QuoteTableEntity(
    id = "$symbol@$ts",
    symbol = symbol,
    priceMinor = priceMinor,
    currency = currency,
    ts = ts,
    encryptedPayload = encryptedPayload,
    schemaVersion = 1,
    module = FinanceModule.MODULE,
    createdAt = createdAt,
    updatedAt = updatedAt,
    dirty = dirty,
    deleted = 0,
)

class QuoteTableRepositoryTest {

    // ============================================================================
    // Stub：QuoteTableDao（interface，Proxy 桩 + LinkedHashMap 模拟 REPLACE）
    // ============================================================================

    private class QuoteTableDaoSpy {
        val rows = LinkedHashMap<String, QuoteTableEntity>()
        val upsertAllBatches = mutableListOf<List<QuoteTableEntity>>()
        val markDirtyCalls = mutableListOf<Pair<String, Int>>()

        fun seed(row: QuoteTableEntity) {
            rows[row.id] = row
        }

        fun toProxy(): QuoteTableDao = Proxy.newProxyInstance(
            QuoteTableDao::class.java.classLoader,
            arrayOf(QuoteTableDao::class.java),
        ) { proxy, method, rawArgs ->
            val args = rawArgs ?: emptyArray<Any?>()
            if (method.declaringClass == Any::class.java) {
                when (method.name) {
                    "toString" -> "QuoteTableDaoSpyProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args.firstOrNull()
                    else -> null
                }
            } else when (method.name) {
                "upsert" -> Unit
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    val entities = args[0] as List<QuoteTableEntity>
                    upsertAllBatches.add(entities)
                    entities.forEach { rows[it.id] = it }
                    Unit
                }
                "getById" -> {
                    val id = args[0] as String
                    rows.values.firstOrNull { it.id == id && it.deleted == 0 }
                }
                "latestTs" ->
                    rows.values.filter { it.deleted == 0 }.maxOfOrNull { it.ts }
                "listByTs" -> {
                    val ts = args[0] as Long
                    rows.values.filter { it.ts == ts && it.deleted == 0 }
                }
                "observeLatest" -> flowOf(emptyList<QuoteTableEntity>())
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
                else -> error("QuoteTableDaoSpy 未覆盖的 DAO 方法：${method.name}")
            }
        } as QuoteTableDao
    }

    // ============================================================================
    // Stub：RecordsRepository（open class，子类 override）
    // ============================================================================

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
        daoSpy: QuoteTableDaoSpy,
        recSpy: RecordsRepositorySpy,
    ): QuoteTableRepository = QuoteTableRepository(
        quoteDao = daoSpy.toProxy(),
        recordsRepository = recSpy.toStub(),
        auth = stubAuthManager(),
    )

    /** 单行全字段断言（导入路径只断言正数；下行路径可断言密文 + 时间）。 */
    private fun assertQuoteRow(
        row: QuoteTableEntity,
        expectedId: String,
        expectedSymbol: String,
        expectedPrice: Long,
        expectedCurrency: String,
        expectedTs: Long,
        expectedPayload: String,
        expectedDirty: Int,
        expectedDeleted: Int,
    ) {
        assertEquals("行 id", expectedId, row.id)
        assertEquals("symbol", expectedSymbol, row.symbol)
        assertEquals("priceMinor", expectedPrice, row.priceMinor)
        assertEquals("currency", expectedCurrency, row.currency)
        assertEquals("ts", expectedTs, row.ts)
        assertEquals("encryptedPayload", expectedPayload, row.encryptedPayload)
        assertEquals("schemaVersion 本期固定 1", 1, row.schemaVersion)
        assertEquals("module 固定 finance", FinanceModule.MODULE, row.module)
        assertEquals("dirty", expectedDirty, row.dirty)
        assertEquals("deleted", expectedDeleted, row.deleted)
    }

    // ============================================================================
    // 1) importPackage 合法包：records 整包密封 + 本地拆 3 行 + latest 拼回
    // ============================================================================

    @Test
    fun importPackage_validPackage_sealsAndSplitsRows() {
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1735689600000L
        val json = packageJson(
            ts,
            base = "CNY",
            Triple("AAPL", 18500L, "USD"),
            Triple("0700.HK", 38000L, "HKD"),
            Triple("600519.SH", 170000L, "CNY"),
        )

        val result = runBlocking { repo.importPackage(json) }

        assertTrue("合法包应返回 success，实际 $result", result.isSuccess)
        val table = result.getOrNull()
        assertNotNull(table)
        requireNotNull(table)
        assertEquals(ts, table.ts)
        assertEquals("CNY", table.base)
        assertEquals(3, table.quotes.size)
        assertEquals(18500L, table.quotes["AAPL"]?.priceMinor)
        assertEquals(38000L, table.quotes["0700.HK"]?.priceMinor)
        assertEquals(170000L, table.quotes["600519.SH"]?.priceMinor)

        // records 通道恰密封 1 条整包。
        assertEquals("records 通道应密封恰 1 条", 1, recSpy.upsertCalls.size)
        val sealed = recSpy.upsertCalls.single()
        assertEquals("type 必须为 quote", FinanceModule.TYPE_QUOTE, sealed.type)
        assertEquals("包记录 id 必须为 quote@\$ts 形式", "quote@$ts", sealed.id)
        assertEquals("密封明文必须为导入原文", json, sealed.plaintext)

        // 本地表恰收到 1 批 upsertAll、3 行。
        assertEquals(1, daoSpy.upsertAllBatches.size)
        val rows = daoSpy.upsertAllBatches.single()
        assertEquals(3, rows.size)
        val byId = rows.associateBy { it.id }
        assertQuoteRow(
            byId.getValue("AAPL@$ts"),
            expectedId = "AAPL@$ts",
            expectedSymbol = "AAPL", expectedPrice = 18500L, expectedCurrency = "USD",
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )
        assertQuoteRow(
            byId.getValue("0700.HK@$ts"),
            expectedId = "0700.HK@$ts",
            expectedSymbol = "0700.HK", expectedPrice = 38000L, expectedCurrency = "HKD",
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )
        assertQuoteRow(
            byId.getValue("600519.SH@$ts"),
            expectedId = "600519.SH@$ts",
            expectedSymbol = "600519.SH", expectedPrice = 170000L, expectedCurrency = "CNY",
            expectedTs = ts, expectedPayload = "", expectedDirty = 1, expectedDeleted = 0,
        )

        // latest 两步查拼回等值表。
        val latest = runBlocking { repo.latest() }
        assertNotNull(latest)
        requireNotNull(latest)
        assertEquals(ts, latest.ts)
        assertEquals(3, latest.quotes.size)
    }

    // ============================================================================
    // 2) importPackage 非法包（quotes 空）：parse 拒绝，零写入
    // ============================================================================

    @Test
    fun importPackage_invalidJson_zeroWrites() {
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val invalidJson = """{"version":1,"ts":1,"quotes":[]}"""

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
    // 3) 同 ts 连导两次：同 id 覆盖（4 行实例，表内仅 2 行）
    // ============================================================================

    @Test
    fun importPackage_sameTs_idempotentReplace() {
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1700000000000L
        val firstJson = packageJson(ts, null,
            Triple("AAPL", 18000L, "USD"),
            Triple("0700.HK", 38000L, "HKD"),
        )
        val secondJson = packageJson(ts, null,
            Triple("AAPL", 18500L, "USD"),
            Triple("0700.HK", 38500L, "HKD"),
        )

        val first = runBlocking { repo.importPackage(firstJson) }
        val second = runBlocking { repo.importPackage(secondJson) }
        assertTrue("首次导入应成功：$first", first.isSuccess)
        assertTrue("二次导入应成功：$second", second.isSuccess)

        // records 通道密封 2 次，但包记录 id 相同（同键 REPLACE 幂等）。
        assertEquals("records 通道应密封 2 次", 2, recSpy.upsertCalls.size)
        recSpy.upsertCalls.forEach { call ->
            assertEquals(FinanceModule.TYPE_QUOTE, call.type)
            assertEquals("quote@$ts", call.id)
        }

        // DAO 收到 2 批、共 4 个行实例，但内存表按 id REPLACE 后仅 2 行。
        assertEquals("upsertAll 应调 2 次", 2, daoSpy.upsertAllBatches.size)
        val instanceCount = daoSpy.upsertAllBatches.sumOf { it.size }
        assertEquals("两批合计 4 个行实例", 4, instanceCount)
        assertEquals("同 id REPLACE 后表内仅 2 行（不翻倍）", 2, daoSpy.rows.size)

        // latest 只反映第二次导入的新值，旧值被覆盖。
        val latest = runBlocking { repo.latest() }
        assertNotNull(latest)
        requireNotNull(latest)
        assertEquals(ts, latest.ts)
        assertEquals(2, latest.quotes.size)
        assertEquals(18500L, latest.quotes["AAPL"]?.priceMinor)
        assertEquals(38500L, latest.quotes["0700.HK"]?.priceMinor)
    }

    // ============================================================================
    // 4) pullAndDecrypt：合法 / 坏明文 / 墓碑 / 异类型，仅合法包入库
    // ============================================================================

    @Test
    fun pullAndDecrypt_recordsFilteredAndDecrypted() {
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1735689600000L

        // a) finance/quote 未删：解密得合法包 A。
        val plainA = packageJson(ts, null, Triple("AAPL", 18500L, "USD"))
        recSpy.decryptResponses["quote@$ts"] = plainA
        val recA = RecordEntity(
            id = "quote@$ts",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_QUOTE,
            ciphertext = "cipher-a",
            version = 1L,
            createdAt = 1111L,
            updatedAt = 2222L,
        )
        // b) finance/quote 未删：解密得 "{}"，QuoteTables.parse 必拒。
        recSpy.decryptResponses["quote@bad"] = "{}"
        val recB = RecordEntity(
            id = "quote@bad",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_QUOTE,
            ciphertext = "cipher-b",
            version = 1L,
            createdAt = 3333L,
            updatedAt = 4444L,
        )
        // c) finance/quote 墓碑：解密之前跳过。
        val recC = RecordEntity(
            id = "quote@tomb",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_QUOTE,
            ciphertext = "cipher-c",
            version = 1L,
            createdAt = 5L,
            updatedAt = 6L,
            deleted = true,
        )
        // d) finance/rate 异类型：解密之前跳过。
        val recD = RecordEntity(
            id = "rate@$ts",
            module = FinanceModule.MODULE,
            type = FinanceModule.TYPE_RATE,
            ciphertext = "cipher-d",
            version = 1L,
            createdAt = 7L,
            updatedAt = 8L,
        )

        val count = runBlocking { repo.pullAndDecrypt(listOf(recA, recB, recC, recD)) }

        assertEquals("仅合法包 A 成功入库", 1, count)

        // 墓碑与异类型记录不得触发解密；解密只覆盖 a、b 两条。
        assertEquals(listOf("quote@$ts", "quote@bad"), recSpy.decryptCalls)

        // DAO 仅收到 1 批、1 行：下行口径 dirty=0、真实密文与 records 时间落行。
        assertEquals(1, daoSpy.upsertAllBatches.size)
        val rows = daoSpy.upsertAllBatches.single()
        assertEquals(1, rows.size)
        assertQuoteRow(
            rows.single(),
            expectedId = "AAPL@$ts",
            expectedSymbol = "AAPL", expectedPrice = 18500L, expectedCurrency = "USD",
            expectedTs = ts,
            expectedPayload = "cipher-a",
            expectedDirty = 0,
            expectedDeleted = 0,
        )

        // 入库后 latest 可读。
        val latest = runBlocking { repo.latest() }
        assertNotNull(latest)
        requireNotNull(latest)
        assertEquals(ts, latest.ts)
        assertEquals(1, latest.quotes.size)
        assertEquals(18500L, latest.quotes["AAPL"]?.priceMinor)
    }

    // ============================================================================
    // 5) pushChanges：dirty 行分组重建整包密封，逐行翻干净
    // ============================================================================

    @Test
    fun pushChanges_dirtyRowsRebuiltAndCleaned() {
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)
        val ts = 1700000000000L

        daoSpy.seed(
            quoteRow(
                symbol = "AAPL", priceMinor = 18500L, currency = "USD", ts = ts,
                dirty = 1, encryptedPayload = "",
                createdAt = 100L, updatedAt = 200L,
            ),
        )
        daoSpy.seed(
            quoteRow(
                symbol = "0700.HK", priceMinor = 38000L, currency = "HKD", ts = ts,
                dirty = 1, encryptedPayload = "",
                createdAt = 100L, updatedAt = 200L,
            ),
        )

        val cleaned = runBlocking { repo.pushChanges() }

        assertEquals("返回翻干净的本地行数量", 2, cleaned)

        // records 通道恰重建 1 条整包。
        assertEquals(1, recSpy.upsertCalls.size)
        val call = recSpy.upsertCalls.single()
        assertEquals(FinanceModule.TYPE_QUOTE, call.type)
        assertEquals("quote@$ts", call.id)
        val rebuilt = QuoteTables.parse(call.plaintext)
        assertEquals("重建包 ts", ts, rebuilt.ts)
        assertEquals("重建包含 2 个 quote", 2, rebuilt.quotes.size)
        assertEquals(18500L, rebuilt.quotes["AAPL"]?.priceMinor)
        assertEquals(38000L, rebuilt.quotes["0700.HK"]?.priceMinor)

        // 两行各 markDirty(id, 0) 一次（顺序随 dirtyList 插入序）。
        assertEquals(
            listOf("AAPL@$ts" to 0, "0700.HK@$ts" to 0),
            daoSpy.markDirtyCalls,
        )
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
        val daoSpy = QuoteTableDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val cleaned = runBlocking { repo.pushChanges() }

        assertEquals(0, cleaned)
        assertEquals("空列表不得密封 records 包", 0, recSpy.upsertCalls.size)
        assertEquals("空列表不得翻干净任何行", 0, daoSpy.markDirtyCalls.size)
        assertEquals("空列表不得写本地表", 0, daoSpy.upsertAllBatches.size)
    }
}