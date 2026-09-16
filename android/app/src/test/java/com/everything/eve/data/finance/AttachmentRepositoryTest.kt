// ============================================================================
// AttachmentRepository 单元测试（stage5-finance-v2 / TR-3.2 / Task "AttachmentRepository + 附件块加密同步"）
// ============================================================================
//
// 路径：android/app/src/test/java/com/everything/eve/data/finance/AttachmentRepositoryTest.kt
//
// 验证目标（≥6 用例，覆盖 TR-3.2 Pass Condition）：
//   1) upload validBytes happy path（依赖 native libsodium 时跑通加密 + 入库）；
//   2) upload size 超 50MB 上限 → Result.failure（前置校验，**不依赖 native**）；
//   3) upload size=0 → Result.failure（前置校验，**不依赖 native**）；
//   4) upload sha256 hex 长度非法（≠64）→ Result.failure（前置校验，**不依赖 native**）；
//   5) upload sha256 含非 hex 字符（'g'）→ Result.failure（前置校验，**不依赖 native**）；
//   6) download 命中附件 → 解密 → 字节级一致 + sha256 比对通过；
//   7) download 篡改 sha256 后 → Result.failure（sha256 校验失败）；
//   8) delete → markDeleted 被调 + dirty tombstone records 行写入（**部分依赖 native**）。
//
// 设计要点：
//   - JUnit 4 + JVM 单测，**不**依赖 Robolectric / Room in-memory（沿用 FinanceRepositoryTest
//     与 FinancePullDecryptTest 既有 0 新增依赖模式）；
//   - AttachmentDao / RecordsRepository 接口用 java.lang.reflect.Proxy 桩，调用参数由
//     InvocationHandler 捕获用于断言；
//   - AuthManager 是 final class，用 sun.misc.Unsafe.allocateInstance 绕开构造器、反射
//     写入 masterKey 字段（与 FinanceViewModelV2Test 同款反射技巧）；
//   - native libsodium 加载沿用 FinancePullDecryptTest 的 desktop sodium 模式（提取
//     lazysodium-java jar 内的 windows64/libsodium.dll 并预加载 mingw 运行时）；加载
//     失败时加密 / 解密路径走 Assume 跳过，不伪报失败；前置校验用例（size / sha256）
//     不依赖 native，必须无条件跑通。
//
// 零知识纪律（spec NFR-1）：
//   - 测试用 sha256 用业界公开示例（"aaaa..."），不附真实附件 hash；
//   - 断言 message 不打印明文字节 / 不打印 attachment id 之外的元数据；
//   - attachmentDao 桩记录的 attachments 列表只在测试结束时 GC。
// ============================================================================

package com.everything.eve.data.finance

import com.everything.eve.auth.AuthManager
import com.everything.eve.crypto.CryptoEnvelope
import com.everything.eve.data.RecordDao
import com.everything.eve.data.RecordEntity
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.finance.dao.AttachmentDao
import com.everything.eve.data.finance.entity.AttachmentEntity
import com.everything.eve.finance.AttachmentRef
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.jar.JarFile

/**
 * AttachmentRepository JUnit 4 单元测试（≥6 用例）。
 *
 * 用例编号与说明一一对应任务书要求；当前实现 8 个 @Test，前 5 个不依赖 native libsodium
 * （前置校验路径），后 3 个用 Assume.assumeTrue 守护：无 native 时跳过。
 */
class AttachmentRepositoryTest {

    // ============================================================================
    // desktop sodium 加载（与 FinancePullDecryptTest 同款模式）
    // ============================================================================

    companion object {
        /** mingw 运行时 bin 目录候选列表。 */
        private val MINGW_BIN_CANDIDATES = listOf(
            """D:\Program Files\Git\mingw64\bin""",
            """C:\Program Files\Git\mingw64\bin""",
            """C:\Program Files (x86)\Git\mingw64\bin""",
            """C:\msys64\mingw64\bin""",
            """D:\msys64\mingw64\bin""",
        )

        /** native libsodium 是否加载成功；true=可走加密 / 解密链路。 */
        private var sodiumLoaded: Boolean = false

        /** 用于 happy path 测试的固定 MK（32B；与 lazysodium KEY_LEN 一致）。 */
        private val TEST_MK: ByteArray = ByteArray(CryptoEnvelope.KEY_LEN) { 0x11.toByte() }

        @BeforeClass
        @JvmStatic
        fun loadDesktopSodium() {
            val jarFile = runCatching { locateLazysodiumJavaJar() }.getOrNull() ?: return
            val dir = runCatching { Files.createTempDirectory("eve-attachment-sodium").toFile() }.getOrNull() ?: return
            val extracted = runCatching {
                JarFile(jarFile).use { jar ->
                    val entry = jar.getEntry("windows64/libsodium.dll") ?: return@use null
                    jar.getInputStream(entry).use { input ->
                        File(dir, "sodium.dll").outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                }
            }.getOrDefault(false)
            if (extracted != true) return

            val mingwBin = locateMingwBin() ?: return
            for (dep in listOf("libwinpthread-1.dll", "libgcc_s_seh-1.dll")) {
                val depFile = File(mingwBin, dep)
                if (!depFile.isFile) return
                try { System.load(depFile.absolutePath) } catch (_: Throwable) { return }
            }
            System.setProperty("jna.library.path", dir.absolutePath)
            sodiumLoaded = runCatching {
                Class.forName("com.goterl.lazysodium.SodiumJava")
                true
            }.getOrDefault(false)
        }

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

        private fun locateMingwBin(): File? {
            val hasRuntime = { dir: File -> File(dir, "libgcc_s_seh-1.dll").isFile }
            MINGW_BIN_CANDIDATES.map(::File).firstOrNull(hasRuntime)?.let { return it }
            return System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .map { File(it, ".." + File.separator + "mingw64" + File.separator + "bin").canonicalFile }
                .firstOrNull(hasRuntime)
        }
    }

    // ============================================================================
    // Stub 工具：AttachmentDao / RecordsRepository / AuthManager
    // ============================================================================

    /**
     * AttachmentDao Proxy 桩：所有方法默认返回 null / Unit / emptyList()，observe* 返回
     * flowOf(emptyList())；并把调用参数记到 spy 列表供断言用。
     */
    private class AttachmentDaoSpy {
        val upsertCalls = mutableListOf<AttachmentEntity>()
        val markDeletedCalls = mutableListOf<Pair<String, Long>>()
        val getByIdCalls = mutableListOf<String>()
        val markDirtyCalls = mutableListOf<Pair<String, Int>>()

        fun toProxy(): AttachmentDao = Proxy.newProxyInstance(
            AttachmentDao::class.java.classLoader,
            arrayOf(AttachmentDao::class.java),
            { _, method, args ->
                when (method.name) {
                    "upsert" -> {
                        val entity = args?.get(0) as? AttachmentEntity
                        if (entity != null) upsertCalls.add(entity)
                        Unit
                    }
                    "upsertAll" -> {
                        val list = args?.get(0) as? List<*>
                        if (list != null) for (e in list) {
                            (e as? AttachmentEntity)?.let { upsertCalls.add(it) }
                        }
                        Unit
                    }
                    "getById" -> {
                        val id = args?.get(0) as? String
                        if (id != null) getByIdCalls.add(id)
                        // 返回最后一次 upsert 的 entity（模拟 Room 缓存语义，幂等）
                        upsertCalls.lastOrNull()
                    }
                    "observeAll" -> flowOf(emptyList<AttachmentEntity>())
                    "observeByRecordId" -> flowOf(emptyList<AttachmentEntity>())
                    "dirtyList" -> upsertCalls.filter { it.dirty == 1 && it.deleted == 0 }
                    "getUpdatedAfter" -> emptyList<AttachmentEntity>()
                    "markDirty" -> {
                        val id = args?.get(0) as? String
                        val dirty = args?.get(1) as? Int
                        if (id != null && dirty != null) markDirtyCalls.add(id to dirty)
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
     * RecordDao 空 Proxy 桩：所有方法返回 null / Unit / flowOf(emptyList())。
     * 桩场景下 records 通道走我们 override 的 [RecordsRepositorySpy.upsertFinanceAttachment]
     * / [RecordsRepositorySpy.decryptFinanceAttachment]，根本不调 dao，所以这里全部返回 null。
     */
    private fun emptyRecordDao(): RecordDao = Proxy.newProxyInstance(
        RecordDao::class.java.classLoader,
        arrayOf(RecordDao::class.java),
        { _, method, args ->
            when {
                method.returnType == java.lang.Void.TYPE -> Unit
                Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                else -> null
            }
        },
    ) as RecordDao

    /**
     * 用 Unsafe.allocateInstance 创建 AuthManager 实例（绕开构造器对
     * EncryptedSharedPreferences 的依赖），反射写入 masterKey 字段。
     *
     * 注：records spy 类的 companion object 已有 [RecordsRepositorySpy.Companion.makeStubAuth]
     * ——本函数留作 newRepo 装配用。
     */
    private fun stubAuthManager(mk: ByteArray?): AuthManager {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocateMethod = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val auth = allocateMethod.invoke(unsafe, AuthManager::class.java) as AuthManager
        // masterKey 是 public var，直接反射 set 即可。
        val mkField: Field = AuthManager::class.java.getDeclaredField("masterKey")
        mkField.isAccessible = true
        mkField.set(auth, mk)
        return auth
    }

    /**
     * RecordsRepository 桩：upsertFinanceAttachment / decryptFinanceAttachment 调用
     * 参数记到 spy 列表。**不**实际调加密原语（避免 native 依赖在桩内失败）。
     *
     * 实现：直接 extends [com.everything.eve.data.RecordsRepository] 并 override
     * 这两个方法。父类已被测试基础设施兼容地标为 `open class`。
     */
    private class RecordsRepositorySpy : com.everything.eve.data.RecordsRepository(
        dao = makeEmptyRecordDao(),
        auth = makeStubAuth(TEST_MK),
    ) {
        val upsertAttachmentCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun upsertFinanceAttachment(
            attachmentId: String,
            plaintextJson: String,
            recordId: String,
        ): String {
            upsertAttachmentCalls.add(Triple(attachmentId, plaintextJson, recordId))
            return attachmentId
        }

        override fun decryptFinanceAttachment(entity: RecordEntity): String {
            // 桩场景下不应被调用；返回占位 JSON 防 caller 抛 NPE。
            return "{}"
        }

        fun toStub(): RecordsRepository = this

        companion object {
            /** 桩用空 RecordDao（Proxy stub）。 */
            private fun makeEmptyRecordDao(): RecordDao = Proxy.newProxyInstance(
                RecordDao::class.java.classLoader,
                arrayOf(RecordDao::class.java),
                { _, method, _ ->
                    when {
                        method.returnType == java.lang.Void.TYPE -> Unit
                        Flow::class.java.isAssignableFrom(method.returnType) -> flowOf(emptyList<Any>())
                        java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
                        else -> null
                    }
                },
            ) as RecordDao

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

    /**
     * 构造一个 AttachmentRepository 实例（用桩 dao + spy recordsRepository + stub auth）。
     */
    private fun newRepo(
        daoSpy: AttachmentDaoSpy,
        recSpy: RecordsRepositorySpy,
        mk: ByteArray? = TEST_MK,
    ): AttachmentRepository = AttachmentRepository(
        attachmentDao = daoSpy.toProxy(),
        recordsRepository = recSpy.toStub(),
        auth = stubAuthManager(mk),
    )

    /**
     * 计算 64 字符 lowercase SHA-256 hex 字符串（与 [AttachmentRepository.upload] 入参契约一致）。
     */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).toLowerHex()
    }

    private fun ByteArray.toLowerHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    // ============================================================================
    // 1) upload happy path：依赖 native libsodium（无 native 时跳过）
    // ============================================================================

    @Test
    fun upload_validBytes_returnsAttachmentRef() {
        Assume.assumeTrue(
            "JVM stub 环境无 native libsodium，跳过 upload happy path",
            sodiumLoaded,
        )

        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val content = ByteArray(100) { it.toByte() }
        val sha = sha256Hex(content)

        val result = runBlocking {
            repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = sha,
            )
        }

        assertTrue("upload happy path 应返回 success，但得到 $result", result.isSuccess)
        val ref = result.getOrNull()
        assertNotNull("AttachmentRef 不应为 null", ref)
        assertEquals("mime 必须保留", "application/pdf", ref?.mime)
        assertEquals("size 必须 = 100", 100L, ref?.size)
        assertEquals("sha256 必须字节级一致", sha, ref?.sha256)
        // 上传后 attachmentDao.upsert 必须被调一次
        assertEquals("attachmentDao.upsert 应被调一次", 1, daoSpy.upsertCalls.size)
        val entity = daoSpy.upsertCalls.first()
        assertEquals("AttachmentEntity.dirty 必须 = 1（待同步推送）", 1, entity.dirty)
        assertEquals("AttachmentEntity.deleted 必须 = 0", 0, entity.deleted)
        assertEquals("AttachmentEntity.size 必须 = 100", 100L, entity.size)
        // records 通道也应被调
        assertEquals("recordsRepository.upsertFinanceAttachment 应被调一次", 1, recSpy.upsertAttachmentCalls.size)
    }

    // ============================================================================
    // 2) upload size 超 50MB 上限 → Result.failure（前置校验，不依赖 native）
    // ============================================================================

    @Test
    fun upload_sizeExceeds50MB_returnsFailure() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        // masterKey 不需要（前置校验先于加密），传 null 即可。
        val repo = newRepo(daoSpy, recSpy, mk = null)

        // 51MB 上限
        val content = ByteArray((50L * 1024L * 1024L + 1024L).toInt())
        val sha = sha256Hex(content)

        val result = runBlocking {
            repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = sha,
            )
        }

        assertTrue("超 50MB 应返回 failure，但得到 $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull("Result.failure 必须携带异常", ex)
        assertTrue(
            "异常应为 IllegalArgumentException，实际 ${ex!!::class.java.simpleName}",
            ex is IllegalArgumentException,
        )
        // 前置校验失败时，attachmentDao 与 recordsRepository 都不应被调
        assertEquals("前置校验失败：dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
        assertEquals("前置校验失败：records.upsertFinanceAttachment 不应被调", 0, recSpy.upsertAttachmentCalls.size)
    }

    // ============================================================================
    // 3) upload size=0 → Result.failure（前置校验，不依赖 native）
    // ============================================================================

    @Test
    fun upload_sizeZero_returnsFailure() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy, mk = null)

        val content = ByteArray(0)
        val sha = sha256Hex(content)

        val result = runBlocking {
            repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = sha,
            )
        }

        assertTrue("size=0 应返回 failure，但得到 $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "异常应为 IllegalArgumentException，实际 ${ex?.let { it::class.java.simpleName }}",
            ex is IllegalArgumentException,
        )
        assertEquals("前置校验失败：dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
    }

    // ============================================================================
    // 4) upload sha256 长度非法（≠64）→ Result.failure（前置校验，不依赖 native）
    // ============================================================================

    @Test
    fun upload_invalidSha256HexLength_returnsFailure() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy, mk = null)

        val content = ByteArray(100) { it.toByte() }
        // 长度故意 32（应是 64）
        val badSha = "a".repeat(32)

        val result = runBlocking {
            repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = badSha,
            )
        }

        assertTrue("sha256 长度非法应返回 failure，但得到 $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("异常应为 IllegalArgumentException", ex is IllegalArgumentException)
        assertEquals("前置校验失败：dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
    }

    // ============================================================================
    // 5) upload sha256 含非 hex 字符（'g'）→ Result.failure（前置校验，不依赖 native）
    // ============================================================================

    @Test
    fun upload_sha256NotHex_returnsFailure() {
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy, mk = null)

        val content = ByteArray(100) { it.toByte() }
        // 64 字符，但含 'g'（非 hex）
        val badSha = "a".repeat(63) + "g"

        val result = runBlocking {
            repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = badSha,
            )
        }

        assertTrue("sha256 含非 hex 字符应返回 failure，但得到 $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue("异常应为 IllegalArgumentException", ex is IllegalArgumentException)
        assertEquals("前置校验失败：dao.upsert 不应被调", 0, daoSpy.upsertCalls.size)
    }

    // ============================================================================
    // 6) download 命中附件 → 解密 → 字节级一致（依赖 native libsodium）
    // ============================================================================

    @Test
    fun download_existingId_returnsOriginalBytes() {
        Assume.assumeTrue(
            "JVM stub 环境无 native libsodium，跳过 download happy path",
            sodiumLoaded,
        )

        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        // 上传一条附件（happy path 写入 daoSpy.upsertCalls）
        val content = ByteArray(123) { (it * 7).toByte() }
        val sha = sha256Hex(content)
        runBlocking {
            val upRes = repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "image/png",
                sha256Hex = sha,
            )
            assertTrue("upload happy path 应成功", upRes.isSuccess)

            // 拿到 daoSpy 中已存的 entity（含合法 envelope 密文）
            val entity = daoSpy.upsertCalls.first()
            // 下载：AttachmentDaoSpy.getById 返回最后一次 upsert 的 entity（模拟 Room 缓存）
            val dlRes = repo.download(entity.id)
            assertTrue("download 命中应成功，但得到 $dlRes", dlRes.isSuccess)
            val bytes = dlRes.getOrNull()
            assertNotNull("download 字节不应为 null", bytes)
            assertArrayEquals("下载字节必须与上传字节完全一致", content, bytes)
        }
    }

    // ============================================================================
    // 7) download 篡改 sha256 后 → Result.failure（依赖 native libsodium）
    // ============================================================================

    @Test
    fun download_sha256Mismatch_returnsFailure() {
        Assume.assumeTrue(
            "JVM stub 环境无 native libsodium，跳过 sha256 mismatch 用例",
            sodiumLoaded,
        )

        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val content = ByteArray(50) { it.toByte() }
        val realSha = sha256Hex(content)
        runBlocking {
            val upRes = repo.upload(
                recordId = "policy-1",
                content = content,
                mime = "application/pdf",
                sha256Hex = realSha,
            )
            assertTrue("upload 应成功", upRes.isSuccess)
        }

        // 篡改 AttachmentEntity.sha256（模拟本地缓存被调包 / 数据腐坏）
        val originalEntity = daoSpy.upsertCalls.first()
        val tamperedEntity = originalEntity.copy(sha256 = "b".repeat(64))
        // 把最后一次 upsert 的 entity 替换为篡改版（AttachmentDaoSpy.getById 返回最后一次）
        daoSpy.upsertCalls[daoSpy.upsertCalls.size - 1] = tamperedEntity

        runBlocking {
            val dlRes = repo.download(originalEntity.id)
            assertTrue("sha256 不匹配应返回 failure，但得到 $dlRes", dlRes.isFailure)
            val ex = dlRes.exceptionOrNull()
            // 失败类型：SecurityException（sha256 mismatch 守护）
            assertTrue(
                "异常应为 SecurityException，实际 ${ex?.let { it::class.java.simpleName }}",
                ex is SecurityException,
            )
        }
    }

    // ============================================================================
    // 8) delete → markDeleted 被调 + records tombstone 写入（部分依赖 native）
    // ============================================================================

    @Test
    fun delete_existingId_marksDeletedAndDirty() {
        // delete 路径调 recordsRepository.upsertFinanceAttachment（要 crypto），但桩里
        // 不实际调加密原语——所以本测试不依赖 native libsodium（桩总是成功）。
        val daoSpy = AttachmentDaoSpy()
        val recSpy = RecordsRepositorySpy()
        val repo = newRepo(daoSpy, recSpy)

        val id = "att-tombstone-1"
        runBlocking {
            val result = repo.delete(id)

            assertTrue("delete 应成功，但得到 $result", result.isSuccess)
        }
        // attachmentDao.markDeleted 应被调一次，参数 = (id, now)
        assertEquals("attachmentDao.markDeleted 应被调一次", 1, daoSpy.markDeletedCalls.size)
        val (calledId, calledNow) = daoSpy.markDeletedCalls.first()
        assertEquals("markDeleted 第一个参数 id 应 = '$id'", id, calledId)
        assertTrue("markDeleted 第二个参数 now 应 > 0（System.currentTimeMillis）", calledNow > 0L)
        // records 通道 tombstone 应被推一次
        assertEquals(
            "recordsRepository.upsertFinanceAttachment 应被调一次（推墓碑）",
            1, recSpy.upsertAttachmentCalls.size,
        )
        val (recId, plain, _) = recSpy.upsertAttachmentCalls.first()
        assertEquals("墓碑 records id 应 = '$id'", id, recId)
        assertTrue(
            "墓碑明文 JSON 应包含 deleted=true；实际 '$plain'",
            plain.contains("\"deleted\":true"),
        )
        assertTrue(
            "墓碑明文 JSON 应包含 id='$id'；实际 '$plain'",
            plain.contains("\"id\":\"$id\""),
        )
    }
}