/*
 * 阶段 4b — Task 6 / TR-6.1 辅助工具：RRULE 与 JSON 字符串互转。
 *
 * 用途：编辑器（RRuleBuilder）生成 value 后序列化为 JSON 字符串写入
 * EventRule.rrule_json；亦用于反序列化解析现有规则回填到 RRuleBuilder。
 *
 * 与 EventsRepository.rrule_json 字段保持完全一致——唯一标定 freq/interval/
 * byweekday/end 四字段，不引入额外字段。
 */

package com.everything.eve.recurrence

import org.json.JSONArray
import org.json.JSONObject

/** RRULE B 档子集 ↔ JSON 字符串互转。 */
object RecurrenceJson {
    /**
     * 把 RRule 序列化为 B 档 JSON 字符串。
     *
     * 输出格式（与 Web `RRule` 严格一致，与 fixture `cases.json` 兼容）：
     * ```
     * {"freq":"WEEKLY","interval":1,"byweekday":["MO"],"end":{"kind":"never"}}
     * ```
     */
    fun encode(rule: RRule): String {
        val endObj = when (val e = rule.end) {
            RRuleEnd.Never -> JSONObject().put("kind", "never")
            is RRuleEnd.Date -> JSONObject().put("kind", "date").put("until", e.until)
            is RRuleEnd.Count -> JSONObject().put("kind", "count").put("count", e.count)
        }
        return JSONObject()
            .put("freq", rule.freq.name)
            .put("interval", rule.interval.coerceAtLeast(1))
            .put("byweekday", JSONArray(rule.byweekday.map { it.name }))
            .put("end", endObj)
            .toString()
    }

    /**
     * 把 B 档 JSON 字符串反序列化为 RRule。
     *
     * 失败回退 null（与 ReminderScheduler.entityToRule 同款"不抛异常"纪律，避免
     * 编辑器打开已损坏记录时崩溃）。
     */
    fun decode(json: String?): RRule? {
        if (json.isNullOrEmpty() || json == "null") return null
        return try {
            val o = JSONObject(json)
            val freqName = o.optString("freq")
            val freq = Frequency.entries.firstOrNull { it.name == freqName } ?: return null
            val interval = o.optInt("interval", 1).coerceAtLeast(1)
            val bdArr = o.optJSONArray("byweekday")
            val bd = if (bdArr != null) (0 until bdArr.length()).mapNotNull { i ->
                val s = bdArr.optString(i)
                Weekday.entries.firstOrNull { it.name == s }
            } else emptyList()
            val endObj = o.optJSONObject("end") ?: return null
            val kind = endObj.optString("kind")
            val end = when (kind) {
                "never" -> RRuleEnd.Never
                "date" -> RRuleEnd.Date(endObj.optString("until"))
                "count" -> RRuleEnd.Count(endObj.optInt("count"))
                else -> return null
            }
            RRule(freq, interval, bd, end)
        } catch (e: Exception) {
            null
        }
    }
}
