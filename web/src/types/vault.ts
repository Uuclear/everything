// 密码库各类型的明文 JSON 约定（加密前的 payload；服务端只看到密文）。
// module 归属：
//   pass/login、pass/note、pass/card 属于密码库模块 pass；
//   identity/{id_card|passport|driver_license|generic} 属于证件模块 identity。
// 历史数据 module='note'（阶段 1 加密笔记）读取时按 note 兼容展示。

export type RecordKind = 'login' | 'note' | 'card' | 'identity'
export type IdentityKind = 'id_card' | 'passport' | 'driver_license' | 'generic'

/** login 项内嵌 TOTP 配置（RFC6238；与认证器 otpauth 对齐）。 */
export interface TotpConfig {
  secret: string // RFC4648 Base32
  issuer?: string
  digits?: number // 默认 6
  period?: number // 默认 30 秒
}

export interface LoginData {
  title: string
  username?: string
  password?: string
  urls?: string[]
  notes?: string
  totp?: TotpConfig
}

export interface NoteData {
  title: string
  body?: string
}

export interface CardData {
  title: string
  cardholder?: string
  number?: string // 完整卡号仅存在于密文内；列表只展示尾号
  exp_month?: number // 1-12
  exp_year?: number // 四位年
  cvv?: string
  notes?: string
}

export interface IdentityData {
  title: string
  kind: IdentityKind
  name?: string
  number?: string // 证件号码
  issuer?: string // 签发机构
  issued_on?: string // YYYY-MM-DD
  expires_on?: string // YYYY-MM-DD（到期提醒据此计算）
  notes?: string
}

export type VaultData = LoginData | NoteData | CardData | IdentityData

/**
 * 命名地点（阶段 4a，module=place）明文 payload。
 *
 * 记录 id 规则：`place:{geohash7(中心点)}`；幂等覆盖语义（同 id 重复命名时
 * version 递增覆盖，不重复建记录）。独立 placeRecords 缓存，不混入
 * pass/identity 列表（tasks.md Task 9；写入路径属 Task 10）。
 */
export interface PlaceData {
  /** 地点名称（"家"/"公司"/自定义文本）。 */
  name: string
  /** 分类（home/work/custom 等），可选。 */
  category?: string
  /** 中心纬度（WGS84 度）。 */
  center_lat: number
  /** 中心经度（WGS84 度）。 */
  center_lon: number
  /** 覆盖半径（米，约定 100）。 */
  radius_m: number
}

/** 解密后的内存记录（含信封元数据，供 version 递增编辑/墓碑删除）。 */
export interface DecryptedRecord {
  id: string
  module: string
  type: string
  version: number
  createdAt: number
  updatedAt: number
  deleted: boolean
  data: VaultData
}

/** kind → (module, type) 的规范映射；证件的子类型即记录 type（id_card 等）。 */
export function moduleTypeFor(
  kind: RecordKind,
  identityKind: IdentityKind = 'generic',
): { module: string; type: string } {
  if (kind === 'identity') return { module: 'identity', type: identityKind }
  return { module: 'pass', type: kind }
}

/** 根据远端 module/type 判定 UI 归类（兼容旧 note 模块）。 */
export function kindOf(module: string, type: string): RecordKind | null {
  if (module === 'pass') {
    if (type === 'login' || type === 'note' || type === 'card') return type
    return null
  }
  if (module === 'identity') {
    return type === 'id_card' || type === 'passport' || type === 'driver_license' ||
      type === 'generic'
      ? 'identity'
      : null
  }
  if (module === 'note') return 'note' // 阶段 1 历史数据
  return null
}

export const IDENTITY_KIND_LABELS: Record<IdentityKind, string> = {
  id_card: '身份证',
  passport: '护照',
  driver_license: '驾驶证',
  generic: '通用证件',
}
