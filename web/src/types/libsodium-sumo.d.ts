// libsodium-wrappers-sumo（完整版，含 Argon2id crypto_pwhash）不带类型声明，
// 复用标准版 @types/libsodium-wrappers 的全部类型（运行时 API 是超集）。
declare module 'libsodium-wrappers-sumo' {
  export * from 'libsodium-wrappers'
  import * as sodium from 'libsodium-wrappers'
  const _default: typeof sodium
  export default _default
}
