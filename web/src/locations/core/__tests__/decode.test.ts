// 轨迹块解密单测（tasks.md Task 8 / AC-9），核心是跨端一致性锚点：
// Android sealLocationBlock（Task 5）真实密封产物 ↔ Web openLocationBlock 还原。
//
// 样例来源（生成方式记录备查，TR-8 Notes）：
//   由 Android 侧 LocationBlockAnchorJvmTest 用线上 CryptoEnvelope.sealLocationBlock
//   以固定 MK 对固定块明文 JSON（moshi 实际输出字节）一次性密封产出；
//   nonce 为随机一次性值，故密文不可复现、但固定可解（AAD/密钥/算法一致即可开）。
//   明文坐标仅存在本测试内存，绝不持久化（NFR-1）。

import { beforeAll, describe, expect, it } from 'vitest'
import {
  loadSodium,
  locationBlockAAD,
  toBase64,
  type Sodium,
} from '../../../crypto/envelope'
import { blockId, decryptBlock, type ApiLocationBlock } from '../decode'

// ---- 跨端锚点样例（Android Task 5 产出，完整转交自 tasks.md 交接）----

/** 固定 MK（32 字节 hex）：00 01 02 ... 1f。 */
const FIXED_MK_HEX = '000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f'

/** 块 id（AAD 组成部分）：blockId(deviceId, startTs, endTs) 派生值。 */
const FIXED_BLOCK_ID = 'dev-fixed-1:1700000000000:1700000060000'

/** 密文信封（hex）：nonce(24B) || ciphertext(含 16B Poly1305 tag)。 */
const FIXED_CIPHER_HEX =
  '2ad781f93f0e8068df64b9acfd036c6d3b32328c202e50e9bf836b7fc506879408e294f3f08e218ce9bafd432432b44ab75ed568c90dc8fcb2a3dd8c3a795bfea5ca30e57674ea7d083318c89907cc2adbe2a4039a8a1bc520ead3f77cb54fe5fe7eb5a7383aa74e5812e8abc9fda7fd383a47e605c6231b80e10c01e4b5507b34ae508681b7398be87f06e691f8f6c5dee2570c1a39d13e86495668dcd7ac42c9b93fa2afc7ce56525464c9615feb22e22a44077b6c8ec14eee64ed534f33da1794b8576967a7cd026f44a817e78f6f9b5debf02ed8dc819f1092e2aabe5ee0146b27bfa517cd06f88db1c1130e734d2207c9461b55'

/** hex 字符串 → 字节数组（测试内工具，core 包不携带）。 */
function fromHex(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16)
  }
  return out
}

/** 用锚点样例组装 API 密文块形态（cipher 字段为 base64，与线上 GET 一致）。 */
function anchorRawBlock(cipherOverride?: Uint8Array, idOverride?: string): ApiLocationBlock {
  return {
    id: idOverride ?? FIXED_BLOCK_ID,
    device_id: 'dev-fixed-1',
    start_ts: 1700000000000,
    end_ts: 1700000060000,
    point_count: 2,
    cipher: toBase64(cipherOverride ?? fromHex(FIXED_CIPHER_HEX)),
    created_at: 1700000100000,
  }
}

let sodium: Sodium

beforeAll(async () => {
  sodium = await loadSodium()
})

describe('blockId（同 Android BlockPacker 规则）', () => {
  it('派生规则 "{deviceId}:{startTs}:{endTs}"，与样例块 id 逐字一致', () => {
    expect(blockId('dev-fixed-1', 1700000000000, 1700000060000)).toBe(FIXED_BLOCK_ID)
  })

  it('deviceId 为空拒绝派生（未配对设备不得封块/解块）', () => {
    expect(() => blockId('', 1, 2)).toThrow()
  })
})

describe('locationBlockAAD', () => {
  it('AAD 域为 "eve:v1:location-block:" + blockId 的 UTF-8 字节（无版本号段）', () => {
    const aad = locationBlockAAD(FIXED_BLOCK_ID)
    expect(new TextDecoder().decode(aad)).toBe(`eve:v1:location-block:${FIXED_BLOCK_ID}`)
  })
})

describe('decryptBlock 跨端锚点（Android seal ↔ Web open）', () => {
  it('固定 MK 解 Android 真实密封样例，逐字段还原块明文 JSON', () => {
    const block = decryptBlock(sodium, fromHex(FIXED_MK_HEX), anchorRawBlock())

    // 块头三字段（snake_case 三端契约）。
    expect(block.device_id).toBe('dev-fixed-1')
    expect(block.start_ts).toBe(1700000000000)
    expect(block.end_ts).toBe(1700000060000)

    // 轨迹点逐字段还原（moshi 跳过 null 字段，故每点恰 4 键）。
    expect(block.points).toEqual([
      { ts: 1700000000000, lat: 39.9042, lon: 116.4074, acc: 12.5 },
      { ts: 1700000060000, lat: 39.905, lon: 116.408, acc: 10 },
    ])
    // 浮点数值相等显式锁定（39.905 / 116.408 / 10.0）。
    expect(block.points[1].lat).toBe(39.905)
    expect(block.points[1].lon).toBe(116.408)
    expect(block.points[1].acc).toBe(10)
  })

  it('blockId 不符（AAD 错）时解密失败抛异常', () => {
    const wrongId = blockId('dev-fixed-1', 1700000000000, 1700000060001)
    expect(() =>
      decryptBlock(sodium, fromHex(FIXED_MK_HEX), anchorRawBlock(undefined, wrongId)),
    ).toThrow()
  })

  it('密文被篡改（AEAD tag 校验失败）时解密失败抛异常', () => {
    const tampered = fromHex(FIXED_CIPHER_HEX)
    tampered[tampered.length - 1] ^= 0x01 // 翻转末字节最低位
    expect(() =>
      decryptBlock(sodium, fromHex(FIXED_MK_HEX), anchorRawBlock(tampered)),
    ).toThrow()
  })

  it('MK 不符时解密失败抛异常（零知识：错钥拿不到任何明文）', () => {
    const wrongMk = fromHex(FIXED_MK_HEX)
    wrongMk[0] ^= 0xff
    expect(() => decryptBlock(sodium, wrongMk, anchorRawBlock())).toThrow()
  })
})
