// 将响应式源 debounce 为只读 ref（顶栏全局搜索用）。
import { onScopeDispose, ref, watch, type Ref } from 'vue'

export function useDebouncedRef<T>(source: Ref<T>, delayMs: number): Ref<T> {
  const debounced = ref(source.value) as Ref<T>
  let timer: ReturnType<typeof setTimeout> | null = null

  watch(
    source,
    (value) => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        debounced.value = value
      }, delayMs)
    },
    { flush: 'post' },
  )

  onScopeDispose(() => {
    if (timer) clearTimeout(timer)
  })

  return debounced
}
