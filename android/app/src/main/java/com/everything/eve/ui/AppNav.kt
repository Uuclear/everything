package com.everything.eve.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.everything.eve.ui.finance.FinanceScreen
import com.everything.eve.ui.screens.CalendarScreen
import com.everything.eve.ui.screens.CollectorScreen
import com.everything.eve.ui.screens.DevicesScreen
import com.everything.eve.ui.screens.ItemDetailScreen
import com.everything.eve.ui.screens.ItemEditorScreen
import com.everything.eve.ui.screens.IdentitiesScreen
import com.everything.eve.ui.screens.IdentityEditorScreen
import com.everything.eve.ui.screens.ItemScannerScreen
import com.everything.eve.ui.screens.ItemsScreen
import com.everything.eve.ui.screens.ArchiveWallScreen
import com.everything.eve.ui.screens.VaultHomeScreen
import com.everything.eve.ui.screens.VaultScreen
import com.everything.eve.ui.screens.WelcomeScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

object Routes {
    const val WELCOME = "welcome"
    const val VAULT = "vault"
    const val DEVICES = "devices"
    const val COLLECTOR = "collector"
    // 阶段 4b Task 9 / TR-9.1：日历入口（沿用 4a 既有模式——TopAppBar actions TextButton，
    // 调用 nav.navigate(Routes.CALENDAR) 进入 CalendarScreen；与 COLLECTOR 同款镜像）。
    const val CALENDAR = "calendar"
    // 阶段 5 Task 10 / TR-10.1：财务入口（沿用 4b CALENDAR 同款镜像——TopAppBar actions
    // TextButton → onOpenFinance 回调 → nav.navigate(Routes.FINANCE) → FinanceScreen。
    // 字面量与 FinanceRoutes.ROOT 同源（均为 "finance"），保证主导航与模块内二级路由
    // 共享同一 namespace，避免出现两条不互通的 finance 路径）。
    const val FINANCE = "finance"
    // 阶段 5 items Task 9
    const val ITEMS = "items"
    const val ITEMS_SCAN = "items/scan"
    const val ITEM_DETAIL = "items/{itemId}"
    const val ITEM_EDITOR = "items/editor?itemId={itemId}"
    const val IDENTITIES = "identities"
    const val IDENTITY_EDITOR = "identities/editor?identityId={identityId}"
    const val VAULT_HOME = "vault/home"
    const val ARCHIVE_WALL = "vault/archive"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val start = if (com.everything.eve.ServiceLocator.auth.isLoggedIn) Routes.VAULT else Routes.WELCOME
    NavHost(nav, start) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onEntered = {
                nav.navigate(Routes.VAULT) {
                    popUpTo(Routes.WELCOME) { inclusive = true }
                }
            })
        }
        composable(Routes.VAULT) {
            VaultScreen(
                onLoggedOut = {
                    nav.navigate(Routes.WELCOME) {
                        popUpTo(Routes.VAULT) { inclusive = true }
                    }
                },
                onOpenDevices = { nav.navigate(Routes.DEVICES) },
                onOpenCollector = { nav.navigate(Routes.COLLECTOR) },
                // 阶段 4b Task 9 / TR-9.1：日历入口回调（沿用 4a 既有 onOpenCollector 模式镜像新增）。
                onOpenCalendar = { nav.navigate(Routes.CALENDAR) },
                // 阶段 5 Task 10 / TR-10.1：财务入口回调（沿用 4b CALENDAR 同款镜像新增；
                // 顶部"财务"TextButton → onOpenFinance → nav.navigate(Routes.FINANCE)）。
                onOpenFinance = { nav.navigate(Routes.FINANCE) },
                onOpenItems = { nav.navigate(Routes.ITEMS) },
                onOpenIdentities = { nav.navigate(Routes.IDENTITIES) },
                onOpenVaultHome = { nav.navigate(Routes.VAULT_HOME) },
                onOpenArchiveWall = { nav.navigate(Routes.ARCHIVE_WALL) },
            )
        }
        composable(Routes.VAULT_HOME) {
            VaultHomeScreen(
                onBack = { nav.popBackStack() },
                onOpenArchive = { nav.navigate(Routes.ARCHIVE_WALL) },
                onOpenIdentities = { nav.navigate(Routes.IDENTITIES) },
            )
        }
        composable(Routes.ARCHIVE_WALL) {
            ArchiveWallScreen(
                onBack = { nav.popBackStack() },
                onOpenIdentity = { id -> nav.navigate("identities/editor?identityId=$id") },
                onOpenFinance = { nav.navigate(Routes.FINANCE) },
            )
        }
        composable(Routes.DEVICES) {
            DevicesScreen(onBack = { nav.popBackStack() })
        }
        // 采集页仅登录后可达（与设备管理并列，未登录起点为 WELCOME）
        composable(Routes.COLLECTOR) {
            CollectorScreen(onBack = { nav.popBackStack() })
        }
        // 阶段 4b Task 9 / TR-9.1：日历页（与 4a COLLECTOR 同款镜像：登录后可达、popBackStack 返回）。
        // CalendarScreen 内部自行管理状态与 TopAppBar 返回，不需外部 onBack 参数（4b TR-6.3 已落盘签名）。
        composable(Routes.CALENDAR) {
            CalendarScreen()
        }
        // 阶段 5 Task 10 / TR-10.1：财务页（与 4b CALENDAR 同款镜像——登录后可达、共享
        // "finance" namespace；具体 Tab / 编辑器子路由由 FinanceScreen 内部维护，
        // 主导航仅需把 ROOT 入口接通即可。FinanceScreen 内部自行管理 TopAppBar 返回，
        // 无需外部 onBack 参数，与 4b CALENDAR 保持一致）。
        composable(Routes.FINANCE) {
            FinanceScreen()
        }
        composable(Routes.ITEMS) {
            ItemsScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { id -> nav.navigate("items/$id") },
                onOpenEditor = { id ->
                    if (id == null) nav.navigate("items/editor")
                    else nav.navigate("items/editor?itemId=$id")
                },
                onOpenScan = { nav.navigate(Routes.ITEMS_SCAN) },
            )
        }
        composable(
            route = Routes.ITEM_DETAIL,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("itemId") ?: return@composable
            ItemDetailScreen(
                itemId = id,
                onBack = { nav.popBackStack() },
                onEdit = { nav.navigate("items/editor?itemId=$id") },
                onDeleted = {
                    nav.popBackStack(Routes.ITEMS, false)
                },
            )
        }
        composable(
            route = Routes.ITEM_EDITOR,
            arguments = listOf(
                navArgument("itemId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("itemId")
            ItemEditorScreen(
                itemId = id,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.ITEMS_SCAN) {
            ItemScannerScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { id ->
                    nav.navigate("items/$id") {
                        popUpTo(Routes.ITEMS)
                    }
                },
            )
        }
        composable(Routes.IDENTITIES) {
            IdentitiesScreen(
                onBack = { nav.popBackStack() },
                onOpenEditor = { id ->
                    if (id == null) nav.navigate("identities/editor")
                    else nav.navigate("identities/editor?identityId=$id")
                },
            )
        }
        composable(
            route = Routes.IDENTITY_EDITOR,
            arguments = listOf(
                navArgument("identityId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("identityId")
            IdentityEditorScreen(
                identityId = id,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
    }
}
