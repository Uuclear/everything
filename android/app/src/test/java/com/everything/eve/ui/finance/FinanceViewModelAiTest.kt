// ============================================================================
// FinanceViewModel B8 AI 映射 actions 单元测试（9 用例，纯 JVM / JUnit4）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/ui/finance/FinanceViewModelAiTest.kt
//
// 覆盖（setReceiptHint / setSpeechHint / applyReceiptHintToBuffer /
//       applySpeechHintToBuffer / aiHint / AiHintState）：
//   1) applyReceiptHintToBuffer：3500 分 → balance="35"，ts 写入
//      occurredAtMs，merchant 写入空备注；
//   2) 小数口径：3550 → "35.5"、10005 → "100.05"；
//   3) merchant 不覆盖用户已填备注（备注非空时保持原值）；
//   4) apply 后 aiHint.value.receipt 为 null；
//   5) 无 receipt hint 时 buffer 原样返回（数据类全等）；
//   6) applySpeechHintToBuffer：金额 + category + ts 三项覆盖；
//   7) speech hint 的 category 为 null 时不改动 buffer.category；
//   8) apply 后 aiHint.value.speech 为 null；
//   9) setReceiptHint 后 aiHint 流能立即取到值。
//
// 基建纪律（与 FinanceViewModelBudgetTest 口径一致）：
//   - 纯 JVM：不加 Robolectric runner；Dispatchers.setMain 注入
//     UnconfinedTestDispatcher，让构造期协程立即跑完（异常均被 VM 内
//     runCatching / try 兜住）；
//   - Unsafe.allocateInstance 绕过构造器分配 Application 与 final
//     FinanceRepository，反射写入最小字段集；
//   - DAO 接口用 JDK Proxy 空桩，observeAll 经 Flow 分支回放空列表；
//   - ServiceLocator.financeRepo 反射注入并在 @After 还原。
//
// 零知识：测试只做纯内存映射断言，不涉及任何持久化、不打日志；
// 识别原文从不进入 VM。
//
// 编码纪律：中文注释；严禁 ASCII 双连字符。
// ============================================================================

package com.everything.eve.ui.finance

import android.app.Application
import com.everything.eve.ServiceLocator
import com.everything.eve.data.finance.FinanceRepository
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.finance.ReceiptHint
import com.everything.eve.finance.SpeechHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class FinanceViewModelAiTest {

    companion object {
        // 固定时间锚：buffer 初始时刻与两类 hint 的时间要素各不相同，
        // 便于断言 occurredAtMs 确实被覆盖。
        private const val BUFFER_TS: Long = 1_780_000_000_000L
        private const val RECEIPT_TS: Long = 1_781_000_000_000L
        private const val SPEECH_TS: Long = 1_782_000_000_000L

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

        /** 测试用 Application：覆写 getApplicationContext，避开 android.jar stub。 */
        private open class TestApplication : Application() {
            override fun getApplicationContext(): Application = this
        }

        /**
         * JDK Proxy 通用默认返回值：
         *  - Flow 类型回放空列表流；List 返回空列表；
         *  - Boolean 给 false；void 给 Unit；其余 null。
         * VM 初始化只会订阅 observeAll（Flow 分支），其余方法不会触达。
         */
        private fun defaultProxyReturn(returnType: Class<*>): Any? = when {
            Flow::class.java.isAssignableFrom(returnType) -> flowOf(emptyList<Any>())
            java.util.List::class.java.isAssignableFrom(returnType) -> emptyList<Any>()
            returnType == java.lang.Boolean::class.java -> false
            returnType == java.lang.Void.TYPE -> Unit
            else -> null
        }

        /** 单个 DAO 接口的空 Proxy。 */
        private fun emptyDao(iface: Class<*>): Any = Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
        ) { _, method, _ -> defaultProxyReturn(method.returnType) }
    }

    // ========================================================================
    // ServiceLocator 注入状态保存 / 还原
    // ========================================================================

    private var financeRepoHadValue = false
    private var savedFinanceRepo: Any? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // 保存既有 financeRepo，测试结束还原，避免污染同 JVM 内其它测试类。
        val f = ServiceLocator::class.java.getDeclaredField("financeRepo")
        f.isAccessible = true
        financeRepoHadValue = runCatching { f.get(ServiceLocator) }.isSuccess
        savedFinanceRepo = runCatching { f.get(ServiceLocator) }.getOrNull()
    }

    @After
    fun tearDown() {
        val f = ServiceLocator::class.java.getDeclaredField("financeRepo")
        f.isAccessible = true
        if (financeRepoHadValue) {
            f.set(ServiceLocator, savedFinanceRepo)
        } else {
            runCatching { f.set(ServiceLocator, null) }
        }
        Dispatchers.resetMain()
    }

    // ========================================================================
    // 最小装配：VM 构造期 state 初始化只调用 financeRepo 的三个 observe 方法
    // ========================================================================

    /**
     * 构造可独立运行的 VM：
     *  1) Unsafe 分配 final FinanceRepository，仅写 accountDao / cardDao /
     *     txDao 三个空 Proxy（observeAccounts / observeCards / observeTxs
     *     分别委托其 observeAll，回放空列表即可让主 state 正常完成 combine）；
     *  2) 注入 ServiceLocator.financeRepo 后 new VM。
     * 构造期 hydrateV2 / hydrateBudgets / hydrateRateTable 对 DB / MK 未就绪
     * 均有静默容错，不影响本批次映射逻辑。
     */
    private fun newVm(): FinanceViewModel {
        val repo = allocateInstance(FinanceRepository::class.java) as FinanceRepository
        setField(repo, "accountDao", emptyDao(FinanceAccountDao::class.java))
        setField(repo, "cardDao", emptyDao(FinanceCardDao::class.java))
        setField(repo, "txDao", emptyDao(FinanceTxDao::class.java))

        val locatorField = ServiceLocator::class.java.getDeclaredField("financeRepo")
        locatorField.isAccessible = true
        locatorField.set(ServiceLocator, repo)

        val app = allocateInstance(TestApplication::class.java) as Application
        return FinanceViewModel(app)
    }

    /**
     * 最小合法流水 buffer helper（字段口径对齐 BudgetTest 的 expenseBuffer）。
     * 默认：空金额、other 分类、空备注、固定发生时刻。
     */
    private fun txBuffer(
        balance: String = "",
        category: String = "other",
        note: String = "",
        occurredAt: Long = BUFFER_TS,
    ): FinanceEditorBuffer = FinanceEditorBuffer(
        id = "tx-new",
        kind = FinanceEditorKind.TX,
        accountId = "acc-1",
        balance = balance,
        txKind = "expense",
        category = category,
        currency = "CNY",
        occurredAtMs = occurredAt,
        note = note,
    )

    // ========================================================================
    // 用例 1：小票 hint 应用 —— 3500 分得 35 元，ts 与商家分别落位
    // ========================================================================

    @Test
    fun applyReceipt_mapsAmountTsAndMerchant() {
        val vm = newVm()
        vm.setReceiptHint(
            ReceiptHint(
                amountMinor = 3500,
                ts = RECEIPT_TS,
                merchant = "晨星便利店",
            ),
        )

        val result = vm.applyReceiptHintToBuffer(txBuffer())

        assertEquals("3500 分应映射为 35", "35", result.balance)
        assertEquals("小票日期应写入发生时刻", RECEIPT_TS, result.occurredAtMs)
        assertEquals("空备注时商家应写入备注", "晨星便利店", result.note)
    }

    // ========================================================================
    // 用例 2：小数口径 —— 一位小数与两位小数均精确呈现（禁止 Double）
    // ========================================================================

    @Test
    fun applyReceipt_decimalAmounts_mapExactly() {
        val vm = newVm()
        vm.setReceiptHint(
            ReceiptHint(amountMinor = 3550, ts = RECEIPT_TS, merchant = null),
        )
        assertEquals("3550 分应映射为 35.5", "35.5", vm.applyReceiptHintToBuffer(txBuffer()).balance)

        val vm2 = newVm()
        vm2.setReceiptHint(
            ReceiptHint(amountMinor = 10005, ts = RECEIPT_TS, merchant = null),
        )
        assertEquals("10005 分应映射为 100.05", "100.05", vm2.applyReceiptHintToBuffer(txBuffer()).balance)
    }

    // ========================================================================
    // 用例 3：merchant 不覆盖用户已填备注
    // ========================================================================

    @Test
    fun applyReceipt_merchantKeepsExistingNote() {
        val vm = newVm()
        vm.setReceiptHint(
            ReceiptHint(amountMinor = 3500, ts = RECEIPT_TS, merchant = "晨星便利店"),
        )

        // 备注非空：商家名必须让位给用户输入。
        val result = vm.applyReceiptHintToBuffer(txBuffer(note = "用户自己的备注"))

        assertEquals("用户备注应保持不变", "用户自己的备注", result.note)
        // 金额映射不受备注规则影响。
        assertEquals("35", result.balance)
    }

    // ========================================================================
    // 用例 4：apply 后 receipt hint 立即清空
    // ========================================================================

    @Test
    fun applyReceipt_clearsReceiptHint() {
        val vm = newVm()
        vm.setReceiptHint(
            ReceiptHint(amountMinor = 3500, ts = RECEIPT_TS, merchant = "晨星便利店"),
        )
        assertNotNull("前置：应用前 receipt 应存在", vm.aiHint.value.receipt)

        vm.applyReceiptHintToBuffer(txBuffer())

        assertNull("应用后 receipt 必须清空", vm.aiHint.value.receipt)
    }

    // ========================================================================
    // 用例 5：无 receipt hint 时 buffer 原样返回（数据类全等）
    // ========================================================================

    @Test
    fun applyReceipt_withoutHint_returnsBufferUnchanged() {
        val vm = newVm()
        // 不调用 setReceiptHint：receipt 为 null。
        val buffer = txBuffer(balance = "12.34", category = "交通", note = "地铁")

        val result = vm.applyReceiptHintToBuffer(buffer)

        assertEquals("无 hint 时 buffer 必须全等返回", buffer, result)
    }

    // ========================================================================
    // 用例 6：语音 hint 应用 —— 金额、分类、时间三项覆盖
    // ========================================================================

    @Test
    fun applySpeech_mapsAmountCategoryAndTs() {
        val vm = newVm()
        vm.setSpeechHint(
            SpeechHint(amountMinor = 3500, category = "餐饮", ts = SPEECH_TS),
        )

        val result = vm.applySpeechHintToBuffer(txBuffer(balance = "999", category = "other"))

        assertEquals("金额应覆盖为 35", "35", result.balance)
        assertEquals("分类应覆盖为餐饮", "餐饮", result.category)
        assertEquals("语音时间应写入发生时刻", SPEECH_TS, result.occurredAtMs)
    }

    // ========================================================================
    // 用例 7：语音 category 为 null 时不改动 buffer.category
    // ========================================================================

    @Test
    fun applySpeech_nullCategoryKeepsExisting() {
        val vm = newVm()
        vm.setSpeechHint(
            SpeechHint(amountMinor = 3500, category = null, ts = SPEECH_TS),
        )

        val result = vm.applySpeechHintToBuffer(txBuffer(balance = "1", category = "交通"))

        assertEquals("无分类 hint 时原分类应保留", "交通", result.category)
        assertEquals("金额仍应正常覆盖", "35", result.balance)
        assertEquals("时间仍应正常覆盖", SPEECH_TS, result.occurredAtMs)
    }

    // ========================================================================
    // 用例 8：apply 后 speech hint 立即清空
    // ========================================================================

    @Test
    fun applySpeech_clearsSpeechHint() {
        val vm = newVm()
        vm.setSpeechHint(
            SpeechHint(amountMinor = 3500, category = "餐饮", ts = SPEECH_TS),
        )
        assertNotNull("前置：应用前 speech 应存在", vm.aiHint.value.speech)

        vm.applySpeechHintToBuffer(txBuffer())

        assertNull("应用后 speech 必须清空", vm.aiHint.value.speech)
    }

    // ========================================================================
    // 用例 9：setReceiptHint 后 aiHint 流立即取到值（receipt 要素透传）
    // ========================================================================

    @Test
    fun setReceiptHint_emitsViaAiHintFlow() {
        val vm = newVm()
        assertNull("前置：初始无 receipt", vm.aiHint.value.receipt)

        vm.setReceiptHint(
            ReceiptHint(amountMinor = 10005, ts = RECEIPT_TS, merchant = "晨星便利店"),
        )

        val receipt = vm.aiHint.value.receipt
        assertNotNull("设置后应能从 aiHint 取到 receipt", receipt)
        assertEquals(10005L, receipt!!.amountMinor)
        assertEquals(RECEIPT_TS, receipt.ts)
        assertEquals("晨星便利店", receipt.merchant)
        // speech 通道保持独立、不受影响。
        assertNull(vm.aiHint.value.speech)
    }
}
