// vault 通用附件单测：元数据 JSON、50MB 拒绝、parentModule 密封绑定。
import { describe, it, expect } from 'vitest'
const NodeBuffer: typeof Buffer = (globalThis as { Buffer?: typeof Buffer }).Buffer!
import {
  attachmentMetadataJson,
  cryptoRandomId,
  isAllowedMimeType,
  sha256HexOf,
  uploadFile,
  type AttachmentChannel,
  type AttachmentRecord,
  ATTACHMENT_MAX_SIZE_BYTES,
} from '../attachment'

function memoryChannel(parentModule: string): AttachmentChannel & {
  remote: Map<string, AttachmentRecord>
} {
  const remote = new Map<string, AttachmentRecord>()
  return {
    parentModule,
    remote,
    seal(plain) {
      return NodeBuffer.from(plain).toString('base64')
    },
    open(_id, b64) {
      return new Uint8Array(NodeBuffer.from(b64, 'base64'))
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
      return { applied: 1, skipped: 0, server_time: Date.now() }
    },
  }
}

function makeFile(size: number, mime: string, content?: Uint8Array): File {
  const buf = content ?? new Uint8Array(size).fill(0x61)
  return {
    size: buf.byteLength,
    type: mime,
    arrayBuffer: async () => buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength),
  } as unknown as File
}

describe('vault attachment / 元数据与校验', () => {
  it('attachmentMetadataJson 含 parent_ref_id 与 recordId', () => {
    const json = attachmentMetadataJson(
      { id: 'a', mime: 'image/png', size: 10, sha256: 'f'.repeat(64) },
      'parent-1',
    )
    const o = JSON.parse(json) as { recordId: string; parent_ref_id: string }
    expect(o.recordId).toBe('parent-1')
    expect(o.parent_ref_id).toBe('parent-1')
  })

  it('超过 50MB 拒绝', async () => {
    const ch = memoryChannel('identity')
    const file = makeFile(ATTACHMENT_MAX_SIZE_BYTES + 1, 'image/jpeg')
    const r = await uploadFile('id-1', file, ch)
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.error).toMatch(/50MB/)
  })

  it('upload 写入 parentModule=identity', async () => {
    const ch = memoryChannel('identity')
    const content = new TextEncoder().encode('scan-page')
    const file = makeFile(content.byteLength, 'image/png', content)
    const r = await uploadFile('doc-1', file, ch)
    expect(r.ok).toBe(true)
    if (!r.ok) return
    const rec = ch.remote.get(r.value.id)
    expect(rec?.parentModule).toBe('identity')
    expect(rec?.recordId).toBe('doc-1')
    const meta = JSON.parse(rec!.plaintextJson!)
    expect(meta.sha256).toBe(await sha256HexOf(content.buffer.slice(0)))
  })

  it('isAllowedMimeType 支持 webp', () => {
    expect(isAllowedMimeType('image/webp')).toBe(true)
  })

  it('cryptoRandomId 为 UUID v4 形态', () => {
    expect(cryptoRandomId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })
})
