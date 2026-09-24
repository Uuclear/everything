package com.everything.eve.data.finance

/**
 * 财务模块常量（stage5-finance / TR-4.6 / TR-11.2）。
 *
 * 与 Web `web/src/finance/types.ts` 中 `FINANCE_MODULE = 'finance'` 字节级一致。
 * 与 Android 既有 `RecordsRepository.moduleFinance` 私有常量口径一致（本常量类
 * 对外暴露，便于 CollectorWorker 等模块无需反射私有字段即可引用）。
 *
 * 子类型（type 字段值）：
 *   - "account"      —— 账户
 *   - "card"         —— 银行卡 / 信用卡
 *   - "tx"           —— 流水
 *   - v2 子类型（stage5-finance-v2 B4 起持久化闭环 + 提醒链路启用）：
 *     - "subscription" —— 订阅
 *     - "policy"       —— 保单
 *     - "loan"         —— 应收借款
 *     - "contract"     —— 合同 / 发票
 *     - "rate"         —— 离线汇率包（B5 多币种折算；整包一条 records 行）
 *     - "budget"       —— 预算（B6 预算硬约束；预计累计达阈值端侧实时预警 / 阻断确认）
 *     - "quote"        —— 投资账户手动行情包（Task 8；整包一条 records 行）
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

    /** 子类型：附件（policy / contract 等 v2 父记录下挂的本地密文 envelope）。 */
    const val TYPE_ATTACHMENT: String = "attachment"

    // ============================================================================
    // v2 子类型（stage5-finance-v2 / B4：records 持久化闭环 + ReminderScheduler
    // 三类提醒扩展）；与 web/src/finance/types.ts FINANCE_TYPES 字节级一致。
    // ============================================================================

    /** 子类型：订阅（v2）。 */
    const val TYPE_SUBSCRIPTION: String = "subscription"

    /** 子类型：保单（v2）。 */
    const val TYPE_POLICY: String = "policy"

    /** 子类型：应收借款（v2）。 */
    const val TYPE_LOAN: String = "loan"

    /** 子类型：合同 / 发票（v2）。 */
    const val TYPE_CONTRACT: String = "contract"

    // ============================================================================
    // B5 多币种折算（stage5-finance-v2 / FR-V2-C.2）
    // ============================================================================

    /**
     * 子类型：离线汇率包（B5）。
     *
     * 一个生效时刻整包一条 records 行（id = "rate@${effective_ts}"），
     * 明文载荷契约见 [com.everything.eve.finance.RateTables.parse]；
     * 本地 finance_rate 表按货币对拆行冗余缓存，密文以 records 通道为准。
     */
    const val TYPE_RATE: String = "rate"

    // ============================================================================
    // B6 预算硬约束（stage5-finance-v2 / FR-V2-F）
    // ============================================================================

    /**
     * 子类型：预算（B6）。
     *
     * 预算走 records 密文通道（module=finance）保存；保存支出流水时由端侧
     * 纯函数 BudgetEnforcer 实时判定预计累计占比，达预警阈值保存后提示、
     * 达阻断阈值保存前弹确认框；全程端侧判定, 不接 Reminders 通道。
     */
    const val TYPE_BUDGET: String = "budget"

    // ============================================================================
    // Task 8 投资账户手动行情（stage5-finance-v2 / FR-V2-D.2）
    // ============================================================================

    /**
     * 子类型：投资账户手动行情包（Task 8）。
     *
     * 一个生效时刻整包一条 records 行（id = "quote@${ts}"），
     * 明文载荷契约见 [com.everything.eve.finance.QuoteTables.parse]；
     * 本地 finance_quote 表按 symbol 拆行冗余缓存，密文以 records 通道为准。
     */
    const val TYPE_QUOTE: String = "quote"
}