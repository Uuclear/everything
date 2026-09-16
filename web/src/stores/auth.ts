import { defineStore } from 'pinia'
import {
  loadSodium,
  randomSalt,
  newMasterKey,
  deriveKey,
  wrapMasterKey,
  unwrapMasterKey,
  wrapForRecovery,
  unwrapForRecovery,
  toBase64,
  fromBase64,
  type Sodium,
} from '../crypto/envelope'
import {
  crockfordEncode,
  normalizeRecoveryCode,
  formatRecoveryCode,
} from '../crypto/crockford'
import {
  loadOrCreateDeviceIdentity,
  boxSeal,
  boxOpen,
  type DeviceIdentity,
} from '../crypto/device-box'
import {
  api,
  clearTokens,
  setTokens,
  ApiError,
  type LoginBundle,
  type LoginResponse,
  type PairingInfo,
  type PairingStatus,
  type PasswordMaterialFields,
  type RecoveryMaterialFields,
} from '../api/client'

const USERNAME_KEY = 'eve.username'

/** 一次注册/恢复轮换产出的恢复密钥材料（原始码只在此刻出现，服务端永不接触）。 */
export interface GeneratedRecovery {
  code: string // 32 字符大写无分隔（派生时使用）
  formatted: string // 4 组×8 展示用
  fields: RecoveryMaterialFields
}

interface AuthState {
  sodium: Sodium | null
  username: string
  masterKey: Uint8Array | null // 仅存内存：刷新页面必须重新解锁
  userId: string
  deviceId: string
  device: DeviceIdentity | null // 设备私钥仅存内存；seed 在 localStorage
  // 登录中间态（三态登录的 pending / mfa 分支）。
  pendingAccess: string
  pendingPairing: PairingInfo | null
  mfaToken: string
  loginDeviceName: string
  /**
   * MFA 会话期间内存保留的主密码（仅用于 verify 通过后解 KEK 包裹，不落盘）。
   * 风险边界：与 MK 同属内存敏感数据，5 分钟 mfa 会话结束/完成后立即清空。
   */
  mfaPassword: string
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    sodium: null,
    username: localStorage.getItem(USERNAME_KEY) ?? '',
    masterKey: null,
    userId: '',
    deviceId: '',
    device: null,
    pendingAccess: '',
    pendingPairing: null,
    mfaToken: '',
    loginDeviceName: '',
    mfaPassword: '',
  }),
  getters: {
    unlocked: (s) => s.masterKey !== null,
  },
  actions: {
    async initCrypto() {
      if (!this.sodium) {
        this.sodium = await loadSodium()
        // 载入（或首次生成）本机设备 X25519 身份。
        this.device = loadOrCreateDeviceIdentity(this.sodium)
      }
      return this.sodium
    },

    // ---- 材料构造（客户端零知识协议的核心）----

    /** 为主密码构造 auth 验证器 + KEK 包裹 MK 四元组。 */
    buildPasswordMaterials(password: string, mk: Uint8Array): PasswordMaterialFields {
      const sodium = this.sodium!
      const authSalt = randomSalt(sodium)
      const kekSalt = randomSalt(sodium)
      const verifier = deriveKey(sodium, password, authSalt)
      const kek = deriveKey(sodium, password, kekSalt)
      const wrapped = wrapMasterKey(sodium, kek, mk)
      return {
        auth_salt: toBase64(authSalt),
        kek_salt: toBase64(kekSalt),
        auth_verifier: toBase64(verifier),
        wrapped_master_key: toBase64(wrapped),
      }
    },

    /** 随机生成恢复码并构造恢复验证器 + REK 包裹 MK 四元组。 */
    generateRecovery(mk: Uint8Array): GeneratedRecovery {
      const sodium = this.sodium!
      const raw = sodium.randombytes_buf(20)
      const code = crockfordEncode(raw)
      const fields = this.buildRecoveryMaterials(code, mk)
      return { code, formatted: formatRecoveryCode(code), fields }
    },

    /** 由用户输入的恢复码（任意大小写/连字符）构造恢复四元组。 */
    buildRecoveryMaterials(inputCode: string, mk: Uint8Array): RecoveryMaterialFields {
      const sodium = this.sodium!
      const code = normalizeRecoveryCode(inputCode)
      const authSalt = randomSalt(sodium)
      const kekSalt = randomSalt(sodium)
      const verifier = deriveKey(sodium, code, authSalt)
      const rek = deriveKey(sodium, code, kekSalt)
      const wrapped = wrapForRecovery(sodium, rek, mk)
      return {
        recovery_auth_salt: toBase64(authSalt),
        recovery_kek_salt: toBase64(kekSalt),
        recovery_verifier: toBase64(verifier),
        wrapped_master_key_recovery: toBase64(wrapped),
      }
    },

    /** 新用户注册：生成 MK/恢复码/设备密钥，服务端只拿到派生值与密文。 */
    async register(username: string, password: string): Promise<GeneratedRecovery> {
      const sodium = await this.initCrypto()
      const mk = newMasterKey(sodium)
      const recovery = this.generateRecovery(mk)
      const pair = await api.register({
        username,
        ...this.buildPasswordMaterials(password, mk),
        ...recovery.fields,
        device_name: navigator.userAgent,
        device_public_key: toBase64(this.device!.publicKey),
      })
      setTokens(pair.access_token, pair.refresh_token)
      this.username = username
      this.masterKey = mk
      this.userId = pair.user_id
      this.deviceId = pair.device_id
      localStorage.setItem(USERNAME_KEY, username)
      // 恢复码仅这一次返回给 UI 强制离线备份，store 不落盘、不保留。
      return recovery
    },

    /**
     * 密码登录（可能的第二因素/设备审批由调用方按返回状态接续）：
     *   approved → 已解 MK 并落令牌；
     *   pending  → 存 pendingAccess/pendingPairing，等待审批（finishPairing）；
     *   mfa_required → 存 mfaToken，等待 TOTP（finishMfa）。
     */
    async login(username: string, password: string): Promise<LoginResponse> {
      const sodium = await this.initCrypto()
      // 开新登录前先清空上一轮可能残留的审批/MFA 临时会话，避免面板闪现。
      this.pendingAccess = ''
      this.pendingPairing = null
      this.mfaToken = ''
      this.mfaPassword = ''
      const params = await api.loginParams(username)
      const verifier = deriveKey(sodium, password, fromBase64(params.auth_salt))
      const result = await api.login({
        username,
        auth_verifier: toBase64(verifier),
        device_name: navigator.userAgent,
        device_public_key: toBase64(this.device!.publicKey),
      })
      this.username = username
      localStorage.setItem(USERNAME_KEY, username)
      this.loginDeviceName = navigator.userAgent

      if (result.status === 'approved' && result.bundle) {
        this.applyApprovedBundle(result.bundle, password)
      } else if (result.status === 'pending' && result.pending) {
        this.pendingAccess = result.pending.access_token
        this.pendingPairing = result.pending.pairing
        this.mfaToken = ''
      } else if (result.status === 'mfa_required') {
        this.mfaToken = result.mfa_token ?? ''
        // verify 通过后 approved 分支仍需密码派生 KEK 解 MK；仅在内存保留到会话结束。
        this.mfaPassword = password
        this.pendingAccess = ''
        this.pendingPairing = null
      }
      return result
    },

    /** approved 直通：落令牌并用密码派生 KEK 解开 MK。 */
    applyApprovedBundle(bundle: LoginBundle, password: string) {
      const sodium = this.sodium!
      setTokens(bundle.access_token, bundle.refresh_token)
      const kek = deriveKey(sodium, password, fromBase64(bundle.kek_salt))
      this.masterKey = unwrapMasterKey(sodium, kek, fromBase64(bundle.wrapped_master_key))
      this.userId = bundle.user_id
      this.deviceId = bundle.device_id
    },

    /**
     * MFA 第二步：提交 TOTP 码，结果仍可能 approved/pending（设备分支）。
     * 错码时服务端保留 mfa 会话（受限流约束），本方法抛 ApiError 且不清空状态，
     * UI 可停留在入码页重试。
     *
     * 注意：成功时【不能】在此清空 mfaToken——本方法返回后 MfaPanel 还要
     * emit('resolved') 通知父组件跳转；若这里先清空，await 恢复时 Vue 会先
     * flush 卸载面板（v-else-if="mfaToken" 变 false），而 Vue 对已卸载组件的
     * emit 静默丢弃，父组件将收不到 approved 事件导致无法进入资料库。
     * 清理动作统一由 MfaPanel 在 emit 之后调用 consumeMfa() 完成。
     */
    async finishMfa(code: string): Promise<LoginResponse> {
      const result = await api.totpVerify(this.mfaToken, {
        code,
        device_public_key: toBase64(this.device!.publicKey),
        device_name: this.loginDeviceName || navigator.userAgent,
      })
      if (result.status === 'approved' && result.bundle) {
        // 已批准设备：用登录时内存保留的主密码派生 KEK 解开 MK。
        this.applyApprovedBundle(result.bundle, this.mfaPassword)
      } else if (result.status === 'pending' && result.pending) {
        // TOTP 通过但设备是新设备：继续走审批，MK 将由审批盒端到端下发（无需密码）。
        this.pendingAccess = result.pending.access_token
        this.pendingPairing = result.pending.pairing
      }
      return result
    },

    /**
     * MFA 验证成功（approved/pending）后，由 MfaPanel 在 emit('resolved')
     * 【之后】调用：销毁临时 mfa 会话与内存主密码。必须晚于 emit 的原因见 finishMfa。
     */
    consumeMfa() {
      this.mfaToken = ''
      this.mfaPassword = ''
    },

    /**
     * 待审批设备轮询状态。approved 时用审批端密封的 crypto_box 开箱 MK，
     * 换发正式令牌并解锁；rejected/expired 抛出供 UI 提示。
     *
     * 与 finishMfa 同理：本方法【不能】在此清空 pendingAccess——返回后
     * PendingPanel 要 emit('unlocked') 通知父组件跳转，先清空会导致面板在
     * await 恢复时被卸载，Vue 丢弃已卸载组件的 emit。approved 时状态随路由
     * 切换自然失效；rejected/expired/令牌过期由面板自行调 rejectPairingState()。
     */
    async finishPairing(): Promise<PairingStatus> {
      // pairing/status 只接受 pending access（显式传入，不动 localStorage 中的正式令牌）。
      const st = await api.pairingStatus(this.pendingAccess)
      if (st.state === 'approved' && st.tokens && st.ephemeral_public_key && st.nonce) {
        const sodium = this.sodium!
        const mk = boxOpen(
          sodium,
          {
            sealed: fromBase64(st.wrapped_master_key!),
            nonce: fromBase64(st.nonce),
            ephemeralPublicKey: fromBase64(st.ephemeral_public_key),
          },
          fromBase64(st.ephemeral_public_key),
          this.device!.secretKey,
        )
        setTokens(st.tokens.access_token, st.tokens.refresh_token)
        this.masterKey = mk
        this.userId = st.tokens.user_id
        this.deviceId = st.tokens.device_id
      }
      return st
    },

    /** 审批端：用待审批设备公钥密封 MK 并批准。 */
    async approvePairing(pairingId: string, devicePublicKeyB64: string): Promise<void> {
      const sodium = this.sodium!
      const box = boxSeal(sodium, this.masterKey!, fromBase64(devicePublicKeyB64))
      await api.approvePairing(pairingId, {
        ephemeral_public_key: toBase64(box.ephemeralPublicKey),
        nonce: toBase64(box.nonce),
        wrapped_master_key: toBase64(box.sealed),
      })
    },

    // ---- 忘记主密码恢复 ----

    /**
     * 恢复向导第一步：校验恢复码（服务端常量时间比对，失败与用户不存在同形）。
     * 成功后客户端离线用 REK 解开 MK；返回 recovery 会话供 reset。
     */
    async recoveryStart(
      username: string,
      inputCode: string,
    ): Promise<{ token: string; mk: Uint8Array }> {
      const sodium = await this.initCrypto()
      const code = normalizeRecoveryCode(inputCode)
      const params = await api.loginParams(username)
      const verifier = deriveKey(sodium, code, fromBase64(params.recovery_auth_salt))
      const session = await api.recoveryStart(username, toBase64(verifier))
      // 零知识：MK 由客户端用恢复码派生的 REK 离线解开，服务端全程不可见。
      const rek = deriveKey(sodium, code, fromBase64(params.recovery_kek_salt))
      const mk = unwrapForRecovery(
        sodium,
        rek,
        fromBase64(params.wrapped_master_key_recovery),
      )
      return { token: session.recovery_token, mk }
    },

    /** 恢复第二步：设置新主密码 + 强制轮换新恢复码，reset 后以新设备身份登录。 */
    async recoveryReset(
      recoveryToken: string,
      mk: Uint8Array,
      newPassword: string,
      username: string,
    ): Promise<GeneratedRecovery> {
      const recovery = this.generateRecovery(mk) // 旧恢复码立即作废，必须展示新码
      const pair = await api.recoveryReset(recoveryToken, {
        ...this.buildPasswordMaterials(newPassword, mk),
        ...recovery.fields,
        device_public_key: toBase64(this.device!.publicKey),
        device_name: navigator.userAgent,
      })
      setTokens(pair.access_token, pair.refresh_token)
      this.masterKey = mk
      this.userId = pair.user_id
      this.deviceId = pair.device_id
      // 恢复可能发生在另一账户曾登录过的设备（同源残留旧用户名），必须同步覆盖，
      // 否则锁屏/顶栏会显示旧账户名（数据本身是新账户的，造成误导）。
      this.username = username
      localStorage.setItem(USERNAME_KEY, username)
      return recovery
    },

    /**
     * 已解锁设备修改主密码：用当前 MK 重新包裹；可选同时轮换恢复码。
     * 服务端会吊销其他设备会话，返回的新令牌对立即生效。
     */
    async changePassword(
      newPassword: string,
      rotateRecovery: boolean,
      currentRefreshToken?: string,
    ): Promise<GeneratedRecovery | null> {
      if (!this.masterKey) throw new Error('未解锁，不能修改主密码')
      const body: Record<string, unknown> = {
        ...this.buildPasswordMaterials(newPassword, this.masterKey),
      }
      let recovery: GeneratedRecovery | null = null
      if (rotateRecovery) {
        recovery = this.generateRecovery(this.masterKey)
        Object.assign(body, recovery.fields)
      }
      if (currentRefreshToken) body.refresh_token = currentRefreshToken
      const pair = await api.changePassword(body as any)
      setTokens(pair.access_token, pair.refresh_token)
      this.deviceId = pair.device_id
      return recovery
    },

    rejectPairingState() {
      this.pendingAccess = ''
      this.pendingPairing = null
    },

    /** 放弃 TOTP 验证：销毁内存主密码与 mfa 会话，回到账号密码表单。 */
    rejectMfa() {
      this.mfaToken = ''
      this.mfaPassword = ''
    },

    lock() {
      this.masterKey = null
    },

    logout() {
      this.lock()
      clearTokens()
      this.userId = ''
      this.deviceId = ''
      this.pendingAccess = ''
      this.pendingPairing = null
      this.mfaToken = ''
      this.mfaPassword = ''
    },
  },
})

// 让 ApiError 类型在 UI 层可经 store 模块导入（统一错误处理入口）。
export { ApiError }
