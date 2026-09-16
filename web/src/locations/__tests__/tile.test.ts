// 瓦片 URL 校验与读写单测（tasks.md Task 10 / TR-10.2）。
//
// 覆盖：
//   - isValidTileUrl：合法自定义（http/https + 三占位符）通过；
//     非 http(s)、缺任一占位符、空串均拒绝；
//   - saveTileUrl / loadTileUrl：合法值持久化并可读回（自定义生效）、
//     非法值拒绝且不覆盖既有配置、缺失/脏数据回退缺省 OSM。
// node 环境无 localStorage，测试内补最小 stub（同 vault-place.test.ts 做法）。

import { beforeEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_TILE_URL,
  TILE_URL_STORAGE_KEY,
  isValidTileUrl,
  loadTileUrl,
  saveTileUrl,
} from '../tile'

describe('isValidTileUrl 校验', () => {
  it('合法 https 自定义源（含 {z}/{x}/{y}）通过', () => {
    expect(isValidTileUrl('https://tiles.example.com/{z}/{x}/{y}.png')).toBe(true)
  })

  it('http 源同样允许（内网自部署场景）', () => {
    expect(isValidTileUrl('http://192.168.1.10:8080/tile/{z}/{x}/{y}.png')).toBe(true)
  })

  it('缺省 OSM 源通过', () => {
    expect(isValidTileUrl(DEFAULT_TILE_URL)).toBe(true)
  })

  it('前后空白 trim 后校验', () => {
    expect(isValidTileUrl('  https://tiles.example.com/{z}/{x}/{y}.png  ')).toBe(true)
  })

  it('拒绝非 http(s) 协议', () => {
    expect(isValidTileUrl('ftp://tiles.example.com/{z}/{x}/{y}.png')).toBe(false)
    expect(isValidTileUrl('file:///tiles/{z}/{x}/{y}.png')).toBe(false)
    expect(isValidTileUrl('//tiles.example.com/{z}/{x}/{y}.png')).toBe(false)
  })

  it('拒绝缺任一占位符', () => {
    expect(isValidTileUrl('https://tiles.example.com/{x}/{y}.png')).toBe(false) // 缺 {z}
    expect(isValidTileUrl('https://tiles.example.com/{z}/{y}.png')).toBe(false) // 缺 {x}
    expect(isValidTileUrl('https://tiles.example.com/{z}/{x}.png')).toBe(false) // 缺 {y}
  })

  it('拒绝无占位符的固定 URL', () => {
    expect(isValidTileUrl('https://tiles.example.com/1/2/3.png')).toBe(false)
  })

  it('拒绝空串', () => {
    expect(isValidTileUrl('')).toBe(false)
    expect(isValidTileUrl('   ')).toBe(false)
  })
})

describe('saveTileUrl / loadTileUrl 持久化', () => {
  let storage: Map<string, string>

  beforeEach(() => {
    // 最小 localStorage stub（仅内存 Map，不落盘）。
    storage = new Map<string, string>()
    globalThis.localStorage = {
      get length() {
        return storage.size
      },
      clear: () => storage.clear(),
      getItem: (k: string) => storage.get(k) ?? null,
      key: () => null,
      removeItem: (k: string) => {
        storage.delete(k)
      },
      setItem: (k: string, v: string) => {
        storage.set(k, v)
      },
    } as unknown as Storage
  })

  it('无配置时回退缺省 OSM 源', () => {
    expect(loadTileUrl()).toBe(DEFAULT_TILE_URL)
  })

  it('合法自定义值保存生效并可读回', () => {
    const url = 'https://tiles.example.com/{z}/{x}/{y}.png'
    expect(saveTileUrl(url)).toBe(true)
    expect(storage.get(TILE_URL_STORAGE_KEY)).toBe(url)
    expect(loadTileUrl()).toBe(url)
  })

  it('非法值拒绝保存，且不覆盖既有合法配置', () => {
    const good = 'https://tiles.example.com/{z}/{x}/{y}.png'
    expect(saveTileUrl(good)).toBe(true)

    expect(saveTileUrl('not-a-url')).toBe(false)
    expect(storage.get(TILE_URL_STORAGE_KEY)).toBe(good) // 未被覆盖
    expect(loadTileUrl()).toBe(good)
  })

  it('localStorage 脏数据（非法值）回退缺省源', () => {
    storage.set(TILE_URL_STORAGE_KEY, 'not-a-url')
    expect(loadTileUrl()).toBe(DEFAULT_TILE_URL)
  })
})
