/*
 * ============================================================================
 * FinanceRoutes —— 财务模块路由常量（stage5-finance / Task 7 / TR-7.4 + stage5-finance-v2 / TR-2.5）
 * ============================================================================
 *
 * 设计要点：
 *   1. **与 4b AppNav.Routes 同款镜像**：单独 object 集中维护路由常量，
 *      避免后续 T10 主导航接入时散落在多个文件里。
 *   2. **path-pattern 显式参数**：账户/卡片/流水三类编辑器共用一个 route
 *      + path 参数 `kind` + 可选 `entityId`；导航时仅传 kind 与可选 id。
 *   3. **二级路由**：列表入口 `finance/{tab}` 用 tab=accounts / cards /
 *      txs 切换；编辑器入口 `finance/{tab}/editor?id={entityId}`。
 *   4. **v2 扩展（TR-2.5）**：新增 4 个订阅/保单/借款/合同子类型列表 tab
 *      + 4 个编辑器 kind 常量；命名风格与 v1 同款镜像（lowercase）。
 *
 * 关联：
 *   - tasks.md TR-7.4（FinanceScreen + 主导航入口）
 *   - tasks.md TR-2.5（v2 子类型路由常量扩展）
 *   - 4b AppNav.kt Routes 同款命名风格
 * ============================================================================
 */

package com.everything.eve.ui.finance

/**
 * Finance 模块路由常量集合（与 4b AppNav.Routes 同款风格）。
 *
 * 注：本 T7 任务不接入 AppNav 主导航（任务描述明确"FinanceScreen 可以编译，
 * main navigation 接入 is T10 scope"），但保留 Routes 常量为 T10 接入点。
 */
object FinanceRoutes {
    /** 财务主页（顶部 Tab + Dashboard + 列表）。 */
    const val ROOT = "finance"

    /** 财务主页 + 选中 tab（accounts / cards / txs / dashboard）。 */
    const val TAB = "finance/{tab}"

    /** 编辑器（kind = account / card / tx；id 可选，新建时省略）。 */
    const val EDITOR = "finance/{tab}/editor"

    /** 三个枚举 tab key。 */
    const val TAB_DASHBOARD = "dashboard"
    const val TAB_ACCOUNTS = "accounts"
    const val TAB_CARDS = "cards"
    const val TAB_TXS = "txs"

    /** 编辑器 kind（与 FinanceEditorKind 枚举共享命名）。 */
    const val EDITOR_ACCOUNT = "account"
    const val EDITOR_CARD = "card"
    const val EDITOR_TX = "tx"

    // ============================================================================
    // v2 子类型列表 tab 常量（stage5-finance-v2 / Task 3 / TR-2.5）
    // ============================================================================
    // 4 个 v2 子类型列表入口：与 FinanceRoutes.TAB 同款 path-pattern 镜像，
    // 导航时通过 FinanceRoutes.tabRoute("subscriptions") 拼接完整路由。
    // 命名风格：lowercase + tab_<type>，与 v1 既有 tab key 一致。
    // ============================================================================

    /** v2 订阅列表 tab key。 */
    const val TAB_SUBSCRIPTIONS = "subscriptions"

    /** v2 保单列表 tab key。 */
    const val TAB_POLICIES = "policies"

    /** v2 应收借款列表 tab key。 */
    const val TAB_LOANS = "loans"

    /** v2 合同/发票列表 tab key。 */
    const val TAB_CONTRACTS = "contracts"

    // ============================================================================
    // v2 子类型编辑器 kind 常量（stage5-finance-v2 / Task 3 / TR-2.5）
    // ============================================================================
    // 4 个 v2 子类型编辑器入口：与 FinanceRoutes.EDITOR_ACCOUNT 同款命名。
    // 编辑器加载时按 kind 字段路由到对应 EditorScreen。
    // ============================================================================

    /** v2 订阅编辑器 kind。 */
    const val EDITOR_SUBSCRIPTION = "subscription"

    /** v2 保单编辑器 kind。 */
    const val EDITOR_POLICY = "policy"

    /** v2 应收借款编辑器 kind。 */
    const val EDITOR_LOAN = "loan"

    /** v2 合同/发票编辑器 kind。 */
    const val EDITOR_CONTRACT = "contract"

    /**
     * 构造带 tab 参数的列表路由。
     *
     * @param tab accounts / cards / txs / dashboard。
     */
    fun tabRoute(tab: String): String = "$ROOT/$tab"

    /**
     * 构造带 tab + 可选 id 的编辑器路由。
     *
     * @param tab 当前列表 tab（编辑器用其做返回路径）。
     * @param entityId 编辑器对应的 entity id；新建传 null。
     */
    fun editorRoute(tab: String, entityId: String? = null): String {
        return if (entityId == null) "$ROOT/$tab/editor" else "$ROOT/$tab/editor?id=$entityId"
    }
}