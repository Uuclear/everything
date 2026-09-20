/*
 * ============================================================================
 * FinanceViewModel —— 财务模块 ViewModel（stage5-finance / Task 7 / TR-7.1~7.4）
 * ============================================================================
 *
 * 设计要点（与 4b CalendarViewModel 同款架构模式）：
 *   1. **AndroidViewModel + ServiceLocator 注入**：本任务未引入 Hilt
 *      （build.gradle.kts 未注入 Hilt 插件），沿用 4b `AndroidViewModel(app)`
 *      + `ServiceLocator.financeRepo` 模式，**不引入新依赖**。
 *   2. **derived state**（combine + stateIn）：UI 订阅 ViewModel.state，
 *      内部 combine 三个 Flow（accounts / cards / txs）→ FinanceAggregator
 *      实时计算 DashboardSnapshot + MonthlyReport + BudgetStatus。
 *   3. **UiEvent 通道**：snackbar 等一次性事件经 [eventFlow] 暴露，避免
 *      旋转屏重放（spec NFR-3 一次性事件契约）。
 *   4. **CRUD 入口**：upsert / delete 调 FinanceRepository + 触发
 *      ReminderScheduler.rebuildChain（spec FR-5 提醒链路一致）。
 *   5. **零知识（spec NFR-1）**：错误日志仅含 entity.id / kind，不含
 *      name / last4 / amount / 备注原文。
 *   6. **编辑模式**：[EditorBuffer] data class 承载编辑器内存态；save 时
 *      把 EditorBuffer → Entity 调 FinanceRepository.upsert。
 *
 * 字段流转：
 *   accounts/cards/txs Flow → combine → DashboardSnapshot + MonthlyReport
 *                             → FinanceUiState
 *                             → stateIn(viewModelScope, ...)
 *
 * 关联：
 *   - tasks.md TR-7.1 / TR-7.2 / TR-7.3 / TR-7.4
 *   - 4b CalendarViewModel.kt 同款 stateIn 模式
 *   - finance/FinanceAggregator.kt 净资产/月报聚合
 * ============================================================================
 */

package com.everything.eve.ui.finance

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.data.RecordDao
import com.everything.eve.data.finance.AttachmentRepository
import com.everything.eve.data.finance.FinanceModule
import com.everything.eve.data.finance.entity.AttachmentEntity
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity
import com.everything.eve.finance.AttachmentRef
import com.everything.eve.finance.BudgetCheckResult
import com.everything.eve.finance.BudgetEnforcer
import com.everything.eve.finance.BudgetLevel
import com.everything.eve.finance.BudgetRecord
import com.everything.eve.finance.BudgetTxLike
import com.everything.eve.finance.ContractRecord
import com.everything.eve.finance.FinanceAggregator
import com.everything.eve.finance.FinanceRecords
import com.everything.eve.finance.LoanRecord
import com.everything.eve.finance.PolicyRecord
import com.everything.eve.finance.SubscriptionRecord
import com.everything.eve.finance.ValidationResult
import com.everything.eve.finance.NextCardFiring
import com.everything.eve.finance.RateTable
import com.everything.eve.finance.V2PayloadCodec
import com.everything.eve.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 编辑器类型枚举（spec FR-1 / FR-3 / FR-4）。
 *
 * 与 [FinanceRoutes.EDITOR_ACCOUNT] / [EDITOR_CARD] / [EDITOR_TX] 三选一对应。
 */
enum class FinanceEditorKind { ACCOUNT, CARD, TX }

/**
 * 编辑器数据形态（编辑器内存态承载，save 时由 FinanceRepository.upsert
 * 转 Entity 入库）。
 *
 * 注意：编辑器只承载最小字段集；其它展示字段（如 createdAt / updatedAt /
 * dirty / deleted / schema_version / module / type）由 Repository 层
 * 维护。
 */
data class FinanceEditorBuffer(
    /** entity id；新建时由 vm.newId() 生成。 */
    val id: String,
    /** 编辑器类型。 */
    val kind: FinanceEditorKind,
    /** 名称（账户/卡片）/ 类别（流水公用同一字段；流水时仅展示）。 */
    val name: String = "",
    /** 货币（默认 CNY；spec FR-1）。 */
    val currency: String = "CNY",
    /** 账户 / 卡片 / 流水的 kind 枚举值。 */
    val itemKind: String = "",
    /** 余额 / 金额 / 信用额度（decimal-as-string）。 */
    val balance: String = "",
    /** 卡号原文（仅校验瞬间驻留内存，不入库）。 */
    val pan: String = "",
    /** 已通过 Luhn 校验后提取的 last4；卡片保存后即丢弃完整 PAN。 */
    val last4: String = "",
    /** 已用额度（仅信用卡）。 */
    val usedLimit: String = "",
    /** 信用额度（仅信用卡）。 */
    val creditLimit: String = "",
    /** 账单日（1-31，仅信用卡）。 */
    val billingDay: Int? = null,
    /** 还款日 offset（0-60，仅信用卡）。 */
    val dueDay: Int? = null,
    /** 卡组织（visa / mastercard / unionpay / ...）。 */
    val brand: String = "other",
    /** 流水主账户 id。 */
    val accountId: String = "",
    /** 流水关联卡 id（可选）。 */
    val cardId: String = "",
    /** 流水类型 income / expense / transfer。 */
    val txKind: String = "expense",
    /** 流水分类。 */
    val category: String = "",
    /** 流水发生时刻 ms。 */
    val occurredAtMs: Long = System.currentTimeMillis(),
    /** 转账时转入账户 id。 */
    val transferToAccountId: String = "",
    /** 备注。 */
    val note: String = "",
    /** 8 色板 key。 */
    val color: String = "blue",
    /** 归档标记。 */
    val archived: Boolean = false,
    /** 持卡人姓名（卡片专用）。 */
    val holder: String = "",
    /** 发卡行（卡片专用）。 */
    val issuer: String = "",
)

// =============================================================================
// v2 子类型编辑器 buffer（stage5-finance-v2 / Task 3 / TR-2.6）
// ============================================================================
// 4 个 v2 sub-type EditorBuffer —— 用于将来 EditorScreen 复用。
// 设计意图：仅承载用户在编辑器中可编辑的最小字段集（与 v1 FinanceEditorBuffer 同款
// 模式）；保存时由 EditorScreen 调 FinanceViewModel.upsertSubscription / upsertPolicy /
// upsertLoan / upsertContract，最终入 FinanceRecords.validate* 校验后写入内存。
// 现暂留 buffer 类型，便于后续 EditorScreen 直接复用；本任务范围内不强求 UI。
// ============================================================================

/**
 * v2 subscription 编辑器 buffer —— 承载订阅条目可编辑字段最小集。
 */
data class SubscriptionEditorBuffer(
    val id: String,
    val name: String = "",
    val provider: String = "",
    val amountMinor: String = "",
    val currency: String = "CNY",
    val billingCycle: String = "monthly", // monthly | quarterly | yearly | custom_days
    val customDays: Long? = null,
    val startTs: Long = System.currentTimeMillis(),
    val nextRenewalTs: Long = System.currentTimeMillis(),
    val reminders: List<Long> = emptyList(),
    val active: Boolean = true,
    val category: String = "other",
)

/**
 * v2 policy 编辑器 buffer —— 承载保单条目可编辑字段最小集。
 */
data class PolicyEditorBuffer(
    val id: String,
    val name: String = "",
    val policyNumber: String = "",
    val policyNumberEncrypted: Boolean = false,
    val provider: String = "",
    val premiumMinor: String = "",
    val currency: String = "CNY",
    val billingCycle: String = "yearly", // monthly | quarterly | yearly | single
    val startTs: Long = System.currentTimeMillis(),
    val expiryTs: Long = System.currentTimeMillis(),
    val reminders: List<Long> = emptyList(),
    val coverageMinor: String = "",
    val active: Boolean = true,
    val linkedAccountId: String? = null,
)

/**
 * v2 loan 编辑器 buffer —— 承载应收借款条目可编辑字段最小集。
 */
data class LoanEditorBuffer(
    val id: String,
    val counterparty: String = "",
    val principalMinor: String = "",
    val currency: String = "CNY",
    val direction: String = "lent", // lent | borrowed
    val issueTs: Long = System.currentTimeMillis(),
    val dueTs: Long = System.currentTimeMillis(),
    val interestRateApyBps: Long = 0L,
    val status: String = "active", // active | partially_paid | paid | overdue
    val paidMinor: String = "0.00",
    val reminders: List<Long> = emptyList(),
    val linkedAccountId: String? = null,
    val includeInNetAssets: Boolean = true,
)

/**
 * v2 contract 编辑器 buffer —— 承载合同/发票条目可编辑字段最小集。
 */
data class ContractEditorBuffer(
    val id: String,
    val title: String = "",
    val counterparty: String = "",
    val kind: String = "other", // rental | service | purchase | loan | other
    val amountMinor: String = "",
    val currency: String = "CNY",
    val signedTs: Long = System.currentTimeMillis(),
    val startTs: Long = System.currentTimeMillis(),
    val endTs: Long = System.currentTimeMillis(),
    val autoRenew: Boolean = false,
    val noticePeriodDays: Long = 0L,
    val noticeDeadlineTs: Long = System.currentTimeMillis(),
    val status: String = "active", // active | expired | terminated | renewed
    val linkedAccountId: String? = null,
)

/**
 * FinanceViewModel 整体 UI 状态。
 *
 * @param accounts 实时账户列表（删除过滤后）。
 * @param cards 实时卡片列表。
 * @param txs 实时流水列表。
 * @param dashboard 净资产看板快照（FinanceAggregator.netWorth 派生）。
 * @param monthly 当月月报快照。
 * @param budget 预算阈值状态（OK / WARNING / EXCEEDED）。
 * @param search 搜索关键字（账户/卡片/分类）。
 * @param sortKey 排序键（updated / balance）。
 * @param filterKind 类型筛选（all / cash / deposit / ...）。
 * @param errorMessage 错误文案（null 表示无错误）。
 */
data class FinanceUiState(
    val accounts: List<FinanceAccountEntity> = emptyList(),
    val cards: List<FinanceCardEntity> = emptyList(),
    val txs: List<FinanceTxEntity> = emptyList(),
    // =============================================================================
    // v2 子类型 —— stage5-finance-v2 / TR-2.6 扩展（默认 emptyList；Room v2 表待 B3 接入）
    // =============================================================================
    /** 订阅（v2 sub-type） */
    val subscriptions: List<SubscriptionRecord> = emptyList(),
    /** 保单（v2 sub-type） */
    val policies: List<PolicyRecord> = emptyList(),
    /** 应收借款（v2 sub-type） */
    val loans: List<LoanRecord> = emptyList(),
    /** 合同/发票（v2 sub-type） */
    val contracts: List<ContractRecord> = emptyList(),
    /** 预算（v2 sub-type；B6 预算硬约束，保存支出时做闸门判定）。 */
    val budgets: List<BudgetRecord> = emptyList(),
    val dashboard: FinanceAggregator.DashboardSnapshot =
        FinanceAggregator.DashboardSnapshot("0.00", "0.00", "0.00", 0, 0, 0, "CNY"),
    val monthly: FinanceAggregator.MonthlyReport =
        FinanceAggregator.MonthlyReport(currentYearMonth(), "0.00", "0.00", "0.00", 0, emptyMap()),
    val budget: FinanceAggregator.BudgetStatus = FinanceAggregator.BudgetStatus.OK,
    val search: String = "",
    val sortKey: FinanceSortKey = FinanceSortKey.UPDATED_DESC,
    val filterKind: String = "all",
    val errorMessage: String? = null,
)

/** 排序键枚举（与 strings.xml finance_list_sort_* 对齐）。 */
enum class FinanceSortKey { UPDATED_DESC, UPDATED_ASC, BALANCE_ASC, BALANCE_DESC }

/** 一次性 UI 事件（snackbar / toast / nav 跳转）。 */
sealed class FinanceUiEvent {
    /** 保存成功（id 用于日志摘要）。 */
    data class SaveSucceeded(val entityId: String) : FinanceUiEvent()

    /** 删除成功。 */
    data class DeleteSucceeded(val entityId: String) : FinanceUiEvent()

    /** 操作失败（错误码；不含 name/last4/amount 等明文）。 */
    data class Error(val code: String) : FinanceUiEvent()
}

/**
 * FinanceViewModel —— 财务模块统一 ViewModel（spec FR-10 / TR-7.x）。
 *
 * 数据流：
 *   FinanceAccountDao.observeAll() ─┐
 *   FinanceCardDao.observeAll()     ├─► combine → FinanceAggregator → stateIn
 *   FinanceTxDao.observeAll()       ─┘
 *
 * 编辑器侧通过 [saveBuffer] / [deleteEntity] 调 FinanceRepository +
 * ReminderScheduler.rebuildChain。
 */
open class FinanceViewModel(app: Application) : AndroidViewModel(app) {

    private val financeRepo = ServiceLocator.financeRepo
    private val appCtx = app.applicationContext

    // =============================================================================
    // 编辑器模式可变状态（仅编辑器场景消费；其余场景不写入）
    // =============================================================================

    /** 当前搜索关键字。 */
    private val searchFlow = MutableStateFlow("")

    /** 排序键。 */
    private val sortFlow = MutableStateFlow(FinanceSortKey.UPDATED_DESC)

    /** 类型筛选。 */
    private val filterFlow = MutableStateFlow("all")

    /** 错误文案。 */
    private val errorFlow = MutableStateFlow<String?>(null)

    // =============================================================================
    // v2 子类型内存数据源（stage5-finance-v2 / Task 3 / TR-2.6；B4 持久化闭环）
    // ============================================================================
    // 4 个 v2 子类型不建独立 Room 表，统一走 records 密文通道
    // （module=finance，type=subscription/policy/loan/contract）：
    //   - 启动时 init { hydrateV2() } 从 records 表解密回填（与 v1 账户经 Room
    //     Flow 在 combine 中自动可用同时机：VM 创建即拉）；
    //   - upsert / delete 先改内存 StateFlow，再 best-effort 密封落库 + 标 dirty；
    //   - 远端下行的入库由 RecordsRepository.sync + v2 Collector 路由承接（后续批次）。
    // ============================================================================

    /** v2 subscription 内存列表。 */
    private val subscriptionsFlow = MutableStateFlow<List<SubscriptionRecord>>(emptyList())

    /** v2 policy 内存列表。 */
    private val policiesFlow = MutableStateFlow<List<PolicyRecord>>(emptyList())

    /** v2 loan 内存列表。 */
    private val loansFlow = MutableStateFlow<List<LoanRecord>>(emptyList())

    /** v2 contract 内存列表。 */
    private val contractsFlow = MutableStateFlow<List<ContractRecord>>(emptyList())

    /** B6 预算内存列表（与其余 v2 子类型同款，走 records 密文通道，无独立 Room 表）。 */
    private val budgetsFlow = MutableStateFlow<List<BudgetRecord>>(emptyList())

    // =============================================================================
    // B5 多币种折算状态（stage5-finance-v2 / FR-V2-C.2、FR-V2-C.3）
    // ============================================================================
    // rateTable：最新生效汇率包（null=未导入，聚合按面值口径不折算）；
    // defaultCurrency：看板 / 月报折算目标币（默认 CNY，持久化在 eve-finance prefs）。
    // 二者都进 [state] 的 combine，驱动 netWorth / monthlyReport 的折算口径；
    // 同时以独立只读 StateFlow 暴露给 RatesImportScreen 展示状态。
    // ============================================================================

    /** 最新汇率表（私有可变源；导入 / 启动回填写入）。 */
    private val rateTableFlow = MutableStateFlow<RateTable?>(null)

    /** 最新汇率表只读流（RatesImportScreen 订阅展示）。 */
    val rateTableState = rateTableFlow.asStateFlow()

    /** 默认折算目标币（私有可变源；设置屏 / 启动回填写入）。 */
    private val defaultCurrencyFlow = MutableStateFlow(FinanceSettings.DEFAULT_CURRENCY)

    /** 默认折算目标币只读流（Dashboard 入口与设置屏订阅）。 */
    val defaultCurrencyState = defaultCurrencyFlow.asStateFlow()

    /**
     * VM 创建即从 records 通道回填 v2 四类记录，并回填 B5 汇率表与默认币种。
     *
     * 时机选择：v1 账户 / 卡 / 流水通过 Room Flow 在 [state] 的 combine 中自动
     * 可用（VM 创建后首个订阅者即拿到全表）；v2 没有独立 Room 表，故在同一
     * "VM 创建即 hydrate" 时机做一次性解密回填，语义对齐。MK 未解锁 / DB 未就绪
     * （如极早启动或 JVM 单测桩环境）时协程整体静默跳过，内存保持空列表，
     * 不影响 VM 构造与 v1 链路。
     */
    init {
        hydrateV2()
        hydrateBudgets()
        hydrateRateTable()
        // 默认币种取自 eve-finance SharedPreferences；JVM 桩环境（Application 未
        // mock getSharedPreferences）异常时回退默认 CNY，不阻断 VM 构造。
        defaultCurrencyFlow.value = runCatching {
            FinanceSettings.getDefaultCurrency(appCtx)
        }.getOrDefault(FinanceSettings.DEFAULT_CURRENCY)
    }

    // =============================================================================
    // 一次性事件 Channel（spec NFR-3 一次性契约，避免旋转屏重放）
    // =============================================================================

    private val _eventChannel = Channel<FinanceUiEvent>(Channel.BUFFERED)
    val eventFlow: Flow<FinanceUiEvent> = _eventChannel.receiveAsFlow()

    // =============================================================================
    // 主 State（聚合三表 Flow + 派生聚合 + 编辑器 UI 状态）
    // =============================================================================

    /**
     * 主 UI state —— combine 三表 Flow + FinanceAggregator 派生 + 搜索/排序/筛选。
     *
     * 空集合时返回默认 FinanceUiState（所有 0 / CNY / 空列表），不抛错。
     *
     * v2 扩展（TR-2.6）：combine 入参由 7 个增至 11 个；由于 kotlinx.coroutines.flow.combine
     * 至多支持 5 个类型化 Flow 直接传入，超出后必须改用 vararg 重载：
     *   combine(vararg flows: Flow<T>, transform: suspend (Array<T>) -> R)
     * 在此统一存为 Array<Flow<Any?>>（运行时类型擦除），按索引解包。
     *
     * 顺序约定（仅文档约束，**严禁改变既有索引** —— 下游按索引取值）：
     *   [0] accounts       [4] sort       [8]  policies
     *   [1] cards          [5] filter     [9]  loans
     *   [2] txs            [6] error      [10] contracts
     *   [3] search         [7] subscriptions
     *   B5 追加（顺延在末位，不挪动既有索引）：
     *   [11] rateTable（RateTable?；null=未导入按面值口径）
     *   [12] defaultCurrency（String；折算目标币，默认 CNY）
     *   B6 追加（顺延在末位，不挪动既有索引）：
     *   [13] budgets（List<BudgetRecord>；预算硬约束候选全集）
     */
    val state: kotlinx.coroutines.flow.StateFlow<FinanceUiState> = combine(
        financeRepo.observeAccounts(),
        financeRepo.observeCards(),
        financeRepo.observeTxs(),
        searchFlow,
        sortFlow,
        filterFlow,
        errorFlow,
        subscriptionsFlow as Flow<Any?>,
        policiesFlow as Flow<Any?>,
        loansFlow as Flow<Any?>,
        contractsFlow as Flow<Any?>,
        rateTableFlow as Flow<Any?>,
        defaultCurrencyFlow as Flow<Any?>,
        budgetsFlow as Flow<Any?>,
    ) { values: Array<Any?> ->
        // 类型按索引解包（顺序与上文约定一致）。
        @Suppress("UNCHECKED_CAST")
        val accounts = values[0] as List<FinanceAccountEntity>
        @Suppress("UNCHECKED_CAST")
        val cards = values[1] as List<FinanceCardEntity>
        @Suppress("UNCHECKED_CAST")
        val txs = values[2] as List<FinanceTxEntity>
        val search = values[3] as String
        val sort = values[4] as FinanceSortKey
        val filter = values[5] as String
        val error = values[6] as String?
        @Suppress("UNCHECKED_CAST")
        val subscriptions = values[7] as List<SubscriptionRecord>
        @Suppress("UNCHECKED_CAST")
        val policies = values[8] as List<PolicyRecord>
        @Suppress("UNCHECKED_CAST")
        val loans = values[9] as List<LoanRecord>
        @Suppress("UNCHECKED_CAST")
        val contracts = values[10] as List<ContractRecord>
        // B5：最新汇率表（null=未导入，按面值口径）与折算目标币。
        val rateTable = values[11] as RateTable?
        val targetCurrency = values[12] as String
        // B6：预算候选全集（保存支出时的闸门判定输入）。
        @Suppress("UNCHECKED_CAST")
        val budgets = values[13] as List<BudgetRecord>

        val dashboard = FinanceAggregator.netWorth(
            accounts.map { acc ->
                FinanceAggregator.AccountLike(
                    id = acc.id, balance = acc.balance, currency = acc.currency, archived = acc.archived,
                )
            },
            cards.map { card ->
                FinanceAggregator.CardLike(
                    id = card.id,
                    kind = card.kind,
                    usedLimit = card.usedLimit,
                    archived = card.archived,
                    currency = card.currency,
                )
            },
            txs.map { tx ->
                FinanceAggregator.TxLike(
                    id = tx.id,
                    accountId = tx.accountId,
                    cardId = tx.cardId,
                    kind = tx.kind,
                    amount = tx.amount,
                    category = tx.category,
                    occurredAt = tx.occurredAt,
                    transferToAccountId = tx.transferToAccountId,
                    currency = tx.currency,
                )
            },
            // B4 LoanLike 适配 + B5 折算：direction/principalMinor/paidMinor/
            // includeInNetAssets/currency/status 逐字段映射。
            loans.map { loan ->
                FinanceAggregator.LoanLike(
                    id = loan.id,
                    direction = loan.direction,
                    principalMinor = loan.principalMinor,
                    paidMinor = loan.paidMinor,
                    includeInNetAssets = loan.includeInNetAssets,
                    currency = loan.currency,
                    status = loan.status,
                )
            },
            targetCurrency = targetCurrency,
            rateTable = rateTable,
        )

        val monthly = FinanceAggregator.monthlyReport(
            currentYearMonth(),
            txs.map { tx ->
                FinanceAggregator.TxLike(
                    id = tx.id,
                    accountId = tx.accountId,
                    cardId = tx.cardId,
                    kind = tx.kind,
                    amount = tx.amount,
                    category = tx.category,
                    occurredAt = tx.occurredAt,
                    transferToAccountId = tx.transferToAccountId,
                    currency = tx.currency,
                )
            },
            targetCurrency = targetCurrency,
            rateTable = rateTable,
        )
        val budget = FinanceAggregator.budgetThreshold(monthly.income, monthly.expense, 0.8)

        FinanceUiState(
            accounts = accounts,
            cards = cards,
            txs = txs,
            subscriptions = subscriptions,
            policies = policies,
            loans = loans,
            contracts = contracts,
            budgets = budgets,
            dashboard = dashboard,
            monthly = monthly,
            budget = budget,
            search = search,
            sortKey = sort,
            filterKind = filter,
            errorMessage = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

    // =============================================================================
    // 列表过滤 / 排序 / 搜索（UI 调用）
    // =============================================================================

    fun setSearch(q: String) { searchFlow.value = q }
    fun setSort(k: FinanceSortKey) { sortFlow.value = k }
    fun setFilter(k: String) { filterFlow.value = k }

    /**
     * 应用 search + sort + archived-last 规则后的账户列表。
     *
     * 规则：归档置底；其余按 sort 排序。
     * 实现委托给 [FinanceFilter.accounts] —— 便于 JVM 单测直接断言。
     */
    fun filteredAccounts(state: FinanceUiState): List<FinanceAccountEntity> {
        return FinanceFilter.accounts(
            list = state.accounts,
            search = state.search,
            sortKey = state.sortKey,
            filterKind = state.filterKind,
        )
    }

    /** 应用 search + sort + archived-last 规则后的卡片列表。 */
    fun filteredCards(state: FinanceUiState): List<FinanceCardEntity> {
        return FinanceFilter.cards(
            list = state.cards,
            search = state.search,
            sortKey = state.sortKey,
            filterKind = state.filterKind,
        )
    }

    /** 应用 search + sort + archived-last 规则后的流水列表。 */
    fun filteredTxs(state: FinanceUiState): List<FinanceTxEntity> {
        return FinanceFilter.txs(
            list = state.txs,
            search = state.search,
            filterKind = state.filterKind,
            accounts = state.accounts,
        )
    }

    // =============================================================================
    // 编辑器入口
    // =============================================================================

    /** 生成新 entity id（编辑器新建分支）。 */
    fun newId(): String = java.util.UUID.randomUUID().toString()

    /**
     * 把 Entity 转 EditorBuffer（编辑器加载已存在条目时使用）。
     */
    fun bufferFor(kind: FinanceEditorKind, entityId: String): FinanceEditorBuffer? {
        return when (kind) {
            FinanceEditorKind.ACCOUNT -> {
                val acc = state.value.accounts.firstOrNull { it.id == entityId } ?: return null
                FinanceEditorBuffer(
                    id = acc.id, kind = kind,
                    name = acc.name, currency = acc.currency, itemKind = acc.kind,
                    balance = acc.balance, note = acc.note.orEmpty(), color = acc.color ?: "blue",
                    archived = acc.archived,
                )
            }
            FinanceEditorKind.CARD -> {
                val card = state.value.cards.firstOrNull { it.id == entityId } ?: return null
                FinanceEditorBuffer(
                    id = card.id, kind = kind,
                    name = card.name, currency = card.currency, itemKind = card.kind,
                    last4 = card.last4, creditLimit = card.creditLimit.orEmpty(),
                    usedLimit = card.usedLimit.orEmpty(),
                    billingDay = card.billingDay, dueDay = card.dueDay, brand = card.brand ?: "other",
                    holder = card.holder.orEmpty(), issuer = card.issuer,
                    note = card.note.orEmpty(), color = card.color ?: "blue",
                    archived = card.archived,
                )
            }
            FinanceEditorKind.TX -> {
                val tx = state.value.txs.firstOrNull { it.id == entityId } ?: return null
                FinanceEditorBuffer(
                    id = tx.id, kind = kind,
                    accountId = tx.accountId, cardId = tx.cardId.orEmpty(),
                    txKind = tx.kind, balance = tx.amount, category = tx.category,
                    occurredAtMs = tx.occurredAt, transferToAccountId = tx.transferToAccountId.orEmpty(),
                    note = tx.note.orEmpty(), color = tx.color ?: "blue",
                )
            }
        }
    }

    /**
     * 保存编辑器 buffer → Entity → FinanceRepository.upsert + rebuildChain。
     *
     * 校验失败 → 通过 eventFlow 发送 Error，**不**入库。
     *
     * B6 预算硬约束：支出（expense）落库前先做一次同步预校验（[precheckTxBuffer]）。
     * 结果为 [BudgetLevel.BLOCK] 且用户未在确认对话框选择"仍保存"
     * （[overspendAcknowledged]=false）时，发送 "budget_blocked" 事件并直接拦截，
     * 不构造/落库任何实体；WARNING/OK 不拦截（WARNING 的 Toast 由 UI 层处理）。
     * [overspendAcknowledged] 仅对 TX 生效，并随实体落库做本地审计留痕。
     */
    fun saveBuffer(buffer: FinanceEditorBuffer, overspendAcknowledged: Boolean = false) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                when (buffer.kind) {
                    FinanceEditorKind.ACCOUNT -> {
                        if (buffer.name.isBlank()) {
                            _eventChannel.send(FinanceUiEvent.Error("account_name_empty"))
                            return@launch
                        }
                        val entity = FinanceAccountEntity(
                            id = buffer.id,
                            name = buffer.name.trim(),
                            kind = buffer.itemKind.ifBlank { "other" },
                            currency = buffer.currency.ifBlank { "CNY" },
                            balance = buffer.balance.ifBlank { "0" },
                            note = buffer.note.takeIf { it.isNotBlank() },
                            icon = null,
                            color = buffer.color,
                            archived = buffer.archived,
                            createdAt = state.value.accounts.firstOrNull { it.id == buffer.id }?.createdAt ?: now,
                            updatedAt = now,
                            dirty = true,
                            deleted = false,
                        )
                        financeRepo.upsertAccount(entity)
                    }
                    FinanceEditorKind.CARD -> {
                        if (buffer.name.isBlank()) {
                            _eventChannel.send(FinanceUiEvent.Error("card_name_empty"))
                            return@launch
                        }
                        if (buffer.issuer.isBlank()) {
                            _eventChannel.send(FinanceUiEvent.Error("card_issuer_empty"))
                            return@launch
                        }
                        // 仅在编辑器新输入 PAN 时重新计算 last4；既有 card 不变更 last4。
                        val finalLast4 = if (buffer.pan.isNotBlank()) {
                            com.everything.eve.finance.Luhn.extractLast4(buffer.pan)
                                ?: run {
                                    _eventChannel.send(FinanceUiEvent.Error("card_luhn_failed"))
                                    return@launch
                                }
                        } else {
                            buffer.last4.ifBlank { "0000" }
                        }
                        val entity = FinanceCardEntity(
                            id = buffer.id,
                            name = buffer.name.trim(),
                            kind = buffer.itemKind.ifBlank { "credit" },
                            issuer = buffer.issuer.trim(),
                            last4 = finalLast4,
                            currency = buffer.currency.ifBlank { "CNY" },
                            creditLimit = buffer.creditLimit.takeIf { it.isNotBlank() },
                            usedLimit = buffer.usedLimit.takeIf { it.isNotBlank() },
                            billingDay = buffer.billingDay,
                            dueDay = buffer.dueDay,
                            brand = buffer.brand.ifBlank { "other" },
                            expiryMonth = null,
                            expiryYear = null,
                            holder = buffer.holder.takeIf { it.isNotBlank() },
                            note = buffer.note.takeIf { it.isNotBlank() },
                            icon = null,
                            color = buffer.color,
                            archived = buffer.archived,
                            createdAt = state.value.cards.firstOrNull { it.id == buffer.id }?.createdAt ?: now,
                            updatedAt = now,
                            dirty = true,
                            deleted = false,
                        )
                        financeRepo.upsertCard(entity)
                    }
                    FinanceEditorKind.TX -> {
                        if (buffer.accountId.isBlank()) {
                            _eventChannel.send(FinanceUiEvent.Error("tx_account_required"))
                            return@launch
                        }
                        if (buffer.balance.isBlank() || !isDecimalLike(buffer.balance)) {
                            _eventChannel.send(FinanceUiEvent.Error("tx_amount_invalid"))
                            return@launch
                        }
                        if (buffer.txKind == "transfer") {
                            if (buffer.transferToAccountId.isBlank()) {
                                _eventChannel.send(FinanceUiEvent.Error("tx_transfer_missing"))
                                return@launch
                            }
                            if (buffer.accountId == buffer.transferToAccountId) {
                                _eventChannel.send(FinanceUiEvent.Error("tx_transfer_same"))
                                return@launch
                            }
                        }
                        // B6 预算硬约束闸门：仅对支出做拦截；非支出（收入/转账）直接放行。
                        if (buffer.txKind.ifBlank { "expense" } == "expense") {
                            val check = precheckTxBuffer(buffer)
                            if (check.level == BudgetLevel.BLOCK && !overspendAcknowledged) {
                                // 触发 UI 层 BudgetConfirmDialog；本次不落库。
                                _eventChannel.send(FinanceUiEvent.Error("budget_blocked"))
                                return@launch
                            }
                        }
                        val entity = FinanceTxEntity(
                            id = buffer.id,
                            accountId = buffer.accountId,
                            cardId = buffer.cardId.takeIf { it.isNotBlank() },
                            kind = buffer.txKind.ifBlank { "expense" },
                            amount = buffer.balance,
                            currency = buffer.currency.ifBlank { "CNY" },
                            category = buffer.category.ifBlank { "other" },
                            occurredAt = buffer.occurredAtMs,
                            note = buffer.note.takeIf { it.isNotBlank() },
                            icon = null,
                            color = buffer.color,
                            transferToAccountId = buffer.transferToAccountId.takeIf { it.isNotBlank() },
                            createdAt = state.value.txs.firstOrNull { it.id == buffer.id }?.createdAt ?: now,
                            updatedAt = now,
                            dirty = true,
                            deleted = false,
                            overspendAcknowledged = overspendAcknowledged,
                        )
                        financeRepo.upsertTx(entity)
                    }
                }
                ReminderScheduler.rebuildChain(appCtx)
                _eventChannel.send(FinanceUiEvent.SaveSucceeded(buffer.id))
            } catch (e: Exception) {
                _eventChannel.send(FinanceUiEvent.Error("save_failed"))
            }
        }
    }

    /**
     * 删除 entity（按 kind + id 软删 / 真删）。
     *
     * 注意：FinanceRepository 尚未暴露 delete* 方法（本任务范围仅搭骨架）；
     * 此处通过 DAO 直调 markDeleted 软删 → UI 过滤 deleted=0 → records 通道
     * 由后续 RecordsRepository.upsertFinance* 接管 tombstone 上行。
     */
    fun deleteEntity(kind: FinanceEditorKind, entityId: String) {
        viewModelScope.launch {
            try {
                when (kind) {
                    FinanceEditorKind.ACCOUNT -> ServiceLocator.db.financeAccountDao().markDeleted(entityId)
                    FinanceEditorKind.CARD -> ServiceLocator.db.financeCardDao().markDeleted(entityId)
                    FinanceEditorKind.TX -> ServiceLocator.db.financeTxDao().markDeleted(entityId)
                }
                ReminderScheduler.rebuildChain(appCtx)
                _eventChannel.send(FinanceUiEvent.DeleteSucceeded(entityId))
            } catch (e: Exception) {
                _eventChannel.send(FinanceUiEvent.Error("delete_failed"))
            }
        }
    }

    /** 清空错误态（UI 已展示 Snack 后调）。 */
    fun clearError() {
        errorFlow.value = null
    }

    // =============================================================================
    // v2 子类型 CRUD（stage5-finance-v2 / Task 3 / TR-2.6；B4 records 持久化闭环）
    // ============================================================================
    // 4 个 v2 子类型（SubscriptionRecord / PolicyRecord / LoanRecord / ContractRecord）
    // 统一走 records 密文通道（无独立 Room 表）：
    //   - upsert：校验通过 → 先更新内存 MutableStateFlow（stateIn 自动推送新 state，
    //     返回值语义保持不变）→ viewModelScope.launch best-effort 调
    //     RecordsRepository.upsertFinanceV2（V2PayloadCodec 编码 + sealRecord + dirty=1）；
    //   - delete：内存移除 → launch best-effort 推墓碑 upsertFinanceV2Tombstone；
    //   - 持久化失败（MK 未解锁 / ServiceLocator 未就绪 / 加密异常）静默吞掉，
    //     内存结果不回滚（与 v1 upsertAccount 协程模式同款 best-effort 纪律）；
    //   - 校验仍在内存更新前完成：FinanceRecords.validate* 失败返回 Result.failure，
    //     不触发任何持久化。
    // ============================================================================

    /**
     * v2 subscription upsert（内存更新 + records 通道 best-effort 持久化）。
     *
     * @param r 完整 SubscriptionRecord（id 必填；schema_version=2 必填）
     * @return Ok / Invalid(reason)
     */
    fun upsertSubscription(r: SubscriptionRecord): Result<Unit> {
        val validation = FinanceRecords.validateSubscription(r)
        if (validation is ValidationResult.Invalid) {
            _eventChannel.trySend(FinanceUiEvent.Error("subscription_invalid"))
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val current = subscriptionsFlow.value
        val replaced = current.filterNot { it.id == r.id }.toMutableList().apply { add(r) }
        subscriptionsFlow.value = replaced
        _eventChannel.trySend(FinanceUiEvent.SaveSucceeded(r.id))
        persistV2(FinanceModule.TYPE_SUBSCRIPTION, r.id, V2PayloadCodec.encodeSubscription(r))
        return Result.success(Unit)
    }

    /** v2 subscription delete（内存移除 + 墓碑 best-effort）。id 不存在视为成功（幂等）。 */
    fun deleteSubscription(id: String): Result<Unit> {
        val current = subscriptionsFlow.value
        subscriptionsFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        tombstoneV2(FinanceModule.TYPE_SUBSCRIPTION, id)
        return Result.success(Unit)
    }

    /** v2 policy upsert（内存更新 + records 通道 best-effort 持久化）。 */
    fun upsertPolicy(r: PolicyRecord): Result<Unit> {
        val validation = FinanceRecords.validatePolicy(r)
        if (validation is ValidationResult.Invalid) {
            _eventChannel.trySend(FinanceUiEvent.Error("policy_invalid"))
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val current = policiesFlow.value
        val replaced = current.filterNot { it.id == r.id }.toMutableList().apply { add(r) }
        policiesFlow.value = replaced
        _eventChannel.trySend(FinanceUiEvent.SaveSucceeded(r.id))
        persistV2(FinanceModule.TYPE_POLICY, r.id, V2PayloadCodec.encodePolicy(r))
        return Result.success(Unit)
    }

    /** v2 policy delete（内存移除 + 墓碑 best-effort）。 */
    fun deletePolicy(id: String): Result<Unit> {
        val current = policiesFlow.value
        policiesFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        tombstoneV2(FinanceModule.TYPE_POLICY, id)
        return Result.success(Unit)
    }

    /** v2 loan upsert（内存更新 + records 通道 best-effort 持久化）。 */
    fun upsertLoan(r: LoanRecord): Result<Unit> {
        val validation = FinanceRecords.validateLoan(r)
        if (validation is ValidationResult.Invalid) {
            _eventChannel.trySend(FinanceUiEvent.Error("loan_invalid"))
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val current = loansFlow.value
        val replaced = current.filterNot { it.id == r.id }.toMutableList().apply { add(r) }
        loansFlow.value = replaced
        _eventChannel.trySend(FinanceUiEvent.SaveSucceeded(r.id))
        persistV2(FinanceModule.TYPE_LOAN, r.id, V2PayloadCodec.encodeLoan(r))
        return Result.success(Unit)
    }

    /** v2 loan delete（内存移除 + 墓碑 best-effort）。 */
    fun deleteLoan(id: String): Result<Unit> {
        val current = loansFlow.value
        loansFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        tombstoneV2(FinanceModule.TYPE_LOAN, id)
        return Result.success(Unit)
    }

    /** v2 contract upsert（内存更新 + records 通道 best-effort 持久化）。 */
    fun upsertContract(r: ContractRecord): Result<Unit> {
        val validation = FinanceRecords.validateContract(r)
        if (validation is ValidationResult.Invalid) {
            _eventChannel.trySend(FinanceUiEvent.Error("contract_invalid"))
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val current = contractsFlow.value
        val replaced = current.filterNot { it.id == r.id }.toMutableList().apply { add(r) }
        contractsFlow.value = replaced
        _eventChannel.trySend(FinanceUiEvent.SaveSucceeded(r.id))
        persistV2(FinanceModule.TYPE_CONTRACT, r.id, V2PayloadCodec.encodeContract(r))
        return Result.success(Unit)
    }

    /** v2 contract delete（内存移除 + 墓碑 best-effort）。 */
    fun deleteContract(id: String): Result<Unit> {
        val current = contractsFlow.value
        contractsFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        tombstoneV2(FinanceModule.TYPE_CONTRACT, id)
        return Result.success(Unit)
    }

    // =============================================================================
    // B6 预算 CRUD + 超支闸门（走 v2 records 密文通道，无独立 Room 表）
    // ============================================================================

    /**
     * B6 预算 upsert（内存更新 + records 通道 best-effort 持久化）。
     *
     * 与其余 v2 子类型同纪律：[FinanceRecords.validateBudget] 失败先发
     * "budget_invalid" 事件并返回 failure，不动内存、不持久化；成功先换内存再
     * best-effort 密封落库。
     */
    fun upsertBudget(r: BudgetRecord): Result<Unit> {
        val validation = FinanceRecords.validateBudget(r)
        if (validation is ValidationResult.Invalid) {
            _eventChannel.trySend(FinanceUiEvent.Error("budget_invalid"))
            return Result.failure(IllegalArgumentException(validation.reason))
        }
        val current = budgetsFlow.value
        budgetsFlow.value = current.filterNot { it.id == r.id } + r
        _eventChannel.trySend(FinanceUiEvent.SaveSucceeded(r.id))
        persistV2(FinanceModule.TYPE_BUDGET, r.id, V2PayloadCodec.encodeBudget(r))
        return Result.success(Unit)
    }

    /** B6 预算 delete（内存移除 + 墓碑 best-effort）。id 不存在视为成功（幂等）。 */
    fun deleteBudget(id: String): Result<Unit> {
        val current = budgetsFlow.value
        budgetsFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        tombstoneV2(FinanceModule.TYPE_BUDGET, id)
        return Result.success(Unit)
    }

    /**
     * B6 支出保存前同步预校验：把当前内存中的预算全集、既有流水与编辑器本笔
     * 组装成纯函数 [BudgetEnforcer.checkTx] 的输入，返回闸门判定结果。
     *
     * - 非支出（收入 / 转账等）直接返回 [BudgetCheckResult.OK_EMPTY]；
     * - 既有流水取 state 中的 txs（编辑场景同 id 旧额由纯函数自动排除）；
     * - 异币折算复用 B5 的 rateTableFlow；预算与流水均为 minor 元字符串口径；
     * - 纯同步、无副作用、不抛业务异常（预算缺失时纯函数返回 OK_EMPTY）。
     */
    fun precheckTxBuffer(buffer: FinanceEditorBuffer): BudgetCheckResult {
        val kind = buffer.txKind.ifBlank { "expense" }
        if (kind != "expense") return BudgetCheckResult.OK_EMPTY
        val existing = state.value.txs.map { entity ->
            BudgetTxLike(
                id = entity.id,
                kind = entity.kind,
                amountMinor = entity.amount,
                category = entity.category,
                currency = entity.currency,
                occurredAt = entity.occurredAt,
            )
        }
        val incoming = BudgetTxLike(
            id = buffer.id,
            kind = kind,
            amountMinor = buffer.balance,
            category = buffer.category.ifBlank { "other" },
            currency = buffer.currency.ifBlank { "CNY" },
            occurredAt = buffer.occurredAtMs,
        )
        return BudgetEnforcer.checkTx(
            incoming = incoming,
            budgets = budgetsFlow.value,
            existing = existing,
            rateTable = rateTableFlow.value,
            nowMs = System.currentTimeMillis(),
        )
    }

    // =============================================================================
    // v2 records 通道持久化辅助（B4）—— best-effort 协程，全部异常静默
    // ============================================================================

    /**
     * 把一条已通过校验、已写入内存的 v2 记录密封落入 records 表。
     *
     * 参照 v1 saveBuffer → financeRepo.upsertAccount 的协程模式：viewModelScope
     * 内执行；MK 未解锁 / ServiceLocator 未就绪（JVM 桩）/ Room 异常一律吞掉，
     * 不回滚内存、不打扰 UI（下次同步前本地仍可见；待解锁后由后续编辑或
     * hydrate / 同步链路重新收敛）。
     */
    private fun persistV2(type: String, id: String, plaintextJson: String) {
        viewModelScope.launch {
            try {
                ServiceLocator.repo.upsertFinanceV2(type, id, plaintextJson)
                // v2 三类提醒候选发生变化，重算全局单闹钟链头（与 v1 saveBuffer
                // 末尾调 rebuildChain 同款时机；best-effort，失败不阻断）。
                try {
                    ReminderScheduler.rebuildChain(appCtx)
                } catch (_: Exception) {
                    // 调度器重算失败不阻断持久化路径。
                }
            } catch (_: Exception) {
                // 持久化 best-effort：内存更新已成功，失败静默。
            }
        }
    }

    /** 删除一条 v2 记录：推 records 墓碑（deleted=1 / dirty=1），best-effort。 */
    private fun tombstoneV2(type: String, id: String) {
        viewModelScope.launch {
            try {
                ServiceLocator.repo.upsertFinanceV2Tombstone(type, id)
                try {
                    ReminderScheduler.rebuildChain(appCtx)
                } catch (_: Exception) {
                    // 调度器重算失败不阻断墓碑路径。
                }
            } catch (_: Exception) {
                // 墓碑 best-effort：内存移除已成功，失败静默。
            }
        }
    }

    /**
     * 从 records 表一次性解密回填 v2 四类内存列表。
     *
     * 流程（每类独立）：RecordDao.getActiveByModuleType（deleted=0 过滤）→
     * RecordsRepository.decryptFinanceV2 → V2PayloadCodec.decode* → 替换对应
     * MutableStateFlow。任意**单条**解密 / 解析失败跳过该条不阻塞；整体查询
     * 失败（DB / MK 未就绪）静默保留当前内存态。幂等：可被外部重复调用
     * （如解锁后手动刷新），每次以 records 表快照全量替换内存列表。
     */
    fun hydrateV2() {
        viewModelScope.launch {
            try {
                val recordsDao = ServiceLocator.db.recordDao()
                val recordsRepo = ServiceLocator.repo

                val subscriptions = recordsDao
                    .getActiveByModuleType(FinanceModule.MODULE, FinanceModule.TYPE_SUBSCRIPTION)
                    .mapNotNull { entity ->
                        runCatching {
                            V2PayloadCodec.decodeSubscription(recordsRepo.decryptFinanceV2(entity))
                        }.getOrNull()
                    }
                val policies = recordsDao
                    .getActiveByModuleType(FinanceModule.MODULE, FinanceModule.TYPE_POLICY)
                    .mapNotNull { entity ->
                        runCatching {
                            V2PayloadCodec.decodePolicy(recordsRepo.decryptFinanceV2(entity))
                        }.getOrNull()
                    }
                val loans = recordsDao
                    .getActiveByModuleType(FinanceModule.MODULE, FinanceModule.TYPE_LOAN)
                    .mapNotNull { entity ->
                        runCatching {
                            V2PayloadCodec.decodeLoan(recordsRepo.decryptFinanceV2(entity))
                        }.getOrNull()
                    }
                val contracts = recordsDao
                    .getActiveByModuleType(FinanceModule.MODULE, FinanceModule.TYPE_CONTRACT)
                    .mapNotNull { entity ->
                        runCatching {
                            V2PayloadCodec.decodeContract(recordsRepo.decryptFinanceV2(entity))
                        }.getOrNull()
                    }

                subscriptionsFlow.value = subscriptions
                policiesFlow.value = policies
                loansFlow.value = loans
                contractsFlow.value = contracts
            } catch (_: Exception) {
                // DB / MK / ServiceLocator 未就绪：保留内存现状，不打扰 UI。
            }
        }
    }

    /**
     * B6：VM 创建即从 records 表一次性解密回填预算列表（与 [hydrateV2] 同款）。
     *
     * RecordDao.getActiveByModuleType(module=finance, type=budget) 只取
     * deleted=0 的密封记录 → RecordsRepository.decryptFinanceV2 解密 →
     * V2PayloadCodec.decodeBudget 解析；单条失败跳过，整体失败（DB / MK /
     * ServiceLocator 未就绪，如 JVM 桩环境）静默保留空内存态。
     */
    fun hydrateBudgets() {
        viewModelScope.launch {
            try {
                val recordsDao = loadBudgetRecordDao() ?: return@launch
                val recordsRepo = ServiceLocator.repo
                val budgets = recordsDao
                    .getActiveByModuleType(FinanceModule.MODULE, FinanceModule.TYPE_BUDGET)
                    .mapNotNull { entity ->
                        runCatching {
                            V2PayloadCodec.decodeBudget(recordsRepo.decryptFinanceV2(entity))
                        }.getOrNull()
                    }
                budgetsFlow.value = budgets
            } catch (_: Exception) {
                // DB / MK / ServiceLocator 未就绪：保留内存现状，不打扰 UI。
            }
        }
    }

    /**
     * B6 测试可替换接缝：默认直接取 [ServiceLocator.db] 的 RecordDao；
     * JVM 单测可覆写返回桩 DAO，避免触碰抽象 RoomDatabase。
     * 返回 null 表示环境未就绪，hydrate 静默跳过。
     */
    internal open fun loadBudgetRecordDao(): RecordDao? =
        runCatching { ServiceLocator.db.recordDao() }.getOrNull()

    // =============================================================================
    // B5 汇率包与默认币种（stage5-finance-v2 / FR-V2-C.2、FR-V2-C.3）
    // ============================================================================
    // 汇率仓库走 ServiceLocator.rateTableRepository 直接懒取（与 persistV2 内
    // 直接取 ServiceLocator.repo 同款；不引入 bind 注入）。MK / DB 未就绪的 JVM
    // 桩环境由 try/catch 兜住，不阻断 VM 构造与既有链路。
    // ============================================================================

    /**
     * VM 创建即从本地 finance_rate 表回填最新汇率包。
     *
     * ServiceLocator 未初始化（JVM 单测桩）/ DB 异常时静默保留 null，
     * 聚合按面值口径降级（RateTable=null 不折算）。
     */
    private fun hydrateRateTable() {
        viewModelScope.launch {
            try {
                rateTableFlow.value = ServiceLocator.rateTableRepository.latest()
            } catch (_: Exception) {
                // 未就绪：保持 null（未导入口径）。
            }
        }
    }

    /**
     * 导入一份汇率包明文 JSON（设置屏 SAF 选文件后调用）。
     *
     * 同步返回 [Result]（与 [addAttachment] 同款设计：launch 在 viewModelScope
     * 内执行；测试在 Dispatchers.setMain 的 UnconfinedTestDispatcher 下可立即
     * 跑完，返回值即时反映成败）：
     *  - 成功：rateTable state 更新为新包 + 发 SaveSucceeded 事件；
     *  - 失败（包非法 / MK 未解锁 / DB 异常）：发 Error("rate_import_invalid")，
     *    返回 [Result.failure]。
     *
     * @param json 汇率包明文 JSON（spec FR-V2-C.2 契约）。
     */
    fun importRateTable(json: String): Result<Unit> {
        var outcome: Result<Unit> = Result.success(Unit)
        viewModelScope.launch {
            try {
                val result = ServiceLocator.rateTableRepository.importPackage(json)
                val table = result.getOrNull()
                if (table != null) {
                    rateTableFlow.value = table
                    _eventChannel.trySend(
                        FinanceUiEvent.SaveSucceeded("rate@${table.effectiveTs}"),
                    )
                } else {
                    outcome = Result.failure(
                        result.exceptionOrNull()
                            ?: IllegalArgumentException("汇率包导入失败"),
                    )
                    _eventChannel.trySend(FinanceUiEvent.Error("rate_import_invalid"))
                }
            } catch (e: Exception) {
                outcome = Result.failure(e)
                _eventChannel.trySend(FinanceUiEvent.Error("rate_import_invalid"))
            }
        }
        return outcome
    }

    /**
     * 设置默认折算目标币种。
     *
     * 校验 3 位大写字母 ISO 代码（[FinanceSettings.isValidCurrencyCode]）：
     *  - 合法：更新 state + 写 eve-finance SharedPreferences（写入 best-effort，
     *    JVM 桩环境异常不影响内存态）；
     *  - 非法：发 Error("default_currency_invalid") 事件并返回 [Result.failure]，
     *    不更新 state。
     */
    fun setDefaultCurrency(code: String): Result<Unit> {
        if (!FinanceSettings.isValidCurrencyCode(code)) {
            _eventChannel.trySend(FinanceUiEvent.Error("default_currency_invalid"))
            return Result.failure(
                IllegalArgumentException("默认币种必须为 3 位大写字母代码（如 CNY）"),
            )
        }
        defaultCurrencyFlow.value = code
        runCatching { FinanceSettings.setDefaultCurrency(appCtx, code) }
        return Result.success(Unit)
    }

    // =============================================================================
    // 附件绑定（stage5-finance-v2 / Task 5 / TR-3.4 / SA-3）
    // ============================================================================
    // 构造器已固化（SA-2 范围外）—— 改为运行时绑定模式：
    //   * 字段 attachmentRepoRef 默认 null；
    //   * 应用启动处 / Compose 入口处调 bindAttachmentRepository 注入；
    //   * 内部访问 attachmentRepo 时取 attachmentRepoRef（未绑定抛 ISE 早暴露）。
    // ============================================================================

    /** 附件仓库引用（SA-3 注入；构造期 null，由 ServiceLocator 启动后 bind）。 */
    private var attachmentRepoRef: AttachmentRepository? = null

    /**
     * 注入附件仓库（SA-3）。
     *
     * 调用方：在持有 FinanceViewModel 引用的 Compose Screen（PolicyEditorScreen /
     * ContractEditorScreen）的 LaunchedEffect 中调
     * `vm.bindAttachmentRepository(ServiceLocator.attachmentRepo)`。
     */
    fun bindAttachmentRepository(repo: AttachmentRepository) {
        attachmentRepoRef = repo
    }

    /** 内部访问附件仓库（未绑定抛 ISE，避免 NPE 静默）。 */
    private val attachmentRepo: AttachmentRepository
        get() = attachmentRepoRef
            ?: error("AttachmentRepository 未绑定 —— 必须在 VM 构造后调 bindAttachmentRepository(ServiceLocator.attachmentRepo)")

    // =============================================================================
    // 附件 observe / action（stage5-finance-v2 / Task 5 / TR-3.4 / SA-3）
    // ============================================================================
    // 设计要点：
    //   1. observe 简化封装：attachmentsByRecordId(recordId) → 直接转发 Repository；
    //   2. addAttachment / removeAttachment：viewModelScope 内调 Repository，失败
    //      经 _eventChannel.send(FinanceUiEvent.Error(...)) 报告；
    //   3. 端侧 50MB 校验双保险：UI 端预检（直接 Toast 拒收）+ VM verifySize 二次
    //      校验（Repository 内还有 size > 0 与 ATTACHMENT_MAX_SIZE_BYTES 终检）；
    //   4. sha256 在 VM 层计算（hex 64 字符）→ 传给 Repository.upload。
    // ============================================================================

    /**
     * 按父记录 id 实时观察附件列表（Flow 转发）。
     *
     * Compose AttachmentList 直接订阅此 Flow，UI 随 Room 写入自动刷新。
     *
     * @param recordId 父记录 id（policy / contract 等 v2 子类型记录的主键）。
     */
    fun attachmentsByRecordId(recordId: String): Flow<List<AttachmentEntity>> =
        attachmentRepo.listByRecordId(recordId)

    /**
     * 上传一个附件：UI 端选择文件 → 读字节 → sha256 → Repository 双写。
     *
     * 失败语义：Repository 返回 failure → 转发 _eventChannel.send(Error)。
     *
     * @param recordId 父记录 id。
     * @param content 附件明文字节（policy 合同扫描件 / 保单 PDF 等）。
     * @param mime MIME 类型（application/pdf / image/jpeg / image/png 等白名单内）。
     * @return [Result.success] [AttachmentRef]（id / mime / size / sha256）或
     *   [Result.failure]（含 IllegalArgumentException / IllegalStateException 提示）。
     */
    fun addAttachment(
        recordId: String,
        content: ByteArray,
        mime: String,
    ): Result<AttachmentRef> {
        // ---- 端侧预校验：size ----
        when (val sizeCheck = verifySize(content.size.toLong())) {
            is ValidationResult.Invalid -> {
                viewModelScope.launch {
                    _eventChannel.send(FinanceUiEvent.Error("attachment_size_exceeded"))
                }
                return Result.failure(IllegalArgumentException(sizeCheck.reason))
            }
            else -> Unit
        }

        // ---- sha256 端侧计算 ----
        val sha256Hex = sha256HexOf(content)

        viewModelScope.launch {
            try {
                val result = attachmentRepo.upload(recordId, content, mime, sha256Hex)
                if (result.isFailure) {
                    _eventChannel.send(FinanceUiEvent.Error("attachment_upload_failed"))
                }
            } catch (e: Exception) {
                _eventChannel.send(FinanceUiEvent.Error("attachment_upload_failed"))
            }
        }
        return Result.success(
            AttachmentRef(
                id = "pending-${System.currentTimeMillis()}",
                mime = mime,
                size = content.size.toLong(),
                sha256 = sha256Hex,
            ),
        )
    }

    /**
     * 删除一个附件（软删除墓碑 + 推 records 通道）。
     *
     * 失败语义：Repository 返回 failure → 转发 _eventChannel.send(Error)。
     *
     * @param id 附件 UUID。
     */
    fun removeAttachment(id: String): Result<Unit> {
        viewModelScope.launch {
            try {
                val result = attachmentRepo.delete(id)
                if (result.isFailure) {
                    _eventChannel.send(FinanceUiEvent.Error("attachment_delete_failed"))
                }
            } catch (e: Exception) {
                _eventChannel.send(FinanceUiEvent.Error("attachment_delete_failed"))
            }
        }
        return Result.success(Unit)
    }

    /**
     * 端侧 50MB 校验（与 [com.everything.eve.finance.FinanceRecords.ATTACHMENT_MAX_SIZE_BYTES] 同口径）。
     *
     * @param size 待校验字节数。
     * @return [ValidationResult.Ok] / [ValidationResult.Invalid(reason)]。
     */
    private fun verifySize(size: Long): ValidationResult {
        val maxBytes = com.everything.eve.finance.ATTACHMENT_MAX_SIZE_BYTES
        return if (size <= 0L) {
            ValidationResult.Invalid("附件字节数必须 > 0")
        } else if (size > maxBytes) {
            ValidationResult.Invalid("附件超过 ${maxBytes} 字节上限（50MB）")
        } else {
            ValidationResult.Ok
        }
    }

    /**
     * 把字节数组转 SHA-256 小写 hex 字符串（64 字符）。
     *
     * @param content 待哈希字节。
     * @return 64 字符 hex。
     */
    private fun sha256HexOf(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 打开一个附件：解密 → 写入 cacheDir → 返回 file://-scheme Uri。
     *
     * 实现要点：
     *   1) attachmentRepo.download(id) → 明文字节（Result.failure 时返回 null）；
     *   2) 按 mime 推断文件扩展名（pdf / jpg / png / bin）；
     *   3) 写入 cacheDir/$attachmentId.$ext —— cacheDir 是 app-private，无需权限；
     *   4) 用 Uri.fromFile(...) 返回 file://-scheme Uri。
     *
     * @param context 应用上下文（用于 cacheDir 解析）。
     * @param attachmentId 附件 UUID。
     * @param mime 附件 MIME（由调用方从附件列表传入；空时默认 application/octet-stream）。
     * @return Uri 或 null（下载失败 / 写文件失败）。
     */
    fun openAttachment(context: Context, attachmentId: String, mime: String? = null): Uri? {
        val ctx = context.applicationContext
        val downloadResult = runCatching {
            kotlinx.coroutines.runBlocking { attachmentRepo.download(attachmentId) }
        }.getOrNull() ?: return null
        val bytes = downloadResult.getOrNull() ?: return null

        val effectiveMime = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val ext = when {
            effectiveMime.contains("pdf", ignoreCase = true) -> "pdf"
            effectiveMime.contains("jpeg", ignoreCase = true) || effectiveMime.contains("jpg", ignoreCase = true) -> "jpg"
            effectiveMime.contains("png", ignoreCase = true) -> "png"
            else -> "bin"
        }

        val outFile = File(ctx.cacheDir, "$attachmentId.$ext")
        return try {
            outFile.writeBytes(bytes)
            Uri.fromFile(outFile)
        } catch (e: Exception) {
            null
        }
    }

    // =============================================================================
    // 提醒触发钩子（dashboard 显示最近 N 次信用卡触发）
    // =============================================================================

    /**
     * 取 N 张非归档卡的下一次触发时刻（升序）。纯函数派生，UI 直接消费。
     *
     * @param limit 上限条数（默认 5，与 4b rebuildChain 取全局最小一致）。
     */
    suspend fun upcomingCardTriggersMs(limit: Int = 5): List<Long> = withContext(Dispatchers.Default) {
        val nowMs = System.currentTimeMillis()
        val cards = state.value.cards.map { card ->
            NextCardFiring.CardLike(
                id = card.id,
                kind = card.kind,
                billingDay = card.billingDay,
                dueDay = card.dueDay,
                archived = card.archived,
            )
        }
        NextCardFiring.upcomingTriggers(cards, nowMs, limit)
    }

    // =============================================================================
    // 一次性快照：编辑器加载已存在条目（与 CalendarViewModel 同款模式）
    // =============================================================================

    /** 一次性拉取当前账户列表快照（不订阅）。 */
    suspend fun accountsSnapshot(): List<FinanceAccountEntity> = withContext(Dispatchers.IO) {
        runCatching { financeRepo.observeAccounts().first() }.getOrDefault(emptyList())
    }

    /** 一次性拉取当前卡片列表快照。 */
    suspend fun cardsSnapshot(): List<FinanceCardEntity> = withContext(Dispatchers.IO) {
        runCatching { financeRepo.observeCards().first() }.getOrDefault(emptyList())
    }

    /** 一次性拉取当前流水列表快照。 */
    suspend fun txsSnapshot(): List<FinanceTxEntity> = withContext(Dispatchers.IO) {
        runCatching { financeRepo.observeTxs().first() }.getOrDefault(emptyList())
    }
}

// =============================================================================
// 私有工具方法 —— 当前本地年月键 + decimal-as-string cents 解析
// =============================================================================

/**
 * 当前本地年月键 "YYYY-MM"（与 FinanceAggregator.yearMonthOf 同口径 CST 锚定）。
 */
private fun currentYearMonth(): String {
    val cal = java.util.Calendar.getInstance()
    return "%04d-%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
    )
}

/**
 * decimal-as-string → cents（Long）。容错：非法 / 空串 → 0L。
 *
 * 复用 FinanceAggregator 的同款算法骨架（独立维护，避免暴露内部 parseDecimalAsCents）。
 */
private fun balanceCents(s: String): Long {
    if (s.isBlank()) return 0L
    var negative = false
    var seenDot = false
    var whole = 0L
    var frac = 0L
    var fracDigits = 0
    for (c in s.trim()) {
        when {
            c == '-' && whole == 0L && !seenDot && !negative -> negative = true
            c == '+' && whole == 0L && !seenDot && !negative -> Unit
            c == '.' && !seenDot -> seenDot = true
            c in '0'..'9' -> {
                val digit = c.code - '0'.code
                if (seenDot) {
                    if (fracDigits < 2) {
                        frac = frac * 10L + digit.toLong()
                        fracDigits++
                    }
                } else {
                    whole = whole * 10L + digit.toLong()
                }
            }
            else -> return 0L
        }
    }
    while (fracDigits < 2) {
        frac *= 10L
        fracDigits++
    }
    var cents = whole * 100L + frac
    if (negative) cents = -cents
    return cents
}

/**
 * 校验字符串是否为合法 decimal-as-string（仅整数 + 一位/两位小数 + 可选负号）。
 */
private fun isDecimalLike(s: String): Boolean {
    if (s.isBlank()) return false
    val t = s.trim()
    val regex = Regex("^-?\\d+(\\.\\d{1,2})?$")
    return regex.matches(t)
}

// =============================================================================
// 附件 SHA-256 hex 编码表（SA-3；与 AttachmentRepository HEX_CHARS 同口径）
// =============================================================================
/**
 * 小写 hex 编码表（16 字符）。
 * 用于 [FinanceViewModel.sha256HexOf] 把 [MessageDigest] 输出转 hex 字符串。
 */
private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()