package com.everything.eve.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 全部 REST 端点。基址由 ServiceLocator 按用户填写的服务器地址构造（…/api/v1/）。
 *
 * 令牌策略：常规请求由 OkHttp 拦截器自动注入当前 access token；
 * recovery/mfa/pending 三类短期会话用显式 [authHeader]（"Bearer xxx"）覆盖，
 * 传 null 时回落到拦截器里的正式令牌（不应发生，保留防御）。
 */
interface EveApi {

    @GET("health")
    suspend fun health(): Map<String, String?>

    // ---- 账户与认证 ----

    @POST("auth/register")
    suspend fun register(@Body req: RegisterRequest): TokenPair

    @GET("auth/parameters")
    suspend fun loginParams(@Query("username") username: String): LoginParams

    @POST("auth/login")
    suspend fun login(@Body req: LoginRequest): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body req: Map<String, String>): TokenPair

    @POST("auth/recovery/start")
    suspend fun recoveryStart(@Body req: RecoveryStartRequest): RecoverySession

    @POST("auth/recovery/reset")
    suspend fun recoveryReset(
        @Header("Authorization") authHeader: String,
        @Body req: RecoveryResetRequest,
    ): TokenPair

    @POST("auth/totp/verify")
    suspend fun totpVerify(
        @Header("Authorization") authHeader: String,
        @Body req: TotpVerifyRequest,
    ): LoginResponse

    @POST("auth/password/change")
    suspend fun changePassword(@Body req: ChangePasswordRequest): TokenPair

    // ---- 设备与配对 ----

    @GET("auth/devices")
    suspend fun listDevices(): DeviceListResponse

    @POST("auth/devices/{id}/revoke")
    suspend fun revokeDevice(@Path("id") id: String): Map<String, String?>

    @GET("auth/pairings")
    suspend fun listPairings(): PairingListResponse

    @POST("auth/pairings/{id}/approve")
    suspend fun approvePairing(
        @Path("id") id: String,
        @Body req: ApprovePairingRequest,
    ): PairingInfo

    @POST("auth/pairings/{id}/reject")
    suspend fun rejectPairing(@Path("id") id: String): Map<String, String?>

    /** 待审批设备轮询自身配对状态（使用 pending access 短期令牌）。 */
    @GET("auth/pairing/status")
    suspend fun pairingStatus(@Header("Authorization") authHeader: String): PairingStatusResponse

    // ---- 记录 ----

    @GET("records")
    suspend fun listRecords(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 500,
    ): SyncResponse

    @POST("records/batch")
    suspend fun pushRecords(@Body req: BatchRequest): BatchResult

    // ---- 位置轨迹（阶段 4a Task 7）----

    /** 批量上行轨迹密文块（≤50 块/批）；user_id/device_id 由服务端从 claims 注入。 */
    @POST("locations/batch")
    suspend fun uploadLocationBlocks(@Body req: LocationBatchRequest): LocationBatchResult
}
