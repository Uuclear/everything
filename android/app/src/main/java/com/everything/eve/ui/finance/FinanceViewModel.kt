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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.everything.eve.ServiceLocator
import com.everything.eve.data.finance.entity.FinanceAccountEntity
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.data.finance.entity.FinanceTxEntity
import com.everything.eve.finance.ContractRecord
import com.everything.eve.finance.FinanceAggregator
import com.everything.eve.finance.FinanceRecords
import com.everything.eve.finance.LoanRecord
import com.everything.eve.finance.PolicyRecord
import com.everything.eve.finance.SubscriptionRecord
import com.everything.eve.finance.ValidationResult
import com.everything.eve.finance.NextCardFiring
import com.everything.eve.reminder.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
class FinanceViewModel(app: Application) : AndroidViewModel(app) {

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
    // v2 子类型内存数据源（stage5-finance-v2 / Task 3 / TR-2.6）
    // ============================================================================
    // 4 个 v2 子类型尚未建 Room 表（B3 接入），此处用 MutableStateFlow 暂存
    // 内存数据；ViewModel 单例生命周期内有效。
    // TODO(B3): 替换为 FinanceSubscriptionDao.observeAll() / FinancePolicyDao.observeAll()
    //                 / FinanceLoanDao.observeAll() / FinanceContractDao.observeAll()
    // ============================================================================

    /** v2 subscription 内存列表。 */
    private val subscriptionsFlow = MutableStateFlow<List<SubscriptionRecord>>(emptyList())

    /** v2 policy 内存列表。 */
    private val policiesFlow = MutableStateFlow<List<PolicyRecord>>(emptyList())

    /** v2 loan 内存列表。 */
    private val loansFlow = MutableStateFlow<List<LoanRecord>>(emptyList())

    /** v2 contract 内存列表。 */
    private val contractsFlow = MutableStateFlow<List<ContractRecord>>(emptyList())

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
     * 顺序约定（仅文档约束，**严禁改变顺序** —— v1 调用方按索引取值）：
     *   [0] accounts    [4] sort        [8] subscriptions
     *   [1] cards       [5] filter      [9] policies
     *   [2] txs         [6] error       [10] loans
     *   [3] search                        [11] contracts
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

        val dashboard = FinanceAggregator.netWorth(
            accounts.map { acc ->
                FinanceAggregator.AccountLike(
                    id = acc.id, balance = acc.balance, currency = acc.currency, archived = acc.archived,
                )
            },
            cards.map { card ->
                FinanceAggregator.CardLike(
                    id = card.id, kind = card.kind, usedLimit = card.usedLimit, archived = card.archived,
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
                )
            },
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
                )
            },
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
     */
    fun saveBuffer(buffer: FinanceEditorBuffer) {
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
    // v2 子类型 CRUD（stage5-finance-v2 / Task 3 / TR-2.6）
    // ============================================================================
    // 4 个 v2 子类型（SubscriptionRecord / PolicyRecord / LoanRecord / ContractRecord）
    // 尚未建 Room 表（B3 接入），此处 upsert 仅更新内存 MutableStateFlow，
    // stateIn 自动推送新 state；delete 同理仅移除条目。
    // 校验：所有 upsert 前必须通过 FinanceRecords.validate*；失败返回 Result.failure。
    // 不持久化到 FinanceRepository —— 避免 v1 协议通道被 v2 数据污染。
    // ============================================================================

    /**
     * v2 subscription upsert（内存）。
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
        // TODO(B3): 持久化到 FinanceSubscriptionDao.upsert(...)，并触发 records 通道
        return Result.success(Unit)
    }

    /** v2 subscription delete（内存）。id 不存在视为成功（幂等）。 */
    fun deleteSubscription(id: String): Result<Unit> {
        val current = subscriptionsFlow.value
        subscriptionsFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        // TODO(B3): DAO.markDeleted(id) + records 通道 tombstone 上行
        return Result.success(Unit)
    }

    /** v2 policy upsert（内存）。 */
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
        // TODO(B3): 持久化到 FinancePolicyDao.upsert(...)
        return Result.success(Unit)
    }

    /** v2 policy delete（内存）。 */
    fun deletePolicy(id: String): Result<Unit> {
        val current = policiesFlow.value
        policiesFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        // TODO(B3): DAO.markDeleted(id)
        return Result.success(Unit)
    }

    /** v2 loan upsert（内存）。 */
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
        // TODO(B3): 持久化到 FinanceLoanDao.upsert(...)
        return Result.success(Unit)
    }

    /** v2 loan delete（内存）。 */
    fun deleteLoan(id: String): Result<Unit> {
        val current = loansFlow.value
        loansFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        // TODO(B3): DAO.markDeleted(id)
        return Result.success(Unit)
    }

    /** v2 contract upsert（内存）。 */
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
        // TODO(B3): 持久化到 FinanceContractDao.upsert(...)
        return Result.success(Unit)
    }

    /** v2 contract delete（内存）。 */
    fun deleteContract(id: String): Result<Unit> {
        val current = contractsFlow.value
        contractsFlow.value = current.filterNot { it.id == id }
        _eventChannel.trySend(FinanceUiEvent.DeleteSucceeded(id))
        // TODO(B3): DAO.markDeleted(id)
        return Result.success(Unit)
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