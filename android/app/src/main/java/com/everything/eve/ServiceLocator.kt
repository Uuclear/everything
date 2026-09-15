package com.everything.eve

import android.content.Context
import com.everything.eve.api.EveApi
import com.everything.eve.auth.AuthManager
import com.everything.eve.data.EveDatabase
import com.everything.eve.data.RecordsRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/** 极简手动依赖容器；服务器地址可在登录页修改后整体重建 API 客户端。 */
object ServiceLocator {

    lateinit var auth: AuthManager
        private set
    lateinit var repo: RecordsRepository
        private set

    @Volatile
    lateinit var api: EveApi
        private set

    @Volatile
    private var baseUrl: String = ""

    fun init(appContext: Context) {
        val ctx = appContext.applicationContext
        auth = AuthManager.create(ctx)
        val db = EveDatabase.build(ctx)
        repo = RecordsRepository(db.recordDao(), auth)
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
                val builder = chain.request().newBuilder()
                auth.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
                chain.proceed(builder.build())
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
