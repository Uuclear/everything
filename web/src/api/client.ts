// 极简 API 客户端：同源 fetch；生产由 Go 托管，开发走 Vite 代理。
// 字节字段（盐/验证器/包裹密文/公钥）在 JSON 中一律标准 base64 字符串。
const TOKEN_KEY = 'eve.accessToken'
const REFRESH_KEY = 'eve.refreshToken'

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY)
}
export function setTokens(access: string, refresh: string) {
  localStorage.setItem(TOKEN_KEY, access)
  localStorage.setItem(REFRESH_KEY, refresh)
}
export function clearTokens() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_KEY)
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message)
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  /** false=不携带令牌；字符串=以该短期令牌覆盖默认 access（recovery/mfa 会话用）。 */
  auth?: boolean | string
  /** 内部标记：该请求已因 401 刷新并重试过一次，防止无限循环。 */
  _retried?: boolean
}

// 会话彻底失效（refresh 也失败）时的回调，由 AppShell 注册做清场与跳登录。
let authExpiredHandler: (() => void) | null = null
export function onAuthExpired(fn: () => void): void {
  authExpiredHandler = fn
}

// 并发请求共享同一次刷新（服务端虽不轮换 refresh token，仍避免风暴与竞态）。
let inflightRefresh: Promise<boolean> | null = null
function tryRefresh(): Promise<boolean> {
  if (!inflightRefresh) {
    inflightRefresh = (async () => {
      const rt = getRefreshToken()
      if (!rt) return false
      try {
        const pair = await rawFetch<TokenPair>('/auth/refresh', {
          method: 'POST',
          body: { refresh_token: rt },
        })
        setTokens(pair.access_token, pair.refresh_token)
        return true
      } catch {
        clearTokens()
        return false
      } finally {
        // 微任务后释放，保证同一时刻并发的 401 请求都能复用本次结果。
        setTimeout(() => {
          inflightRefresh = null
        }, 0)
      }
    })()
  }
  return inflightRefresh
}

/** 单次 fetch 封装（供 request 与刷新自身使用，避免循环依赖）。 */
async function rawFetch<T>(path: string, opts: RequestOptions): Promise<T> {
  const headers: Record<string, string> = {}
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (opts.auth !== false) {
    const token = typeof opts.auth === 'string' ? opts.auth : getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }
  const resp = await fetch(`/api/v1${path}`, {
    method: opts.method ?? 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })
  const text = await resp.text()
  const data = text ? JSON.parse(text) : {}
  if (!resp.ok) {
    throw new ApiError(resp.status, data.error ?? 'unknown', data.message ?? resp.statusText)
  }
  return data as T
}

export async function request<T = any>(path: string, opts: RequestOptions = {}): Promise<T> {
  try {
    return await rawFetch<T>(path, opts)
  } catch (err) {
    // 仅默认 access 令牌的鉴权 401（error=unauthorized）才自动刷新；
    // 业务 401（如 invalid_totp 验证码错误）、显式短期令牌（mfa/recovery/events）
    // 与匿名请求（auth:false）的错误原样抛给调用方处理。
    const canRefresh =
      err instanceof ApiError &&
      err.status === 401 &&
      err.code === 'unauthorized' &&
      opts.auth === undefined &&
      !opts._retried
    if (!canRefresh) throw err
    const ok = await tryRefresh()
    if (!ok) {
      authExpiredHandler?.()
      throw err
    }
    return rawFetch<T>(path, { ...opts, _retried: true })
  }
}

// ---- 认证 DTO ----

/** 登录参数：主密码盐 + 恢复盐与恢复包裹（恢复向导据此离线解 MK）。 */
export interface LoginParameters {
  auth_salt: string
  kek_salt: string
  wrapped_master_key: string
  recovery_auth_salt: string
  recovery_kek_salt: string
  wrapped_master_key_recovery: string
}

export interface TokenPair {
  access_token: string
  refresh_token: string
  expires_in: number
  token_type: string
  user_id: string
  device_id: string
}

export interface LoginBundle extends TokenPair {
  username: string
  auth_salt: string
  kek_salt: string
  wrapped_master_key: string
}

/** 配对信息（pending 登录与审批列表共用，字节字段为 base64）。 */
export interface PairingInfo {
  id: string
  device_id: string
  device_name: string
  device_public_key: string
  ephemeral_public_key?: string
  nonce?: string
  wrapped_master_key?: string
  state: 'pending' | 'approved' | 'rejected' | 'revoked' | 'expired'
  /** 毫秒级 Unix 时间戳（与服务端 pairing.go 的 UnixMilli 一致）。 */
  created_at: number
  /** 毫秒级 Unix 时间戳。 */
  expires_at: number
  responded_device_id?: string
  pairing_code: string
  fingerprint: string
}

export interface PendingLogin {
  access_token: string
  expires_in: number
  token_type: string
  user_id: string
  device_id: string
  pairing: PairingInfo
}

/** 登录多态响应：approved 直通 / pending 待审批 / mfa_required 需二次验证。 */
export interface LoginResponse {
  status: 'approved' | 'pending' | 'mfa_required'
  bundle?: LoginBundle
  pending?: PendingLogin
  mfa_token?: string
  expires_in?: number
}

/** 注册/重置/改密共用的主密码四元组与恢复四元组（字段名与服务端一致）。 */
export interface PasswordMaterialFields {
  auth_salt: string
  kek_salt: string
  auth_verifier: string
  wrapped_master_key: string
}
export interface RecoveryMaterialFields {
  recovery_auth_salt: string
  recovery_kek_salt: string
  recovery_verifier: string
  wrapped_master_key_recovery: string
}

export interface RegisterRequest extends PasswordMaterialFields, RecoveryMaterialFields {
  username: string
  device_name: string
  device_public_key: string
}

export interface RecoverySession {
  recovery_token: string
  expires_in: number
  token_type: string
  user_id: string
  recovery_kek_salt: string
  wrapped_master_key_recovery: string
}

export interface TOTPStatus {
  enabled: boolean
  has_secret: boolean
}
export interface TOTPSetup {
  secret: string
  otpauth_url: string
  qr_data_uri: string
  confirmed: boolean
}

export interface DeviceInfo {
  id: string
  name: string
  state: string
  fingerprint: string
  last_seen: number
  created_at: number
  current: boolean
}

export interface PairingStatus extends PairingInfo {
  /** approved 瞬间服务端换发的正式令牌对（其余状态缺省）。 */
  tokens?: TokenPair
}

export interface RemoteRecord {
  id: string
  module: string
  type: string
  ciphertext: string
  version: number
  device_id: string
  created_at: number
  updated_at: number
  deleted: boolean
}

export const api = {
  health: () => request<{ status: string; version: string }>('/health', { auth: false }),
  loginParams: (username: string) =>
    request<LoginParameters>(`/auth/parameters?username=${encodeURIComponent(username)}`, {
      auth: false,
    }),
  register: (body: RegisterRequest) =>
    request<TokenPair>('/auth/register', { method: 'POST', body, auth: false }),
  login: (body: {
    username: string
    auth_verifier: string
    device_name: string
    device_public_key: string
  }) => request<LoginResponse>('/auth/login', { method: 'POST', body, auth: false }),
  refresh: (refresh_token: string) =>
    request<TokenPair>('/auth/refresh', { method: 'POST', body: { refresh_token }, auth: false }),

  // 恢复与改密。
  recoveryStart: (username: string, recoveryVerifierB64: string) =>
    request<RecoverySession>('/auth/recovery/start', {
      method: 'POST',
      auth: false,
      body: { username, recovery_verifier: recoveryVerifierB64 },
    }),
  recoveryReset: (
    recoveryToken: string,
    body: PasswordMaterialFields &
      RecoveryMaterialFields & { device_public_key: string; device_name: string },
  ) =>
    request<TokenPair>('/auth/recovery/reset', {
      method: 'POST',
      auth: recoveryToken,
      body,
    }),
  changePassword: (
    body: Partial<RecoveryMaterialFields> &
      PasswordMaterialFields & { refresh_token?: string },
  ) => request<TokenPair>('/auth/password/change', { method: 'POST', body }),

  // TOTP。
  totpStatus: () => request<TOTPStatus>('/auth/totp'),
  totpSetup: () => request<TOTPSetup>('/auth/totp/setup', { method: 'POST' }),
  totpEnable: (code: string) =>
    request<{ enabled: boolean }>('/auth/totp/enable', { method: 'POST', body: { code } }),
  totpDisable: (code: string) =>
    request<{ enabled: boolean }>('/auth/totp/disable', { method: 'POST', body: { code } }),
  totpVerify: (
    mfaToken: string,
    body: { code: string; device_public_key: string; device_name: string },
  ) => request<LoginResponse>('/auth/totp/verify', { method: 'POST', auth: mfaToken, body }),

  // 设备与配对。
  listDevices: () => request<{ devices: DeviceInfo[] }>('/auth/devices'),
  revokeDevice: (id: string) =>
    request<{ status: string; device_id: string }>(`/auth/devices/${id}/revoke`, {
      method: 'POST',
    }),
  listPairings: () => request<{ pairings: PairingInfo[] }>('/auth/pairings'),
  approvePairing: (
    id: string,
    body: { ephemeral_public_key: string; nonce: string; wrapped_master_key: string },
  ) => request<PairingInfo>(`/auth/pairings/${id}/approve`, { method: 'POST', body }),
  rejectPairing: (id: string) =>
    request<{ status: string; device_id: string }>(`/auth/pairings/${id}/reject`, {
      method: 'POST',
    }),
  /** 待审批设备用自己的 pending access 令牌轮询（不能使用默认 access 存储）。 */
  pairingStatus: (pendingToken: string) =>
    request<PairingStatus>('/auth/pairing/status', { auth: pendingToken }),

  // SSE 短期事件令牌。
  eventsToken: () =>
    request<{ events_token: string; expires_in: number }>('/auth/events-token', {
      method: 'POST',
    }),

  // 资料库。
  listRecords: (since = 0, limit = 500) =>
    request<{ records: RemoteRecord[]; has_more: boolean }>(
      `/records?since=${since}&limit=${limit}`,
    ),
  pushRecords: (records: unknown[]) =>
    // server_time：服务端权威写入时间（毫秒），调用方用它校准本地增量游标（FU-1）。
    request<{ applied: number; skipped: number; server_time: number }>('/records/batch', {
      method: 'POST',
      body: { records },
    }),
}
