// 证件到期分级（与 Web stores/vault expiryDays / expiryLevel 对齐）
package com.everything.eve.vault

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class ExpiryLevel { EXPIRED, SOON, UPCOMING }

/** 本地日历天差：expiresOn 为 YYYY-MM-DD。 */
fun expiryDays(expiresOn: String, today: LocalDate = LocalDate.now()): Int? {
    if (expiresOn.isBlank()) return null
    val d = runCatching {
        LocalDate.parse(expiresOn, DateTimeFormatter.ISO_LOCAL_DATE)
    }.getOrNull() ?: return null
    return ChronoUnit.DAYS.between(today, d).toInt()
}

fun expiryLevel(expiresOn: String?, today: LocalDate = LocalDate.now()): ExpiryLevel? {
    val days = expiryDays(expiresOn ?: "", today) ?: return null
    if (days < 0) return ExpiryLevel.EXPIRED
    if (days <= 30) return ExpiryLevel.SOON
    if (days <= 90) return ExpiryLevel.UPCOMING
    return null
}
