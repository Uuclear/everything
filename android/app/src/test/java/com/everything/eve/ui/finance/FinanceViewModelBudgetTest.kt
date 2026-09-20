// ============================================================================
// FinanceViewModel B6 预算硬约束单元测试（9 用例，纯 JVM / JUnit4）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/ui/finance/FinanceViewModelBudgetTest.kt
//
// 覆盖：
//   1) upsertBudget 合法 → 内存 budgets 含该 id + state 投影可见；
//   2) upsertBudget 持久化走 records V2 通道：type="budget" 且明文为
//      V2PayloadCodec.encodeBudget 输出（可解码还原）；
//   3) upsertBudget 非法（金额非正）→ Result.failure + "budget_invalid" 事件 +
//      内存不写入 + 零密封调用；
//   4) hydrateBudgets：RecordDao 预置密文行 → 解密解码回填 budgetsFlow，
//      损坏行被跳过不阻塞；
//   5) deleteBudget：内存移除 + 墓碑通道 type="budget"；
//   6) precheckTxBuffer：非支出 / 无预算 → OK_EMPTY；
//   7) BLOCK 场景保存：saveBuffer 被拦截，发 "budget_blocked"，txDao.upsert
//      零调用（不落库）；
//   8) BLOCK 场景用户确认：saveBuffer(ack=true) 放行，落库实体
//      overspendAcknowledged=true（审计位随实体持久化）；
//   9) WARNING 场景保存：不拦截，落库实体 overspendAcknowledged=false。
//
// 基建纪律：
//   - FinanceRepository 是 final class：Unsafe.allocateInstance 分配后反射
//     写 5 字段；落库计数用 txDao Proxy 的 "upsert" 记录（等价证明未落库 / 落库）；
//   - RecordsRepository 是 open class：用子类 Spy override open 的 V2 方法，
//     禁止用 java.lang.reflect.Proxy 代理该 class；
//   - DAO 接口用 JDK Proxy；suspend 方法 InvocationHandler 同步返回值即可；
//   - ServiceLocator.financeRepo / repo 反射注入并在 @After 还原；
//   - VM 是 open class，测试用匿名子类覆写 loadBudgetRecordDao 接缝注入
//     RecordDao 桩，避免触碰抽象 RoomDatabase。
//
// 编码纪律：中文注释；严禁 ASCII 双连字符。
// ============================================================================

package com.everything.eve.ui.finance

import android.app.Application
import com.everything.eve.ServiceLocator
import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.FinanceModule
import com.everything.eve.data.finance.FinanceRepository
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceReminderLogDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.data.finance.entity.FinanceTxEntity
import com.everything.eve.finance.BudgetCheckResult
import com.everything.eve.finance.BudgetLevel
import com.everything.eve.finance.BudgetRecord
import com.everything.eve.finance.FinanceRecords
import com.everything.eve.finance.V2PayloadCodec
import com.everything.eve.finance.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

class FinanceViewModelBudgetTest {

    companion object {
        // 固定时间锚（CST，UTC+8），预算与流水都落在 2026 年 6 月同一自然月桶：
        //  JUN_START = 2026-06-01 00:00 CST；JUN_END = 2026-12-31 23:59:59.999 CST；
        //  EXISTING_TS = 2026-06-15 00:00 CST；INCOMING_TS = 2026-06-28 12:00 CST。
        private const val DAY_MS: Long = 86_400_000L
        private const val JUN_START: Long = 1_780_243_200_000L
        private val JUN_END: Long = JUN_START + 213L * DAY_MS + 86_399_999L
        private val EXISTING_TS: Long = JUN_START + 14L * DAY_MS
        private const val INCOMING_TS: Long = 1_782_619_200_000L

        // ====================================================================
        // Unsafe / 反射工具
        // ====================================================================

        private val unsafe: Any by lazy {
            val clazz = Class.forName("sun.misc.Unsafe")
            val field = clazz.getDeclaredField("theUnsafe")
            field.isAccessible = true
            field.get(null)
        }

        private fun allocateInstance(clazz: Class<*>): Any {
            val method = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return method.invoke(unsafe, clazz)
        }

        /** 反射写目标对象继承链上的字段。 */
        private fun setField(target: Any, name: String, value: Any?) {
            var klass: Class<*>? = target.javaClass
            while (klass != null) {
                try {
                    val f = klass.getDeclaredField(name)
                    f.isAccessible = true
                    f.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    klass = klass.superclass
                }
            }
            throw NoSuchFieldException("字段 $name 未在 ${target.javaClass.name} 找到")
        }

        /** 桩 AuthManager（绕开构造器，反射写 masterKey；spy 不实际触达加解密）。 */
        private fun stubAuth(): AuthManager {
            val auth = allocateInstance(AuthManager::class.java) as AuthManager
            val mk = AuthManager::class.java.getDeclaredField("masterKey")
            mk.isAccessible = true
            mk.set(auth, ByteArray(CryptoEnvelope.KEY_LEN) { 0x11 })
            return auth
        }

        /** 测试用 Application：覆写 getApplicationContext，避开 android.jar stub。 */
        private open class TestApplication : Application() {
            override fun getApplicationContext(): Application = this
        }

        /**
         * RecordsRepository Spy（open class，只能子类化，禁止 Proxy 代理 class）：
         *  - upsertFinanceV2：记录三元组不做真实密封（native 加密 JVM 不可用）；
         *  - upsertFinanceV2Tombstone：记录墓碑二元组；
         *  - decryptFinanceV2：按预置映射返回明文。
         */
        class RecordsRepositorySpy(
            private val decryptById: Map<String, String>,
        ) : RecordsRepository(dao = emptyRecordDao(), auth = stubAuth()) {

            data class UpsertCall(val type: String, val id: String, val plaintext: String)
            data class TombstoneCall(val type: String, val id: String)

            val upsertCalls = mutableListOf<UpsertCall>()
            val tombstoneCalls = mutableListOf<TombstoneCall>()

            override suspend fun upsertFinanceV2(
                type: String,
                id: String,
                plaintextJson: String,
            ): String {
                upsertCalls.add(UpsertCall(type, id, plaintextJson))
                return id
            }

            override suspend fun upsertFinanceV2Tombstone(type: String, id: String) {
                tombstoneCalls.add(TombstoneCall(type, id))
            }

            override fun decryptFinanceV2(entity: RecordEntity): String =
                decryptById[entity.id]
                    ?: throw IllegalStateException("测试桩未预置解密响应：${entity.id}")
        }

        /** 空 RecordDao Proxy（RecordsRepository 父类构造器要求非 null，spy 不触达）。 */
        private fun emptyRecordDao(): RecordDao = Proxy.newProxyInstance(
            RecordDao::class.java.classLoader,
            arrayOf(RecordDao::class.java),
        ) { _, method, _ ->
            when {
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                method.returnType == java.lang.Boolean::class.java -> false
                method.returnType == java.lang.Void.TYPE -> Unit
                else -> null
            }
        } as RecordDao

        /**
         * JDK Proxy 通用默认返回值。
         *
         * 必须位于 companion：嵌套类 TxDaoSpy（非 inner）与 Harness 内匿名
         * Proxy 均需静态可达，引用外部类实例成员会导致编译失败。
         */
        private fun defaultProxyReturn(returnType: Class<*>): Any? = when {
            Flow::class.java.isAssignableFrom(returnType) -> flowOf(emptyList<Any>())
            java.util.List::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
            returnType == java.lang.Boolean::class.java -> false
            returnType == java.lang.Void.TYPE -> Unit
            else -> null
        }
    }

    // ========================================================================
    // 被测夹具
    // ========================================================================

    /** txDao 桩：observeAll 回放预置流水；upsert 记录所有落库实体（计数 + 审计位断言）。 */
    private class TxDaoSpy(private val seedTxs: List<FinanceTxEntity>) {
        val upserts = mutableListOf<FinanceTxEntity>()

        fun proxy(): FinanceTxDao = Proxy.newProxyInstance(
            FinanceTxDao::class.java.classLoader,
            arrayOf(FinanceTxDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "observeAll" -> flowOf(seedTxs)
                "upsert" -> {
                    @Suppress("UNCHECKED_CAST")
                    upserts.add(args[0] as FinanceTxEntity)
                    Unit
                }
                else -> defaultProxyReturn(method.returnType)
            }
        } as FinanceTxDao
    }

    /** 其余 DAO 接口的通用空桩。 */
    private fun genericDao(iface: Class<*>): Any = Proxy.newProxyInstance(
        iface.classLoader,
        arrayOf(iface),
    ) { _, method, _ -> defaultProxyReturn(method.returnType) }

    /**
     * 测试夹具：装配注入好的 FinanceRepository + Spy + 可覆写 hydrate 接缝的 VM。
     *
     * @param seedTxs txDao.observeAll 回放的既有流水（闸门判定的 existing 输入）。
     * @param hydrateRecords hydrateBudgets 时 RecordDao 返回的密文行。
     * @param decryptById 密文行 id 到明文 JSON 的预置解密映射。
     */
    private inner class Harness(
        seedTxs: List<FinanceTxEntity> = emptyList(),
        hydrateRecords: List<RecordEntity> = emptyList(),
        decryptById: Map<String, String> = emptyMap(),
    ) {
        val txSpy = TxDaoSpy(seedTxs)
        val recordsSpy = RecordsRepositorySpy(decryptById)

        private val recordDaoStub = Proxy.newProxyInstance(
            RecordDao::class.java.classLoader,
            arrayOf(RecordDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getActiveByModuleType" -> hydrateRecords
                else -> defaultProxyReturn(method.returnType)
            }
        } as RecordDao

        val vm: FinanceViewModel

        init {
            // 1) Unsafe 分配 final FinanceRepository，反射写 5 个字段。
            val repo = allocateInstance(FinanceRepository::class.java) as FinanceRepository
            setField(repo, "accountDao", genericDao(FinanceAccountDao::class.java))
            setField(repo, "cardDao", genericDao(FinanceCardDao::class.java))
            setField(repo, "txDao", txSpy.proxy())
            setField(repo, "reminderLogDao", genericDao(FinanceReminderLogDao::class.java))
            setField(repo, "recordsRepository", recordsSpy)
            injectServiceLocator("financeRepo", repo)
            injectServiceLocator("repo", recordsSpy)

            // 2) 构造 VM（匿名子类覆写 hydrate 接缝，避开抽象 RoomDatabase）。
            val app = allocateInstance(TestApplication::class.java) as Application
            vm = object : FinanceViewModel(app) {
                override fun loadBudgetRecordDao(): RecordDao = recordDaoStub
            }
            // 触发 combine stateIn，让 state.value.txs 拿到预置流水。
            runBlocking { vm.state.first() }
        }
    }

    // ========================================================================
    // ServiceLocator 注入 / 还原
    // ========================================================================

    private data class SavedField(val name: String, val hadValue: Boolean, val value: Any?)

    private val saved = mutableListOf<SavedField>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        listOf("financeRepo", "repo").forEach { name ->
            val f = ServiceLocator::class.java.getDeclaredField(name)
            f.isAccessible = true
            saved.add(
                SavedField(
                    name = name,
                    hadValue = runCatching { f.get(ServiceLocator) }.isSuccess,
                    value = runCatching { f.get(ServiceLocator) }.getOrNull(),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        saved.forEach { savedField ->
            val f = ServiceLocator::class.java.getDeclaredField(savedField.name)
            f.isAccessible = true
            if (savedField.hadValue) {
                f.set(ServiceLocator, savedField.value)
            } else {
                runCatching { f.set(ServiceLocator, null) }
            }
        }
        saved.clear()
        Dispatchers.resetMain()
    }

    private fun injectServiceLocator(name: String, value: Any) {
        val f = ServiceLocator::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(ServiceLocator, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> readBudgetsFlow(vm: FinanceViewModel): MutableList<T> {
        var klass: Class<*>? = vm.javaClass
        while (klass != null) {
            try {
                val f = klass.getDeclaredField("budgetsFlow")
                f.isAccessible = true
                val stateFlow = f.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<List<T>>
                return stateFlow.value.toMutableList()
            } catch (_: NoSuchFieldException) {
                klass = klass.superclass
            }
        }
        error("budgetsFlow 未找到")
    }

    // ========================================================================
    // Fixture 工厂
    // ========================================================================

    /** 合法月度预算（默认：100 元、other 分类、预警 80 / 拦截 100、启用）。 */
    private fun budget(
        id: String = "budget-1",
        amount: String = "100.00",
        category: String = "other",
        warning: Int = 80,
        block: Int = 100,
        active: Boolean = true,
        scope: String = "monthly",
        start: Long = JUN_START,
        end: Long = JUN_END,
    ): BudgetRecord = BudgetRecord(
        id = id,
        scope = scope,
        category = category,
        amountMinor = amount,
        currency = "CNY",
        startTs = start,
        endTs = end,
        warningThresholdPct = warning,
        blockThresholdPct = block,
        active = active,
        createdAt = JUN_START,
        updatedAt = JUN_START,
    )

    /** 既有支出流水（闸门 existing 输入）。 */
    private fun existingExpense(
        id: String,
        amount: String,
        category: String = "other",
        occurredAt: Long = EXISTING_TS,
    ): FinanceTxEntity = FinanceTxEntity(
        id = id,
        accountId = "acc-1",
        cardId = null,
        kind = "expense",
        amount = amount,
        currency = "CNY",
        category = category,
        occurredAt = occurredAt,
        note = null,
        icon = null,
        color = null,
        transferToAccountId = null,
        createdAt = occurredAt,
        updatedAt = occurredAt,
        dirty = false,
        deleted = false,
    )

    /** 编辑器支出 buffer。 */
    private fun expenseBuffer(
        amount: String,
        category: String = "other",
        occurredAt: Long = INCOMING_TS,
        id: String = "tx-new",
    ): FinanceEditorBuffer = FinanceEditorBuffer(
        id = id,
        kind = FinanceEditorKind.TX,
        accountId = "acc-1",
        balance = amount,
        txKind = "expense",
        category = category,
        currency = "CNY",
        occurredAtMs = occurredAt,
    )

    /** 取通道首事件（Channel.BUFFERED 已缓存，保存调用后再收也可拿到）。 */
    private fun nextEvent(vm: FinanceViewModel): FinanceUiEvent =
        runBlocking { vm.eventFlow.first() }

    /**
     * 排空 BUFFERED 通道中的历史事件。
     *
     * upsertBudget / deleteBudget 成功时会先 trySend SaveSucceeded /
     * DeleteSucceeded；若不排空，后续 nextEvent 取到的是残留旧事件而非
     * saveBuffer 刚发出的 budget_blocked（receiveAsFlow 的 first 只取最早一条）。
     */
    private fun drainEvents(vm: FinanceViewModel) {
        // BUFFERED 通道 trySend 永不阻塞且容量有限；循环 tryReceive 至空即可。
        // eventFlow 只暴露 receiveAsFlow，这里通过反射拿到内部 Channel 排空。
        var klass: Class<*>? = vm.javaClass
        while (klass != null) {
            val f = runCatching { klass.getDeclaredField("_eventChannel") }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                val channel = f.get(vm) as kotlinx.coroutines.channels.Channel<*>
                while (true) {
                    val result = channel.tryReceive()
                    if (result.isFailure) break
                }
                return
            }
            klass = klass.superclass
        }
        error("_eventChannel 未找到")
    }

    // ========================================================================
    // 用例 1：upsertBudget 合法 → budgetsFlow + state.budgets 含该 id
    // ========================================================================

    @Test
    fun upsertBudget_valid_budgetsFlowAndStateContainIt() {
        val h = Harness()
        val b = budget()
        val result = h.vm.upsertBudget(b)

        assertTrue("合法预算应 success，实际 $result", result.isSuccess)

        val flowList = readBudgetsFlow<BudgetRecord>(h.vm)
        assertEquals(1, flowList.size)
        assertEquals(b.id, flowList.single().id)

        // state 投影（重新订阅取最新 combine 发射）。
        val state = runBlocking { h.vm.state.first() }
        assertTrue("state.budgets 应含该预算", state.budgets.any { it.id == b.id })
    }

    // ========================================================================
    // 用例 2：密封通道 type=budget，明文为 encodeBudget 输出且可解码还原
    // ========================================================================

    @Test
    fun upsertBudget_valid_sealsViaV2ChannelWithTypeBudget() {
        val h = Harness()
        val b = budget(id = "budget-seal")
        h.vm.upsertBudget(b)

        assertEquals("应恰好密封 1 条", 1, h.recordsSpy.upsertCalls.size)
        val call = h.recordsSpy.upsertCalls.single()
        assertEquals(FinanceModule.TYPE_BUDGET, call.type)
        assertEquals(b.id, call.id)
        assertEquals(V2PayloadCodec.encodeBudget(b), call.plaintext)

        // 明文可被 decodeBudget 无损还原。
        val decoded = V2PayloadCodec.decodeBudget(call.plaintext)
        assertEquals(b, decoded)
    }

    // ========================================================================
    // 用例 3：非法预算 → failure + budget_invalid 事件 + 零写入 / 零密封
    // ========================================================================

    @Test
    fun upsertBudget_invalidAmount_failureAndNoSeal() {
        val h = Harness()
        val invalid = budget(amount = "0.00") // 金额必须为正数
        assertTrue(
            "前置：金额 0 应校验失败",
            FinanceRecords.validateBudget(invalid) is ValidationResult.Invalid,
        )

        val result = h.vm.upsertBudget(invalid)

        assertTrue("非法预算应 failure，实际 $result", result.isFailure)
        assertEquals(0, readBudgetsFlow<BudgetRecord>(h.vm).size)
        assertEquals("非法预算不得触发密封", 0, h.recordsSpy.upsertCalls.size)

        val event = nextEvent(h.vm)
        assertTrue("应发 budget_invalid，实际 $event", event is FinanceUiEvent.Error)
        assertEquals("budget_invalid", (event as FinanceUiEvent.Error).code)
    }

    // ========================================================================
    // 用例 4：hydrateBudgets 解密回填；损坏行跳过不阻塞
    // ========================================================================

    @Test
    fun hydrateBudgets_decodesRecordsAndSkipsCorruptRows() {
        val good = budget(id = "budget-hydrate")
        val records = listOf(
            RecordEntity(
                id = good.id,
                module = FinanceModule.MODULE,
                type = FinanceModule.TYPE_BUDGET,
                ciphertext = "sealed-good",
                version = 2L,
                createdAt = JUN_START,
                updatedAt = JUN_START,
            ),
            RecordEntity(
                id = "budget-corrupt",
                module = FinanceModule.MODULE,
                type = FinanceModule.TYPE_BUDGET,
                ciphertext = "sealed-corrupt",
                version = 2L,
                createdAt = JUN_START,
                updatedAt = JUN_START,
            ),
        )
        val h = Harness(
            hydrateRecords = records,
            decryptById = mapOf(
                good.id to V2PayloadCodec.encodeBudget(good),
                "budget-corrupt" to "这不是合法 JSON",
            ),
        )

        // 构造期 init 已 hydrate 一次；显式再调一次验证幂等。
        h.vm.hydrateBudgets()

        val list = readBudgetsFlow<BudgetRecord>(h.vm)
        assertEquals("损坏行应跳过，仅回填 1 条合法预算", 1, list.size)
        assertEquals(good.id, list.single().id)
        assertEquals("100.00", list.single().amountMinor)
    }

    // ========================================================================
    // 用例 5：deleteBudget 内存移除 + 墓碑通道 type=budget
    // ========================================================================

    @Test
    fun deleteBudget_removesFromFlowAndPushesTombstone() {
        val h = Harness()
        val b = budget(id = "budget-del")
        h.vm.upsertBudget(b)
        assertEquals(1, readBudgetsFlow<BudgetRecord>(h.vm).size)

        val result = h.vm.deleteBudget(b.id)
        assertTrue(result.isSuccess)

        assertEquals(0, readBudgetsFlow<BudgetRecord>(h.vm).size)
        val tomb = h.recordsSpy.tombstoneCalls.single()
        assertEquals(FinanceModule.TYPE_BUDGET, tomb.type)
        assertEquals(b.id, tomb.id)
    }

    // ========================================================================
    // 用例 6：precheckTxBuffer —— 非支出 / 无预算均 OK_EMPTY
    // ========================================================================

    @Test
    fun precheck_nonExpenseOrNoBudget_returnsOkEmpty() {
        val h = Harness()
        h.vm.upsertBudget(budget())

        // 收入：不进预算闸门。
        val income = expenseBuffer(amount = "999.00").copy(txKind = "income")
        assertEquals(BudgetCheckResult.OK_EMPTY, h.vm.precheckTxBuffer(income))

        // 无预算夹具下的支出：无候选预算 → OK_EMPTY。
        val h2 = Harness()
        val check = h2.vm.precheckTxBuffer(expenseBuffer(amount = "999.00"))
        assertEquals(BudgetLevel.OK, check.level)
        assertEquals(BudgetCheckResult.OK_EMPTY, check)
    }

    // ========================================================================
    // 用例 7：BLOCK 保存被拦截 —— budget_blocked 事件 + txDao 零 upsert
    // ========================================================================

    @Test
    fun saveExpense_blockedWithoutAck_emitsEventAndNeverPersists() {
        // 已有 60 元支出，本笔 50 元，预算 100 元 → projected 110% → BLOCK。
        val h = Harness(seedTxs = listOf(existingExpense(id = "tx-old", amount = "60.00")))
        h.vm.upsertBudget(budget(amount = "100.00", warning = 80, block = 100))

        val pre = h.vm.precheckTxBuffer(expenseBuffer(amount = "50.00"))
        assertEquals(BudgetLevel.BLOCK, pre.level)

        // 排空 upsertBudget 残留的 SaveSucceeded，确保取到的是拦截事件本身。
        drainEvents(h.vm)
        h.vm.saveBuffer(expenseBuffer(amount = "50.00"))

        val event = nextEvent(h.vm)
        assertTrue("应发 budget_blocked，实际 $event", event is FinanceUiEvent.Error)
        assertEquals("budget_blocked", (event as FinanceUiEvent.Error).code)
        assertEquals("拦截态不得落库", 0, h.txSpy.upserts.size)
    }

    // ========================================================================
    // 用例 8：BLOCK 但用户确认 —— 放行落库，审计位 overspendAcknowledged=true
    // ========================================================================

    @Test
    fun saveExpense_blockedWithAck_persistsWithAuditFlagTrue() {
        val h = Harness(seedTxs = listOf(existingExpense(id = "tx-old", amount = "60.00")))
        h.vm.upsertBudget(budget(amount = "100.00", warning = 80, block = 100))

        val buffer = expenseBuffer(amount = "50.00", id = "tx-after-ack")
        h.vm.saveBuffer(buffer, overspendAcknowledged = true)

        // 密文通道后续步骤在 JVM 桩环境可能失败，但明文表 upsert 在其之前执行；
        // 只要实体进入 txDao.upsert 即证明闸门放行。
        assertEquals("确认后应落库 1 条", 1, h.txSpy.upserts.size)
        val saved = h.txSpy.upserts.single()
        assertEquals(buffer.id, saved.id)
        assertTrue("审计位必须为 true", saved.overspendAcknowledged)
    }

    // ========================================================================
    // 用例 9：WARNING 不拦截 —— 正常落库，审计位保持默认 false
    // ========================================================================

    @Test
    fun saveExpense_warning_persistsWithAuditFlagFalse() {
        // 无既有支出，本笔 90 元，预算 100 元、预警 80 → 90% → WARNING（< 100）。
        val h = Harness(seedTxs = emptyList())
        h.vm.upsertBudget(budget(amount = "100.00", warning = 80, block = 100))

        val pre = h.vm.precheckTxBuffer(expenseBuffer(amount = "90.00"))
        assertEquals(BudgetLevel.WARNING, pre.level)

        h.vm.saveBuffer(expenseBuffer(amount = "90.00", id = "tx-warning"))

        assertEquals("WARNING 不拦截，应落库 1 条", 1, h.txSpy.upserts.size)
        val saved = h.txSpy.upserts.single()
        assertFalse("未走超支确认，审计位必须为 false", saved.overspendAcknowledged)
    }
}
