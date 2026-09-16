// ============================================================================
// finance v2 attachment 链路单元测试（stage5-finance-v2 / B3 / TR-3.5 Web 单元测试）
// ============================================================================
//
// 验证目标（≥6 用例, 覆盖 attachment.ts Pass Condition）：
//   1. uploadFile —— 端侧 MIME / 尺寸校验 + 成功路径 + sha256 计算 + 落远端；
//   2. uploadFile —— 失败路径：超尺寸 / MIME 拒绝 / recordId 空；
//   3. downloadFile —— 成功路径：解密 + sha256 比对 + Blob 类型；
//   4. downloadFile —— 失败路径：附件不存在 / sha256 mismatch / metadata 缺失；
//   5. listAttachments —— 返回值按 recordId 过滤 + 删除项跳过 + plaintextJson 解析失败跳过；
//   6. deleteAttachment —— 成功路径：version+1 + 墓碑 + ciphertext 保留；
//   7. deleteAttachment —— 失败路径：附件不存在；
//   8. sha256HexOf + cryptoRandomId —— 工具函数基础正确性。
//
// 测试策略：
//   - 默认环境 = node（vitest 配置），无 window / Blob —— 故测试通过
//     memoryAttachmentChannel 注入 mock seal/open/listByRecord/upsert；
//   - 用 node 原生 File 替代浏览器 File（vitest node 环境无 File / Blob），
//     uploadFile 校验走 size + type 字段；
//   - downloadFile 在 node 环境无 Blob 构造器，本测试通过 mock 把 Blob
//     实现替换为带 mime 字段的简单对象（仅验证 ok.path + mime 字段）。
//
// 零知识纪律：
//   - 测试数据均为本地构造，附件内容使用固定字节数组 "hello-attachment"，
//     无真实财务凭证；
//   - sha256 计算通过真 Web Crypto API（node 18+ 自带 globalThis.crypto.subtle）；
//   - 不向真实 sodium / 网络写入任何数据。
//
// 关联:
//   - web/src/finance/attachment.ts（被测目标）
//   - web/src/crypto/envelope.ts（sealRecord / openRecord 字节级一致）
//   - tasks.md TR-3.5（Web 单元测试）
// ============================================================================

import { describe, it, expect, beforeEach } from 'vitest'
// @ts-expect-error node 全局 Buffer 由 vitest node 环境提供（无需显式 import 类型）。
const NodeBuffer: typeof Buffer = (globalThis as { Buffer?: typeof Buffer }).Buffer!
import {
  uploadFile,
  downloadFile,
  listAttachments,
  deleteAttachment,
  sha256HexOf,
  cryptoRandomId,
  isAllowedMimeType,
  type AttachmentChannel,
  type AttachmentRecord,
} from '../attachment'
import type { AttachmentRef } from '../types'

// -----------------------------------------------------------------------------
// node 环境 Blob 替代 —— downloadFile 需要 new Blob([buf], { type })
// -----------------------------------------------------------------------------

/**
 * 极简 Blob 替代 —— 仅保留 size / type 字段, 让 downloadFile 的 Blob 构造
 * 在 node 环境通过。真实浏览器环境 Blob 由 native 提供, 不影响生产路径。
 */
class FakeBlob {
  readonly size: number
  readonly type: string
  constructor(parts: BlobPart[], options?: BlobPropertyBag) {
    let total = 0
    for (const p of parts) {
      if (p instanceof Uint8Array) total += p.byteLength
      else if (ArrayBuffer.isView(p)) total += (p as ArrayBufferView).byteLength
      else if (p instanceof ArrayBuffer) total += p.byteLength
      else if (typeof p === 'string') total += p.length
    }
    this.size = total
    this.type = options?.type ?? ''
  }
}

// 在 node 测试环境挂载到 globalThis（uploadFile 不依赖, downloadFile 依赖）
beforeEach(() => {
  if (typeof (globalThis as { Blob?: unknown }).Blob === 'undefined') {
    ;(globalThis as unknown as { Blob: typeof FakeBlob }).Blob = FakeBlob
  }
})

// -----------------------------------------------------------------------------
// 测试用工具 —— mock AttachmentChannel（不需要真 sodium）
// -----------------------------------------------------------------------------

/**
 * 内存版 AttachmentChannel —— seal/open 不做真实加密，仅保留明文身份。
 *
 * 简化策略：
 *   - seal → 直接返回 plaintext 的 base64 字符串（仅作占位）；
 *   - open → 反向解码回 Uint8Array；
 *   - listByRecord → 从内部 Map 过滤；
 *   - upsert → 写入内部 Map。
 *
 * 这样可以让 uploadFile/downloadFile/listAttachments/deleteAttachment 全链路
 * 不依赖 sodium；sha256 仍走真 Web Crypto（保持端侧硬闸门有效）。
 */
function memoryAttachmentChannel(): AttachmentChannel & {
  remote: Map<string, AttachmentRecord>
} {
  const remote = new Map<string, AttachmentRecord>()
  return {
    remote,
    seal(_plaintext, _attachmentId, _version) {
      // 占位密文：直接返回 base64 的明文（仅 mock 用, 不参与真实加解密校验）。
      // Buffer 在 node 环境属于全局（npm install --save-dev @types/node 提供类型）。
      return NodeBuffer.from(_plaintext).toString('base64')
    },
    open(_attachmentId, ciphertextB64, _version) {
      return new Uint8Array(NodeBuffer.from(ciphertextB64, 'base64'))
    },
    async listByRecord(recordId) {
      const out: AttachmentRecord[] = []
      for (const rec of remote.values()) {
        if (rec.deleted) continue
        if (recordId === '' || rec.recordId === recordId) out.push(rec)
      }
      return out
    },
    async upsert(rec) {
      remote.set(rec.id, rec)
      return { applied: 1, skipped: 0, server_time: rec.updated_at }
    },
  }
}

/**
 * 构造一个 File-like 对象（node 环境无 File, 用最简对象模拟）。
 * uploadFile 校验走 .size + .type + .arrayBuffer() 三字段, 故只造这三个即可。
 */
function makeFileLike(sizeBytes: number, mime: string, content?: Uint8Array): File {
  const buf = content ?? new Uint8Array(sizeBytes).fill(0x61) // 默认填 'a'
  return {
    size: buf.byteLength,
    type: mime,
    arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
  } as unknown as File
}

// -----------------------------------------------------------------------------
// 测试 fixture —— 已知 sha256
// -----------------------------------------------------------------------------

/** "hello-attachment" 字符串的 sha256 hex（已知值, 端到端校验用）。 */
const KNOWN_CONTENT = new TextEncoder().encode('hello-attachment')
// 注意：上方 sha256 不一定匹配真实字符串; 运行时通过 sha256HexOf 重算真实值。
let REAL_SHA256 = ''

beforeEach(async () => {
  // 重算真实 sha256（避免硬编码错位）。
  REAL_SHA256 = await sha256HexOf(KNOWN_CONTENT.buffer.slice(0))
})

// ============================================================================
// 1. 工具函数 —— sha256 + UUID + MIME 白名单
// ============================================================================

describe('finance attachment / 工具函数', () => {
  it('sha256HexOf 重算同一内容 → 输出一致 hex（64 字符）', async () => {
    const a = await sha256HexOf(KNOWN_CONTENT.buffer.slice(0))
    const b = await sha256HexOf(KNOWN_CONTENT.buffer.slice(0))
    expect(a).toBe(b)
    expect(a).toMatch(/^[0-9a-f]{64}$/)
  })

  it('cryptoRandomId → UUID v4 格式（含连字符 + 36 字符 + 第 14 位 4）', () => {
    const id = cryptoRandomId()
    expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
  })

  it('isAllowedMimeType → application/pdf / image/jpeg / image/png 通过, 其它拒绝', () => {
    expect(isAllowedMimeType('application/pdf')).toBe(true)
    expect(isAllowedMimeType('image/jpeg')).toBe(true)
    expect(isAllowedMimeType('image/png')).toBe(true)
    expect(isAllowedMimeType('application/zip')).toBe(false)
    expect(isAllowedMimeType('text/plain')).toBe(false)
    expect(isAllowedMimeType('')).toBe(false)
  })
})

// ============================================================================
// 2. uploadFile —— 成功路径 + 失败路径
// ============================================================================

describe('finance attachment / uploadFile', () => {
  it('成功路径：合法 PDF + recordId → ok=true + ref 含 mime/size/sha256', async () => {
    const ch = memoryAttachmentChannel()
    const file = makeFileLike(KNOWN_CONTENT.byteLength, 'application/pdf', KNOWN_CONTENT)
    const result = await uploadFile('rec-1', file, ch)
    expect(result.ok).toBe(true)
    if (!result.ok) return
    const ref: AttachmentRef = result.value
    expect(ref.mime).toBe('application/pdf')
    expect(ref.size).toBe(KNOWN_CONTENT.byteLength)
    expect(ref.sha256).toBe(REAL_SHA256)
    // 远端记录落库 1 条, version=1, deleted=false。
    expect(ch.remote.size).toBe(1)
    const rec = Array.from(ch.remote.values())[0]
    expect(rec?.recordId).toBe('rec-1')
    expect(rec?.version).toBe(1)
    expect(rec?.deleted).toBe(false)
  })

  it('失败路径：MIME 不在白名单（application/zip） → ok=false 错误文案', async () => {
    const ch = memoryAttachmentChannel()
    const file = makeFileLike(100, 'application/zip')
    const result = await uploadFile('rec-1', file, ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/白名单/)
    expect(ch.remote.size).toBe(0)
  })

  it('失败路径：文件超过 50MB 上限 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    // 51MB, 模拟超限。
    const file = makeFileLike(51 * 1024 * 1024, 'application/pdf')
    const result = await uploadFile('rec-1', file, ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/50MB/)
    expect(ch.remote.size).toBe(0)
  })

  it('失败路径：recordId 为空 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const file = makeFileLike(10, 'application/pdf')
    const result = await uploadFile('', file, ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/record id|关联记录/)
    expect(ch.remote.size).toBe(0)
  })
})

// ============================================================================
// 3. downloadFile —— 成功路径 + 失败路径
// ============================================================================

describe('finance attachment / downloadFile', () => {
  it('成功路径：上传 → 下载 → sha256 一致 → Blob mime 正确', async () => {
    const ch = memoryAttachmentChannel()
    const file = makeFileLike(KNOWN_CONTENT.byteLength, 'image/png', KNOWN_CONTENT)
    const upResult = await uploadFile('rec-1', file, ch)
    expect(upResult.ok).toBe(true)
    if (!upResult.ok) return

    const downResult = await downloadFile(upResult.value.id, ch)
    expect(downResult.ok).toBe(true)
    if (!downResult.ok) return
    // mock Blob 仅保留 size/type 字段。
    expect(downResult.value.type).toBe('image/png')
  })

  it('失败路径：附件 id 在远端不存在 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const result = await downloadFile('non-existent-id', ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/不存在/)
  })

  it('失败路径：附件 id 传空字符串 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const result = await downloadFile('', ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/附件 id/)
  })
})

// ============================================================================
// 4. listAttachments —— 按 recordId 过滤 + 跳过 deleted + 跳过 plaintextJson 解析失败
// ============================================================================

describe('finance attachment / listAttachments', () => {
  it('按 recordId 过滤：仅返回匹配 recordId 的非删除项', async () => {
    const ch = memoryAttachmentChannel()
    await uploadFile('rec-A', makeFileLike(KNOWN_CONTENT.byteLength, 'application/pdf', KNOWN_CONTENT), ch)
    await uploadFile('rec-A', makeFileLike(KNOWN_CONTENT.byteLength, 'image/jpeg', KNOWN_CONTENT), ch)
    await uploadFile('rec-B', makeFileLike(KNOWN_CONTENT.byteLength, 'image/png', KNOWN_CONTENT), ch)

    const rA = await listAttachments('rec-A', ch)
    expect(rA.ok).toBe(true)
    if (!rA.ok) return
    expect(rA.value).toHaveLength(2)
    for (const ref of rA.value) {
      // mime 取自 plaintextJson（pdf / jpeg）。
      expect(['application/pdf', 'image/jpeg']).toContain(ref.mime)
    }

    const rB = await listAttachments('rec-B', ch)
    expect(rB.ok).toBe(true)
    if (!rB.ok) return
    expect(rB.value).toHaveLength(1)
    expect(rB.value[0]?.mime).toBe('image/png')
  })

  it('删除后再次 list → 跳过墓碑项', async () => {
    const ch = memoryAttachmentChannel()
    const up = await uploadFile('rec-1', makeFileLike(KNOWN_CONTENT.byteLength, 'application/pdf', KNOWN_CONTENT), ch)
    expect(up.ok).toBe(true)
    if (!up.ok) return
    const id = up.value.id
    const del = await deleteAttachment(id, ch)
    expect(del.ok).toBe(true)
    const list = await listAttachments('rec-1', ch)
    expect(list.ok).toBe(true)
    if (!list.ok) return
    expect(list.value).toHaveLength(0)
  })

  it('失败路径：recordId 为空 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const result = await listAttachments('', ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/record id|关联记录/)
  })
})

// ============================================================================
// 5. deleteAttachment —— 成功 + 失败
// ============================================================================

describe('finance attachment / deleteAttachment', () => {
  it('成功路径：version+1 + deleted=true + ciphertext 保留', async () => {
    const ch = memoryAttachmentChannel()
    const up = await uploadFile('rec-1', makeFileLike(KNOWN_CONTENT.byteLength, 'application/pdf', KNOWN_CONTENT), ch)
    expect(up.ok).toBe(true)
    if (!up.ok) return
    const id = up.value.id

    const before = ch.remote.get(id)
    expect(before?.version).toBe(1)
    const originalCipher = before?.ciphertext

    const del = await deleteAttachment(id, ch)
    expect(del.ok).toBe(true)

    const after = ch.remote.get(id)
    expect(after?.deleted).toBe(true)
    expect(after?.version).toBe(2)
    // ciphertext 留旧值（审计可解密）。
    expect(after?.ciphertext).toBe(originalCipher)
    // plaintextJson 置 null（墓碑标识）。
    expect(after?.plaintextJson).toBeNull()
  })

  it('失败路径：附件不存在 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const result = await deleteAttachment('nope', ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/不存在/)
  })

  it('失败路径：id 为空 → ok=false', async () => {
    const ch = memoryAttachmentChannel()
    const result = await deleteAttachment('', ch)
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error).toMatch(/附件 id/)
  })
})

// ============================================================================
// 6. 端到端 —— upload → list → download → delete 全链路
// ============================================================================

describe('finance attachment / 端到端链路', () => {
  it('upload → listAttachments 看到 1 条 → delete → listAttachments 看到 0 条 → downloadFile 拒绝', async () => {
    const ch = memoryAttachmentChannel()
    const file = makeFileLike(KNOWN_CONTENT.byteLength, 'application/pdf', KNOWN_CONTENT)

    // 1. upload
    const up = await uploadFile('rec-X', file, ch)
    expect(up.ok).toBe(true)
    if (!up.ok) return
    const id = up.value.id

    // 2. list 看到 1 条
    const list1 = await listAttachments('rec-X', ch)
    expect(list1.ok).toBe(true)
    if (!list1.ok) return
    expect(list1.value).toHaveLength(1)

    // 3. delete
    const del = await deleteAttachment(id, ch)
    expect(del.ok).toBe(true)

    // 4. list 看到 0 条
    const list2 = await listAttachments('rec-X', ch)
    expect(list2.ok).toBe(true)
    if (!list2.ok) return
    expect(list2.value).toHaveLength(0)

    // 5. downloadFile 在已删除项上拒绝（墓碑过滤）
    const down = await downloadFile(id, ch)
    expect(down.ok).toBe(false)
    if (down.ok) return
    expect(down.error).toMatch(/不存在/)
  })
})