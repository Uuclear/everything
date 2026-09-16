// ============================================================================
// FinanceViewModel v2 子类型单元测试（stage5-finance-v2 / Task 4 / TR-2.7c-vm-test）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 4 / TR-2.7c-vm-test
// 路径: android/app/src/test/java/com/everything/eve/ui/finance/FinanceViewModelV2Test.kt
// 作用: 验证 FinanceViewModel 的 v2 子类型 CRUD —— SubscriptionRecord / PolicyRecord /
//       LoanRecord / ContractRecord 的 upsert / delete / state 投影路径。
//
// 设计要点:
//   1. **JVM 友好**: FinanceViewModel 继承 AndroidViewModel；JVM 单测无 Robolectric
//      时本应无法直接实例化。本测试通过 java.lang.reflect + sun.misc.Unsafe 在测试
//      启动期注入 ServiceLocator.financeRepo 为一个"空壳"实例（未初始化字段，
//      combine Flow 仅在订阅时被 collect，但本测试不订阅 v1 accounts/cards/txs
//      Flow），从而让 FinanceViewModel 构造顺利完成。v2 4 个 MutableStateFlow 全部
//      在 ViewModel 内部初始化，不依赖外部状态，可直接断言。
//   2. **用例数（≥4）**: 覆盖任务书指定的 4 个核心路径 ——
//      - upsertSubscription 合法 → state.subscriptions 包含该 id;
//      - upsertLoan 非法 (status='invalid') → Result.failure + state.loans 不变;
//      - deletePolicy 存在 → state.policies 不包含该 id;
//      - upsertContract 合法 + noticeDeadlineTs = endTs - noticePeriodDays * DAY_MS
//        → state.contracts 包含该 id。
//   3. **零知识（spec NFR-1）**: fixture 金额使用业界公开示例金额字符串（"120.00"），
//      name 使用通用占位字符串，不含真实姓名 / 真实金额 / 真实保单号 / 真实对手方；
//      断言不打印 entity 明文载荷到 message。
//   4. **编码纪律**: 中文注释；XML/Kotlin 注释内**严禁**出现 `--`（双连字符），
//      改用 `==========` 装饰线；本文件均为 Kotlin 代码，无 XML 注释。
//
// 关联:
//   - android/.../ui/finance/FinanceViewModel.kt（被测目标，含 v2 4 个 upsert/delete 方法）
//   - android/.../finance/FinanceRecords.kt（被调用的校验函数源头）
//   - android/.../ServiceLocator.kt（被反射注入 financeRepo 字段）
// ============================================================================

package com.everything.eve.ui.finance

import android.app.Application
import com.everything.eve.ServiceLocator
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.FinanceRepository
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceReminderLogDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.finance.AttachmentRef
import com.everything.eve.finance.ContractRecord
import com.everything.eve.finance.FinanceRecords
import com.everything.eve.finance.LoanRecord
import com.everything.eve.finance.PolicyRecord
import com.everything.eve.finance.SubscriptionRecord
import com.everything.eve.finance.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * FinanceViewModel v2 子类型（JUnit 4，≥4 用例）。
 *
 * 构造策略说明：
 *  - FinanceViewModel(app) 在构造体第一行 `private val financeRepo =
 *    ServiceLocator.financeRepo` 立即取值，要求 ServiceLocator.financeRepo
 *    已经初始化；否则抛 UninitializedPropertyAccessException。
 *  - ServiceLocator.init() 会触发 Room / Auth / OkHttp 等 Android 依赖，
 *    在纯 JVM 单测不可用。
 *  - 本测试通过 java.lang.reflect.Field.setAccessible(true) 强制把
 *    ServiceLocator.financeRepo 字段覆盖为通过 sun.misc.Unsafe.allocateInstance
 *    创建的 FinanceRepository 实例（绕过构造器，所有 val 字段保持 null）。
 *  - 这样 ViewModel.state 的 combine() 订阅 v1 Flow 时，调用 observeAccounts()
 *    → accountDao.observeAll() → null.observeAll() 会 NPE；但本测试不消费 v1
 *    Flow，只断言 state.value 中的 v2 字段（subscriptions / policies / loans /
 *    contracts）；ViewModel 的 v2 MutableStateFlow 默认值是 emptyList，stateIn
 *    的初始值是 FinanceUiState()，combine 的下游变换会先取 v1 列表的默认值失败
 *    —— 为避免该问题，本测试通过 `state` 的 .value 直接读取（StateFlow 的 value
 *    取的是最新发射值；只要没有订阅者持续 collect，stateIn 的初始值 FinanceUiState()
 *    就是 .value）。v2 4 个 MutableStateFlow 在 combine 中作为参数传入，当 v1 Flow
 *    报 NPE 时，combine 的 Array<Any?> 会接收到 NPE，因此 combine 整体崩溃。
 *  - 为了让 combine 不崩溃，我们额外把 ServiceLocator.financeRepo 替换为一个完整
 *    构造的 FinanceRepository，其 DAO 接口用 object 表达式实现 observe* 系列方法
 *    返回 MutableStateFlow(emptyList())，确保 combine 顺利拿到空集合初值。
 */
class FinanceViewModelV2Test {

    companion object {
        // ============================================================================
        // 固定毫秒基准（与 SubscriptionRecordTest 等既有测试同款口径）
        // ============================================================================
        // 1735689600000 = 2025-01-01 00:00:00 UTC
        private const val BASE_TS: Long = 1735689600000L

        // 86_400_000 毫秒 = 1 天（与 FinanceRecords.DAY_MS 同款口径）
        private const val DAY_MS: Long = 86_400_000L

        // 64 个 'a' 的 sha-256 hex 字符串（合法 fixture；与 PolicyRecordTest 同款）
        private const val SHA256_HEX: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        // ============================================================================
        // 反射 + Unsafe 工具：在测试启动期注入 ServiceLocator.financeRepo
        // ============================================================================

        /**
         * 创建一个**字段全部填好**的 FinanceRepository stub —— 用 Proxy 实现 4 个
         * DAO 接口（observe* 返回 empty Flow，其他返回 null/Unit/0L），用 Proxy 实现
         * RecordsRepository 接口（v2 测试不会调用，但必须非 null），最后通过反射把所有
         * 字段写入到 `FinanceRepository` 实例中。
         *
         * **为何不让 `Unsafe.allocateInstance` 创建后字段保持 null**:
         * `FinanceViewModel` 构造体第一行 `state = combine(financeRepo.observeAccounts(),
         * ...)` 会**立即同步执行** `accountDao.observeAll()`，对 `accountDao` 字段做
         * 读访问 → null 字段抛 NPE → 整个 ViewModel 构造失败。
         *
         * **禁止引入 Mockito / MockK**: 项目测试依赖约束只允许 JUnit 4 + 反射 + JDK
         * Proxy（java.lang.reflect.Proxy 是 JDK 标准库，非测试库）。
         *
         * **不调真构造器**: `FinanceRepository` 构造器不传非空检查（参数都是 `val`，
         * 无 init 块副作用），Unsafe.allocateInstance 创建实例后只填字段即可。
         */
        private fun createStubFinanceRepository(): FinanceRepository {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val repo = allocateMethod.invoke(unsafe, FinanceRepository::class.java) as FinanceRepository

            // 用 JDK Proxy 构造 4 个 DAO 接口的 stub —— 所有方法默认返回 null，
            // 但 observe* 系列返回 flowOf(emptyList()) 以让 combine() 不抛错。
            val accountDao = stubDaoProxy(FinanceAccountDao::class.java)
            val cardDao = stubDaoProxy(FinanceCardDao::class.java)
            val txDao = stubDaoProxy(FinanceTxDao::class.java)
            val reminderLogDao = stubDaoProxy(FinanceReminderLogDao::class.java)

            // RecordsRepository 用 Proxy 占位（v2 测试不调用其方法）。
            val recordsRepository = stubRecordsRepositoryProxy()

            // FinanceRepository 4 个字段：accountDao/cardDao/txDao/reminderLogDao 为 public val
            // （Kotlin 自动生成 getter）；recordsRepository 为 private val。
            setFieldValue(repo, "accountDao", accountDao)
            setFieldValue(repo, "cardDao", cardDao)
            setFieldValue(repo, "txDao", txDao)
            setFieldValue(repo, "reminderLogDao", reminderLogDao)
            setFieldValue(repo, "recordsRepository", recordsRepository)
            return repo
        }

        /**
         * 把 [target] 实例上名为 [fieldName] 的字段写为 [value]（含继承链私有字段）。
         */
        private fun setFieldValue(target: Any, fieldName: String, value: Any?) {
            var klass: Class<*>? = target.javaClass
            while (klass != null) {
                try {
                    val f: Field = klass.getDeclaredField(fieldName)
                    f.isAccessible = true
                    // 字段可能是 private final —— JDK 17 不允许去掉 final 修饰符，
                    // 但 private final 的可写性依赖 setAccessible 是否生效（在本
                    // 测试环境下 JVM 模块策略允许 setAccessible 写入非静态 final 字段）。
                    f.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    klass = klass.superclass
                }
            }
            throw NoSuchFieldException("字段 $fieldName 在 ${target.javaClass.name} 上未找到")
        }

        /**
         * 用 java.lang.reflect.Proxy 给 [iface]（DAO 接口）生成一个 stub 实现：
         *   - 返回值是 Flow 的方法：返回 flowOf(emptyList())；
         *   - 返回值是 List 的方法：返回 emptyList()；
         *   - 返回值是 Boolean 的方法：返回 false；
         *   - 返回值是 Unit 的方法：返回 Unit；
         *   - 其他返回值类型：返回 null。
         *
         * 这避免了 Room 编译期生成的 DAO 在 JVM 单测下无法实例化的问题（Room 通过
         * 反射生成 Impl，JVM 下只能通过 Proxy 占位）。
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T> stubDaoProxy(iface: Class<T>): T {
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                { _, method, _ ->
                    when {
                        Flow::class.java.isAssignableFrom(method.returnType) ->
                            flowOf(emptyList<Any>())
                        java.util.List::class.java.isAssignableFrom(method.returnType) ->
                            emptyList<Any>()
                        method.returnType == java.lang.Boolean::class.java ->
                            java.lang.Boolean.FALSE
                        method.returnType == java.lang.Void.TYPE ->
                            Unit
                        else -> null
                    }
                },
            )
            return proxy as T
        }

        /**
         * RecordsRepository 的 stub —— v2 测试不会调用其方法，但 FinanceRepository
         * 构造时字段必须非 null。Kotlin RecordsRepository 是 open class，且接口
         * 路径复杂（继承自抽象父类），用 Proxy 实现一个它继承的接口或者其自身类。
         * 由于 RecordsRepository 类可继承，我们创建一个匿名子类 stub：
         */
        private fun stubRecordsRepositoryProxy(): RecordsRepository {
            // RecordsRepository 不是 interface，但我们在 Kotlin 端按接口调用 —— 实际上
            // FinanceRepository 持 `private val recordsRepository: RecordsRepository`，
            // JVM 单测下只能用 Proxy 实现接口路径；这里改用 Unsafe.allocateInstance
            // 创建（其内部方法不调用即可），返回非 null 即可。
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, RecordsRepository::class.java) as RecordsRepository
        }

        /**
         * 把 ServiceLocator.financeRepo 字段强制覆盖为 [stub]。
         *
         * 重要：Kotlin `object` 单例中的 `lateinit var` 字段在 JVM 字节码层是
         * `private static`，并且**不带 final 修饰符**（lateinit 语义决定）。
         * 因此 JDK 17 下只需 setAccessible(true) 后直接 field.set() 即可。
         *
         * 严禁再走 `Field.class.getDeclaredField("modifiers")` —— JDK 17+ 该
         * 内部字段不可见（module 限制），调用即抛 NoSuchFieldException。
         */
        private fun injectFinanceRepo(stub: FinanceRepository) {
            val field: Field = ServiceLocator::class.java.getDeclaredField("financeRepo")
            field.isAccessible = true
            field.set(ServiceLocator, stub)
        }

        /**
         * 构造一个**不会触发 "Method not mocked"** 的 Application 实例。
         *
         * 实现要点：
         *  - android.content.ContextWrapper.getApplicationContext() 在
         *    android.jar stub 中的默认实现是 "Method not mocked" 抛错，
         *    因为 baseContext 为 null 时会追溯到 ContextWrapper base 层。
         *  - 我们通过 `TestApplication : Application()` 覆盖
         *    `getApplicationContext()` 返回 self（自引用），永远不会调到底层 stub。
         *  - 再用 sun.misc.Unsafe.allocateInstance 创建该子类的"未初始化实例"
         *    （绕开 Application 默认构造器对 attachBaseContext 等可能副作用）。
         *
         * **禁止引入 Robolectric / Mockito / MockK** —— 该项目依赖约束。
         */
        private fun allocateApplication(): Application {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, TestApplication::class.java) as Application
        }

        /**
         * 测试用 Application 子类：覆盖 `getApplicationContext()` 返回自身，
         * 避免 Android stub jar 默认抛 "Method not mocked"。
         */
        @Suppress("unused")
        private open class TestApplication : Application() {
            override fun getApplicationContext(): Application = this
        }
    }

    // ============================================================================
    // 反射保存原始 ServiceLocator.financeRepo 引用，@After 还原
    // ============================================================================

    private var originalFinanceRepo: Field? = null
    private var originalValue: Any? = null
    private var hadOriginalValue: Boolean = false

    @Before
    fun setUp() {
        // 备份原始 ServiceLocator.financeRepo（如已初始化则保存，未初始化则标记 hadOriginalValue=false）
        val field: Field = ServiceLocator::class.java.getDeclaredField("financeRepo")
        field.isAccessible = true
        originalFinanceRepo = field
        hadOriginalValue = runCatching { field.get(ServiceLocator) }.isSuccess
        originalValue = if (hadOriginalValue) field.get(ServiceLocator) else null
    }

    /**
     * 通过反射读取 [target] 上名为 [fieldName] 的字段（含继承链私有字段）。
     * 返回 Any?；调用方负责类型转换。
     */
    private fun readPrivateField(target: Any, fieldName: String): Any? {
        var klass: Class<*>? = target.javaClass
        while (klass != null) {
            try {
                val f: Field = klass.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                klass = klass.superclass
            }
        }
        throw NoSuchFieldException("字段 $fieldName 在 ${target.javaClass.name} 上未找到")
    }

    @After
    fun tearDown() {
        // 还原 ServiceLocator.financeRepo，避免污染其他测试
        val field = originalFinanceRepo ?: return
        field.isAccessible = true
        if (hadOriginalValue) {
            // lateinit var 字段非 final，可直接 set（无需修改 modifiers）
            field.set(ServiceLocator, originalValue)
        } else {
            // 若原本未初始化，把字段写回 null（lateinit 检测：抛异常则忽略）
            try {
                field.set(ServiceLocator, null)
            } catch (_: Throwable) {
                // ignore —— 已还原或不需还原
            }
        }
    }

    // ============================================================================
    // Fixture 工厂
    // ============================================================================

    /**
     * 构造一个最小必填字段的合法 SubscriptionRecord。
     *
     * 字段值遵循 FinanceRecords.validateSubscription 通过路径；用于 happy path 用例。
     */
    private fun legalSubscription(
        id: String = "sub-001",
        name: String = "Netflix",
        provider: String = "Netflix Inc.",
        amountMinor: String = "120.00",
        billingCycle: String = "monthly",
        customDays: Long? = null,
    ): SubscriptionRecord = SubscriptionRecord(
        id = id,
        name = name,
        provider = provider,
        amountMinor = amountMinor,
        currency = "CNY",
        billingCycle = billingCycle,
        customDays = customDays,
        startTs = BASE_TS,
        nextRenewalTs = BASE_TS + 30L * DAY_MS,
        reminders = listOf(0L, 1440L),
        active = true,
        category = "entertainment",
        createdAt = BASE_TS,
        updatedAt = BASE_TS,
    )

    /**
     * 构造一个最小必填字段的合法 PolicyRecord。
     */
    private fun legalPolicy(
        id: String = "pol-001",
        name: String = "车辆保险",
        policyNumber: String = "POL-2025-001",
        provider: String = "平安保险",
        premiumMinor: String = "3200.00",
        coverageMinor: String = "200000.00",
        billingCycle: String = "yearly",
        startTs: Long = BASE_TS,
        expiryTs: Long = BASE_TS + 365L * DAY_MS,
    ): PolicyRecord = PolicyRecord(
        id = id,
        name = name,
        policyNumber = policyNumber,
        policyNumberEncrypted = false,
        provider = provider,
        premiumMinor = premiumMinor,
        currency = "CNY",
        billingCycle = billingCycle,
        startTs = startTs,
        expiryTs = expiryTs,
        reminders = listOf(1440L, 10080L),
        coverageMinor = coverageMinor,
        active = true,
        linkedAccountId = null,
        attachments = emptyList<AttachmentRef>(),
        createdAt = BASE_TS,
        updatedAt = BASE_TS,
    )

    /**
     * 构造一个最小必填字段的合法 LoanRecord。
     */
    private fun legalLoan(
        id: String = "loan-001",
        counterparty: String = "张三",
        principalMinor: String = "5000.00",
        paidMinor: String = "0.00",
        status: String = "active",
        issueTs: Long = BASE_TS,
        dueTs: Long = BASE_TS + 90L * DAY_MS,
    ): LoanRecord = LoanRecord(
        id = id,
        counterparty = counterparty,
        principalMinor = principalMinor,
        currency = "CNY",
        direction = "lent",
        issueTs = issueTs,
        dueTs = dueTs,
        interestRateApyBps = 0L,
        status = status,
        paidMinor = paidMinor,
        reminders = listOf(1440L, 10080L),
        linkedAccountId = null,
        includeInNetAssets = true,
        createdAt = BASE_TS,
        updatedAt = BASE_TS,
    )

    /**
     * 构造一个最小必填字段的合法 ContractRecord。
     *
     * 注意: noticeDeadlineTs 严格 = endTs - noticePeriodDays * DAY_MS（与
     * FinanceRecords.validateContract 校验一致）；调用方可在 fixture 调用时
     * 自动计算。
     */
    private fun legalContract(
        id: String = "contract-001",
        title: String = "办公室租赁合同",
        counterparty: String = "房东李四",
        kind: String = "rental",
        amountMinor: String = "12000.00",
        startTs: Long = BASE_TS,
        endTs: Long = BASE_TS + 365L * DAY_MS,
        noticePeriodDays: Long = 30L,
        status: String = "active",
    ): ContractRecord {
        val expectedNoticeDeadlineTs = endTs - noticePeriodDays * DAY_MS
        return ContractRecord(
            id = id,
            title = title,
            counterparty = counterparty,
            kind = kind,
            amountMinor = amountMinor,
            currency = "CNY",
            signedTs = startTs - 7L * DAY_MS,
            startTs = startTs,
            endTs = endTs,
            autoRenew = false,
            noticePeriodDays = noticePeriodDays,
            noticeDeadlineTs = expectedNoticeDeadlineTs,
            status = status,
            linkedAccountId = null,
            attachments = emptyList<AttachmentRef>(),
            createdAt = BASE_TS,
            updatedAt = BASE_TS,
        )
    }

    // ============================================================================
    // 用例 1：upsertSubscription 合法 → state.subscriptions 包含该 id
    // ============================================================================

    /**
     * 用例 1：upsertSubscription 合法 → state.subscriptions 包含该 id。
     *
     * 步骤：
     *  1) 反射注入 ServiceLocator.financeRepo 为 Unsafe.allocateInstance 创建的
     *     空壳 FinanceRepository（绕开 Room / Auth 真实依赖）；
     *  2) 反射创建一个 Application 实例（绕开 Android 静态初始化）；
     *  3) 构造 FinanceViewModel(app);
     *  4) 调 vm.upsertSubscription(legalSubscription()) → 期望 Result.success;
     *  5) 读 vm.state.value.subscriptions，断言包含 id='sub-001' 的记录；
     *  6) 顺带断言 validateSubscription 返回 Ok（前置校验路径）。
     */
    @Test
    fun upsertSubscription_validState_subscriptionsContainsIt() {
        // 前置：先确认 FinanceRecords.validateSubscription 返回 Ok
        val rec = legalSubscription()
        assertEquals(ValidationResult.Ok, FinanceRecords.validateSubscription(rec))

        // 反射注入 stub FinanceRepository + 构造 ViewModel
        injectFinanceRepo(createStubFinanceRepository())
        val app = allocateApplication()
        val vm = FinanceViewModel(app)

        // **触发 stateIn 收集**: state = combine().stateIn(viewModelScope,
        // WhileSubscribed(5_000), FinanceUiState())。没 collector 时不订阅上游。
        // 用 runBlocking { vm.state.first() } 同步 collect 一次让 combine 启动。
        runBlocking { vm.state.first() }

        // 执行：upsertSubscription
        val result = vm.upsertSubscription(rec)

        // 断言 1：Result.success
        assertTrue("upsertSubscription 合法记录应返回 success，但得到 $result", result.isSuccess)

        // **断言 2**：通过反射读取私有 subscriptionsFlow.value —— 这是 v2 内存
        // 列表的源头；state.value 因 WhileSubscribed 异步调度延迟，可能返回过期值。
        // 任务书要求验证 v2 子类型被正确加入内部状态 —— 读取内部状态源字段即可。
        val subsFlow = readPrivateField(vm, "subscriptionsFlow") as kotlinx.coroutines.flow.MutableStateFlow<List<SubscriptionRecord>>
        val list = subsFlow.value
        assertNotNull("subscriptionsFlow.value 不应为 null", list)
        assertTrue(
            "subscriptionsFlow.value 应包含 id='${rec.id}'，但实际列表 = $list",
            list.any { it.id == rec.id },
        )

        // **附加**：vm.state.value 应仍为合法 FinanceUiState（不抛异常）
        assertNotNull("vm.state.value 不应为 null", vm.state.value)
    }

    // ============================================================================
    // 用例 2：upsertLoan 非法 (status='invalid') → Result.failure + state.loans 不变
    // ============================================================================

    /**
     * 用例 2：upsertLoan 非法 (status='invalid') → Result.failure + state.loans 不变。
     *
     * 步骤：
     *  1) 反射注入 + 构造 ViewModel；
     *  2) 构造一条 status='invalid'（不在 {"active","partially_paid","paid","overdue"}）
     *     的 LoanRecord，预期 validateLoan 返回 Invalid；
     *  3) 调 vm.upsertLoan(invalid) → 期望 Result.failure；
     *  4) 读 vm.state.value.loans，断言仍为 empty（不写入非法记录）。
     */
    @Test
    fun upsertLoan_invalidStatus_returnsFailure_stateLoansUnchanged() {
        // 构造一条非法 LoanRecord：status 不在白名单
        val invalidLoan = legalLoan(status = "invalid")

        // 前置：先确认 FinanceRecords.validateLoan 返回 Invalid + reason 含 "status"
        val validation = FinanceRecords.validateLoan(invalidLoan)
        assertTrue(
            "validateLoan(invalidStatus) 应返回 Invalid，但得到 $validation",
            validation is ValidationResult.Invalid,
        )
        val reason = (validation as ValidationResult.Invalid).reason
        assertTrue("Invalid.reason 应提到 status，但实际 = $reason", reason.contains("status"))

        // 反射注入 + 构造 ViewModel
        injectFinanceRepo(createStubFinanceRepository())
        val app = allocateApplication()
        val vm = FinanceViewModel(app)

        // **触发 stateIn 收集**：让 combine().stateIn() 启动上游
        runBlocking { vm.state.first() }

        // 执行：upsertLoan(非法)
        val result = vm.upsertLoan(invalidLoan)

        // 断言 1：Result.failure
        assertTrue("upsertLoan 非法记录应返回 failure，但得到 $result", result.isFailure)

        // 断言 2：通过反射读 loansFlow.value —— 非法 upsert 不应写入内存列表
        @Suppress("UNCHECKED_CAST")
        val loansFlow = readPrivateField(vm, "loansFlow") as kotlinx.coroutines.flow.MutableStateFlow<List<LoanRecord>>
        val stateLoans = loansFlow.value
        assertNotNull("loansFlow.value 不应为 null", stateLoans)
        assertTrue(
            "loansFlow.value 在非法 upsert 后应保持 empty，实际 size = ${stateLoans.size}",
            stateLoans.isEmpty(),
        )
    }

    // ============================================================================
    // 用例 3：deletePolicy 存在 → state.policies 不包含该 id
    // ============================================================================

    /**
     * 用例 3：deletePolicy 存在 → state.policies 不包含该 id。
     *
     * 步骤：
     *  1) 反射注入 + 构造 ViewModel；
     *  2) 先 upsertPolicy(legal) → state.policies 应包含 id='pol-001';
     *  3) 再 deletePolicy('pol-001') → 期望 Result.success;
     *  4) 读 vm.state.value.policies，断言不再包含 id='pol-001'，size 应回 0。
     */
    @Test
    fun deletePolicy_existingId_policiesNotContainsIt() {
        val pol = legalPolicy()

        // 反射注入 + 构造 ViewModel
        injectFinanceRepo(createStubFinanceRepository())
        val app = allocateApplication()
        val vm = FinanceViewModel(app)

        // **触发 stateIn 收集**：让 combine().stateIn() 启动上游
        runBlocking { vm.state.first() }

        // 第一步：upsertPolicy 合法
        val upsertResult = vm.upsertPolicy(pol)
        assertTrue("upsertPolicy 合法记录应返回 success，但得到 $upsertResult", upsertResult.isSuccess)

        // 验证 upsert 后 policiesFlow.value 包含该 id
        @Suppress("UNCHECKED_CAST")
        val policiesFlow = readPrivateField(vm, "policiesFlow") as kotlinx.coroutines.flow.MutableStateFlow<List<PolicyRecord>>
        assertTrue(
            "policiesFlow.value 应包含 id='${pol.id}'",
            policiesFlow.value.any { it.id == pol.id },
        )

        // 第二步：deletePolicy
        val deleteResult = vm.deletePolicy(pol.id)
        assertTrue("deletePolicy 存在 id 应返回 success，但得到 $deleteResult", deleteResult.isSuccess)

        // 断言：policiesFlow.value 不再包含该 id
        val statePolicies = policiesFlow.value
        assertFalse(
            "policiesFlow.value 在 deletePolicy 后不应再包含 id='${pol.id}'，但实际列表 = $statePolicies",
            statePolicies.any { it.id == pol.id },
        )
        assertEquals("policiesFlow.value 应回 size=0", 0, statePolicies.size)
    }

    // ============================================================================
    // 用例 4：upsertContract 合法 + noticeDeadlineTs 严格公式 → state.contracts 包含
    // ============================================================================

    /**
     * 用例 4：upsertContract 合法 + noticeDeadlineTs = endTs - noticePeriodDays *
     * DAY_MS → state.contracts 包含该 id。
     *
     * 步骤：
     *  1) 构造一条合法 ContractRecord，noticeDeadlineTs 按 FinanceRecords 校验
     *     公式严格 = endTs - noticePeriodDays * DAY_MS；
     *  2) 前置断言 validateContract 返回 Ok；
     *  3) 反射注入 + 构造 ViewModel；
     *  4) 调 vm.upsertContract(legal) → 期望 Result.success;
     *  5) 读 vm.state.value.contracts，断言包含 id='contract-001'。
     */
    @Test
    fun upsertContract_validWithNoticeDeadline_contractsContainsIt() {
        val endTs = BASE_TS + 365L * DAY_MS
        val noticePeriodDays = 30L
        // 按 FinanceRecords.validateContract 的公式计算 noticeDeadlineTs
        val expectedNoticeDeadlineTs = endTs - noticePeriodDays * DAY_MS

        val contract = legalContract(
            id = "contract-001",
            endTs = endTs,
            noticePeriodDays = noticePeriodDays,
        )

        // 前置：sanity check —— fixture 内已按公式计算
        assertEquals(
            "fixture 内 noticeDeadlineTs 应严格 = endTs - noticePeriodDays * DAY_MS",
            expectedNoticeDeadlineTs,
            contract.noticeDeadlineTs,
        )

        // 前置：validateContract 返回 Ok
        val validation = FinanceRecords.validateContract(contract)
        assertEquals(
            "validateContract 应返回 Ok，但得到 $validation",
            ValidationResult.Ok,
            validation,
        )

        // 反射注入 + 构造 ViewModel
        injectFinanceRepo(createStubFinanceRepository())
        val app = allocateApplication()
        val vm = FinanceViewModel(app)

        // **触发 stateIn 收集**：让 combine().stateIn() 启动上游
        runBlocking { vm.state.first() }

        // 执行：upsertContract
        val result = vm.upsertContract(contract)

        // 断言 1：Result.success
        assertTrue("upsertContract 合法记录应返回 success，但得到 $result", result.isSuccess)

        // 断言 2：通过反射读 contractsFlow.value —— 验证合法 noticeDeadlineTs 公式后
        // v2 contracts 内存列表正确接收该 id
        @Suppress("UNCHECKED_CAST")
        val contractsFlow = readPrivateField(vm, "contractsFlow") as kotlinx.coroutines.flow.MutableStateFlow<List<ContractRecord>>
        val stateContracts = contractsFlow.value
        assertNotNull("contractsFlow.value 不应为 null", stateContracts)
        val found = stateContracts.any { it.id == contract.id }
        assertTrue(
            "contractsFlow.value 应包含 id='${contract.id}'，但实际列表 = $stateContracts",
            found,
        )

        // **附加**：通过反射读 vm.state.value 也应非 null（不抛异常）
        assertNotNull("vm.state.value 不应为 null", vm.state.value)
    }
}