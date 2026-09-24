package com.everything.eve

import android.content.Context
import com.everything.eve.api.EveApi
import com.everything.eve.auth.AuthManager
import com.everything.eve.data.EveDatabase
import com.everything.eve.data.RecordsRepository
import com.everything.eve.data.event.EventsRepository
import com.everything.eve.data.finance.AttachmentRepository
import com.everything.eve.data.finance.FinanceRepository
import com.everything.eve.data.finance.QuoteTableRepository
import com.everything.eve.data.finance.RateTableRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 极简手动依赖容器；服务器地址可在登录页修改后整体重建 API 客户端。
 *
 * OkHttp 拦截链：
 *  1. 请求未自带 Authorization（recovery/mfa/pending 调用会显式传入短期令牌）时，
 *     注入当前 access token；
 *  2. 响应 401 且不是刷新请求本身：用 refresh token 单飞换发后重试一次；
 *     刷新也失败则维持 401，由 UI 引导重新登录。
 */
object ServiceLocator {

    lateinit var auth: AuthManager
        private set
    lateinit var repo: RecordsRepository
        private set
    lateinit var eventsRepo: EventsRepository
        private set
    // 阶段 5 Task 4：财务模块仓库（明文 4 表 + records 通道复用，仅搭骨架）
    lateinit var financeRepo: FinanceRepository
        private set
    // 阶段 5 v2 / TR-3.2：财务附件仓库（policy / contract 挂的合同扫描件 / 保单 PDF）；
    // 独立于 financeRepo，单独走 AttachmentDao + records.type="attachment" 通道。
    lateinit var attachmentRepo: AttachmentRepository
        private set
    // 阶段 5 v2 / B5：离线汇率包仓库（finance_rate 本地拆行表 + records.type="rate" 通道）。
    lateinit var rateTableRepository: RateTableRepository
        private set
    // 阶段 5 v2 / Task 8：投资行情包仓库（finance_quote 本地拆行表 + records.type="quote" 通道）。
    lateinit var quoteTableRepository: QuoteTableRepository
        private set
    lateinit var db: EveDatabase
        private set
    lateinit var collector: com.everything.eve.collector.CollectorEngine
        private set
    lateinit var locationPackager: com.everything.eve.collector.location.LocationPackager
        private set
    lateinit var locationUploader: com.everything.eve.collector.location.LocationUploader
        private set

    @Volatile
    lateinit var api: EveApi
        private set

    @Volatile
    private var baseUrl: String = ""

    @Volatile
    private var refreshing: Boolean = false

    /** 最近一次刷新的成败；新刷新轮次开始时置 null，等待方据此判定。 */
    @Volatile
    private var lastRefreshOk: Boolean? = null

    fun init(appContext: Context) {
        val ctx = appContext.applicationContext
        auth = AuthManager.create(ctx)
        val db = EveDatabase.build(ctx)
        this.db = db
        repo = RecordsRepository(db.recordDao(), auth)
        // 阶段 4b Task 4：日程/日历模块仓库（明文 event 表 + records 密文通道复用）
        eventsRepo = EventsRepository(db.eventDao(), repo)
        // 阶段 5 Task 4：财务模块仓库骨架（明文 4 表 + records 密文通道复用，
        // T6/T11 才真正编排上行；不同步上行逻辑）。
        financeRepo = FinanceRepository(
            accountDao = db.financeAccountDao(),
            cardDao = db.financeCardDao(),
            txDao = db.financeTxDao(),
            reminderLogDao = db.financeReminderLogDao(),
            recordsRepository = repo,
        )
        // 阶段 5 v2 / TR-3.2：财务附件仓库（独立 AttachmentDao + records 通道复用）；
        // CollectorWorker 末尾调用 pullAndDecrypt + pushChanges 调度上行下行。
        attachmentRepo = AttachmentRepository(
            attachmentDao = db.attachmentDao(),
            recordsRepository = repo,
            auth = auth,
        )
        // 阶段 5 v2 / B5：离线汇率包仓库（FinanceRateDao 拆行表 + records 通道复用）；
        // CollectorWorker 末尾调 pullAndDecrypt + pushChanges 对账。
        rateTableRepository = RateTableRepository(
            rateDao = db.financeRateDao(),
            recordsRepository = repo,
            auth = auth,
        )
        // 阶段 5 v2 / Task 8：投资行情包仓库（QuoteTableDao 拆行表 + records.type="quote" 通道）；
        // CollectorWorker 末尾同样调 pullAndDecrypt + pushChanges 对账。
        quoteTableRepository = QuoteTableRepository(
            quoteDao = db.quoteTableDao(),
            recordsRepository = repo,
            auth = auth,
        )
        collector = com.everything.eve.collector.CollectorEngine(
            appContext = ctx,
            auth = auth,
            recordDao = db.recordDao(),
            stateDao = db.collectorStateDao(),
        )
        // 阶段 4a：明文缓冲 → 密文 outbox 封块编排（MK 取自 auth 内存态）
        locationPackager = com.everything.eve.collector.location.LocationPackager(
            appContext = ctx,
            auth = auth,
            locationDao = db.locationDao(),
            moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        )
        // 阶段 4a Task 7：密文 outbox → 服务端月表上行编排（不依赖 MK）
        locationUploader = com.everything.eve.collector.location.LocationUploader(
            appContext = ctx,
            locationDao = db.locationDao(),
        )
        rebuildApi(auth.serverUrl)
    }

    fun setServer(url: String) {
        auth.serverUrl = url
        rebuildApi(url)
    }

    private fun rebuildApi(rawUrl: String) {
        val root = rawUrl.trimEnd('/')
        baseUrl = "$root/api/v1/"

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                // 显式携带短期令牌（recovery/mfa/pending）的请求不再注入正式令牌。
                val authed = if (original.header("Authorization") != null) {
                    original
                } else {
                    val token = auth.accessToken()
                    if (token != null) {
                        original.newBuilder().header("Authorization", "Bearer $token").build()
                    } else original
                }

                var resp = chain.proceed(authed)
                val isRefreshCall = authed.url.encodedPath.endsWith("/auth/refresh")
                if (resp.code == 401 && !isRefreshCall && auth.refreshToken() != null) {
                    // 单飞刷新：并发请求只触发一次 /auth/refresh，其余等待同一结果。
                    val ok = synchronized(this) {
                        if (refreshing) {
                            var waited = 0
                            while (refreshing && waited < 3_000) {
                                Thread.sleep(50)
                                waited += 50
                            }
                            lastRefreshOk == true
                        } else {
                            refreshing = true
                            lastRefreshOk = null
                            try {
                                runBlocking { auth.tryRefresh() }.also { lastRefreshOk = it }
                            } finally {
                                refreshing = false
                            }
                        }
                    }
                    if (ok) {
                        resp.close()
                        val token = auth.accessToken()
                        val retry = if (token != null) {
                            authed.newBuilder().header("Authorization", "Bearer $token").build()
                        } else authed
                        resp = chain.proceed(retry)
                    }
                }
                resp
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(EveApi::class.java)
    }
}
