// 各类型列表页的公共逻辑：搜索词、编辑/详情弹层开关、保存与删除。
import { computed, ref } from 'vue'
import { useVaultStore } from '../stores/vault'
import { uiSearch } from './useUiSearch'
import type { DecryptedRecord, RecordKind } from '../types/vault'

export function useRecordPage(kind: RecordKind) {
  const vault = useVaultStore()
  // 搜索词来自顶栏（全应用共享单例），切页时保留关键词便于跨类型查找。
  const query = uiSearch
  const editorShow = ref(false)
  const viewerShow = ref(false)
  const editing = ref<DecryptedRecord | null>(null)
  const viewing = ref<DecryptedRecord | null>(null)

  const items = computed(() => vault.search(kind, query.query))

  function create() {
    editing.value = null
    editorShow.value = true
  }
  function open(record: DecryptedRecord) {
    viewing.value = record
    viewerShow.value = true
  }
  function edit(record: DecryptedRecord) {
    editing.value = record
    viewerShow.value = false
    editorShow.value = true
  }
  async function remove(record: DecryptedRecord) {
    await vault.remove(record)
    viewerShow.value = false
    viewing.value = null
  }

  return {
    vault,
    query,
    items,
    editorShow,
    viewerShow,
    editing,
    viewing,
    create,
    open,
    edit,
    remove,
  }
}
