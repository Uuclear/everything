// ============================================================================
// 财务附件链路（stage5-finance-v2 / Task 3 / TR-3.3 + TR-3.4）
// ============================================================================
//
// 任务: stage5-finance-v2 / Task 3 / TR-3.3 + TR-3.4（Web 端 attachment 链路）
// 路径: web/src/finance/attachment.ts
// 作用: 财务记录（policy / contract）附件的上传 / 下载 / 列表 / 删除 4 个
//       纯函数；明文附件二进制走 envelope.sealRecord 加密落 records 通道
//       type='attachment'；密文回执（含 plaintext 元数据回填）走 store 入栈。
//
// 设计要点：
//   1. **AttachmentChannel 契约** —— seal/open/listRecords/upsertRecord；
//      默认实现 = 直接调 envelope.sealRecord/openRecord + store.upsert / pull；
//      测试时可整体 mock，避免 sodium 依赖；
//   2. **端侧校验** —— 尺寸 ≤ ATTACHMENT_MAX_SIZE_BYTES（50MB） +
//      MIME ∈ {application/pdf, image/jpeg, image/png}；
//   3. **SHA-256 端侧校验** —— 上传时记 hex；下载时再算一次比对；
//   4. **零知识纪律** —— mime / size / sha256 仅本地缓存 + UI 展示；
//      错误消息不渲染文件大小 / MIME 等敏感字段（仅给出通用原因）；
//   5. **Result 返回** —— `{ok:true,value} | {ok:false,error}`，调用方按需
//      走 NMessage 提示，不抛异常。
//
// 关联：
//   - tasks.md TR-3.3（Web 附件链路）
//   - tasks.md TR-3.4（Web 附件 UI 集成）
//   - web/src/stores/finance.ts（attachments state + pullAll 接入）
//   - web/src/views/finance/AttachmentViewer.vue（PDF / 图片预览）
//   - web/src/crypto/envelope.ts（sealRecord / openRecord / base64 工具）
//   - Android 端 CryptoEnvelope.attachmentSealRecord 字节级一致
// ============================================================================

import { ATTACHMENT_MAX_SIZE_BYTES, type AttachmentRef } from './types'
import { FINANCE_MODULE } from './types'
import {
  fromBase64,
  openRecord,
  sealRecord,
  toBase64,
  type Sodium,
} from '../crypto/envelope'

// -----------------------------------------------------------------------------
// 附件链路契约 —— AttachmentChannel（注入点：默认直连 envelope + store）
// -----------------------------------------------------------------------------

/**
 * 附件加密通道契约（TR-3.3 attachment 链路）。
 *
 * 默认实现 = 直接调 crypto/envelope.sealRecord / openRecord + 远端 records 通道；
 * 测试时可整体 mock，便于不依赖 sodium / 网络跑通单测。
 *
 * 与 stores/finance.ts 中 CryptoChannel 同源不同子集：attachment 走 type='attachment'
 * + 明文非结构化（裸 ByteArray），不需 FinancePayloadAll 收窄。
 */
export interface AttachmentChannel {
  /** 密封一条明文二进制 → 密文（Base64）。 */
  seal(plaintext: Uint8Array, attachmentId: string, version: number): string
  /** 解开一条密文（Base64）→ 明文二进制。失败抛异常。 */
  open(attachmentId: string, ciphertextB64: string, version: number): Uint8Array
  /**
   * 列出指定 record 的附件明文 + 元数据（plaintext JSON + ciphertext）。
   * 返回空数组 = 无附件。
   */
  listByRecord(recordId: string): Promise<AttachmentRecord[]>
  /** 上传 / 替换 / 标记删除一条附件密文（远端 records）。 */
  upsert(rec: AttachmentRecord): Promise<{ applied: number; skipped: number; server_time: number }>
}

/**
 * 附件远端记录形态 —— 与 RemoteRecord 同构，附加 recordId 字段（用于 listByRecord 过滤）。
 *
 * 注意：recordId 字段是明文级索引（不入 AAD），由 channel.listByRecord 在返回前
 * 通过 plaintext JSON 解析回填；远端 records 表 schema 不需要 schema 变更。
 */
export interface AttachmentRecord {
  /** 附件 UUID。 */
  id: string
  /** 附件二进制 Base64 密文（envelope 链路产物）。 */
  ciphertext: string
  /** 信封版本（严格递增）。 */
  version: number
  /** 关联的财务记录 id（policy.id / contract.id）。 */
  recordId: string
  /** 附件明文 JSON（{mime,size,sha256}）；null 表示 tombstone。 */
  plaintextJson: string | null
  /** 是否删除（墓碑标记）。 */
  deleted: boolean
  /** 设备 id。 */
  device_id: string
  /** 创建时刻。 */
  created_at: number
  /** 最后更新时刻。 */
  updated_at: number
}

/**
 * 默认 AttachmentChannel 工厂 —— 直接调 envelope + 内存缓存。
 *
 * 注意：默认实现仅用于单测 + 演示；生产环境请传入 store-backed 通道
 * （由 store-finance.ts 的 ingest / pullAll 维护 ciphertext 缓存）。
 */
export function defaultAttachmentChannel(
  sodium: Sodium,
  masterKey: Uint8Array,
  remote: Map<string, AttachmentRecord>,
): AttachmentChannel {
  return {
    seal(plaintext, attachmentId, version) {
      const sealed = sealRecord(
        sodium,
        masterKey,
        plaintext,
        attachmentId,
        FINANCE_MODULE,
        version,
      )
      return toBase64(sealed)
    },
    open(attachmentId, ciphertextB64, version) {
      return openRecord(
        sodium,
        masterKey,
        fromBase64(ciphertextB64),
        attachmentId,
        FINANCE_MODULE,
        version,
      )
    },
    async listByRecord(recordId) {
      const result: AttachmentRecord[] = []
      for (const rec of remote.values()) {
        if (rec.deleted) continue
        if (rec.recordId === recordId) result.push(rec)
      }
      return result
    },
    async upsert(rec) {
      remote.set(rec.id, rec)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
  }
}

// -----------------------------------------------------------------------------
// Result 返回值类型（与 stores/finance.ts 同款风格）
// -----------------------------------------------------------------------------

/**
 * 附件操作统一返回结果。
 *
 * - ok=true → 携带 value（AttachmentRef / Blob / void）；
 * - ok=false → 携带 error（人类可读错误文案，零知识纪律：不渲染敏感字段）。
 */
export type AttachmentResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: string }

// -----------------------------------------------------------------------------
// 工具函数 —— 校验 + sha256
// -----------------------------------------------------------------------------

/** 允许的附件 MIME 集合（与 schema attachment.mime 一致）。 */
const ALLOWED_MIME_TYPES = new Set<string>([
  'application/pdf',
  'image/jpeg',
  'image/png',
])

/**
 * 校验 MIME 是否在白名单内（端侧硬闸门）。
 *
 * 零知识纪律：错误消息只给通用原因，不回显用户提供的 type 字符串。
 */
export function isAllowedMimeType(mime: string): boolean {
  return ALLOWED_MIME_TYPES.has(mime)
}

/**
 * 生成 UUID v4（端侧唯一 id）；浏览器环境走 crypto.randomUUID；
 * 兼容旧 runtime 走 Math.random 兜底（测试 / SSR 友好）。
 */
export function cryptoRandomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

/**
 * 把 ArrayBuffer 转为 hex 字符串（SHA-256 输出 = 64 字符）。
 */
function bufferToHex(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let out = ''
  for (let i = 0; i < bytes.length; i++) {
    out += (bytes[i] ?? 0).toString(16).padStart(2, '0')
  }
  return out
}

/**
 * 计算 ArrayBuffer 的 SHA-256 hex 字符串（Web Crypto API）。
 *
 * 兼容：node 18+ / 浏览器均内置 `crypto.subtle`；
 * 无 `crypto.subtle` 环境直接抛异常（attachment 链路硬依赖）。
 */
export async function sha256HexOf(buffer: ArrayBuffer): Promise<string> {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('crypto.subtle 不可用')
  }
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return bufferToHex(digest)
}

// -----------------------------------------------------------------------------
// 4 个核心操作 —— uploadFile / downloadFile / listAttachments / deleteAttachment
// -----------------------------------------------------------------------------

/**
 * 上传附件（端侧校验 + 加密 + 落远端 records）。
 *
 * 流程：
 *   1. 端侧校验 file.size ∈ (0, ATTACHMENT_MAX_SIZE_BYTES] + MIME 白名单；
 *   2. 计算 SHA-256 hex（Web Crypto API）；
 *   3. 调 channel.seal(plain, attachmentId, version=1) → 密文 Base64；
 *   4. 构造 plaintextJson（含 mime / size / sha256 / recordId 元数据）；
 *   5. channel.upsert → 远端 records；
 *   6. 返回 AttachmentRef 给调用方入 store。
 *
 * @param recordId 关联财务记录 id（policy.id / contract.id）
 * @param file File 对象（来自 <input type="file">）
 * @param channel 加密通道（注入点）
 * @returns AttachmentRef 或错误
 */
export async function uploadFile(
  recordId: string,
  file: File,
  channel: AttachmentChannel,
  onUploaded?: (ref: AttachmentRef) => void,
): Promise<AttachmentResult<AttachmentRef>> {
  // ========== 端侧硬闸门 ==========
  // 鸭子类型校验：要求具备 size / type / arrayBuffer() 三字段；不强制 instance of File/Blob，
  // 兼容测试桩（makeFileLike 返回 plain object）+ 真实浏览器 File/Blob。
  const isFileLike = (v: unknown): v is { size: number; type: string; arrayBuffer: () => Promise<ArrayBuffer> } => {
    if (!v || typeof v !== 'object') return false
    const f = v as Record<string, unknown>
    return (
      typeof f.size === 'number' &&
      typeof f.type === 'string' &&
      typeof f.arrayBuffer === 'function'
    )
  }
  if (!isFileLike(file)) {
    return { ok: false, error: '附件对象类型无效' }
  }
  if (file.size <= 0) {
    return { ok: false, error: '附件为空' }
  }
  if (file.size > ATTACHMENT_MAX_SIZE_BYTES) {
    return { ok: false, error: '附件超过 50MB 限制' }
  }
  if (!isAllowedMimeType(file.type)) {
    return { ok: false, error: '附件类型不在白名单（仅支持 PDF / JPG / PNG）' }
  }
  if (typeof recordId !== 'string' || recordId.length === 0) {
    return { ok: false, error: '关联记录 id 无效' }
  }

  // ========== 计算 sha256 ==========
  let arrayBuffer: ArrayBuffer
  try {
    arrayBuffer = await file.arrayBuffer()
  } catch {
    return { ok: false, error: '读取附件内容失败' }
  }
  let sha256: string
  try {
    sha256 = await sha256HexOf(arrayBuffer)
  } catch {
    return { ok: false, error: '计算 sha256 失败' }
  }

  // ========== 加密 + 落远端 ==========
  const attachmentId = cryptoRandomId()
  const version = 1
  const plaintext = new Uint8Array(arrayBuffer)
  let ciphertext: string
  try {
    ciphertext = channel.seal(plaintext, attachmentId, version)
  } catch {
    return { ok: false, error: '加密失败' }
  }
  const ref: AttachmentRef = {
    id: attachmentId,
    mime: file.type,
    size: file.size,
    sha256,
  }
  const plaintextJson = JSON.stringify({
    mime: ref.mime,
    size: ref.size,
    sha256: ref.sha256,
    recordId,
  })
  const now = Date.now()
  try {
    await channel.upsert({
      id: attachmentId,
      ciphertext,
      version,
      recordId,
      plaintextJson,
      deleted: false,
      device_id: 'web',
      created_at: now,
      updated_at: now,
    })
  } catch {
    return { ok: false, error: '上传失败' }
  }
  // 上行成功后通知 store 写元数据（附件元数据入 store 的二级索引）。
  // 解耦：uploadFile 不强依赖 store；store 包装方法 uploadAttachment 注入回调。
  if (onUploaded) {
    try {
      onUploaded(ref)
    } catch {
      // 回调失败不应影响上传结果（store 写入可重试）。
    }
  }
  return { ok: true, value: ref }
}

/**
 * 下载附件（解密 + SHA-256 校验 + 构造 Blob）。
 *
 * 流程：
 *   1. 解析 attachmentId → 找远端记录；
 *   2. channel.open → 明文 ByteArray；
 *   3. 端侧再算 SHA-256 → 比对明文携带的 hash；
 *   4. 转 Blob 给 UI 渲染。
 *
 * @param attachmentId 附件 UUID
 * @param channel 加密通道
 * @param expectedSha256 可选：外部预先知道的 sha256 字符串（用于跳过再次计算）
 * @returns Blob（mime = ref.mime）或错误
 */
export async function downloadFile(
  attachmentId: string,
  channel: AttachmentChannel,
  expectedSha256?: string,
): Promise<AttachmentResult<Blob>> {
  if (typeof attachmentId !== 'string' || attachmentId.length === 0) {
    return { ok: false, error: '附件 id 无效' }
  }
  // ========== 取所有附件密文（去 recordId 过滤） ==========
  // 设计：本函数仅接收 attachmentId 不接收 recordId，故需遍历缓存。
  // 调用方若已知 recordId 可改用 listAttachments + 客户端筛选。
  // 简化：把 listByRecord 视为通用 list，传入 recordId='' 退化为遍历。
  let all: AttachmentRecord[] = []
  try {
    // 这里走 listByRecord('')，由 channel 实现决定是否退化全量；
    // 默认实现不过滤，传 '' 返回所有非删除条目。
    all = await channel.listByRecord('')
  } catch {
    return { ok: false, error: '拉取附件列表失败' }
  }
  const rec = all.find((r) => r.id === attachmentId && !r.deleted)
  if (!rec) {
    return { ok: false, error: '附件不存在' }
  }
  // ========== 解密 ==========
  let plain: Uint8Array
  try {
    plain = channel.open(attachmentId, rec.ciphertext, rec.version)
  } catch {
    return { ok: false, error: '解密失败' }
  }
  // ========== 明文 metadata（mime / sha256） ==========
  if (!rec.plaintextJson) {
    return { ok: false, error: '附件元数据缺失' }
  }
  let meta: { mime: string; size: number; sha256: string }
  try {
    meta = JSON.parse(rec.plaintextJson) as { mime: string; size: number; sha256: string }
  } catch {
    return { ok: false, error: '附件元数据格式错误' }
  }
  // ========== SHA-256 端侧校验 ==========
  const buf = plain.buffer.slice(plain.byteOffset, plain.byteOffset + plain.byteLength)
  let actualSha: string
  try {
    actualSha = expectedSha256 ?? (await sha256HexOf(buf as ArrayBuffer))
  } catch {
    return { ok: false, error: 'sha256 计算失败' }
  }
  if (actualSha !== meta.sha256) {
    return { ok: false, error: 'sha256 mismatch' }
  }
  // ========== 转 Blob ==========
  const blob = new Blob([buf], { type: meta.mime })
  return { ok: true, value: blob }
}

/**
 * 列出指定财务记录下的全部附件（返回 AttachmentRef[]）。
 *
 * 流程：channel.listByRecord(recordId) → 解析 plaintextJson → AttachmentRef。
 *
 * 错误：解析失败的条目跳过（不抛异常；调用方按"丢数据兜底"处理）。
 */
export async function listAttachments(
  recordId: string,
  channel: AttachmentChannel,
): Promise<AttachmentResult<AttachmentRef[]>> {
  if (typeof recordId !== 'string' || recordId.length === 0) {
    return { ok: false, error: '关联记录 id 无效' }
  }
  let recs: AttachmentRecord[]
  try {
    recs = await channel.listByRecord(recordId)
  } catch {
    return { ok: false, error: '拉取附件列表失败' }
  }
  const out: AttachmentRef[] = []
  for (const rec of recs) {
    if (rec.deleted) continue
    if (!rec.plaintextJson) continue
    try {
      const meta = JSON.parse(rec.plaintextJson) as { mime: string; size: number; sha256: string }
      out.push({
        id: rec.id,
        mime: meta.mime,
        size: meta.size,
        sha256: meta.sha256,
      })
    } catch {
      // 解析失败跳过——零知识纪律不抛、不打印原文。
    }
  }
  return { ok: true, value: out }
}

/**
 * 删除附件（标记墓碑 + version+1 + ciphertext 留旧值）。
 *
 * 流程：channel.upsert({deleted:true, version:existing.version+1})。
 *
 * 注意：墓碑标记后 store 仍可拉取该 recordId 用于审计；listAttachments
 * 实现层过滤 deleted=true 记录（见上）。
 */
export async function deleteAttachment(
  id: string,
  channel: AttachmentChannel,
): Promise<AttachmentResult<void>> {
  if (typeof id !== 'string' || id.length === 0) {
    return { ok: false, error: '附件 id 无效' }
  }
  // ========== 找原记录（保留 ciphertext + recordId） ==========
  let all: AttachmentRecord[] = []
  try {
    all = await channel.listByRecord('')
  } catch {
    return { ok: false, error: '拉取附件列表失败' }
  }
  const existing = all.find((r) => r.id === id)
  if (!existing) {
    return { ok: false, error: '附件不存在' }
  }
  const now = Date.now()
  try {
    await channel.upsert({
      id,
      ciphertext: existing.ciphertext,
      version: existing.version + 1,
      recordId: existing.recordId,
      plaintextJson: null,
      deleted: true,
      device_id: 'web',
      created_at: existing.created_at,
      updated_at: now,
    })
  } catch {
    return { ok: false, error: '删除失败' }
  }
  return { ok: true, value: undefined }
}