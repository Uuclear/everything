// ============================================================================
// 物品模块 —— 跨端共享 fixture 加载（stage5-items / T3 / TR-3.3）
// ============================================================================

package com.everything.eve.items

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal object ItemsFixture {

    /** 与 web/src/items/__fixtures__/cases.json 字节级一致（TR-3.3） */
    const val FIXTURE_SHA256: String =
        "5c76b4595730078b20d024088bbe5d99304726d91a0bc01826de83620faa603c"

    private const val CLASSPATH_PATH = "items/__fixtures__/cases.json"

    val bytes: ByteArray by lazy { loadFixtureBytes() }
    val root: JSONObject by lazy { JSONObject(String(bytes, Charsets.UTF_8)) }
    val warrantyCases: JSONArray by lazy { root.getJSONArray("warrantyCases") }
    val qrCases: JSONArray by lazy { root.getJSONArray("qrCases") }

    fun loadFixtureBytes(): ByteArray {
        val cp = ItemsFixture::class.java.classLoader?.getResource(CLASSPATH_PATH)
        if (cp != null) {
            return cp.openStream().use { it.readBytes() }
        }
        val candidates = listOf(
            "src/test/resources/items/__fixtures__/cases.json",
            "android/app/src/test/resources/items/__fixtures__/cases.json",
            "app/src/test/resources/items/__fixtures__/cases.json",
            "d:/github/cursor/everything/android/app/src/test/resources/items/__fixtures__/cases.json",
            "D:/github/cursor/everything/android/app/src/test/resources/items/__fixtures__/cases.json",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException(
                "fixture 资源缺失: 尝试路径 = [$CLASSPATH_PATH classpath, ${candidates.joinToString()}]",
            )
        return file.readBytes()
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun runWarrantyCase(case: JSONObject): Any? {
        return when (case.getString("fn")) {
            "warrantyUntilTs" -> {
                val input = case.getJSONObject("input")
                warrantyUntilTs(
                    input.getLong("purchaseDateTs"),
                    input.getInt("durationDays"),
                )
            }
            "nextItemTrigger" -> {
                val input = case.getJSONObject("input")
                val itemJson = input.getJSONObject("item")
                val item = ItemTriggerLike(
                    warranty_duration_days = itemJson.getInt("warranty_duration_days"),
                    warranty_until_ts = itemJson.getLong("warranty_until_ts"),
                )
                val now = input.getLong("now")
                nextItemTrigger(item, now)
            }
            "normalizeTags" -> {
                val input = case.getJSONObject("input")
                val tags = input.getJSONArray("rawTags").toStringList()
                normalizeTags(tags)
            }
            "isValidReceiptUrl" -> {
                val input = case.getJSONObject("input")
                isValidReceiptUrl(input.getString("url"))
            }
            else -> throw IllegalArgumentException("unknown fn: ${case.getString("fn")}")
        }
    }

    fun expectedWarranty(case: JSONObject): Any? = parseExpected(case.get("expected"))

    fun caseByName(array: JSONArray, name: String): JSONObject {
        for (i in 0 until array.length()) {
            val c = array.getJSONObject(i)
            if (c.getString("name") == name) return c
        }
        throw IllegalArgumentException("fixture case not found: $name")
    }

    private fun parseExpected(raw: Any?): Any? {
        if (raw == null || raw == JSONObject.NULL) return null
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toLong()
            is JSONArray -> raw.toStringList()
            else -> raw
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            out.add(getString(i))
        }
        return out
    }
}
