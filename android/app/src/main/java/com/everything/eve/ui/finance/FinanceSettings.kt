// ============================================================================
// 财务模块本地设置（stage5-finance-v2 / B5 / FR-V2-C.3）
// ============================================================================
//
// 路径：android/app/src/main/java/com/everything/eve/ui/finance/FinanceSettings.kt
//
// 职责：承载财务模块的**非敏感**本地偏好（SharedPreferences）——
//   当前仅一个键：看板多币种折算的默认目标币种（default_currency，默认 CNY）。
//
// 零知识纪律（与 CollectorSettings 同风格）：
//   - 独立 prefs 文件 "eve-finance"，绝不写入账户名 / 金额 / 卡号等业务明文；
//   - 币种代码本身是 ISO 4217 公开枚举，不属于敏感数据；
//   - 汇率包内容不存这里（密文走 records 通道 + finance_rate 本地表）。
//
// 关联：
//   - android/.../collector/CollectorSettings.kt（同款 object + SharedPreferences 范本）
//   - android/.../finance/FinanceRecords.isValidCurrencyCode（3 大写字母粗校验复用）
// ============================================================================

package com.everything.eve.ui.finance

import android.content.Context
import com.everything.eve.finance.FinanceRecords

/**
 * 财务模块非敏感偏好（B5：默认折算目标币种）。
 */
object FinanceSettings {

    /** 财务模块独立 prefs 文件名（与 eve-collector 同风格隔离）。 */
    private const val PREFS = "eve-finance"

    /** 默认折算目标币种键。 */
    private const val KEY_DEFAULT_CURRENCY = "default_currency"

    /** 默认币种（未设置 / 键缺失时回退）。 */
    const val DEFAULT_CURRENCY: String = "CNY"

    /**
     * 预设币种列表（RatesImportScreen 的 FilterChip 候选）。
     *
     * 仅为快捷选择候选，不构成合法性白名单 —— 手填任意 3 大写字母代码均可保存。
     */
    val SUPPORTED_CURRENCIES: List<String> = listOf("CNY", "USD", "EUR", "JPY", "HKD")

    /** 取私有 prefs（MODE_PRIVATE；app 沙箱内）。 */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 读默认折算目标币种；未设置时返回 [DEFAULT_CURRENCY]。
     */
    fun getDefaultCurrency(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_CURRENCY, null)
            ?.takeIf { isValidCurrencyCode(it) }
            ?: DEFAULT_CURRENCY

    /**
     * 写默认折算目标币种。
     *
     * 调用方契约：[code] 必须先通过 [isValidCurrencyCode]（VM 层已做校验，
     * 此处再防御性兜底，非法值直接忽略不写）。
     */
    fun setDefaultCurrency(context: Context, code: String) {
        if (!isValidCurrencyCode(code)) return
        prefs(context).edit().putString(KEY_DEFAULT_CURRENCY, code).apply()
    }

    /**
     * ISO 4217 三字母代码粗校验（3 个大写字母）；直接复用
     * [FinanceRecords.isValidCurrencyCode]，与账户 / 卡 / 流水币种口径一致。
     */
    fun isValidCurrencyCode(code: String): Boolean =
        FinanceRecords.isValidCurrencyCode(code)
}
