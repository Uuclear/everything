// ============================================================================
// 密码库通用附件链路（stage2b-vault-attachments / W2）
// ============================================================================
//
// 从 finance/attachment 泛化：密封 AAD 的 module 由 parentModule 注入
//（identity / pass / finance 等），type 固定 attachment 子记录语义。
// 财务模块通过 finance/attachment.ts 薄封装保持 FINANCE_MODULE 默认行为。
// ============================================================================

import {
  fromBase64,
  openRecord,
  sealRecord,
  toBase64,
  type Sodium,
} from '../crypto/envelope'

/** 单附件元数据引用（与 finance.types AttachmentRef 同构）。 */
export interface AttachmentRef {
  id: string
  mime: string
  size: number
  sha256: string
}

/** 端侧单文件上限（50 MiB）。 */
export const ATTACHMENT_MAX_SIZE_BYTES = 50 * 1024 * 1024

/** records 通道附件子类型常量。 */
export const ATTACHMENT_RECORD_TYPE = 'attachment'

/**
 * 附件加密通道契约。
 *
 * seal/open 使用构造时绑定的 parentModule；测试可整体 mock。
 */
export interface AttachmentChannel {
  readonly parentModule: string
  seal(plaintext: Uint8Array, attachmentId: string, version: number): string
  open(attachmentId: string, ciphertextB64: string, version: number): Uint8Array
  listByRecord(recordId: string): Promise<AttachmentRecord[]>
  upsert(rec: AttachmentRecord): Promise<{ applied: number; skipped: number; server_time: number }>
}

/** 附件远端/内存记录形态（含父记录 id 索引）。 */
export interface AttachmentRecord {
  id: string
  ciphertext: string
  version: number
  recordId: string
  parentModule: string
  plaintextJson: string | null
  deleted: boolean
  device_id: string
  created_at: number
  updated_at: number
}

export type AttachmentResult<T> =
  | { ok: true; value: T }
  | { ok: false; error: string }

const ALLOWED_MIME_TYPES = new Set<string>([
  'application/pdf',
  'image/jpeg',
  'image/png',
  'image/webp',
])

/** MIME 白名单校验。 */
export function isAllowedMimeType(mime: string): boolean {
  return ALLOWED_MIME_TYPES.has(mime)
}

/** UUID v4（浏览器 crypto.randomUUID 优先）。 */
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

function bufferToHex(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer)
  let out = ''
  for (let i = 0; i < bytes.length; i++) {
    out += (bytes[i] ?? 0).toString(16).padStart(2, '0')
  }
  return out
}

/** 计算 SHA-256 hex（Web Crypto）。 */
export async function sha256HexOf(buffer: ArrayBuffer): Promise<string> {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('crypto.subtle 不可用')
  }
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return bufferToHex(digest)
}

/**
 * 默认内存 AttachmentChannel（单测 / 演示）。
 *
 * @param parentModule 父记录 module（identity / pass / finance）
 */
export function defaultAttachmentChannel(
  sodium: Sodium,
  masterKey: Uint8Array,
  parentModule: string,
  remote: Map<string, AttachmentRecord>,
): AttachmentChannel {
  return {
    parentModule,
    seal(plaintext, attachmentId, version) {
      const sealed = sealRecord(
        sodium,
        masterKey,
        plaintext,
        attachmentId,
        parentModule,
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
        parentModule,
        version,
      )
    },
    async listByRecord(recordId) {
      const result: AttachmentRecord[] = []
      for (const rec of remote.values()) {
        if (rec.deleted) continue
        if (recordId === '' || rec.recordId === recordId) result.push(rec)
      }
      return result
    },
    async upsert(rec) {
      remote.set(rec.id, rec)
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
  }
}

/** 构造附件明文 JSON（含 parent_ref_id，与 module-schemas §9.18 对齐）。 */
export function attachmentMetadataJson(
  ref: AttachmentRef,
  recordId: string,
): string {
  return JSON.stringify({
    mime: ref.mime,
    size: ref.size,
    sha256: ref.sha256,
    recordId,
    parent_ref_id: recordId,
  })
}

/**
 * 上传附件：校验 → sha256 → 密封二进制 → upsert。
 */
export async function uploadFile(
  recordId: string,
  file: File,
  channel: AttachmentChannel,
  onUploaded?: (ref: AttachmentRef) => void,
): Promise<AttachmentResult<AttachmentRef>> {
  const isFileLike = (
    v: unknown,
  ): v is { size: number; type: string; arrayBuffer: () => Promise<ArrayBuffer> } => {
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
    return { ok: false, error: '附件类型不在白名单（仅支持 PDF / JPG / PNG / WebP）' }
  }
  if (typeof recordId !== 'string' || recordId.length === 0) {
    return { ok: false, error: '关联记录 id 无效' }
  }

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
  const plaintextJson = attachmentMetadataJson(ref, recordId)
  const now = Date.now()
  try {
    await channel.upsert({
      id: attachmentId,
      ciphertext,
      version,
      recordId,
      parentModule: channel.parentModule,
      plaintextJson,
      deleted: false,
      device_id: 'web',
      created_at: now,
      updated_at: now,
    })
  } catch {
    return { ok: false, error: '上传失败' }
  }
  if (onUploaded) {
    try {
      onUploaded(ref)
    } catch {
      // 回调失败不影响上传结果
    }
  }
  return { ok: true, value: ref }
}

/** 下载并校验 sha256，返回 Blob。 */
export async function downloadFile(
  attachmentId: string,
  channel: AttachmentChannel,
  expectedSha256?: string,
): Promise<AttachmentResult<Blob>> {
  if (typeof attachmentId !== 'string' || attachmentId.length === 0) {
    return { ok: false, error: '附件 id 无效' }
  }
  let all: AttachmentRecord[] = []
  try {
    all = await channel.listByRecord('')
  } catch {
    return { ok: false, error: '拉取附件列表失败' }
  }
  const rec = all.find((r) => r.id === attachmentId && !r.deleted)
  if (!rec) {
    return { ok: false, error: '附件不存在' }
  }
  let plain: Uint8Array
  try {
    plain = channel.open(attachmentId, rec.ciphertext, rec.version)
  } catch {
    return { ok: false, error: '解密失败' }
  }
  if (!rec.plaintextJson) {
    return { ok: false, error: '附件元数据缺失' }
  }
  let meta: { mime: string; size: number; sha256: string }
  try {
    meta = JSON.parse(rec.plaintextJson) as { mime: string; size: number; sha256: string }
  } catch {
    return { ok: false, error: '附件元数据格式错误' }
  }
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
  const blob = new Blob([buf], { type: meta.mime })
  return { ok: true, value: blob }
}

/** 列出某父记录下的附件元数据。 */
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
      const meta = JSON.parse(rec.plaintextJson) as {
        mime: string
        size: number
        sha256: string
      }
      out.push({
        id: rec.id,
        mime: meta.mime,
        size: meta.size,
        sha256: meta.sha256,
      })
    } catch {
      // 跳过坏元数据
    }
  }
  return { ok: true, value: out }
}

/** 墓碑删除（version+1，保留旧 ciphertext）。 */
export async function deleteAttachment(
  id: string,
  channel: AttachmentChannel,
): Promise<AttachmentResult<void>> {
  if (typeof id !== 'string' || id.length === 0) {
    return { ok: false, error: '附件 id 无效' }
  }
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
      parentModule: existing.parentModule,
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
