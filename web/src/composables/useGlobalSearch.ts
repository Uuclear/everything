// 解锁后全局搜索：聚合各 store 明文，debounce 后纯本地匹配。
import { computed, toRef } from 'vue'
import { useFinanceStore } from '../stores/finance'
import { useItemsStore } from '../stores/items'
import { useVaultStore } from '../stores/vault'
import { globalSearch, SEARCH_DEBOUNCE_MS } from '../vault/aggregate'
import { useDebouncedRef } from './useDebouncedRef'
import { uiSearch } from './useUiSearch'

export function useGlobalSearch() {
  const vault = useVaultStore()
  const finance = useFinanceStore()
  const items = useItemsStore()

  const debouncedQuery = useDebouncedRef(toRef(uiSearch, 'query'), SEARCH_DEBOUNCE_MS)

  const hits = computed(() => {
    const q = debouncedQuery.value
    if (!q.trim()) return []
    return globalSearch(q, {
      identities: vault.byKind('identity'),
      items: items.list,
      financeTxs: finance.listTxs,
      passRecords: [...vault.byKind('login'), ...vault.byKind('note')],
    })
  })

  const searching = computed(
    () => uiSearch.query.trim() !== '' && debouncedQuery.value !== uiSearch.query,
  )

  return { debouncedQuery, hits, searching }
}
