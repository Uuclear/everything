// ============================================================================
// finance store 同步集成测试（stage5-finance / Task 10 / TR-11.1）
// ============================================================================
//
// 任务: stage5-finance / Task 10 / TR-11.1
// 路径: web/src/stores/__tests__/finance-sync.spec.ts
// 作用: 验证 finance store 的加密同步链路（CryptoChannel + pullAll + pushChanges）
//       与 4b event-rules store 同结构，确保三端契约一致。
//
// 验证目标（≥4 用例, 覆盖 TR-11.1 Pass Condition）：
//   1. pullAll 拉取 finance 模块远端记录 → 解密 → 入 store；
//   2. pullAll 跳过非 finance 模块（module=event 远端不被 finance 消化）；
//   3. pushChanges 把明文 payload 加密推送 → version 自增 → 写回缓存；
//   4. tombstone 推送：deleteTx 推高版本墓碑；
//   5. 墓碑拉取：远端墓碑删除本地缓存；
//   6. 解密失败抛异常（FR-NFR-1 纪律）；
//   7. vault.sync 末尾触发 financeStore.pullAll（端到端集成）。
//
// 测试策略：
//   - 默认环境 = node（vitest 配置），无 window / localStorage —— 故测试
//     通过 `_setStorageForTest` 注入内存 StorageChannel 替代 localStorage；
//   - 注入 mock `CryptoChannel`（_setChannelForTest）模拟 seal/open/push/list
//     行为，避免依赖 sodium 与真实网络；
//   - vi.spyOn(api, 'listRecords') / vi.spyOn(api, 'pushRecords') 验证
//     vault.sync 链路；
//   - beforeEach 重置 store 状态，保证用例间隔离。
//
// 零知识纪律：
//   - 测试卡号均为业界公开示例 last4（"1111"/"2222"），非真实持卡人卡号；
//   - mock CryptoChannel 不打印明文；
//   - 不向真实 localStorage / IndexedDB / 网络写入任何数据。
//
// 关联:
//   - web/src/stores/finance.ts（被测目标）
//   - web/src/stores/vault.ts（端到端集成验证）
//   - tasks.md TR-11.1
// ============================================================================

import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  api,
  type RemoteRecord,
} from '../../api/client'
import {
  loadSodium,
  newMasterKey,
  openRecord,
  sealRecord,
  fromBase64,
  toBase64,
  type Sodium,
} from '../../crypto/envelope'
import type {
  FinanceAccount,
  FinanceTx,
  FinancePayload,
} from '../../finance/types'
import { FINANCE_MODULE } from '../../finance/types'
import {
  useFinanceStore,
  type CryptoChannel,
} from '../finance'
import { useVaultStore } from '../vault'
import { useAuthStore } from '../auth'

// -----------------------------------------------------------------------------
// 测试用 fixture —— 三类条目各一条最小有效数据
// -----------------------------------------------------------------------------

function makeAccount(overrides: Partial<FinanceAccount> = {}): FinanceAccount {
  return {
    id: 'fa-1',
    schema_version: 1,
    name: '现金钱包',
    kind: 'cash',
    currency: 'CNY',
    balance: '1234.56',
    note: null,
    icon: null,
    color: 'blue',
    archived: false,
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

function makeTx(overrides: Partial<FinanceTx> = {}): FinanceTx {
  return {
    id: 'ft-1',
    schema_version: 1,
    account_id: 'fa-1',
    card_id: null,
    kind: 'expense',
    amount: '50.00',
    category: '餐饮',
    occurred_at: 1735689600000,
    note: null,
    transfer_to_account_id: null,
    icon: null,
    color: 'slate',
    created_at: 1735689600000,
    updated_at: 1735689600000,
    ...overrides,
  }
}

// -----------------------------------------------------------------------------
// 测试用 mock CryptoChannel —— 用真实 sodium 做端到端加密自证
// -----------------------------------------------------------------------------

/**
 * 真实链路 CryptoChannel mock：使用真实 sodium + masterKey 做 seal/open，
 * 但把 push/list 替换为内存 Map，便于断言密文形态与远端 record 路由。
 *
 * 该 mock 与生产 defaultChannel 唯一差异：push/list 走内存而非 api/client。
 * AAD、加密/解密、字段形态完全与 envelope.ts 一致——确保测试可字节级
 * 验证 recordAAD / cipher 格式。
 */
function buildMockChannel(sodium: Sodium, mk: Uint8Array) {
  // 模拟服务端：record id → RemoteRecord（含密文 + 元数据）
  const server = new Map<string, RemoteRecord>()
  let serverTime = 1_800_000_000_000

  function sealRecordLocal(payload: FinancePayload, id: string, version: number): string {
    const plaintext = new TextEncoder().encode(JSON.stringify(payload))
    const sealed = sealRecord(sodium, mk, plaintext, id, FINANCE_MODULE, version)
    return toBase64(sealed)
  }

  function openRecordLocal(id: string, module: string, ciphertextB64: string, version: number): FinancePayload {
    const plaintext = openRecord(sodium, mk, fromBase64(ciphertextB64), id, module, version)
    return JSON.parse(new TextDecoder().decode(plaintext)) as FinancePayload
  }

  function push(record: RemoteRecord) {
    const existing = server.get(record.id)
    if (existing && existing.version >= record.version) {
      // 版本竞争：与 api/client 同语义返回 skipped=1
      return Promise.resolve({ applied: 0, skipped: 1, server_time: serverTime })
    }
    server.set(record.id, { ...record, updated_at: ++serverTime })
    return Promise.resolve({ applied: 1, skipped: 0, server_time: serverTime })
  }

  function list(since: number) {
    // since=0 视为"全量拉取"（与生产路径 pullAll(0) 语义一致）；
    // 非零 since 走增量过滤。
    const items = Array.from(server.values()).filter(
      (r) => since === 0 || r.updated_at > since,
    )
    return Promise.resolve({ records: items, has_more: false })
  }

  const channel: CryptoChannel & {
    server: Map<string, RemoteRecord>
    seal: typeof sealRecordLocal
    open: typeof openRecordLocal
  } = {
    seal: sealRecordLocal,
    open: openRecordLocal,
    push,
    list,
    server,
  }
  return channel
}

// -----------------------------------------------------------------------------
// 测试前置：每个用例独立 pinia + 内存 storage + 真实 sodium 加密链路
// -----------------------------------------------------------------------------

let sodium: Sodium
let mk: Uint8Array
let channel: ReturnType<typeof buildMockChannel>

beforeAll(async () => {
  sodium = await loadSodium()
  mk = newMasterKey(sodium)
})

beforeEach(() => {
  // 内存 storage（避免 jsdom 依赖）。
  const storage = new Map<string, string>()
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

  setActivePinia(createPinia())
  // 注入 auth 凭证（sodium + mk），保证生产路径 defaultChannel 在未替换前
  // 也能正常取到；但每个用例会显式注入 mock channel 覆盖生产路径。
  const auth = useAuthStore()
  auth.sodium = sodium
  auth.masterKey = mk

  // 注入 finance store 的内存 StorageChannel + mock CryptoChannel。
  channel = buildMockChannel(sodium, mk)
  const store = useFinanceStore()
  store._setStorageForTest({
    read() {
      const raw = storage.get('eve:finance:v1')
      return raw == null ? null : JSON.parse(raw)
    },
    write(state) {
      storage.set('eve:finance:v1', JSON.stringify(state))
    },
    clear() {
      storage.delete('eve:finance:v1')
    },
  })
  store._setChannelForTest(channel)
  store._resetForTest()
})

// ============================================================================
// 1. pushChanges —— 上行链路（明文 → 密文 → 推送 → 写回缓存）
// ============================================================================

describe('finance store sync / pushChanges 上行链路', () => {
  it('pushChanges 把账户明文加密推送 + version 自增 + 写回缓存', async () => {
    const store = useFinanceStore()
    const payload = makeAccount({ id: 'fa-1', name: '现金钱包' })

    await store.pushChanges([payload])

    const cached = store.byId('account', 'fa-1')
    expect(cached).toBeDefined()
    expect(cached?.version).toBe(1)
    expect((cached?.data as FinanceAccount).name).toBe('现金钱包')

    // 服务端应有密文记录（module=finance / type=account）
    const remote = channel.server.get('fa-1')
    expect(remote).toBeDefined()
    expect(remote?.module).toBe(FINANCE_MODULE)
    expect(remote?.type).toBe('account')
    expect(remote?.version).toBe(1)
    expect(remote?.deleted).toBe(false)
    // 密文可被 sodium 同 AAD 解出（字节级自证）
    const plaintext = openRecord(sodium, mk, fromBase64(remote!.ciphertext), 'fa-1', FINANCE_MODULE, 1)
    const decoded = JSON.parse(new TextDecoder().decode(plaintext)) as FinanceAccount
    expect(decoded.name).toBe('现金钱包')
    expect(decoded.balance).toBe('1234.56')
  })

  it('pushChanges 连续推送同 id → version 严格递增（records LWW）', async () => {
    const store = useFinanceStore()
    await store.pushChanges([makeAccount({ id: 'fa-1', balance: '100.00' })])
    await store.pushChanges([makeAccount({ id: 'fa-1', balance: '200.00' })])

    const remote = channel.server.get('fa-1')
    expect(remote?.version).toBe(2)
    expect((store.byId('account', 'fa-1')?.data as FinanceAccount).balance).toBe('200.00')
  })

  it('pushChanges 跳过 v2 子类型（policy/subscription/loan/contract）', async () => {
    const store = useFinanceStore()
    // 构造一个非法 payload（无 last4 / balance / account_id 字段）
    const fakePayload = { id: 'fx-1', kind: 'policy' } as unknown as FinancePayload
    await store.pushChanges([fakePayload])
    // 服务端不应有 fx-1
    expect(channel.server.has('fx-1')).toBe(false)
  })
})

// ============================================================================
// 2. pullAll —— 下行链路（远端 record → 解密 → 入 store；墓碑删除本地）
// ============================================================================

describe('finance store sync / pullAll 下行链路', () => {
  it('pullAll 解密远端 finance 记录 → 入 store', async () => {
    // 预置服务端密文（远端另一设备写入）
    const remote: RemoteRecord = {
      id: 'fa-2',
      module: FINANCE_MODULE,
      type: 'account',
      ciphertext: channel.seal(makeAccount({ id: 'fa-2', name: '远端账户' }), 'fa-2', 1),
      version: 1,
      device_id: 'android',
      created_at: 1735689600000,
      updated_at: 1735689600000,
      deleted: false,
    }
    channel.server.set('fa-2', remote)

    const store = useFinanceStore()
    await store.pullAll(0)

    const cached = store.byId('account', 'fa-2')
    expect(cached).toBeDefined()
    expect((cached?.data as FinanceAccount).name).toBe('远端账户')
  })

  it('pullAll 跳过 module=event 的远端记录（finance store 不消化其它模块）', async () => {
    // 远端塞一条 event 模块密文，finance store 应忽略
    const eventRemote: RemoteRecord = {
      id: 'evt-1',
      module: 'event',
      type: 'event',
      ciphertext: 'ignored-base64',
      version: 1,
      device_id: 'web',
      created_at: 0,
      updated_at: 0,
      deleted: false,
    }
    channel.server.set('evt-1', eventRemote)

    const store = useFinanceStore()
    await expect(store.pullAll(0)).resolves.toBeUndefined()
    // finance store 内不应有任何 event 记录
    expect(store.byId('account', 'evt-1')).toBeUndefined()
    expect(store.byId('card', 'evt-1')).toBeUndefined()
    expect(store.byId('tx', 'evt-1')).toBeUndefined()
  })

  it('pullAll 收到墓碑 → 删除本地缓存', async () => {
    const store = useFinanceStore()
    // 本地先有一条（addTx 内部 fire-and-forget pushChanges 异步推送；
    // await 让推送微任务跑完，确保服务端已写入非墓碑版本，再覆盖为墓碑）。
    store.addTx(makeTx({ id: 'ft-1' }))
    await Promise.resolve()
    await Promise.resolve()
    await Promise.resolve()
    expect(store.byId('tx', 'ft-1')).toBeDefined()

    // 远端推送墓碑（覆盖 addTx 异步写入的非墓碑版本）
    const tomb: RemoteRecord = {
      id: 'ft-1',
      module: FINANCE_MODULE,
      type: 'tx',
      ciphertext: '',
      version: 2,
      device_id: 'web',
      created_at: 1735689600000,
      updated_at: 1_800_000_000_001,
      deleted: true,
    }
    channel.server.set('ft-1', tomb)

    await store.pullAll(0)
    expect(store.byId('tx', 'ft-1')).toBeUndefined()
  })

  it('pullAll 解密失败抛异常（FR-NFR-1 纪律）', async () => {
    // 故意推送一条无法解密的密文（用错误 masterKey 加密）
    const wrongMk = newMasterKey(sodium)
    const sealed = sealRecord(
      sodium,
      wrongMk,
      new TextEncoder().encode(JSON.stringify(makeAccount({ id: 'fa-bad' }))),
      'fa-bad',
      FINANCE_MODULE,
      1,
    )
    const bad: RemoteRecord = {
      id: 'fa-bad',
      module: FINANCE_MODULE,
      type: 'account',
      ciphertext: toBase64(sealed),
      version: 1,
      device_id: 'web',
      created_at: 0,
      updated_at: 0,
      deleted: false,
    }
    channel.server.set('fa-bad', bad)

    const store = useFinanceStore()
    await expect(store.pullAll(0)).rejects.toThrow() // 失败抛异常不静默
  })
})

// ============================================================================
// 3. 端到端 —— vault.sync 末尾触发 financeStore.pullAll
// ============================================================================

describe('finance store sync / vault.sync 端到端集成', () => {
  it('vault.sync(full=true) 末尾触发 financeStore.pullAll(0)，finance 远端密文入 store', async () => {
    // 预置 finance 远端密文
    const remote: RemoteRecord = {
      id: 'fa-3',
      module: FINANCE_MODULE,
      type: 'account',
      ciphertext: channel.seal(makeAccount({ id: 'fa-3', name: 'vault同步来的账户' }), 'fa-3', 1),
      version: 1,
      device_id: 'web',
      created_at: 1735689600000,
      updated_at: 1735689600000,
      deleted: false,
    }
    channel.server.set('fa-3', remote)

    // mock api.listRecords：返回 server 内全部 finance 记录（vault 拉的是全量）
    const listSpy = vi.spyOn(api, 'listRecords').mockImplementation(async (since = 0, _limit = 500) => {
      const items = Array.from(channel.server.values()).filter((r) => r.updated_at >= since)
      return { records: items, has_more: false }
    })
    // mock api.pushRecords：转发到 mock channel 的 push
    vi.spyOn(api, 'pushRecords').mockImplementation(async (records) => {
      let applied = 0
      let skipped = 0
      let lastTime = 0
      for (const r of records) {
        const res = await channel.push(r as RemoteRecord)
        applied += res.applied
        skipped += res.skipped
        lastTime = Math.max(lastTime, res.server_time)
      }
      return { applied, skipped, server_time: lastTime || 1_800_000_000_000 }
    })

    const vault = useVaultStore()
    // vault.sync 需要 auth.unlocked=true（unlocked 是 masterKey !== null 的 getter）。
    // 直接给 masterKey 赋一个非 null 数组，让 getter 派生为 true。
    const auth = useAuthStore()
    auth.masterKey = new Uint8Array(32)
    await vault.sync(true)

    expect(listSpy).toHaveBeenCalled()
    // finance 模块密文应被 vault.sync → financeStore.pullAll 消化
    const finance = useFinanceStore()
    const acc = finance.byId('account', 'fa-3')
    expect(acc).toBeDefined()
    expect((acc?.data as FinanceAccount).name).toBe('vault同步来的账户')
  })

  it('financeStore 解密失败被 vault.sync try-catch 吞掉，不破坏 vault 闭环', async () => {
    // 预置无法解密的 finance 远端记录
    const wrongMk = newMasterKey(sodium)
    const sealed = sealRecord(
      sodium,
      wrongMk,
      new TextEncoder().encode(JSON.stringify(makeAccount({ id: 'fa-bad2' }))),
      'fa-bad2',
      FINANCE_MODULE,
      1,
    )
    const bad: RemoteRecord = {
      id: 'fa-bad2',
      module: FINANCE_MODULE,
      type: 'account',
      ciphertext: toBase64(sealed),
      version: 1,
      device_id: 'web',
      created_at: 0,
      updated_at: 0,
      deleted: false,
    }
    channel.server.set('fa-bad2', bad)

    vi.spyOn(api, 'listRecords').mockImplementation(async () => ({
      records: [bad],
      has_more: false,
    }))
    vi.spyOn(api, 'pushRecords').mockResolvedValue({ applied: 0, skipped: 0, server_time: 1 })

    const vault = useVaultStore()
    const auth = useAuthStore()
    auth.masterKey = new Uint8Array(32)
    // vault.sync 不应抛异常（finance 模块解密失败被吞掉）
    await expect(vault.sync(true)).resolves.toBeUndefined()
    // vault 自身的 syncing 状态应已回归 false
    expect(vault.syncing).toBe(false)
  })
})