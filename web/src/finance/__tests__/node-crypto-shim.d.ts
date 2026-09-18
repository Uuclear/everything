// ============================================================================
// node:crypto 最小类型垫片（仅测试用）
// ============================================================================
//
// 本仓库未安装 @types/node；Vitest 运行于 node 环境，import 'node:crypto'
// 在运行时由 Node 内置模块直接提供。此声明仅为通过 vue-tsc 严格类型检查，
// 只暴露测试所需的 createHash 一个能力（不做任何运行时实现）。
// 如后续工程正式引入 @types/node，可删除本文件。
// ============================================================================

declare module 'node:crypto' {
  /** node:crypto Hash 的最小可用面（链式 update + hex digest）。 */
  interface Hash {
    update(data: Uint8Array | string, encoding?: string): Hash
    digest(encoding: 'hex'): string
  }

  export function createHash(algorithm: string): Hash
}
