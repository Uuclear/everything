// 轨迹密文块解密纯函数（阶段 4a，FR-10：浏览器内存解密，明文坐标绝不持久化）。
//
// 解密链路：API 密文块（cipher 为 base64）→ fromBase64 → openLocationBlock
// （XChaCha20-Poly1305，AAD = "eve:v1:location-block:" + blockId）→ JSON.parse。
// AAD 与 Android CryptoEnvelope.sealLocationBlock 逐字节一致（Task 5 契约锚点），
// 跨端互通由 __tests__/decode.test.ts 的 Android 真实密封样例锁定。

import {
  fromBase64,
  openLocationBlock,
  type Sodium,
} from '../../crypto/envelope'
import type { LocationBlockJson } from './types'

/**
 * API 返回的位置轨迹密文块形态（GET /api/v1/locations 的 blocks[] 元素）。
 *
 * 与 server/internal/vault/location_store.go 的 LocationBlock JSON 序列化字段
 * 逐字段一致；cipher 经 Go 的 []byte → base64 字符串。
 */
export interface ApiLocationBlock {
  /** 块 id："{deviceId}:{startTs}:{endTs}"（上行时客户端派生，服务端按 id 幂等）。 */
  id: string
  /** 采集设备 id（服务端以 token claims 覆盖写入）。 */
  device_id: string
  /** 块首点时刻（UTC 毫秒）。 */
  start_ts: number
  /** 块末点时刻（UTC 毫秒）。 */
  end_ts: number
  /** 块内点数（客户端自声明元数据，服务端不验证真实性）。 */
  point_count: number
  /** XChaCha20-Poly1305 密文信封（nonce||ciphertext）的 base64。 */
  cipher: string
  /** 服务端权威写入时间（UTC 毫秒）。 */
  created_at: number
}

/**
 * 块 id 派生规则（三端契约，与 Android BlockPacker.blockId 逐字一致）：
 *
 *   "{deviceId}:{startTs}:{endTs}"
 *
 * 时间均为 UTC 毫秒。解密端用它从块元数据重建 AAD id（与上行 seal 同值）。
 */
export function blockId(deviceId: string, startTs: number, endTs: number): string {
  if (deviceId.length === 0) {
    throw new Error('deviceId 为空：无法派生块 id')
  }
  return `${deviceId}:${startTs}:${endTs}`
}

/**
 * 解密一个 API 密文块为块明文 DTO。
 *
 * AAD 使用 raw.id（上行时即 blockId(device_id, start_ts, end_ts) 派生值），
 * 与 Android sealLocationBlock 的 AAD 逐字节一致；服务端幂等契约保证
 * raw.id 与块元数据自洽。
 *
 * 解密失败（密钥错/AAD 不符/密文损坏）时 libsodium 抛异常，调用方（store）
 * 应置错误态且不泄露任何坐标信息。
 *
 * @param sodium 已就绪的 libsodium 实例
 * @param mk     主密钥（32 字节，仅存内存）
 * @param raw    API 返回的密文块
 * @returns 块明文 DTO（仅驻留内存，调用方负责不持久化）
 */
export function decryptBlock(
  sodium: Sodium,
  mk: Uint8Array,
  raw: ApiLocationBlock,
): LocationBlockJson {
  const sealed = fromBase64(raw.cipher)
  const plain = openLocationBlock(sodium, mk, sealed, raw.id)
  // moshi 序列化的 UTF-8 JSON 字节；解析结果字段与 LocationBlockJson 契约一致。
  return JSON.parse(new TextDecoder().decode(plain)) as LocationBlockJson
}
