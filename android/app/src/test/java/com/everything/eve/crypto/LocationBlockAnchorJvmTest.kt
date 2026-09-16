package com.everything.eve.crypto

import com.everything.eve.collector.location.core.LocationBlockJson
import com.everything.eve.collector.location.core.TrackPoint
import com.goterl.lazysodium.SodiumJava
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.HexFormat
import java.util.jar.JarFile

/**
 * 跨端一致性锚点（阶段 4a Task 5 附加交付，纯 JVM 单测）。
 *
 * 以固定 MK / 固定 blockId / 固定明文，经线上真实路径
 * [CryptoEnvelope.sealLocationBlock] 生成一份样例密文，供 Web 端
 * （crypto/envelope.ts 的 openLocationBlock 对端实现）与 docs/crypto.md
 * 做逐字节对拍：
 *  - 原语：XChaCha20-Poly1305-IETF（Native 9 参 API，nsec=null）；
 *  - AAD："eve:v1:location-block:" + blockId（sealLocationBlock 内部拼接）；
 *  - 布局：nonce(24) || ciphertext（含 16B Poly1305 tag）。
 *
 * 注意 nonce 由 aeadSeal 内部随机生成（与线上完全一致），故密文每次运行不同，
 * 锚点语义 = "同 MK + 同 AAD 可解密回同一明文"，而非密文逐字节固定。
 *
 * JVM 可用性探针结论（本机实测）：
 *  - lazysodium-android 的 `SodiumAndroid()` = JNA
 *    `Native.register(Sodium::class.java, "sodium")`（Windows 解析为 sodium.dll，
 *    搜索 jna.library.path），构造期不做任何 Android 特有调用；
 *  - lazysodium-java jar 自带桌面原生库，但其无参构造经 resource-loader 2.0.1
 *    提取时踩 Windows zipfs bug（FileSystemNotFoundException），故改走
 *    手动提取 windows64/libsodium.dll → 重命名 sodium.dll → jna.library.path；
 *  - 该 dll 为 mingw 编译，硬依赖 libgcc_s_seh-1.dll（传递依赖
 *    libwinpthread-1.dll）；LoadLibrary 依赖搜索不含被加载 dll 所在目录，
 *    故先按绝对路径 System.load 预加载 mingw 运行时（Windows 依赖解析优先
 *    命中进程内已加载同名模块），运行时取自 Git for Windows 自带的 mingw64\bin；
 *  - CryptoEnvelope 仅 import android.util.Base64（类加载不触发），
 *    seal/openLocationBlock 返回 ByteArray 不触碰 Base64，JVM 下原样可跑。
 */
class LocationBlockAnchorJvmTest {

    companion object {
        /** 固定主密钥：hex "000102...1e1f"（32 字节，三端锚点共用）。 */
        private val FIXED_MK: ByteArray = ByteArray(32) { it.toByte() }

        /** 固定块 id（派生规则："{deviceId}:{startTs}:{endTs}"，UTC 毫秒）。 */
        private const val FIXED_BLOCK_ID = "dev-fixed-1:1700000000000:1700000060000"

        /** mingw 运行时（libwinpthread-1.dll / libgcc_s_seh-1.dll）的常见来源目录。 */
        private val MINGW_BIN_CANDIDATES = listOf(
            // Git for Windows 自带完整 mingw 运行时（开发机几乎必有）
            """D:\Program Files\Git\mingw64\bin""",
            """C:\Program Files\Git\mingw64\bin""",
            """C:\Program Files (x86)\Git\mingw64\bin""",
            """C:\msys64\mingw64\bin""",
            """D:\msys64\mingw64\bin""",
        )

        /**
         * 让 CryptoEnvelope 在纯 JVM 可用：
         *  1. 从 lazysodium-java jar 提取 windows64/libsodium.dll → 临时目录 sodium.dll；
         *  2. 定位 mingw 运行时并按依赖序 System.load 预加载（winpthread → libgcc）；
         *  3. jna.library.path 指向临时目录，供 SodiumAndroid() 的 JNA
         *     Native.register("sodium") 加载 sodium.dll（其依赖已在进程内）。
         * 环境缺少 mingw 运行时或 jar 内桌面库时 Assume 跳过（不伪报失败）。
         */
        @BeforeClass
        @JvmStatic
        fun loadDesktopSodium() {
            // 1. 提取 libsodium 本体
            val jarFile = locateLazysodiumJavaJar()
            val dir = Files.createTempDirectory("eve-anchor-sodium").toFile()
            JarFile(jarFile).use { jar ->
                val entry = jar.getEntry("windows64/libsodium.dll")
                Assume.assumeTrue(
                    "lazysodium-java jar 内无 windows64/libsodium.dll，跳过锚点测试",
                    entry != null,
                )
                jar.getInputStream(entry).use { input ->
                    File(dir, "sodium.dll").outputStream().use { output -> input.copyTo(output) }
                }
            }

            // 2. 定位并预加载 mingw 运行时（依赖顺序：被依赖者先加载）
            val mingwBin = locateMingwBin()
            Assume.assumeTrue(
                "未找到 mingw 运行时（libgcc_s_seh-1.dll），跳过锚点测试",
                mingwBin != null,
            )
            for (dep in listOf("libwinpthread-1.dll", "libgcc_s_seh-1.dll")) {
                val depFile = File(mingwBin, dep)
                Assume.assumeTrue("缺少 $dep，跳过锚点测试", depFile.isFile)
                System.load(depFile.absolutePath)
            }

            // 3. 交给 JNA 按库名解析
            System.setProperty("jna.library.path", dir.absolutePath)
        }

        /** 定位测试类路径上的 lazysodium-java jar（codeSource 优先，java.class.path 兜底）。 */
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
            // PATH 兜底：git.exe 所在 cmd 目录的同级 mingw64\bin（Git for Windows 布局）
            return System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .map { path -> File(path, ".." + File.separator + "mingw64" + File.separator + "bin").canonicalFile }
                .firstOrNull(hasRuntime)
        }
    }

    /**
     * 固定向量密封 + 可逆 + AAD 绑定：
     *  - 明文：moshi 实际序列化字节（snake_case，Android/docs/Web 三方契约）；
     *  - 断言 1：密文长度 = nonce(24) + 明文 + tag(16)；
     *  - 断言 2：同 MK 同 blockId 解密回同一明文（可逆，锚点核心）；
     *  - 断言 3：换块 id（AAD 错配）解密必须失败（密文与块 id 绑定）。
     * 样例密文 hex 与明文 JSON 打印进 test report system-out，供汇报带回。
     */
    @Test
    fun anchor_fixedVector_sealRoundtrip() {
        // 固定明文：device_id=dev-fixed-1，两点（北京城区示例坐标）
        val block = LocationBlockJson(
            deviceId = "dev-fixed-1",
            startTs = 1_700_000_000_000L,
            endTs = 1_700_000_060_000L,
            points = listOf(
                TrackPoint(ts = 1_700_000_000_000L, lat = 39.9042, lon = 116.4074, acc = 12.5f),
                TrackPoint(ts = 1_700_000_060_000L, lat = 39.9050, lon = 116.4080, acc = 10.0f),
            ),
        )
        // moshi 反射序列化（与 LocationPackager 线上路径同一适配器配置）
        val plainJson = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(LocationBlockJson::class.java)
            .toJson(block)
        val plainBytes = plainJson.toByteArray(Charsets.UTF_8)

        // 密封：线上真实路径（内部随机 24B nonce + XChaCha20-Poly1305-IETF）
        val sealed = CryptoEnvelope.sealLocationBlock(FIXED_MK, plainBytes, FIXED_BLOCK_ID)

        // 断言 1：密文布局 nonce(24) || cipher(plain + 16B tag)
        assertEquals(24 + plainBytes.size + 16, sealed.size)

        // 断言 2：可逆（同 MK 同 AAD）
        val opened = CryptoEnvelope.openLocationBlock(FIXED_MK, sealed, FIXED_BLOCK_ID)
        assertArrayEquals(plainBytes, opened)

        // 断言 3：AAD 绑定（块 id 任一字符变化即解密失败，aeadOpen check 抛 ISE）
        assertThrows(IllegalStateException::class.java) {
            CryptoEnvelope.openLocationBlock(FIXED_MK, sealed, "dev-fixed-1:1700000000000:1700000060001")
        }

        // 带回汇报：明文 JSON（moshi 实际输出字节）与样例密文 hex。
        // HexFormat 为 JDK17 API，绕开 android.util.Base64（JVM stub 会抛异常）。
        System.out.println("ANCHOR-JSON $plainJson")
        System.out.println("ANCHOR-HEX ${HexFormat.of().formatHex(sealed)}")
    }
}
