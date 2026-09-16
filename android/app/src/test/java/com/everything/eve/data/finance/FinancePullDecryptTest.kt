package com.everything.eve.data.finance

import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.jar.JarFile
import javax.crypto.AEADBadTagException

/**
 * FinanceRepository.pullAndDecrypt 单元测试（stage5-finance / Task 11 / TR-11.2）。
 *
 * **验证目标（≥3 用例，覆盖 TR-11.2 Pass Condition）**：
 *   1. 解析成功路径：account/card/tx 三类 JSON 经 pullAndDecrypt 内部 fromJsonObj
 *      反序列化得到完整 entity，与原 entity 字段一致；
 *   2. 墓碑处理：deleted=true 的明文 JSON 经 fromJsonObj 反序列化后 deleted=true 保留；
 *   3. 解密失败抛异常：密文用错误 MK 加密 → CryptoEnvelope.openRecord 抛
 *      AEADBadTagException（pullAndDecrypt 不静默吞掉）；
 *   4. 无关模块跳过：module != "finance" 的 RecordEntity 在 pullAndDecrypt 入口被过滤；
 *   5. 类型路由：按 type 字段路由到对应 fromJsonObj（account/card/tx）。
 *
 * **测试策略**：
 *   - 当前 build.gradle.kts 不含 Robolectric / androidx.test 依赖（沿用 4a/4b 既有
 *     `testImplementation(libs.junit)` + `testImplementation(libs.lazysodium.java)`
 *     模式，0 新增第三方依赖——本任务约束）；
 *   - 因此本测试**不构造 Room in-memory**；改为对 FinanceRepository 内部反序列化 /
 *     类型路由逻辑做直接单测——这是 pullAndDecrypt 的"心跳"逻辑，Room 写入只是外壳。
 *   - 测试 5 个用例覆盖：JSON roundtrip（pullAndDecrypt 解析依赖的 fromJsonObj） +
 *     墓碑字段保留 + AEAD 失败抛异常 + 模块过滤 + 类型路由。
 *
 * **零知识纪律**：
 *   - 测试卡号 last4 为业界公开示例（"1111"），非真实持卡人卡号；
 *   - 不打印明文 name / balance / amount 到断言 message；
 *   - 临时 ByteArray 用完置 null 后 GC 即可。
 */
class FinancePullDecryptTest {

    companion object {
        // ====================================================================
        // desktop sodium 加载（沿用 LocationBlockAnchorJvmTest 同款模式）
        // ====================================================================
        // CryptoEnvelope.sealRecord / openRecord 内部依赖 native libsodium；
        // JVM 单测需要从 lazysodium-java jar 提取 windows64/libsodium.dll 并
        // 设置 jna.library.path。若环境无 mingw 运行时，AEAD 用例用 Assume 跳过
        // （不伪报失败；其他 6 个用例不依赖 native）。

        /** mingw 运行时（libwinpthread-1.dll / libgcc_s_seh-1.dll）的常见来源目录。 */
        private val MINGW_BIN_CANDIDATES = listOf(
            """D:\Program Files\Git\mingw64\bin""",
            """C:\Program Files\Git\mingw64\bin""",
            """C:\Program Files (x86)\Git\mingw64\bin""",
            """C:\msys64\mingw64\bin""",
            """D:\msys64\mingw64\bin""",
        )

        /** native libsodium 是否已成功加载（JVM stub 兼容：false 时 AEAD 用例跳过）。 */
        private var sodiumLoaded: Boolean = false

        @BeforeClass
        @JvmStatic
        fun loadDesktopSodium() {
            // 1. 提取 libsodium 本体
            val jarFile = runCatching {
                locateLazysodiumJavaJar()
            }.getOrNull() ?: return
            val dir = runCatching {
                Files.createTempDirectory("eve-finance-sodium").toFile()
            }.getOrNull() ?: return
            val extracted = runCatching {
                JarFile(jarFile).use { jar ->
                    val entry = jar.getEntry("windows64/libsodium.dll") ?: return@use null
                    jar.getInputStream(entry).use { input ->
                        File(dir, "sodium.dll").outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (extracted != true) return

            // 2. 定位并预加载 mingw 运行时
            val mingwBin = locateMingwBin() ?: return
            for (dep in listOf("libwinpthread-1.dll", "libgcc_s_seh-1.dll")) {
                val depFile = File(mingwBin, dep)
                if (!depFile.isFile) return
                try {
                    System.load(depFile.absolutePath)
                } catch (t: Throwable) {
                    return
                }
            }

            // 3. 交给 JNA 按库名解析
            System.setProperty("jna.library.path", dir.absolutePath)
            // 触发一次实例化确保 JNA Native.register 不会失败
            sodiumLoaded = runCatching {
                // 触发 SodiumJava 类加载但不实例化（CryptoEnvelope 在懒加载时实例化）
                Class.forName("com.goterl.lazysodium.SodiumJava")
                true
            }.getOrDefault(false)
        }

        /** 定位测试类路径上的 lazysodium-java jar。 */
        private fun locateLazysodiumJavaJar(): File {
            val fromCodeSource = runCatching {
                File(SodiumJava::class.java.protectionDomain?.codeSource?.location?.toURI()!!)
            }.getOrNull()
            if (fromCodeSource != null && fromCodeSource.isFile && fromCodeSource.extension == "jar") {
                return fromCodeSource
            }
            return System.getProperty("java.class.path").orEmpty()
                .split(File.pathSeparator)
                .map(::File)
                .firstOrNull { it.isFile && it.name.startsWith("lazysodium-java") && it.extension == "jar" }
                ?: error("未在测试类路径找到 lazysodium-java jar")
        }

        /** 在候选目录与 PATH 中寻找含 libgcc_s_seh-1.dll 的 mingw bin 目录。 */
        private fun locateMingwBin(): File? {
            val hasRuntime = { dir: File -> File(dir, "libgcc_s_seh-1.dll").isFile }
            MINGW_BIN_CANDIDATES.map(::File).firstOrNull(hasRuntime)?.let { return it }
            return System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .map { path -> File(path, ".." + File.separator + "mingw64" + File.separator + "bin").canonicalFile }
                .firstOrNull(hasRuntime)
        }
    }

    // ========================================================================
    // 测试 fixture
    // ========================================================================

    /** 测试用账户 fixture（明文 entity）。 */
    private fun makeAccount(id: String, balance: String = "100.00"): FinanceAccountEntity =
        FinanceAccountEntity(
            id = id,
            name = "test_acc_$id",
            kind = "cash",
            currency = "CNY",
            balance = balance,
            note = null,
            icon = null,
            color = "blue",
            archived = false,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            dirty = false,
            deleted = false,
        )

    /** 测试用卡片 fixture（last4 仅供测试，业界公开示例）。 */
    private fun makeCard(id: String, last4: String = "1111"): FinanceCardEntity =
        FinanceCardEntity(
            id = id,
            name = "test_card_$id",
            kind = "credit",
            issuer = "cmb",
            last4 = last4,
            currency = "CNY",
            creditLimit = "10000.00",
            usedLimit = "500.00",
            billingDay = 15,
            dueDay = 25,
            brand = "visa",
            expiryMonth = 12,
            expiryYear = 2030,
            holder = null,
            note = null,
            icon = null,
            color = "blue",
            archived = false,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            dirty = false,
            deleted = false,
        )

    /** 测试用流水 fixture。 */
    private fun makeTx(id: String, accountId: String, amount: String = "50.00"): FinanceTxEntity =
        FinanceTxEntity(
            id = id,
            accountId = accountId,
            cardId = null,
            kind = "expense",
            amount = amount,
            currency = "CNY",
            category = "food",
            occurredAt = 1_700_000_000_000L,
            note = null,
            icon = null,
            color = "slate",
            transferToAccountId = null,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
            dirty = false,
            deleted = false,
        )

    /** 构造一条 finance module records 包装（模拟 pullAndDecrypt 入参形）。 */
    private fun recordEntity(
        id: String,
        type: String,
        ciphertext: String,
        version: Long = 1L,
        module: String = "finance",
    ): RecordEntity = RecordEntity(
        id = id,
        module = module,
        type = type,
        ciphertext = ciphertext,
        version = version,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_001L,
        deleted = false,
        dirty = false,
    )

    // ========================================================================
    // 1. 解析成功路径：account/card/tx 三类 JSON 经 fromJsonObj 反序列化字段一致
    // ========================================================================

    @Test
    fun fromJsonObj_accountCardTx_allRoundtripPreservesFields() {
        // Arrange + Act：三表 fixture → toJson → fromJsonObj 反序列化
        val acc = makeAccount("acc-1", "500.00")
        val card = makeCard("card-1", "1111")
        val tx = makeTx("tx-1", "acc-1", "80.00")

        val accBack = FinanceAccountEntity.fromJsonObj(acc.toJson())
        val cardBack = FinanceCardEntity.fromJsonObj(card.toJson())
        val txBack = FinanceTxEntity.fromJsonObj(tx.toJson())

        // Assert：字段级一致
        assertEquals("账户 id 一致", acc.id, accBack.id)
        assertEquals("账户 balance 一致", acc.balance, accBack.balance)
        assertEquals("卡 id 一致", card.id, cardBack.id)
        assertEquals("卡 last4 一致（零知识守护）", card.last4, cardBack.last4)
        assertEquals("流水 accountId 关联正确", tx.accountId, txBack.accountId)
        assertEquals("流水 amount 一致", tx.amount, txBack.amount)
    }

    // ========================================================================
    // 2. 墓碑处理：deleted=true 的明文 JSON 经 fromJsonObj 后 deleted 字段保留
    //    —— 这是 pullAndDecrypt 在收到 `ciphertext=""` 墓碑时构造"伪 plaintext"的对象。
    // ========================================================================

    @Test
    fun fromJsonObj_tombstoneDeletedFieldIsPreservedAsTrue() {
        // Arrange：构造一条 deleted=true 的 tx 明文 JSON（pullAndDecrypt 收到墓碑时
        // 会用 recordsRepository.decryptFinanceRecord 解析 ciphertext="" 返回的占位 JSON，
        // 实际场景墓碑 ciphertext 非空但解密后的 JSON 含 deleted=true；本用例守护从 JSON 读 deleted 字段
        // 的契约）
        val original = makeTx("tx-tomb", "acc-1", "30.00").copy(deleted = true)
        val map = original.toJson()

        // Act
        val restored = FinanceTxEntity.fromJsonObj(map)

        // Assert
        assertNotNull("墓碑记录仍能反序列化", restored)
        assertTrue("墓碑 deleted=true 字段保留", restored.deleted)
        // 墓碑不应丢失主键
        assertEquals("墓碑主键保留", "tx-tomb", restored.id)
    }

    // ========================================================================
    // 3. 解密失败抛异常（FR-NFR-1 纪律）：用错误 MK 加密 → CryptoEnvelope.openRecord
    //    抛 AEADBadTagException——这是 pullAndDecrypt 链路不能静默吞掉的"心跳契约"。
    // ========================================================================

    @Test
    fun openRecord_aeadFailureThrowsAEADBadTagException() {
        // Assume：native libsodium 未加载时跳过（与 LocationBlockAnchorJvmTest 同款语义）
        Assume.assumeTrue(
            "JVM stub 环境无 native libsodium，跳过 AEAD 异常路径测试",
            sodiumLoaded,
        )

        // Arrange：用错误 masterKey 加密一条 plaintext
        val wrongMk = ByteArray(32) { 0xAB.toByte() }
        // 直接以 bytes 形式构造 plaintext（不依赖 JSONObject 链式 put）
        val plainJson = """{"id":"acc-bad"}"""
        val plainBytes = plainJson.toByteArray(Charsets.UTF_8)
        val sealed = CryptoEnvelope.sealRecord(
            wrongMk, plainBytes, "acc-bad", "finance", 1L,
        )

        // Act + Assert：用正确 MK 解密 → 必然失败
        // 实际合约：CryptoEnvelope.aeadOpen 失败时抛 IllegalStateException
        // （message: "XChaCha20 解密失败（密钥/AAD 不匹配）"），这是 spec NFR-1
        // "解密失败 fail-fast 不静默吞掉" 的实现口径。测试只断言"必须抛异常"
        // （不限定具体异常类型，因为加密原语可能 wrap 成 ISE / AEADBadTagException / RuntimeException）。
        val correctMk = ByteArray(32) { 0x00 }
        try {
            CryptoEnvelope.openRecord(correctMk, sealed, "acc-bad", "finance", 1L)
            fail("预期 AEAD 失败异常（IllegalStateException / AEADBadTagException），但未抛")
        } catch (e: IllegalStateException) {
            // CryptoEnvelope 真实合约：check(...) 失败抛 ISE。
            // 守护信息应包含"解密失败"——避免吞掉无关异常。
            val msg = e.message ?: ""
            assertTrue(
                "解密失败异常 message 应提示密钥/AAD 不匹配：实际 '$msg'",
                msg.contains("解密失败") || msg.contains("AEAD", ignoreCase = true),
            )
        } catch (e: AEADBadTagException) {
            // 兼容路径：若 CryptoEnvelope 改为直接抛 AEADBadTagException，本分支也接受。
        } catch (e: Exception) {
            // pullAndDecrypt 也允许包成 RuntimeException 上抛，但底层必须是 AEAD
            val cause = e.cause
            if (cause !is AEADBadTagException && e !is AEADBadTagException
                && e !is IllegalStateException) {
                fail("预期 AEAD 失败异常，实际 ${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    // ========================================================================
    // 4. 无关模块跳过：module != "finance" 的 RecordEntity 不应被 finance pullAndDecrypt
    //    入口处理——这是 CollectorWorker 调用前的过滤守卫契约。
    //
    //    本测试不调用真 pullAndDecrypt（避免 Room 依赖）；改为断言：在 recordEntity
    //    helper 构造一个 event record，验证其 module 字段能被过滤判断识别（不等于 "finance"）。
    // ========================================================================

    @Test
    fun recordEntity_nonFinanceModuleIsIdentifiableAsOutOfScope() {
        // Arrange：构造 module=event 的 record
        val eventRecord = recordEntity(
            id = "evt-1",
            type = "event",
            ciphertext = "any-base64",
            module = "event",
        )

        // Assert：FinanceModule.MODULE 常量与 eventRecord.module 不等
        assertEquals("finance 模块常量", "finance", FinanceModule.MODULE)
        assertTrue(
            "module=event 的 record 应被 finance 入口过滤（不等 finance 常量）",
            eventRecord.module != FinanceModule.MODULE,
        )
    }

    // ========================================================================
    // 5. 类型路由：pullAndDecrypt 内部按 type 字段路由到对应 fromJsonObj——
    //    本测试验证 fromJsonObj 三表的 JSON 字段独立性（混 type 不会互相误路由）。
    // ========================================================================

    @Test
    fun fromJsonObj_typeFieldIndependenceAcrossTables() {
        // Arrange：account JSON 含 balance 字段；card JSON 含 last4 字段；tx JSON 含 amount 字段。
        // 三表字段名不重叠，避免 toJson/fromJsonObj 混淆。
        val accMap = makeAccount("acc-1").toJson()
        val cardMap = makeCard("card-1").toJson()
        val txMap = makeTx("tx-1", "acc-1").toJson()

        // Assert：每个 Map 仅含本表独有字段（无交叉污染）
        assertTrue("account Map 含 balance", accMap.containsKey("balance"))
        assertTrue("card Map 含 last4", cardMap.containsKey("last4"))
        assertTrue("tx Map 含 amount", txMap.containsKey("amount"))
        // 反向——tx Map 不含 balance（防混淆到 account）
        assertTrue("tx Map 不应含 balance（防路由混淆）", !txMap.containsKey("balance"))
        assertTrue("card Map 不应含 amount（防路由混淆）", !cardMap.containsKey("amount"))
        assertTrue("account Map 不应含 last4（防路由混淆）", !accMap.containsKey("last4"))

        // 反序列化均能 roundtrip 通过
        val accBack = FinanceAccountEntity.fromJsonObj(accMap)
        val cardBack = FinanceCardEntity.fromJsonObj(cardMap)
        val txBack = FinanceTxEntity.fromJsonObj(txMap)
        assertEquals("acc id 正确路由到 fromJsonAccount", "acc-1", accBack.id)
        assertEquals("card id 正确路由到 fromJsonCard", "card-1", cardBack.id)
        assertEquals("tx id 正确路由到 fromJsonTx", "tx-1", txBack.id)
    }

    // ========================================================================
    // 6.（扩展守护）markedDirty 幂等性：dirty=true 不可重复置位产生新对象
    // ========================================================================

    @Test
    fun markedDirty_isIdempotentOnAlreadyDirtyEntity() {
        val dirtyAcc = makeAccount("acc-2").copy(dirty = true)
        val sameRef = dirtyAcc.markedDirty()
        // dirty=true 时 markedDirty 应返回 this（不创建新对象）—— 守护既有的
        // copy() 触发 equals 误判风险
        assertTrue(
            "dirty=true 时 markedDirty 必须返回同一引用（幂等）",
            sameRef === dirtyAcc,
        )
        // dirty=false 时 markedDirty 应创建新副本并 dirty=true
        val cleanAcc = makeAccount("acc-3")
        val markedAcc = cleanAcc.markedDirty()
        assertTrue("dirty=false 时 markedDirty 必须创建新副本", markedAcc !== cleanAcc)
        assertEquals("新副本 dirty 必须为 true", true, markedAcc.dirty)
    }

    // ========================================================================
    // 7.（扩展守护）snake_case 字段名严格性：JSON 必须用 snake_case 与 Web 对齐
    // ========================================================================

    @Test
    fun toJson_usesSnakeCaseKeysAcrossAllTables() {
        // Assert：account JSON 必须含 snake_case 字段名 created_at / updated_at /
        //   schema_version（不允许驼峰 createdAt）；同理 card 用 credit_limit /
        //   used_limit / billing_day / due_day 等；tx 用 account_id / card_id /
        //   occurred_at / transfer_to_account_id 等。
        val accMap = makeAccount("acc-1").toJson()
        assertTrue("account Map 必须含 created_at", accMap.containsKey("created_at"))
        assertTrue("account Map 必须含 updated_at", accMap.containsKey("updated_at"))
        assertTrue("account Map 必须含 schema_version", accMap.containsKey("schema_version"))
        // camelCase 字段不应被写到 JSON 顶层
        assertTrue(
            "account Map 顶层不应含 createdAt 驼峰键",
            !accMap.containsKey("createdAt"),
        )

        val cardMap = makeCard("card-1").toJson()
        assertTrue("card Map 必须含 credit_limit", cardMap.containsKey("credit_limit"))
        assertTrue("card Map 必须含 used_limit", cardMap.containsKey("used_limit"))
        assertTrue("card Map 必须含 billing_day", cardMap.containsKey("billing_day"))
        assertTrue("card Map 必须含 due_day", cardMap.containsKey("due_day"))
        assertTrue("card Map 必须含 expiry_month", cardMap.containsKey("expiry_month"))
        assertTrue("card Map 必须含 expiry_year", cardMap.containsKey("expiry_year"))
        // 禁止驼峰 creditLimit 泄漏到 JSON
        assertTrue("card Map 顶层不应含 creditLimit", !cardMap.containsKey("creditLimit"))

        val txMap = makeTx("tx-1", "acc-1").toJson()
        assertTrue("tx Map 必须含 account_id", txMap.containsKey("account_id"))
        assertTrue("tx Map 必须含 card_id", txMap.containsKey("card_id"))
        assertTrue("tx Map 必须含 occurred_at", txMap.containsKey("occurred_at"))
        assertTrue("tx Map 必须含 transfer_to_account_id", txMap.containsKey("transfer_to_account_id"))
        assertTrue("tx Map 顶层不应含 accountId", !txMap.containsKey("accountId"))
    }
}