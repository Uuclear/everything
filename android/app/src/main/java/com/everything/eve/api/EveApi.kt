package com.everything.eve.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface EveApi {
    @GET("health")
    suspend fun health(): Map<String, String?>

    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenPair

    @GET("auth/parameters")
    suspend fun loginParams(@Query("username") username: String): LoginParams

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): LoginBundle

    @GET("records")
    suspend fun listRecords(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 500,
    ): SyncResponse

    @POST("records/batch")
    suspend fun pushRecords(@Body req: BatchRequest): BatchResult
}
