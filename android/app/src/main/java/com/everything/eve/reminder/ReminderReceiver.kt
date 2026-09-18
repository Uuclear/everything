/*
 * 阶段 4b + 阶段 5 — 闹钟触发接收器（tasks.md Task 5 / Task 6 / TR-5.3 + TR-6.2）。
 *
 * 职责（spec FR-5 / AC-5 / 阶段 5 FR-4 / TR-6.2）：
 *  1) 接收 PendingIntent 广播（请求码 REMINDER_REQUEST_CODE，单闹钟链式）；
 *  2) 按 `module` 字段路由分支：
 *     - `module="event"`：4b 既有逻辑；拉 EventDao 渲染"title + N 分钟后开始"通知；
 *     - `module="finance"`：5 T6 新增；拉 FinanceCardDao，按 `ref_kind` 渲染
 *       抽象通知文案（账单 / 还款）；不渲染金额/卡号后四位/具体日期数字。
 *  3) 链式：触发后调 `ReminderScheduler.rebuildChain` 算下一全局最小触发点
 *     → 注册下一闹钟。
 *
 * 通知权限降级（spec FR-6 / TR-6.2）：
 *  - API 33+ 用户拒绝 POST_NOTIFICATIONS → 不弹横幅；仅写
 *    event_reminder_log kind=notification_denied；
 *  - channelId = "events"（与日程通知共用 channel；阶段 5 不新建 channel，
 *    减少 channel 管理负担）；首次启动时一次性创建。
 *
 * 零知识红线（spec NFR-1 / FR-5 / 阶段 5 FR-4）：
 *  - 通知文案**仅**渲染抽象文本（title + "即将开始" / "账单已生成" / "还款临近"），
 *    **不渲染** start_ts 数值、note 原文、金额数字、卡号后四位、具体日期数字；
 *  - log 仅存 event_id + occurrence_ts + kind（事件）/ ref_id + ref_kind +
 *    fire_at + delivered（财务）；不写 title 原文、不写金额 / 卡号后四位；
 *  - 异常捕获后静默降级，不崩溃（避免国产 ROM 杀进程时弹窗）。
 *
 * 模块分支（阶段 5 TR-6.2 / B4）：
 *  - 缺失 `module` extras → 走事件分支兜底（向后兼容 4b 已注册的旧闹钟）；
 *  - `module="finance"` 且 `ref_kind` 为 v2 启用值（订阅续费 / 保单到期 /
 *    借款到期）→ B4 新增：查 records 行、解密 decode、校验状态后渲染抽象文案；
 *  - `module="finance"` 但 `ref_kind` 不在启用集合 → 静默忽略，不弹通知；
 *  - `module="finance"` 但卡片 / v2 记录已删除（getById=null）→
 *    静默忽略，仅触发链式 rebuildChain（避免悬挂 PendingIntent）。
 */

package com.everything.eve.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.everything.eve.R
import com.everything.eve.ServiceLocator
import com.everything.eve.data.finance.FinanceModule
import com.everything.eve.data.finance.entity.FinanceCardEntity
import com.everything.eve.finance.V2PayloadCodec
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * 闹钟触发接收器（全局单闹钟 requestCode = REMINDER_REQUEST_CODE）。
 *
 * Manifest 注册（TR-5.5）：
 *  - `android:exported="false"`（仅应用内 PendingIntent 触发，不接收外部广播）；
 *  - 4a 既有的 BootReceiver（exported=true，仅接收 BOOT_COMPLETED 系统广播）
 *    不受影响。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return

        // 阶段 5 TR-6.2：拉取模块分支标识；缺省按事件分支处理（向后兼容 4b）。
        val module = intent.getStringExtra(EXTRA_MODULE).orEmpty().ifEmpty { MODULE_EVENT }
        val refKind = intent.getStringExtra(EXTRA_REF_KIND).orEmpty()
        val refId = intent.getStringExtra(EXTRA_EVENT_ID).orEmpty()
        val occurrenceTs = intent.getLongExtra(EXTRA_OCCURRENCE_TS, 0L)

        val appCtx = context.applicationContext

        // 确保通知 channel 已创建（API 26+；minSdk=26 兜底）。幂等：重复
        // createNotificationChannel 系统会自动忽略同名 channel。
        ensureChannel(appCtx)

        // 模块分支路由（阶段 5 TR-6.2）
        when (module) {
            MODULE_FINANCE -> {
                // 财务模块分支：拉 FinanceCardDao 按 ref_kind 渲染抽象通知
                handleFinanceModule(appCtx, refId, refKind)
            }
            else -> {
                // 事件模块分支（4b 既有逻辑，含 MODULE_EVENT 与 module="" 兜底）
                handleEventModule(appCtx, refId, refKind, occurrenceTs)
            }
        }

        // 链式：重算全局 nextTrigger → rebuildChain 注册下一闹钟（spec FR-5 / TR-5.3）。
        try {
            runBlocking {
                ReminderScheduler.rebuildChain(appCtx)
            }
        } catch (e: Exception) {
            // 链式调度失败不阻塞通知展示（best-effort）。
        }
    }

    // -------------------------------------------------------------------------
    // 事件模块分支（4b 既有逻辑；阶段 5 沿用不变）
    // -------------------------------------------------------------------------

    /**
     * 事件模块分支（4b 既有；阶段 5 沿用不变）。
     *
     * 流程：
     *  1) 拉事件明文（仅 title 渲染通知；不进 log）；
     *  2) 渲染通知文案（title + "N 分钟后开始"或"即将开始"）；
     *  3) 通知权限被拒 → 写 event_reminder_log kind=notification_denied。
     *
     * @param eventId 事件 UUID。
     * @param occurrenceTs 已注册闹钟的触发时刻（写 log 用）。
     */
    private fun handleEventModule(
        appCtx: Context,
        eventId: String,
        refKind: String, // 事件分支不使用；占位以匹配 handleFinanceModule 签名
        occurrenceTs: Long,
    ) {
        if (eventId.isEmpty()) return

        // 拉取事件明文（仅渲染通知；不进 log / 不进 SharedPreferences）。
        val title = try {
            runBlocking {
                ServiceLocator.eventsRepo.getById(eventId)?.title
            }
        } catch (e: Exception) {
            null
        } ?: DEFAULT_NOTIFICATION_TITLE

        // 通知文案：title + 距开始分钟数（不渲染 occurrence_ts / note 原文）。
        val leadMinutes = computeLeadMinutes(occurrenceTs, eventId)
        val contentText = if (leadMinutes == 0L) {
            // reminders[0]=0 触发 → 抽象文案"即将开始"（不渲染任何时刻数字）
            appCtx.getString(R.string.reminder_now_text)
        } else if (leadMinutes > 0) {
            appCtx.getString(R.string.reminder_lead_text, leadMinutes.toInt())
        } else {
            // 数据缺失时仍走"即将开始"文案（spec FR-5 文案纪律：不渲染任何时刻数字）
            appCtx.getString(R.string.reminder_now_text)
        }

        // 通知权限检查（API 33+）：被拒时不弹横幅，仅写 log（spec FR-6）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                postNotification(appCtx, eventId, title, contentText)
            } else {
                logNotificationDenied(appCtx, eventId, occurrenceTs)
            }
        } else {
            // API <33：默认有通知权限（用户在系统设置关闭的边缘场景由 Android 框架静默吞掉）
            try {
                postNotification(appCtx, eventId, title, contentText)
            } catch (e: SecurityException) {
                logNotificationDenied(appCtx, eventId, occurrenceTs)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 财务模块分支（阶段 5 TR-6.2 / TR-6.3）
    // -------------------------------------------------------------------------

    /**
     * 财务模块分支（阶段 5 TR-6.2；B4 扩展路由）。
     *
     * 流程：
     *  1) 按 ref_kind 路由：v2 三类（订阅 / 保单 / 借款）转
     *     [handleV2FinanceModule]；卡两类继续下方既有路径；
     *  2) 拉卡片明文（仅 last4 字段存在；用于**判断卡片是否存在**而非渲染文案）；
     *  3) 卡片已删 / 已归档 / 非信用卡 → 静默忽略（不弹通知；链式仍触发）；
     *  4) 按 ref_kind 分发到具体文案：
     *     - `card_statement_due` → "信用卡账单已生成，点击查看"（抽象）；
     *     - `card_payment_due` → "信用卡还款日临近，点击查看"（抽象）；
     *     - 其他 ref_kind → 静默忽略，不弹通知；
     *  5) 通知文案**绝不渲染**：金额数字、卡号后四位、具体日期数字。
     *
     * @param cardId 卡片 / v2 记录的 UUID。
     * @param refKind 提醒种类（启用集合见 [ENABLED_FINANCE_KINDS]）。
     */
    private fun handleFinanceModule(
        appCtx: Context,
        cardId: String,
        refKind: String,
    ) {
        if (cardId.isEmpty()) return
        // 仅处理启用集合内的 ref_kind；未知值静默忽略（防御性）。
        if (refKind !in ENABLED_FINANCE_KINDS) return

        // B4：v2 三类走 records 通道分支（订阅续费 / 保单到期 / 借款到期）。
        if (refKind == REF_KIND_SUBSCRIPTION_RENEWAL ||
            refKind == REF_KIND_POLICY_EXPIRY ||
            refKind == REF_KIND_LOAN_DUE
        ) {
            handleV2FinanceModule(appCtx, cardId, refKind)
            return
        }

        // 拉卡片明文（仅 last4 字段；用于校验卡片是否已被删除 / 归档）
        val card: FinanceCardEntity? = try {
            runBlocking {
                ServiceLocator.financeRepo.cardDao.getById(cardId)
            }
        } catch (e: Exception) {
            null
        }

        // 卡片已删 / 已归档 → 静默忽略（避免悬挂通知；链式 rebuildChain 仍触发）
        if (card == null || card.archived) return
        // 非信用卡不触发（nextCardFiring 已在调度时过滤；此处双保险）
        if (card.kind != CARD_KIND_CREDIT) return

        // 文案渲染（零知识红线：仅抽象文案 + 跳转，不渲染金额 / 卡号后四位 / 日期数字）
        val title = appCtx.getString(R.string.finance_reminder_title)
        val contentText = when (refKind) {
            REF_KIND_CARD_STATEMENT_DUE ->
                appCtx.getString(R.string.finance_reminder_statement_due_text)
            REF_KIND_CARD_PAYMENT_DUE ->
                appCtx.getString(R.string.finance_reminder_payment_due_text)
            else -> return // 其他 ref_kind 静默忽略（防御性）
        }

        // 通知 id 用 cardId.hashCode（同一卡片的多次提醒覆盖同一槽位；不同
        // 卡片并行展示——避免通知栏被同一卡片堆满）。
        val notificationId = cardId.hashCode()

        // 通知权限检查（API 33+）：被拒时不弹横幅，仅写 log。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                postNotificationById(appCtx, notificationId, title, contentText)
            } else {
                logFinanceNotificationDenied(appCtx, cardId, refKind)
            }
        } else {
            try {
                postNotificationById(appCtx, notificationId, title, contentText)
            } catch (e: SecurityException) {
                logFinanceNotificationDenied(appCtx, cardId, refKind)
            }
        }
    }

    /**
     * v2 财务记录提醒分支（B4）：订阅续费 / 保单到期 / 借款到期。
     *
     * 流程：
     *  1) recordDao.getById(refId) 取行；缺失 / module≠finance / deleted=true → 静默 return；
     *  2) [RecordsRepository.decryptFinanceV2] 解密 + [V2PayloadCodec] 对应 decode；
     *     解密 / 解析任一失败 → 静默 return（避免崩溃）；
     *  3) 状态校验（与 NextCardFiring 纯函数同语义，二次防漂移）：
     *     - subscription：type=subscription 且 active=true；
     *     - policy：type=policy 且 active=true；
     *     - loan：type=loan 且 status≠"paid"；
     *  4) 渲染抽象文案（零知识红线：不渲染金额 / 日期 / 对手方 / 保单号 / 名称）；
     *     notificationId=refId.hashCode()；权限拒绝复用
     *     [logFinanceNotificationDenied]（refKind 透传）。
     *
     * @param refId v2 记录 UUID（records 表主键）。
     * @param refKind 提醒种类（[REF_KIND_SUBSCRIPTION_RENEWAL] /
     *   [REF_KIND_POLICY_EXPIRY] / [REF_KIND_LOAN_DUE]）。
     */
    private fun handleV2FinanceModule(
        appCtx: Context,
        refId: String,
        refKind: String,
    ) {
        // 1) 取行：缺失 / 非 finance / 已删除 → 静默忽略（链式 rebuildChain 仍触发）。
        val entity = try {
            runBlocking { ServiceLocator.db.recordDao().getById(refId) }
        } catch (e: Exception) {
            null
        } ?: return
        if (entity.module != MODULE_FINANCE || entity.deleted) return

        // 2) 解密 + 按 kind 对应 decode；3) 同时校验 type 与启用 / 结清状态。
        //    任一步异常（MK 未解锁 / JSON 损坏等）→ 静默 return。
        val stillRelevant: Boolean = try {
            // decryptFinanceV2 为非 suspend open 函数（与卡分支 openRecord
            // 同步解密同构）；直接调用即可，这里统一包在 try 内。
            val json = ServiceLocator.repo.decryptFinanceV2(entity)
            when (refKind) {
                REF_KIND_SUBSCRIPTION_RENEWAL ->
                    entity.type == FinanceModule.TYPE_SUBSCRIPTION &&
                        V2PayloadCodec.decodeSubscription(json).active
                REF_KIND_POLICY_EXPIRY ->
                    entity.type == FinanceModule.TYPE_POLICY &&
                        V2PayloadCodec.decodePolicy(json).active
                REF_KIND_LOAN_DUE ->
                    entity.type == FinanceModule.TYPE_LOAN &&
                        V2PayloadCodec.decodeLoan(json).status != "paid"
                else -> false
            }
        } catch (e: Exception) {
            false
        }
        if (!stillRelevant) return

        // 4) 抽象文案渲染（title 与卡分支共用；正文 3 条新文案）。
        val title = appCtx.getString(R.string.finance_reminder_title)
        val contentText = when (refKind) {
            REF_KIND_SUBSCRIPTION_RENEWAL ->
                appCtx.getString(R.string.finance_reminder_subscription_renewal_due_text)
            REF_KIND_POLICY_EXPIRY ->
                appCtx.getString(R.string.finance_reminder_policy_expiry_due_text)
            REF_KIND_LOAN_DUE ->
                appCtx.getString(R.string.finance_reminder_loan_due_due_text)
            else -> return
        }

        // 同一 v2 记录的多次提醒覆盖同一通知槽位。
        val notificationId = refId.hashCode()

        // 通知权限检查（API 33+）：被拒时不弹横幅，仅写 finance_reminder_log。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                postNotificationById(appCtx, notificationId, title, contentText)
            } else {
                logFinanceNotificationDenied(appCtx, refId, refKind)
            }
        } else {
            try {
                postNotificationById(appCtx, notificationId, title, contentText)
            } catch (e: SecurityException) {
                logFinanceNotificationDenied(appCtx, refId, refKind)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 通知发送辅助（事件 / 财务共用 channel "events"；NotificationManagerCompat 调用）
    // -------------------------------------------------------------------------

    /**
     * 创建通知 channel "events"（API 26+；幂等）。
     *
     * Importance = HIGH：触发时刻到点即弹横幅（spec FR-5 通知语义）。
     * description 由 strings.xml 提供，集中文案维护。
     */
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            ctx.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.reminder_channel_description)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * 发送通知（事件分支）。
     *
     * 通知 id 用 eventId 的 hashCode（同一事件的多次提醒覆盖同一通知槽；不同
     * 事件并行展示——避免通知栏被同一事件堆满）。
     */
    private fun postNotification(
        ctx: Context,
        eventId: String,
        title: String,
        contentText: String,
    ) {
        val notification = NotificationCompat.Builder(ctx, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(eventId.hashCode(), notification)
    }

    /**
     * 发送通知（财务分支；阶段 5 TR-6.2）。
     *
     * 与事件分支共用 channel "events"；通知 id 由调用方显式传入（事件用
     * eventId.hashCode；财务用 cardId.hashCode）。
     */
    private fun postNotificationById(
        ctx: Context,
        notificationId: Int,
        title: String,
        contentText: String,
    ) {
        val notification = NotificationCompat.Builder(ctx, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(notificationId, notification)
    }

    // -------------------------------------------------------------------------
    // 日志写入辅助（event_reminder_log / finance_reminder_log）
    // -------------------------------------------------------------------------

    /**
     * 写一条 notification_denied 日志（spec FR-6；事件分支）。
     */
    private fun logNotificationDenied(ctx: Context, eventId: String, occurrenceTs: Long) {
        try {
            runBlocking {
                ServiceLocator.db.eventReminderLogDao().insertRaw(
                    eventId = eventId,
                    occurrenceTs = occurrenceTs,
                    kind = "notification_denied",
                    createdTs = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            // 日志写入失败静默吞掉，不阻塞通知路径。
        }
    }

    /**
     * 写一条 notification_denied 日志（财务分支；阶段 5 TR-6.2）。
     *
     * 注：finance_reminder_log 表的 insertRaw 方法由 T4 已落地（TR-6.3）；
     * 本方法仅写"通知权限被拒"语义，delivered=false。
     */
    private fun logFinanceNotificationDenied(ctx: Context, cardId: String, refKind: String) {
        try {
            runBlocking {
                ServiceLocator.db.financeReminderLogDao().insertRaw(
                    refId = cardId,
                    refKind = refKind,
                    fireAt = System.currentTimeMillis(),
                    delivered = false,
                )
            }
        } catch (e: Exception) {
            // 日志写入失败静默吞掉。
        }
    }

    // -------------------------------------------------------------------------
    // 辅助：渲染文案用"距开始分钟数"计算（仅事件分支使用）
    // -------------------------------------------------------------------------

    /**
     * 渲染文案用"距开始分钟数"计算（抽象；不暴露原始毫秒）。
     *
     * 实现：再读一次 event + reminders_json（闹钟触发本身是低频事件；
     * 每事件一次 IO 性能可接受）。reminders=[0] 时返回 0 → "即将开始"。
     *
     * @return 距开始分钟数（reminders[0]=0 时为 0）；数据缺失时返回 -1 → 走"即将开始"文案。
     */
    private fun computeLeadMinutes(occurrenceTs: Long, eventId: String): Long {
        return try {
            val entity = runBlocking { ServiceLocator.eventsRepo.getById(eventId) }
                ?: return -1L
            val reminders = try {
                val arr = JSONArray(entity.reminders_json)
                IntArray(arr.length()) { arr.getInt(it) }
            } catch (e: Exception) {
                return -1L
            }
            if (reminders.isEmpty()) return -1L
            // reminders[0] = 实际触发的"提前分钟数"（因为我们注册的闹钟 ts =
            // start_ts - r*60_000，r 即对应 lead minutes）。
            // 由于 reminders 数组本身可含多个值，这里用"已注册闹钟 ts 与 start_ts
            // 偏差"反推 leadMinutes：偏差 = (start_ts - occurrenceTs) / 60_000。
            val diffMs = entity.start_ts - occurrenceTs
            if (diffMs <= 0) 0L else diffMs / 60_000L
        } catch (e: Exception) {
            -1L
        }
    }

    companion object {
        /** ReminderScheduler 同步常量（与 buildPendingIntent 一致）。 */
        const val ACTION_FIRE = "com.everything.eve.reminder.FIRE"
        // EXTRA_EVENT_ID / EXTRA_OCCURRENCE_TS / EXTRA_MODULE / EXTRA_REF_KIND
        // 由 ReminderScheduler 同名常量提供；此处直接复用。
        private const val DEFAULT_NOTIFICATION_TITLE = "日程提醒"

        /**
         * 阶段 5 已启用的财务 ref_kind 集合（TR-6.2 卡两类 + B4 v2 三类）。
         *
         * 集合外的 ref_kind 收到闹钟时静默忽略，不弹通知（防御性，避免误渲染）。
         */
        private val ENABLED_FINANCE_KINDS: Set<String> = setOf(
            REF_KIND_CARD_STATEMENT_DUE,
            REF_KIND_CARD_PAYMENT_DUE,
            REF_KIND_SUBSCRIPTION_RENEWAL,
            REF_KIND_POLICY_EXPIRY,
            REF_KIND_LOAN_DUE,
        )
    }
}