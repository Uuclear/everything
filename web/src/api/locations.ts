// 位置轨迹端点封装（阶段 4a / tasks.md Task 9）。
//
// 对齐 client.ts 的 rawFetch 模式：统一走 request()（自动附带 access token、
// 401 刷新重试、ApiError 错误形态）。零知识红线（TR-9.2）：本层只透传密文块
// （cipher 为 base64 字符串）与最小元数据，明文坐标绝不出现在网络层。
//
// 服务端契约（server/internal/api/locations_handler.go，只读对照）：
//   GET    /api/v1/locations?from=&to=  → { blocks: ApiLocationBlock[] }
//   DELETE /api/v1/locations?from=&to=  → { deleted: number }
//   from/to 均为 UTC 毫秒整数；跨度 >62 天服务端返回 400。

import { request } from './client'
import type { ApiLocationBlock } from '../locations/core/decode'

/**
 * 拉取 [from, to]（UTC 毫秒，跨度 ≤62 天）范围内的轨迹密文块。
 *
 * @returns 按 start_ts 升序的密文块数组（解密由调用方在内存中完成）
 */
export async function listLocations(from: number, to: number): Promise<ApiLocationBlock[]> {
  const resp = await request<{ blocks: ApiLocationBlock[] }>(`/locations?from=${from}&to=${to}`)
  // 服务端始终返回 blocks 字段；防御性缺省为空数组，避免极端响应形态导致 UI 崩溃。
  return resp.blocks ?? []
}

/**
 * 删除 [from, to]（UTC 毫秒，跨度 ≤62 天）范围内的轨迹块（NFR-6 用户可控删除）。
 *
 * @returns 服务端实际删除的块数 { deleted }
 */
export function deleteLocations(from: number, to: number): Promise<{ deleted: number }> {
  return request<{ deleted: number }>(`/locations?from=${from}&to=${to}`, { method: 'DELETE' })
}
