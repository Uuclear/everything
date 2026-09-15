// 极简 API 客户端：同源 fetch；生产由 Go 托管，开发走 Vite 代理。
const TOKEN_KEY = 'eve.accessToken'
const REFRESH_KEY = 'eve.refreshToken'

export function getAccessToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
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
  auth?: boolean
}

export async function request<T = any>(path: string, opts: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (opts.auth !== false) {
    const token = getAccessToken()
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

// ---- 认证 DTO ----

export interface LoginParameters {
  auth_salt: string
  kek_salt: string
  wrapped_master_key: string
}

export interface TokenPair {
  access_token: string
  refresh_token: string
  expires_in: number
  user_id: string
  device_id: string
}

export interface LoginBundle extends TokenPair {
  username: string
  auth_salt: string
  kek_salt: string
  wrapped_master_key: string
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
  register: (body: Record<string, unknown>) =>
    request<TokenPair>('/auth/register', { method: 'POST', body, auth: false }),
  login: (body: Record<string, unknown>) =>
    request<LoginBundle>('/auth/login', { method: 'POST', body, auth: false }),
  listRecords: (since = 0, limit = 500) =>
    request<{ records: RemoteRecord[]; has_more: boolean }>(
      `/records?since=${since}&limit=${limit}`,
    ),
  pushRecords: (records: unknown[]) =>
    request<{ applied: number; skipped: number }>('/records/batch', {
      method: 'POST',
      body: { records },
    }),
}
