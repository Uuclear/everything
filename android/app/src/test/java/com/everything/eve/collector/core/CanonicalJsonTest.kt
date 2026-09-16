package com.everything.eve.collector.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 1 TR-1.1：规范化序列化与变化检测（spec FR-5）。
 */
class CanonicalJsonTest {

    private val source = CollectorSource(systemId = 1L, lookupKey = "lk-1", lastUpdated = 100L)

    @Test
    fun sameContent_keyOrderAndWhitespaceIrrelevant() {
        val a = """{"a":"1","b":"2"}"""
        val b = """{ "b" : "2", "a" : "1" }"""
        assertTrue(CanonicalJson.sameContent(a, b))
    }

    @Test
    fun sameContent_anyFieldChangeIsDetected() {
        val base = """{"number":"13800000000","type":"mobile"}"""
        // 号码改一位、类型不同都必须判变（漏变会导致系统更新不同步）。
        assertFalse(CanonicalJson.sameContent(base, """{"number":"13800000001","type":"mobile"}"""))
        assertFalse(CanonicalJson.sameContent(base, """{"number":"13800000000","type":"home"}"""))
        // 多/少一个键同样判变。
        assertFalse(CanonicalJson.sameContent(base, """{"number":"13800000000"}"""))
    }

    @Test
    fun sameContent_listOrderIsSignificant_numbersNumeric() {
        // 电话列表顺序变化在本期视为内容变化（保守策略，宁可重封）。
        assertFalse(
            CanonicalJson.sameContent(
                """{"phones":["a","b"]}""",
                """{"phones":["b","a"]}""",
            ),
        )
        // 数字按数值比较：100 与 100.0 语义相同。
        assertTrue(
            CanonicalJson.sameContent(
                """{"x":100}""",
                """{"x":100.0}""",
            ),
        )
    }

    @Test
    fun sameContent_brokenJsonFallsBackToUnequal() {
        // 解析失败宁可判变重新密封，不可把可能变化的数据误判为未变。
        assertFalse(CanonicalJson.sameContent("""{"a":1}""", "{not json"))
        assertFalse(CanonicalJson.sameContent("{oops", """{"a":1}"""))
    }

    @Test
    fun dtoSerialization_usesSnakeCaseAndRoundTrips() {
        val sms = SmsData(
            address = "10086",
            body = "余额提醒",
            date = 1_700_000_000_000L,
            type = "inbox",
            read = true,
            threadId = 9L,
            source = CollectorSource(systemId = 55L),
        )
        val json = String(CanonicalJson.toJsonBytes(sms), Charsets.UTF_8)
        // 关键 snake_case 键存在（与 docs/module-schemas.md 一致）。
        assertTrue(json.contains(""""address":"10086""""))
        assertTrue(json.contains(""""thread_id":9"""))
        assertTrue(json.contains(""""system_id":55"""))
        // 回树结构相等（自往返）。
        val again = String(CanonicalJson.toJsonBytes(sms), Charsets.UTF_8)
        assertTrue(CanonicalJson.sameContent(json, again))
    }

    @Test
    fun contactDto_serializesNestedCollections() {
        val contact = ContactData(
            displayName = "张三",
            name = ContactName(family = "张", given = "三"),
            organization = "一切公司",
            jobTitle = "工程师",
            phones = listOf(
                ContactPhone(number = "13800000000", type = "mobile", isPrimary = true),
                ContactPhone(number = "010-1234", type = "work"),
            ),
            emails = listOf(ContactEmail(email = "a@b.c", type = "home")),
            addresses = emptyList(),
            birthday = "1990-01-02",
            notes = null,
            source = source,
        )
        val json = String(CanonicalJson.toJsonBytes(contact), Charsets.UTF_8)
        assertTrue(json.contains(""""display_name":"张三""""))
        assertTrue(json.contains(""""is_primary":true"""))
        assertTrue(json.contains(""""lookup_key":"lk-1""""))
        // 嵌套两个电话：列表长度敏感，列表内容参与变化检测。
        val moved = contact.copy(
            phones = contact.phones.reversed(),
        )
        assertFalse(
            CanonicalJson.sameContent(
                json,
                String(CanonicalJson.toJsonBytes(moved), Charsets.UTF_8),
            ),
        )
        // 同内容重建：树相等，即使序列化实例不同。
        val rebuilt = contact.copy()
        assertEquals(
            true,
            CanonicalJson.sameContent(
                json,
                String(CanonicalJson.toJsonBytes(rebuilt), Charsets.UTF_8),
            ),
        )
    }
}
