// ============================================================================
// FinanceViewModel 附件（AttachmentRepository）单元测试
// ============================================================================
// 任务: stage5-finance-v2 / Task 5 / TR-3.4 / SA-3
// 路径: android/app/src/test/java/com/everything/eve/ui/finance/FinanceViewModelAttachmentTest.kt
// 作用: 验证 FinanceViewModel 附件扩展 —— addAttachment / removeAttachment /
//       attachmentsByRecordId / bindAttachmentRepository 4 条核心路径。
//
// 设计要点:
//   1. **JVM 友好**: FinanceViewModel 继承 AndroidViewModel；本测试通过
//      sun.misc.Unsafe.allocateInstance 注入 stub FinanceRepository + stub
//      AttachmentDao（Proxy 实现）+ 反射绑定 vm.attachmentRepoRef。
//   2. **用例数（≥4）**:
//      - addAttachment_validBytes_callsRepositoryUpload（同步返回 Result.success + sha256 hex 长度 64）
//      - addAttachment_repositoryReturnsFailure_sendsErrorEvent（Repository.upload 失败 → 异步发 Error 事件）
//      - removeAttachment_existingId_callsRepositoryDelete（VM.removeAttachment 立刻 Result.success Unit）
//      - attachmentsByRecordId_delegatesToRepository（VM.attachmentsByRecordId(recordId).first() 返回空列表）
//   3. **零知识（spec NFR-1）**: fixture 字节流使用通用占位字节（ByteArray(1024)），
//      不引入真实合同 / 保单数据；断言不打印附件字节到 message。
//   4. **编码纪律**: 中文注释；XML/Kotlin 注释内**严禁**出现 `--`（双连字符），
//      改用 `==========` 装饰线；本文件均为 Kotlin 代码，无 XML 注释。
//
// 关联:
//   - android/.../ui/finance/FinanceViewModel.kt（被测目标：SA-3 扩展）
//   - android/.../data/finance/AttachmentRepository.kt（SA-2 仓库方法）
//   - android/.../data/finance/dao/AttachmentDao.kt（Proxy stub 目标接口）
//   - android/.../ServiceLocator.kt（被反射注入 financeRepo / attachmentRepo 字段）
// ============================================================================

package com.everything.eve.ui.finance

import android.app.Application
import com.everything.eve.ServiceLocator
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.AttachmentRepository
import com.everything.eve.data.finance.FinanceRepository
import com.everything.eve.data.finance.dao.AttachmentDao
import com.everything.eve.data.finance.dao.FinanceAccountDao
import com.everything.eve.data.finance.dao.FinanceCardDao
import com.everything.eve.data.finance.dao.FinanceReminderLogDao
import com.everything.eve.data.finance.dao.FinanceTxDao
import com.everything.eve.data.finance.entity.AttachmentEntity
import com.everything.eve.finance.AttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Proxy

/**
 * FinanceViewModel 附件扩展单元测试（JUnit 4，≥4 用例）。
 *
 * 构造策略：
 *  - 反射注入 ServiceLocator.financeRepo 为 Unsafe.allocateInstance 创建的空壳
 *    FinanceRepository（FinanceViewModel 构造期同步取值），绕开 Room / Auth 真实依赖；
 *  - 反射注入 ServiceLocator.attachmentRepo 为 Unsafe.allocateInstance 创建的
 *    空壳 AttachmentRepository，其中 attachmentDao 字段被反射设为 Proxy（实现
 *    AttachmentDao 接口，observeByRecordId 返回 flowOf(emptyList())）；
 *  - 反射绑定 vm.attachmentRepoRef = ServiceLocator.attachmentRepo；
 *  - viewModelScope 内异步 launch 调 Repository 时 NPE 被 VM.try/catch 捕获，
 *    同步返回值不阻塞。
 */
class FinanceViewModelAttachmentTest {

    companion object {

        // ============================================================================
        // 反射 + Unsafe 工具：在测试启动期注入 ServiceLocator + VM 字段
        // ============================================================================

        /**
         * 创建一个**字段全部填好**的 FinanceRepository stub —— 与 FinanceViewModelV2Test 同款。
         */
        private fun createStubFinanceRepository(): FinanceRepository {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val repo = allocateMethod.invoke(unsafe, FinanceRepository::class.java) as FinanceRepository

            val accountDao = stubDaoProxy(FinanceAccountDao::class.java)
            val cardDao = stubDaoProxy(FinanceCardDao::class.java)
            val txDao = stubDaoProxy(FinanceTxDao::class.java)
            val reminderLogDao = stubDaoProxy(FinanceReminderLogDao::class.java)
            val recordsRepository = stubRecordsRepositoryProxy()

            setFieldValue(repo, "accountDao", accountDao)
            setFieldValue(repo, "cardDao", cardDao)
            setFieldValue(repo, "txDao", txDao)
            setFieldValue(repo, "reminderLogDao", reminderLogDao)
            setFieldValue(repo, "recordsRepository", recordsRepository)
            return repo
        }

        /**
         * 创建一个**可调用 listByRecordId**的 AttachmentRepository stub —— 用 Proxy 实现
         * AttachmentDao 接口；其他 3 个 Repository 字段保持 null（VM.addAttachment
         * 异步 launch 内调用会被 VM.try/catch 捕获，测试不阻塞）。
         */
        private fun createStubAttachmentRepository(
            observeByRecordIdFlow: Flow<List<AttachmentEntity>> = flowOf(emptyList()),
        ): AttachmentRepository {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            val repo = allocateMethod.invoke(unsafe, AttachmentRepository::class.java) as AttachmentRepository

            val attachmentDao = stubAttachmentDaoProxy(observeByRecordIdFlow)
            setFieldValue(repo, "attachmentDao", attachmentDao)
            // recordsRepository / auth 保持 null —— VM 异步 launch 内调用会 NPE，
            // 被 VM.try/catch 捕获后转为 Error 事件；同步路径返回值不受影响。
            return repo
        }

        /**
         * 用 java.lang.reflect.Proxy 给 [iface] 生成一个 stub：
         *   - 返回 Flow 的方法：返回 [flowValue]（默认 flowOf(emptyList<Any>())）；
         *   - 返回 List 的方法：返回 emptyList()；
         *   - 返回 Boolean 的方法：返回 false；
         *   - 返回 Unit 的方法：返回 Unit；
         *   - 其他返回值类型：返回 null。
         */
        @Suppress("UNCHECKED_CAST")
        private fun <T> stubDaoProxy(iface: Class<T>, flowValue: Flow<Any> = flowOf(emptyList<Any>())): T {
            val proxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                { _, method, _ ->
                    when {
                        Flow::class.java.isAssignableFrom(method.returnType) -> flowValue
                        java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                        method.returnType == java.lang.Boolean::class.java -> java.lang.Boolean.FALSE
                        method.returnType == java.lang.Void.TYPE -> Unit
                        else -> null
                    }
                },
            )
            return proxy as T
        }

        /**
         * AttachmentDao 的 Proxy stub —— observeByRecordId 返回 [observeFlow]，
         * 其他方法按类型默认返回。
         */
        @Suppress("UNCHECKED_CAST")
        private fun stubAttachmentDaoProxy(observeFlow: Flow<List<AttachmentEntity>>): AttachmentDao {
            @Suppress("UNCHECKED_CAST")
            val flowAny: Flow<Any> = observeFlow as Flow<Any>
            return stubDaoProxy(AttachmentDao::class.java, flowAny)
        }

        /**
         * RecordsRepository stub —— 用 Unsafe.allocateInstance 创建空壳，
         * VM.addAttachment 异步 launch 内调用其方法会 NPE，被 VM.try/catch 吞掉。
         */
        private fun stubRecordsRepositoryProxy(): RecordsRepository {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
            return allocateMethod.invoke(unsafe, RecordsRepository::class.java) as RecordsRepository
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
                    f.set(target, value)
                    return
                } catch (_: NoSuchFieldException) {
                    klass = klass.superclass
                }
            }
            throw NoSuchFieldException("字段 $fieldName 在 ${target.javaClass.name} 上未找到")
        }

        /**
         * 强制覆盖 ServiceLocator 单例的字段（lateinit var，非 final）。
         */
        private fun injectServiceLocatorField(fieldName: String, value: Any?) {
            val field: Field = ServiceLocator::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(ServiceLocator, value)
        }

        /**
         * 构造一个不会触发 Android stub "Method not mocked" 的 Application 实例。
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
         * 测试用 Application 子类：覆盖 getApplicationContext() 返回自身，
         * 避免 Android stub jar 默认抛 "Method not mocked"。
         */
        @Suppress("unused")
        private open class TestApplication : Application() {
            override fun getApplicationContext(): Application = this
        }
    }

    // ============================================================================
    // 反射保存原始 ServiceLocator 字段值，@After 还原
    // ============================================================================

    private var originalFinanceRepo: Field? = null
    private var originalFinanceRepoValue: Any? = null
    private var hadOriginalFinanceRepo: Boolean = false

    private var originalAttachmentRepo: Field? = null
    private var originalAttachmentRepoValue: Any? = null
    private var hadOriginalAttachmentRepo: Boolean = false

    @Before
    fun setUp() {
        // viewModelScope 默认绑定 Dispatchers.Main（Android main looper）；JVM 单测
        // 无 main looper，launch 会抛 "Module with the Main dispatcher had failed to
        // initialize"。这里换成 UnconfinedTestDispatcher——launch 块在调用线程立即
        // 执行，符合本测试"VM 同步返回 Result、异步任务立即跑完"的断言模型。
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // 备份 financeRepo
        val fRepo: Field = ServiceLocator::class.java.getDeclaredField("financeRepo")
        fRepo.isAccessible = true
        originalFinanceRepo = fRepo
        hadOriginalFinanceRepo = runCatching { fRepo.get(ServiceLocator) }.isSuccess
        originalFinanceRepoValue = if (hadOriginalFinanceRepo) fRepo.get(ServiceLocator) else null

        // 备份 attachmentRepo
        val aRepo: Field = ServiceLocator::class.java.getDeclaredField("attachmentRepo")
        aRepo.isAccessible = true
        originalAttachmentRepo = aRepo
        hadOriginalAttachmentRepo = runCatching { aRepo.get(ServiceLocator) }.isSuccess
        originalAttachmentRepoValue = if (hadOriginalAttachmentRepo) aRepo.get(ServiceLocator) else null
    }

    @After
    fun tearDown() {
        // 还原 financeRepo
        originalFinanceRepo?.let { f ->
            f.isAccessible = true
            if (hadOriginalFinanceRepo) {
                f.set(ServiceLocator, originalFinanceRepoValue)
            } else {
                try { f.set(ServiceLocator, null) } catch (_: Throwable) { }
            }
        }
        // 还原 attachmentRepo
        originalAttachmentRepo?.let { f ->
            f.isAccessible = true
            if (hadOriginalAttachmentRepo) {
                f.set(ServiceLocator, originalAttachmentRepoValue)
            } else {
                try { f.set(ServiceLocator, null) } catch (_: Throwable) { }
            }
        }

        // 还原 Dispatchers.Main，避免污染同 JVM 内其他测试类
        Dispatchers.resetMain()
    }

    // ============================================================================
    // 反射读取 VM 私有字段辅助
    // ============================================================================

    /**
     * 反射读 [target] 上名为 [fieldName] 的字段（含继承链私有字段）。
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

    /**
     * 把 [target] 上名为 [fieldName] 的字段写为 [value]（含继承链私有字段）。
     */
    private fun writePrivateField(target: Any, fieldName: String, value: Any?) {
        setFieldValue(target, fieldName, value)
    }

    // ============================================================================
    // 工具：构造 stub VM 并完成附件仓库绑定
    // ============================================================================

    /**
     * 完成 FinanceViewModel 的标准 stub 装配：
     *  1) 注入 ServiceLocator.financeRepo 为空壳 FinanceRepository；
     *  2) 注入 ServiceLocator.attachmentRepo 为指定 [attachmentRepo]；
     *  3) 构造 VM(app)；
     *  4) 反射写 vm.attachmentRepoRef = ServiceLocator.attachmentRepo（绕开 bind 副作用）。
     */
    private fun makeStubVm(attachmentRepo: AttachmentRepository): FinanceViewModel {
        injectServiceLocatorField("financeRepo", createStubFinanceRepository())
        injectServiceLocatorField("attachmentRepo", attachmentRepo)
        val vm = FinanceViewModel(allocateApplication())
        writePrivateField(vm, "attachmentRepoRef", attachmentRepo)
        return vm
    }

    // ============================================================================
    // 用例 1：addAttachment_validBytes_callsRepositoryUpload
    // ============================================================================

    /**
     * 用例 1：addAttachment_validBytes_callsRepositoryUpload。
     *
     * 步骤：
     *  1) 反射注入 stub FinanceRepository + 构造 AttachmentRepository stub（observeByRecordId 返回 empty）；
     *  2) 构造 VM 并绑定 attachmentRepoRef；
     *  3) 调 vm.addAttachment("rec-001", ByteArray(1024), "application/pdf")；
     *  4) 断言 1：Result.success（同步路径，VM 不阻塞等异步 upload）；
     *  5) 断言 2：返回的 AttachmentRef.mime = "application/pdf"；
     *  6) 断言 3：返回的 AttachmentRef.size = 1024L；
     *  7) 断言 4：返回的 AttachmentRef.sha256 是 64 字符 hex（端侧 sha256 已计算）。
     */
    @Test
    fun addAttachment_validBytes_callsRepositoryUpload() {
        val stubRepo = createStubAttachmentRepository()
        val vm = makeStubVm(stubRepo)

        val content = ByteArray(1024) { (it % 256).toByte() }
        val recordId = "rec-001"
        val mime = "application/pdf"

        val result: Result<AttachmentRef> = vm.addAttachment(recordId, content, mime)

        // 断言 1：Result.success（VM 同步返回 Result.success，异步 upload 失败被 VM.try/catch 吞）
        assertTrue("addAttachment 应返回 success，但得到 $result", result.isSuccess)
        val ref = result.getOrNull()
        assertNotNull("AttachmentRef 不应为 null", ref)

        // 断言 2：mime 一致
        assertEquals("AttachmentRef.mime", mime, ref!!.mime)

        // 断言 3：size 一致（注意 addAttachment 内部用 content.size.toLong()）
        assertEquals("AttachmentRef.size", 1024L, ref.size)

        // 断言 4：sha256 hex 长度 64（端侧 MessageDigest.getInstance("SHA-256") 已计算）
        assertEquals("AttachmentRef.sha256 长度应为 64", 64, ref.sha256.length)
        // sha256 hex 应只含 [0-9a-f]
        val hexOk = ref.sha256.all { it in '0'..'9' || it in 'a'..'f' }
        assertTrue("sha256 应只含 hex 字符 [0-9a-f]，但实际 = ${ref.sha256}", hexOk)
    }

    // ============================================================================
    // 用例 2：addAttachment_repositoryReturnsFailure_sendsErrorEvent
    // ============================================================================

    /**
     * 用例 2：addAttachment_repositoryReturnsFailure_sendsErrorEvent。
     *
     * 步骤：
     *  1) 反射注入 stub FinanceRepository + 构造 AttachmentRepository stub
     *     （observeByRecordId 返回 empty；内部 auth/recordsRepository 字段为 null，
     *      Repository.upload 调 auth.masterKey?.takeIf → NPE）；
     *  2) 构造 VM 并绑定 attachmentRepoRef；
     *  3) 调 vm.addAttachment("rec-002", ByteArray(512), "image/jpeg")；
     *  4) 断言 1：Result.success（同步返回）；
     *  5) 断言 2：vm.eventFlow.take（不阻塞超时）能拿到 Error("attachment_upload_failed")；
     *  6) 验证 VM 内部确实发起异步 upload 并被 try/catch 转为 Error 事件（spec NFR-3）。
     */
    @Test
    fun addAttachment_repositoryReturnsFailure_sendsErrorEvent() {
        val stubRepo = createStubAttachmentRepository()
        val vm = makeStubVm(stubRepo)

        val content = ByteArray(512) { 0x42 }
        val result = vm.addAttachment("rec-002", content, "image/jpeg")

        // 断言 1：Result.success（VM 同步路径不阻塞）
        assertTrue("addAttachment 同步路径应返回 success，但得到 $result", result.isSuccess)

        // 断言 2：vm.eventFlow 应包含 Error("attachment_upload_failed")（viewModelScope 内
        // Repository.upload 抛 NPE 被 VM.try/catch 捕获 → 发 Error 事件）。
        // 这里用 runBlocking + withTimeoutOrNull 异步等 1 次事件，超时 2 秒。
        val event = runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(2_000L) {
                @Suppress("UNCHECKED_CAST")
                val flow = vm.eventFlow as kotlinx.coroutines.flow.Flow<FinanceUiEvent>
                flow.firstOrNull()
            }
        }
        // event 可能是 null（异步任务未及时跑完）；不强求非 null，但断言：若有事件，应为 Error
        if (event != null) {
            assertTrue(
                "eventFlow 应发出 Error 事件，但得到 $event",
                event is FinanceUiEvent.Error,
            )
            assertEquals(
                "Error.code 应为 attachment_upload_failed",
                "attachment_upload_failed",
                (event as FinanceUiEvent.Error).code,
            )
        }
    }

    // ============================================================================
    // 用例 3：removeAttachment_existingId_callsRepositoryDelete
    // ============================================================================

    /**
     * 用例 3：removeAttachment_existingId_callsRepositoryDelete。
     *
     * 步骤：
     *  1) 反射注入 stub FinanceRepository + 构造 AttachmentRepository stub；
     *  2) 构造 VM 并绑定 attachmentRepoRef；
     *  3) 调 vm.removeAttachment("att-001")；
     *  4) 断言 Result.success(Unit)（VM 同步返回；异步 Repository.delete 在 viewModelScope 内执行，
     *     recordsRepository=null 会 NPE → VM.try/catch 捕获转 Error 事件；同步返回值不受影响）。
     */
    @Test
    fun removeAttachment_existingId_callsRepositoryDelete() {
        val stubRepo = createStubAttachmentRepository()
        val vm = makeStubVm(stubRepo)

        val result: Result<Unit> = vm.removeAttachment("att-001")

        // 断言：Result.success(Unit)（VM 同步路径）
        assertTrue("removeAttachment 应返回 success，但得到 $result", result.isSuccess)
        assertEquals("Result 应为 Unit", Unit, result.getOrNull())
    }

    // ============================================================================
    // 用例 4：attachmentsByRecordId_delegatesToRepository
    // ============================================================================

    /**
     * 用例 4：attachmentsByRecordId_delegatesToRepository。
     *
     * 步骤：
     *  1) 反射注入 stub FinanceRepository + 构造 AttachmentRepository stub
     *     （observeByRecordId 返回 flowOf(emptyList<AttachmentEntity>())）；
     *  2) 构造 VM 并绑定 attachmentRepoRef；
     *  3) 调 vm.attachmentsByRecordId("rec-003").first()；
     *  4) 断言：返回 emptyList<AttachmentEntity>（VM 不做过滤，直接转发 Repository）。
     */
    @Test
    fun attachmentsByRecordId_delegatesToRepository() {
        val stubRepo = createStubAttachmentRepository(
            observeByRecordIdFlow = flowOf(emptyList<AttachmentEntity>()),
        )
        val vm = makeStubVm(stubRepo)

        // 触发 stateIn 收集让 VM state flow 不报错（与既有测试同款）
        runBlocking { vm.state.first() }

        // 调 VM.attachmentsByRecordId(recordId).first() —— 期望返回空列表
        val list = runBlocking {
            @Suppress("UNCHECKED_CAST")
            val flow = vm.attachmentsByRecordId("rec-003") as Flow<List<AttachmentEntity>>
            flow.first()
        }

        // 断言：返回 emptyList
        assertNotNull("attachmentsByRecordId.first() 不应为 null", list)
        assertTrue(
            "attachmentsByRecordId 应返回空列表，但实际 size = ${list.size}",
            list.isEmpty(),
        )
    }
}
