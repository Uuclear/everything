// 证件/卡面附件缩略图：内存 blob URL，组件卸载时 revoke。
import { onUnmounted, ref, watch, type Ref } from 'vue'
import { downloadFile } from '../finance/attachment'
import type { AttachmentChannel } from '../vault/attachment'

export function useVaultThumb(
  attachmentId: Ref<string | null | undefined>,
  channel: () => AttachmentChannel,
  sha256: Ref<string | undefined>,
) {
  const url = ref<string | null>(null)
  const loading = ref(false)
  const error = ref(false)

  function revoke() {
    if (url.value) {
      try {
        URL.revokeObjectURL(url.value)
      } catch {
        /* ignore */
      }
      url.value = null
    }
  }

  async function load(id: string, hash: string) {
    loading.value = true
    error.value = false
    revoke()
    const down = await downloadFile(id, channel(), hash)
    loading.value = false
    if (!down.ok) {
      error.value = true
      return
    }
    url.value = URL.createObjectURL(down.value)
  }

  watch(
    [attachmentId, sha256],
    ([id, hash]) => {
      if (!id || !hash) {
        revoke()
        return
      }
      void load(id, hash)
    },
    { immediate: true },
  )

  onUnmounted(revoke)

  return { url, loading, error }
}
