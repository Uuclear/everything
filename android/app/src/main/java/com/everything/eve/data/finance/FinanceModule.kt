package com.everything.eve.data.finance

/**
 * 财务模块常量（stage5-finance / TR-4.6 / TR-11.2）。
 *
 * 与 Web `web/src/finance/types.ts` 中 `FINANCE_MODULE = 'finance'` 字节级一致。
 * 与 Android 既有 `RecordsRepository.moduleFinance` 私有常量口径一致（本常量类
 * 对外暴露，便于 CollectorWorker 等模块无需反射私有字段即可引用）。
 *
 * 子类型（type 字段值）：
 *   - "account" —— 账户
 *   - "card"    —— 银行卡 / 信用卡
 *   - "tx"      —— 流水
 *   - v2 子类型（policy/subscription/loan/contract）本期不在编辑器 / 同步流程暴露。
 */
object FinanceModule {
    /** records module 字段值（AAD / 路由都用）。 */
    const val MODULE: String = "finance"

    /** 子类型：账户。 */
    const val TYPE_ACCOUNT: String = "account"

    /** 子类型：银行卡 / 信用卡。 */
    const val TYPE_CARD: String = "card"

    /** 子类型：流水（transaction）。 */
    const val TYPE_TX: String = "tx"
}