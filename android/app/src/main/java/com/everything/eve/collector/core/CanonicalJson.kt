package com.everything.eve.collector.core

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * 明文规范化 JSON：密封前序列化与"变化检测"（spec FR-5）共用。
 *
 * - 序列化使用 moshi 反射（kotlin adapter），字段顺序由 DTO 声明顺序固定，
 *   同一内容字节稳定；
 * - 变化检测不做字符串直比（字段顺序、空白差异会误判），而是把新旧 JSON 都解析成
 *   Map/List 通用树做结构相等：键顺序无关、值敏感（号码/正文变一位即判变）。
 *
 * 纯 JVM 实现，无 Android 依赖。
 */
object CanonicalJson {

    /** 全局单例 moshi（反射适配器，与 ServiceLocator 网络层配置等价）。 */
    val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /** 任意采集 DTO 的规范化 JSON 字节（UTF-8），直接作为 sealRecord 明文。 */
    inline fun <reified T : Any> toJsonBytes(value: T): ByteArray {
        val adapter = moshi.adapter(T::class.java)
        return adapter.toJson(value).toByteArray(Charsets.UTF_8)
    }

    /**
     * 非 reified 变体：按运行时类型序列化（引擎遍历星投影 source 结果时使用）。
     * 与 [toJsonBytes] 输出一致（同一 moshi 实例与反射适配器）。
     */
    fun toJsonBytesOf(value: Any): ByteArray {
        @Suppress("UNCHECKED_CAST")
        val adapter = moshi.adapter(value.javaClass) as JsonAdapter<Any>
        return adapter.toJson(value).toByteArray(Charsets.UTF_8)
    }

    /** 把 JSON 文本解析为通用树（Map/List/String/Double/Boolean/null）。 */
    fun parseTree(json: String): Any? {
        val type = Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java,
        )
        val adapter: JsonAdapter<Map<String, Any?>> =
            moshi.adapter<Map<String, Any?>>(type).nullSafe()
        // 顶层恒为对象（三类 DTO 都是 object）；保留 null 传递语义。
        return adapter.fromJson(json)
    }

    /**
     * 结构相等判定：两段 JSON 表达相同数据即 true（键顺序无关）。
     * 解析失败视为"不相等"，让上游重新密封（宁可多封，不可漏变）。
     */
    fun sameContent(jsonA: String, jsonB: String): Boolean {
        if (jsonA == jsonB) return true
        return try {
            deepEquals(parseTree(jsonA), parseTree(jsonB))
        } catch (_: Exception) {
            false
        }
    }

    /** 通用树递归相等：Map 比键集合与各键值，List 顺序敏感，标量直比。 */
    private fun deepEquals(a: Any?, b: Any?): Boolean {
        return when {
            a == null || b == null -> a == null && b == null
            a is Map<*, *> && b is Map<*, *> -> {
                a.keys == b.keys && a.keys.all { deepEquals(a[it], b[it]) }
            }
            a is List<*> && b is List<*> -> {
                a.size == b.size && a.indices.all { deepEquals(a[it], b[it]) }
            }
            // moshi 把数字读为 Double；Kotlin Long/Int 进入树比较时统一按数值归一。
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            a is Boolean && b is Boolean -> a == b
            a is String && b is String -> a == b
            else -> a == b
        }
    }
}
