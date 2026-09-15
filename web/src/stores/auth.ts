import { defineStore } from 'pinia'
import {
  loadSodium,
  randomSalt,
  newMasterKey,
  deriveKey,
  wrapMasterKey,
  unwrapMasterKey,
  toBase64,
  fromBase64,
  type Sodium,
} from '../crypto/envelope'
import { api, clearTokens, setTokens } from '../api/client'

const USERNAME_KEY = 'eve.username'

interface AuthState {
  sodium: Sodium | null
  username: string
  masterKey: Uint8Array | null // 仅存内存：刷新页面需重新输入主密码
  userId: string
  deviceId: string
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    sodium: null,
    username: localStorage.getItem(USERNAME_KEY) ?? '',
    masterKey: null,
    userId: '',
    deviceId: '',
  }),
  getters: {
    unlocked: (s) => s.masterKey !== null,
  },
  actions: {
    async initCrypto() {
      if (!this.sodium) this.sodium = await loadSodium()
      return this.sodium
    },

    /** 新用户注册：客户端生成盐、主密钥，服务端只拿到派生验证器与包裹后的 MK。 */
    async register(username: string, password: string) {
      const sodium = await this.initCrypto()
      const authSalt = randomSalt(sodium)
      const kekSalt = randomSalt(sodium)
      const verifier = deriveKey(sodium, password, authSalt)
      const kek = deriveKey(sodium, password, kekSalt)
      const mk = newMasterKey(sodium)
      const wrapped = wrapMasterKey(sodium, kek, mk)

      const pair = await api.register({
        username,
        auth_salt: toBase64(authSalt),
        kek_salt: toBase64(kekSalt),
        auth_verifier: toBase64(verifier),
        wrapped_master_key: toBase64(wrapped),
        device_name: navigator.userAgent,
      })
      setTokens(pair.access_token, pair.refresh_token)
      this.username = username
      this.masterKey = mk
      this.userId = pair.user_id
      this.deviceId = pair.device_id
      localStorage.setItem(USERNAME_KEY, username)
    },

    /** 登录 + 解锁：取盐 → 派生验证器登录 → 用 KEK 解开主密钥。 */
    async login(username: string, password: string) {
      const sodium = await this.initCrypto()
      const params = await api.loginParams(username)
      const authSalt = fromBase64(params.auth_salt)
      const kekSalt = fromBase64(params.kek_salt)
      const verifier = deriveKey(sodium, password, authSalt)
      const bundle = await api.login({
        username,
        auth_verifier: toBase64(verifier),
        device_name: navigator.userAgent,
      })
      setTokens(bundle.access_token, bundle.refresh_token)
      const kek = deriveKey(sodium, password, kekSalt)
      this.masterKey = unwrapMasterKey(sodium, kek, fromBase64(bundle.wrapped_master_key))
      this.username = username
      this.userId = bundle.user_id
      this.deviceId = bundle.device_id
      localStorage.setItem(USERNAME_KEY, username)
    },

    lock() {
      this.masterKey = null
    },

    logout() {
      this.lock()
      clearTokens()
      this.userId = ''
      this.deviceId = ''
    },
  },
})
